import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { startDb, type Db } from './db';

let db: Db;
beforeAll(async () => { db = await startDb(); });
afterAll(async () => { await db?.stop(); });
beforeEach(async () => { await db.resetHouseholds(); });

interface Ctx { owner: string; tv: string; h: string; toi: string; soeur: string }

async function foyer(autre = false): Promise<Ctx> {
  const owner = await db.newUser({ email: `s${Math.random()}@exemple.test` });
  const noms = ['Toi', 'Sœur'];
  const h = autre
    ? await db.seedHousehold(owner, noms)
    : (await db.as(owner, (c) => c.query(`select create_household('Maison', $1::text[]) id`, [noms]))).rows[0].id as string;
  const tv = await db.newUser({ anonymous: true });
  await db.admin.query(`insert into household_members (household_id, user_id, role) values ($1, $2, 'device')`, [h, tv]);
  const p = await db.admin.query('select id, name from profiles where household_id = $1', [h]);
  const id = (n: string) => p.rows.find((r) => r.name === n).id as string;
  return { owner, tv, h, toi: id('Toi'), soeur: id('Sœur') };
}

const cats = (...ids: string[]) => JSON.stringify(ids.map((id, i) => ({ id, name: `Serie ${id}`, position: i })));
const q = async <T = any>(u: string, sql: string, params: unknown[] = []) =>
  (await db.as(u, (c) => c.query(sql, params))).rows as T[];
const publish = (tv: string, h: string, ...ids: string[]) => q(tv, 'select publish_series_categories($1, $2::jsonb)', [h, cats(...ids)]);
const sections = async (u: string, h: string, p: string | null) =>
  (await q(u, 'select org_series_categories($1, $2) r', [h, p]))[0].r as any[];
const ordre = async (u: string, h: string, p: string | null) => (await sections(u, h, p)).map((r) => r.id as string);
const prefs = async (u: string, p: string) => (await q(u, 'select profile_prefs($1) r', [p]))[0].r as any;

const BASE = Date.now() - 3600_000;
const T = (n: number) => new Date(BASE + n * 1000).toISOString();
const rec12 = (p: string, id: string, pos: number, dur: number, at: string | null, serie: string | null,
  s: number, e: number, nom: string | null, aff: string | null, cat: string | null) =>
  (c: any) => c.query('select record_progress($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12) ok',
    [p, 'episode', id, pos, dur, at, serie, s, e, nom, aff, cat]);
const rec9 = (p: string, id: string, pos: number, dur: number, at: string | null, serie: string | null, s: number, e: number) =>
  (c: any) => c.query('select record_progress($1, $2, $3, $4, $5, $6, $7, $8, $9) ok', [p, 'episode', id, pos, dur, at, serie, s, e]);

describe('series', () => {
  it('publication_categories_series_et_lecture_membre', async () => {
    const f = await foyer();
    await publish(f.tv, f.h, 'a', 'b', 'c');
    const rows = (await q(f.owner, 'select id, name, position from series_catalog_category where household_id = $1 order by position', [f.h]));
    expect(rows.map((r) => r.id)).toEqual(['a', 'b', 'c']);
    // Liste vide : rien ne change ; catégories absentes : supprimées.
    await q(f.tv, `select publish_series_categories($1, '[]'::jsonb)`, [f.h]);
    expect((await q(f.owner, 'select count(*)::int n from series_catalog_category'))[0].n).toBe(3);
    await publish(f.tv, f.h, 'b', 'd');
    expect((await q(f.tv, 'select id from series_catalog_category order by id')).map((r) => r.id)).toEqual(['b', 'd']);
    // Aucune écriture directe.
    await expect(db.as(f.tv, (c) => c.query(`insert into series_catalog_category (household_id, id, name, position) values ($1, 'x', 'X', 0)`, [f.h])))
      .rejects.toBeTruthy();
  });

  it('masquer_et_ordre_section_series', async () => {
    const f = await foyer();
    await publish(f.tv, f.h, 'a', 'b', 'c');
    // Profil : la télé peut écrire pour un profil du foyer.
    expect(await q(f.tv, `select org_set_hidden($1, $2, 'series_category', array['b'], true) n`, [f.h, f.soeur])).toEqual([{ n: 1 }]);
    const s = await sections(f.tv, f.h, f.soeur);
    expect(s.find((r) => r.id === 'b')).toMatchObject({ hidden_global: false, hidden_profile: true });
    expect((await sections(f.tv, f.h, f.toi)).find((r) => r.id === 'b')).toMatchObject({ hidden_profile: false });
    // Tout le monde : propriétaire seulement.
    await expect(q(f.tv, `select org_set_hidden($1, null, 'series_category', array['a'], true)`, [f.h])).rejects.toMatchObject({ code: '42501' });
    await q(f.owner, `select org_set_hidden($1, null, 'series_category', array['a'], true)`, [f.h]);
    expect((await sections(f.tv, f.h, f.toi)).find((r) => r.id === 'a')).toMatchObject({ hidden_global: true });
    await expect(q(f.owner, `select org_set_hidden($1, null, 'vod_category', array['a'], true)`, [f.h])).rejects.toMatchObject({ message: 'type-invalide' });
    // Tout masquer sauf.
    expect((await q(f.owner, `select org_series_hide_all_except($1, $2, array['c']) n`, [f.h, f.toi]))[0].n).toBe(2);
    expect((await sections(f.owner, f.h, f.toi)).filter((r) => r.hidden_profile).map((r) => r.id)).toEqual(['a', 'b']);
    // Ordre : profil, puis tout le monde, puis fournisseur ; indépendant de la section direct.
    expect(await ordre(f.owner, f.h, null)).toEqual(['a', 'b', 'c']);
    await q(f.owner, `select org_set_series_category_order($1, null, array['c','a','c','zz'])`, [f.h]);
    expect(await ordre(f.owner, f.h, f.toi)).toEqual(['c', 'a', 'b']);
    await q(f.tv, `select org_set_series_category_order($1, $2, array['b'])`, [f.h, f.toi]);
    expect(await ordre(f.owner, f.h, f.toi)).toEqual(['b', 'a', 'c']);
    expect(await ordre(f.owner, f.h, f.soeur)).toEqual(['c', 'a', 'b']);
    await expect(q(f.tv, `select org_set_series_category_order($1, null, array['b'])`, [f.h])).rejects.toMatchObject({ code: '42501' });
    const live = (await q(f.owner, 'select org_categories($1, null) r', [f.h]))[0].r;
    expect(live).toEqual([]);
    expect((await q(f.owner, `select section from category_order order by section`)).map((r) => r.section)).toEqual(['series', 'series']);
  });

  it('ma_liste_series_ordre', async () => {
    const f = await foyer();
    const r0 = Number((await q(f.tv, 'select prefs_rev($1) r', [f.h]))[0].r);
    for (const s of ['s1', 's2', 's3']) await q(f.tv, 'select org_set_series_list($1, $2, true)', [f.toi, s]);
    await q(f.tv, 'select org_set_series_list($1, $2, true)', [f.toi, 's2']); // déjà présent
    expect((await prefs(f.tv, f.toi)).series_list).toEqual(['s1', 's2', 's3']);
    await q(f.tv, `select org_reorder_series_list($1, array['s3','s1'])`, [f.toi]);
    expect((await prefs(f.tv, f.toi)).series_list).toEqual(['s3', 's1', 's2']);
    await q(f.tv, 'select org_set_series_list($1, $2, false)', [f.toi, 's1']);
    expect((await prefs(f.tv, f.toi)).series_list).toEqual(['s3', 's2']);
    expect((await prefs(f.tv, f.soeur)).series_list).toEqual([]);
    expect(Number((await q(f.tv, 'select prefs_rev($1) r', [f.h]))[0].r)).toBeGreaterThan(r0);
    // Séparée des favoris de chaînes.
    await q(f.tv, `select org_set_favorite($1, 'ch1', true)`, [f.toi]);
    const pr = await prefs(f.tv, f.toi);
    expect(pr.favorites).toEqual(['ch1']);
    expect(pr.series_list).toEqual(['s3', 's2']);
    const g = await foyer(true);
    await expect(q(g.owner, 'select org_set_series_list($1, $2, true)', [f.toi, 's1'])).rejects.toMatchObject({ code: '42501' });
  });

  it('profile_prefs_contient_series_order_et_series_list', async () => {
    const f = await foyer();
    let pr = await prefs(f.tv, f.toi);
    expect(pr.series_order).toBeNull();
    expect(pr.series_list).toEqual([]);
    for (const k of ['rev', 'hidden', 'favorites', 'playlists', 'order', 'group_copies']) expect(pr).toHaveProperty(k);
    await publish(f.tv, f.h, 'a', 'b');
    await q(f.owner, `select org_set_series_category_order($1, null, array['b','a'])`, [f.h]);
    await q(f.owner, `select org_set_hidden($1, null, 'series_category', array['a'], true)`, [f.h]);
    pr = await prefs(f.tv, f.toi);
    expect(pr.series_order).toEqual(['b', 'a']);
    expect(pr.hidden).toContainEqual({ item_type: 'series_category', item_id: 'a' });
    expect(pr.order).toBeNull();
  });

  it('record_progress_refuse_horodatage_nul', async () => {
    const f = await foyer();
    await expect(db.as(f.tv, rec9(f.soeur, 'e1', 10, 100, null, 's1', 1, 1))).rejects.toMatchObject({ message: 'horodatage-invalide' });
    await expect(db.as(f.tv, rec12(f.soeur, 'e1', 10, 100, null, 's1', 1, 1, 'N', null, null))).rejects.toMatchObject({ message: 'horodatage-invalide' });
    await expect(db.as(f.tv, (c) => c.query('select record_progress($1, $2, $3, $4, $5, null) ok', [f.soeur, 'movie', 'm', 1, 10])))
      .rejects.toMatchObject({ message: 'horodatage-invalide' });
    expect((await q(f.owner, 'select count(*)::int n from watch_progress'))[0].n).toBe(0);
  });

  it('record_progress_enrichi_et_ancienne_signature', async () => {
    const f = await foyer();
    expect((await db.as(f.tv, rec12(f.soeur, 'e1', 10, 100, T(1), 's1', 1, 1, 'Ma serie', 'http://img/a.jpg', 'c1'))).rows[0].ok).toBe(true);
    // Ancienne signature (9 arguments, puis 6 avec valeurs par défaut) : toujours utilisable, garde l'enrichissement.
    expect((await db.as(f.tv, rec9(f.soeur, 'e1', 20, 100, T(2), 's1', 1, 1))).rows[0].ok).toBe(true);
    expect((await db.as(f.tv, (c) => c.query('select record_progress($1, $2, $3, $4, $5, $6) ok', [f.soeur, 'episode', 'e1', 30, 100, T(3)]))).rows[0].ok).toBe(true);
    let row = (await q(f.owner, 'select * from watch_progress where item_id = $1', ['e1']))[0];
    expect(row).toMatchObject({ position_s: 30, series_name: 'Ma serie', series_cover: 'http://img/a.jpg', series_category: 'c1', series_id: 's1' });
    // NULL conserve ; une valeur remplace.
    await db.as(f.tv, rec12(f.soeur, 'e1', 40, 100, T(4), 's1', 1, 1, null, null, 'c2'));
    row = (await q(f.owner, 'select * from watch_progress where item_id = $1', ['e1']))[0];
    expect(row).toMatchObject({ position_s: 40, series_name: 'Ma serie', series_category: 'c2' });
    // Appel nommé façon PostgREST (9 clés puis 12 clés) : sans ambiguïté.
    await q(f.tv, `select record_progress(p => $1, item_type => 'episode', item_id => 'e1', position_s => 50, duration_s => 100,
      client_updated_at => $2, series_id => 's1', season => 1, episode => 1)`, [f.soeur, T(5)]);
    await q(f.tv, `select record_progress(p => $1, item_type => 'episode', item_id => 'e1', position_s => 60, duration_s => 100,
      client_updated_at => $2, series_id => 's1', season => 1, episode => 1, series_name => 'Renommee', series_cover => null, series_category => null)`,
      [f.soeur, T(6)]);
    row = (await q(f.owner, 'select * from watch_progress where item_id = $1', ['e1']))[0];
    expect(row).toMatchObject({ position_s: 60, series_name: 'Renommee', series_category: 'c2' });
    // Le plus récent gagne, enrichissement compris.
    expect((await db.as(f.tv, rec12(f.soeur, 'e1', 1, 100, T(1), 's1', 1, 1, 'Vieux', null, null))).rows[0].ok).toBe(false);
    expect((await q(f.owner, 'select series_name from watch_progress where item_id = $1', ['e1']))[0].series_name).toBe('Renommee');
  });

  it('continue_watching_exclut_categorie_masquee', async () => {
    const f = await foyer();
    await db.as(f.tv, rec12(f.soeur, 'e1', 10, 100, T(1), 'sA', 1, 1, 'Serie A', 'ca', 'cat1'));
    await db.as(f.tv, rec12(f.soeur, 'e2', 10, 100, T(2), 'sB', 1, 1, 'Serie B', 'cb', 'cat2'));
    await db.as(f.tv, rec9(f.soeur, 'e3', 10, 100, T(3), 'sC', 1, 1)); // sans catégorie connue
    const lire = async () => (await q(f.owner, 'select item_id, series_name, series_cover, series_category from continue_watching($1)', [f.soeur]));
    let r = await lire();
    expect(r.map((x) => x.item_id)).toEqual(['e3', 'e2', 'e1']);
    expect(r[1]).toMatchObject({ series_name: 'Serie B', series_cover: 'cb', series_category: 'cat2' });
    await q(f.owner, `select org_set_hidden($1, $2, 'series_category', array['cat1'], true)`, [f.h, f.soeur]);
    expect((await lire()).map((x) => x.item_id)).toEqual(['e3', 'e2']);
    await q(f.owner, `select org_set_hidden($1, null, 'series_category', array['cat2'], true)`, [f.h]);
    expect((await lire()).map((x) => x.item_id)).toEqual(['e3']);
    // L'autre profil n'est pas touché par le masquage du profil (mais l'est par le global).
    await db.as(f.tv, rec12(f.toi, 'e1', 10, 100, T(1), 'sA', 1, 1, 'Serie A', 'ca', 'cat1'));
    expect((await q(f.owner, 'select item_id from continue_watching($1)', [f.toi])).map((x) => x.item_id)).toEqual(['e1']);
  });

  it('set_watched_marque_et_demarque_sans_remonter_la_serie', async () => {
    const f = await foyer();
    await db.as(f.tv, rec12(f.soeur, 'e1', 50, 100, T(1), 's1', 1, 1, 'Serie', null, 'c'));
    await db.as(f.tv, rec12(f.soeur, 'x9', 5, 100, T(9), 's9', 1, 1, 'Autre', null, 'c'));
    const items = JSON.stringify([
      { item_id: 'e1', series_id: 's1', season: 1, episode: 1, duration_s: 100 },
      { item_id: 'e2', series_id: 's1', season: 1, episode: 2 },
      { item_id: 'e3', series_id: 's1', season: 1, episode: 3, duration_s: 0 },
    ]);
    const n = (await q(f.tv, 'select set_watched($1, $2::jsonb, true) n', [f.soeur, items]))[0].n;
    expect(n).toBe(3);
    const rows = await q(f.owner, `select item_id, watched, position_s, duration_s, client_updated_at from watch_progress
      where series_id = 's1' order by item_id`);
    expect(rows.map((r) => [r.item_id, r.watched, r.position_s, r.duration_s])).toEqual([
      ['e1', true, 50, 100], ['e2', true, 0, 1], ['e3', true, 0, 1]]);
    expect(new Date(rows[0].client_updated_at).toISOString()).toBe(T(1));
    expect(new Date(rows[1].client_updated_at).toISOString()).toBe('2000-01-01T00:00:00.000Z');
    // La série ne remonte pas devant une autre plus récente.
    expect((await q(f.owner, 'select item_id from continue_watching($1)', [f.soeur])).map((r) => r.item_id)).toEqual(['x9', 'e1']);
    // Démarquer : watched redevient faux, horodatage intact, aucune ligne créée pour un épisode inconnu.
    const m = (await q(f.tv, 'select set_watched($1, $2::jsonb, false) n',
      [f.soeur, JSON.stringify([{ item_id: 'e1' }, { item_id: 'e2' }, { item_id: 'inconnu' }])]))[0].n;
    expect(m).toBe(2);
    const apres = await q(f.owner, `select item_id, watched, position_s, client_updated_at from watch_progress where series_id = 's1' order by item_id`);
    expect(apres.map((r) => [r.item_id, r.watched])).toEqual([['e1', false], ['e2', false], ['e3', true]]);
    // Démarquer remet aussi la position à 0 : l'épisode ne réapparaît pas « vu » (règle des 90 %).
    expect(apres.map((r) => r.position_s)).toEqual([0, 0, 0]);
    expect(new Date(apres[0].client_updated_at).toISOString()).toBe(T(1));
    expect((await q(f.owner, `select count(*)::int n from watch_progress where item_id = 'inconnu'`))[0].n).toBe(0);
    // Doublons dans la liste : acceptés.
    await q(f.tv, `select set_watched($1, '[{"item_id":"d"},{"item_id":"d"}]'::jsonb, true)`, [f.soeur]);
    await expect(q(f.tv, `select set_watched($1, '{}'::jsonb, true)`, [f.soeur])).rejects.toMatchObject({ message: 'items-invalides' });
  });

  it('series_progress_rend_toutes_les_lignes_de_la_serie_en_une_valeur', async () => {
    const f = await foyer();
    // Plus de 1000 épisodes : un seul bloc jsonb, sans plafond.
    await db.admin.query(`insert into watch_progress (profile_id, item_type, item_id, series_id, season, episode, position_s, duration_s, watched, client_updated_at)
      select $1, 'episode', 'x' || g, 's1', 1 + g / 600, g % 600, 10, 100, false, now() - interval '1 hour' from generate_series(1, 1200) g`, [f.soeur]);
    await db.as(f.tv, rec12(f.soeur, 'autre', 5, 100, T(1), 's2', 1, 1, 'Autre', null, 'c'));
    const r = await q(f.tv, 'select series_progress($1, $2) r', [f.soeur, 's1']);
    expect(r).toHaveLength(1);
    const rows = r[0].r as any[];
    expect(rows).toHaveLength(1200);
    expect(Object.keys(rows[0]).sort()).toEqual(['client_updated_at', 'duration_s', 'episode', 'item_id', 'position_s', 'season', 'watched']);
    const order = rows.map((x) => [x.season, x.episode]);
    expect(order).toEqual([...order].sort((a, b) => a[0] - b[0] || a[1] - b[1]));
    expect(rows.some((x) => x.item_id === 'autre')).toBe(false);
    // Série inconnue : tableau vide, jamais null.
    expect((await q(f.tv, 'select series_progress($1, $2) r', [f.soeur, 'nulle']))[0].r).toEqual([]);
  });

  it('series_progress_refuse_un_etranger', async () => {
    const f = await foyer();
    const g = await foyer(true);
    await expect(q(g.owner, 'select series_progress($1, $2)', [f.soeur, 's1'])).rejects.toMatchObject({ code: '42501' });
    // Sans session : refusé (permission denied ou 42501).
    const anon = await q(null as any, 'select series_progress($1, $2)', [f.soeur, 's1']).then(() => null, (e) => e);
    expect(anon).not.toBeNull();
    expect(anon.code === '42501' || /permission denied/i.test(anon.message)).toBe(true);
  });

  it('continue_watching_categorie_resolue_sur_les_lignes_de_la_serie', async () => {
    const f = await foyer();
    // Ligne ancienne avec catégorie, ligne récente sans (ancien client ou set_watched) : la catégorie connue s'applique.
    await db.as(f.tv, rec12(f.soeur, 'e1', 10, 100, T(1), 'sA', 1, 1, 'Serie A', null, 'cat1'));
    await db.as(f.tv, rec9(f.soeur, 'e2', 10, 100, T(2), 'sA', 1, 2));
    await q(f.tv, `select set_watched($1, '[{"item_id":"e3","series_id":"sA","season":1,"episode":3}]'::jsonb, true)`, [f.soeur]);
    expect((await q(f.owner, 'select item_id, series_category from continue_watching($1)', [f.soeur]))).toEqual([{ item_id: 'e2', series_category: 'cat1' }]);
    await q(f.owner, `select org_set_hidden($1, $2, 'series_category', array['cat1'], true)`, [f.h, f.soeur]);
    expect(await q(f.owner, 'select item_id from continue_watching($1)', [f.soeur])).toEqual([]);
    // Sans aucune catégorie connue, la série reste visible (limite documentée).
    await db.as(f.tv, rec9(f.soeur, 'z1', 10, 100, T(5), 'sZ', 1, 1));
    expect((await q(f.owner, 'select item_id from continue_watching($1)', [f.soeur])).map((r) => r.item_id)).toEqual(['z1']);
  });

  it('set_watched_enrichit_et_conserve_nom_affiche_categorie', async () => {
    const f = await foyer();
    await q(f.tv, `select set_watched($1, '[{"item_id":"e1","series_id":"s1","series_name":"N","series_cover":"C","series_category":"cat1"}]'::jsonb, true)`, [f.soeur]);
    await q(f.tv, `select set_watched($1, '[{"item_id":"e1","series_name":"N2"}]'::jsonb, true)`, [f.soeur]);
    expect((await q(f.owner, 'select series_name, series_cover, series_category from watch_progress'))[0])
      .toEqual({ series_name: 'N', series_cover: 'C', series_category: 'cat1' });
  });

  it('publish_series_categories_sans_ligne_valide_ne_supprime_rien', async () => {
    const f = await foyer();
    await publish(f.tv, f.h, 'a', 'b');
    for (const bad of ['[{}]', '[{"id":null,"name":"X","position":0}]', '[{"id":"","name":"X","position":0}]']) {
      await q(f.tv, 'select publish_series_categories($1, $2::jsonb)', [f.h, bad]);
      expect((await q(f.owner, 'select count(*)::int n from series_catalog_category'))[0].n).toBe(2);
    }
  });

  it('continue_watching_plafonne_max_rows', async () => {
    const f = await foyer();
    for (let i = 0; i < 3; i++) await db.as(f.tv, rec9(f.soeur, `e${i}`, 10, 100, T(i), `s${i}`, 1, 1));
    expect(await q(f.owner, 'select item_id from continue_watching($1, -5)', [f.soeur])).toEqual([]);
    expect(await q(f.owner, 'select item_id from continue_watching($1, 1000000)', [f.soeur])).toHaveLength(3);
  });

  it('droits_complementaires', async () => {
    const f = await foyer();
    await publish(f.tv, f.h, 'a');
    await expect(q(f.tv, `select org_series_hide_all_except($1, null, array['a'])`, [f.h])).rejects.toMatchObject({ code: '42501' });
    const r0 = Number((await q(f.tv, 'select prefs_rev($1) r', [f.h]))[0].r);
    await q(f.owner, `select org_set_hidden($1, $2, 'series_category', array['a'], true)`, [f.h, f.toi]);
    expect(Number((await q(f.tv, 'select prefs_rev($1) r', [f.h]))[0].r)).toBeGreaterThan(r0);
    // can_use_profile = tout membre du foyer : le cas « membre sans droit sur le profil » n'existe pas ;
    // un non-membre est refusé (voir etranger_refuse).
    const nonMembre = await db.newUser({ anonymous: true });
    await expect(q(nonMembre, `select set_watched($1, '[{"item_id":"e"}]'::jsonb, true)`, [f.toi])).rejects.toMatchObject({ code: '42501' });
  });

  it('etranger_refuse', async () => {
    const f = await foyer();
    const g = await foyer(true);
    await publish(f.tv, f.h, 'a');
    await expect(q(g.owner, 'select publish_series_categories($1, $2::jsonb)', [f.h, cats('z')])).rejects.toMatchObject({ code: '42501' });
    await expect(q(g.owner, 'select org_series_categories($1, null)', [f.h])).rejects.toMatchObject({ code: '42501' });
    await expect(q(g.owner, `select org_set_hidden($1, null, 'series_category', array['a'], true)`, [f.h])).rejects.toMatchObject({ code: '42501' });
    await expect(q(g.owner, `select org_series_hide_all_except($1, null, array['a'])`, [f.h])).rejects.toMatchObject({ code: '42501' });
    await expect(q(g.owner, `select org_set_series_category_order($1, null, array['a'])`, [f.h])).rejects.toMatchObject({ code: '42501' });
    await expect(q(g.owner, 'select org_reorder_series_list($1, array[]::text[])', [f.toi])).rejects.toMatchObject({ code: '42501' });
    await expect(db.as(g.owner, rec12(f.toi, 'e1', 1, 10, T(1), 's', 1, 1, 'n', null, null))).rejects.toMatchObject({ code: '42501' });
    await expect(q(g.owner, `select set_watched($1, '[{"item_id":"e"}]'::jsonb, true)`, [f.toi])).rejects.toMatchObject({ code: '42501' });
    expect(await q(g.owner, 'select id from series_catalog_category')).toEqual([]);
    await expect(q(null as any, 'select publish_series_categories($1, $2::jsonb)', [f.h, cats('z')])).rejects.toBeTruthy();
  });
});
