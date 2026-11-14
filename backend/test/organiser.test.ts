import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { startDb, type Db } from './db';

let db: Db;
beforeAll(async () => { db = await startDb(); });
afterAll(async () => { await db?.stop(); });
beforeEach(async () => { await db.resetHouseholds(); });

interface Ctx { owner: string; tv: string; h: string; toi: string; soeur: string }

// Foyer du propriétaire, télé appairée (non propriétaire), deux profils.
async function foyer(autre = false): Promise<Ctx> {
  const owner = await db.newUser({ email: `proprio-${Math.random()}@exemple.test` });
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

const cats = (...ids: string[]) => JSON.stringify(ids.map((id, i) => ({ id, name: `Cat ${id}`, position: i })));
const chans = (ids: (string | number)[], names?: string[]) =>
  JSON.stringify(ids.map((id, i) => {
    const name = names?.[i] ?? `Chaine ${id}`;
    return { id: String(id), name, norm: name.toLowerCase(), number: i + 1, position: i };
  }));

async function publish(tv: string, h: string, catIds: string[], perCat: Record<string, (string | number)[]> = {}, names: Record<string, string[]> = {}) {
  await db.as(tv, (c) => c.query('select * from publish_live_categories($1, $2::jsonb)', [h, cats(...catIds)]));
  for (const [cat, ids] of Object.entries(perCat)) {
    await db.as(tv, (c) => c.query('select publish_live_channels($1, $2, $3, 0, true, $4::jsonb)', [h, cat, `f-${cat}`, chans(ids, names[cat])]));
  }
}

const q = async <T = any>(u: string, sql: string, params: unknown[] = []) =>
  (await db.as(u, (c) => c.query(sql, params))).rows as T[];
const categories = async (u: string, h: string, p: string | null) =>
  (await q(u, 'select org_categories($1, $2) r', [h, p]))[0].r as any[];
const orderOf = async (u: string, h: string, p: string | null) => (await categories(u, h, p)).map((r) => r.id as string);
// publish_live_categories rend un objet { id: empreinte|null }.
const fingerprints = async (u: string, h: string, json: string) =>
  (await q(u, 'select publish_live_categories($1, $2::jsonb) r', [h, json]))[0].r as Record<string, string | null>;
const rev = async (u: string, h: string) => Number((await q(u, 'select prefs_rev($1) r', [h]))[0].r);

describe('correctifs de la copie du catalogue', () => {
  it('chunk_zero_efface_l_empreinte_dans_la_meme_transaction', async () => {
    const { tv, h } = await foyer();
    await publish(tv, h, ['1'], { '1': [10] });
    await db.as(tv, (c) => c.query('select publish_live_channels($1, $2, $3, 0, false, $4::jsonb)', [h, '1', 'nouvelle', chans([10, 11])]));
    const r = await fingerprints(tv, h, cats('1'));
    expect(r).toEqual({ '1': null });
  });

  it('liste_de_categories_vide_ne_supprime_rien', async () => {
    const { tv, owner, h } = await foyer();
    await publish(tv, h, ['1', '2'], { '1': [10], '2': [20] });
    const r = await fingerprints(tv, h, '[]');
    expect(r).toEqual({ '1': 'f-1', '2': 'f-2' });
    expect((await q(owner, 'select count(*)::int n from live_catalog_channel'))[0].n).toBe(2);
  });

  it('republier_une_categorie_avec_moins_de_chaines_retire_les_anciennes', async () => {
    const { tv, owner, h } = await foyer();
    await publish(tv, h, ['1'], { '1': [10, 11, 12] });
    await publish(tv, h, ['1'], { '1': [10] });
    const r = await q(owner, `select id from live_catalog_channel where category_id = '1'`);
    expect(r.map((x) => x.id)).toEqual(['10']);
  });
});

describe('organisation par profil', () => {
  it('ordre_effectif_profil_puis_foyer_puis_fournisseur', async () => {
    const f = await foyer();
    await publish(f.tv, f.h, ['a', 'b', 'c']);
    expect(await orderOf(f.owner, f.h, null)).toEqual(['a', 'b', 'c']);
    await db.as(f.owner, (c) => c.query('select org_set_category_order($1, null, $2)', [f.h, ['c', 'a', 'zz']]));
    expect(await orderOf(f.owner, f.h, null)).toEqual(['c', 'a', 'b']);
    expect(await orderOf(f.tv, f.h, f.toi)).toEqual(['c', 'a', 'b']);
    await db.as(f.owner, (c) => c.query('select org_set_category_order($1, $2, $3)', [f.h, f.toi, ['b']]));
    expect(await orderOf(f.owner, f.h, f.toi)).toEqual(['b', 'a', 'c']);
    expect(await orderOf(f.owner, f.h, f.soeur)).toEqual(['c', 'a', 'b']);
  });

  it('tout_masquer_sauf_portee_profil_et_foyer', async () => {
    const f = await foyer();
    await publish(f.tv, f.h, ['a', 'b', 'c', 'd', 'e']);
    const n = (await q(f.tv, 'select org_hide_all_except($1, $2, $3) n', [f.h, f.toi, ['a', 'b']]))[0].n;
    expect(n).toBe(3);
    const rows = await categories(f.owner, f.h, f.toi);
    expect(rows.filter((r) => r.hidden_profile).map((r) => r.id)).toEqual(['c', 'd', 'e']);
    expect(rows.some((r) => r.hidden_global)).toBe(false);
    // Réaffiche celles de keep.
    await db.as(f.tv, (c) => c.query('select org_hide_all_except($1, $2, $3)', [f.h, f.toi, ['c']]));
    const apres = await categories(f.owner, f.h, f.toi);
    expect(apres.filter((r) => !r.hidden_profile).map((r) => r.id)).toEqual(['c']);
    // Tout le monde : propriétaire seulement.
    expect((await q(f.owner, 'select org_hide_all_except($1, null, $2) n', [f.h, ['a', 'b']]))[0].n).toBe(3);
    const glob = await categories(f.owner, f.h, null);
    expect(glob.filter((r) => r.hidden_global).map((r) => r.id)).toEqual(['c', 'd', 'e']);
    await expect(q(f.tv, 'select org_hide_all_except($1, null, $2)', [f.h, ['a']])).rejects.toMatchObject({ message: 'acces-refuse' });
  });

  it('plus_de_1000_categories_toutes_renvoyees_en_ordre_effectif', async () => {
    const f = await foyer();
    const ids = Array.from({ length: 1500 }, (_, i) => `c${String(i).padStart(4, '0')}`);
    await publish(f.tv, f.h, ids);
    // Ordre du foyer : les 1 500 à l'envers sauf c0000 laissée de côté (elle suit, ordre du fournisseur).
    const inverse = ids.slice(1).reverse();
    await db.as(f.owner, (c) => c.query('select org_set_category_order($1, null, $2)', [f.h, inverse]));
    const r = await categories(f.owner, f.h, null);
    expect(r).toHaveLength(1500);
    expect(r.map((x) => x.id)).toEqual([...inverse, 'c0000']);
    expect(r[0]).toMatchObject({ name: 'Cat c1499', provider_position: 1499, hidden_global: false, hidden_profile: false, channel_count: 0 });
  });

  it('plus_de_1000_empreintes_toutes_renvoyees', async () => {
    const f = await foyer();
    const ids = Array.from({ length: 1500 }, (_, i) => String(i));
    const r = await fingerprints(f.tv, f.h, cats(...ids));
    expect(Object.keys(r)).toHaveLength(1500);
    expect(Object.values(r).every((v) => v === null)).toBe(true);
    await db.as(f.tv, (c) => c.query('select publish_live_channels($1, $2, $3, 0, true, $4::jsonb)', [f.h, '1400', 'fp', chans([1])]));
    expect((await fingerprints(f.tv, f.h, cats(...ids)))['1400']).toBe('fp');
  });

  it('plus_de_1000_categories_masquees_pour_tout_le_monde_toutes_renvoyees', async () => {
    const f = await foyer();
    const ids = Array.from({ length: 1500 }, (_, i) => `c${i}`);
    await publish(f.tv, f.h, ids);
    await db.as(f.owner, (c) => c.query('select org_set_hidden($1, null, $2, $3, true)', [f.h, 'live_category', ids]));
    // Un masquage de profil et une chaîne masquée ne comptent pas.
    await db.as(f.owner, (c) => c.query('select org_set_hidden($1, $2, $3, $4, true)', [f.h, f.toi, 'live_category', ['x-profil']]));
    await db.as(f.owner, (c) => c.query('select org_set_hidden($1, null, $2, $3, true)', [f.h, 'channel', ['chan']]));
    for (const u of [f.owner, f.tv]) {
      const r = (await q(u, 'select global_hidden_live_categories($1) r', [f.h]))[0].r as string[];
      expect(r).toHaveLength(1500);
      expect([...r].sort()).toEqual([...ids].sort());
    }
    expect((await q(f.tv, 'select global_hidden_live_categories($1) r', [(await foyer(true)).h]).catch((e) => e.message))).toBe('acces-refuse');
  });

  it('aucune_categorie_masquee_donne_un_tableau_vide', async () => {
    const f = await foyer();
    expect((await q(f.tv, 'select global_hidden_live_categories($1) r', [f.h]))[0].r).toEqual([]);
  });

  it('favoris_ordre_et_etat', async () => {
    const f = await foyer();
    await publish(f.tv, f.h, ['1', '2'], { '1': ['x'], '2': ['y', 'z'] });
    for (const id of ['x', 'y', 'z']) await db.as(f.tv, (c) => c.query('select org_set_favorite($1, $2, true)', [f.toi, id]));
    expect((await q(f.owner, 'select id from org_favorites($1)', [f.toi])).map((r) => r.id)).toEqual(['x', 'y', 'z']);
    await db.as(f.tv, (c) => c.query('select org_reorder_favorites($1, $2)', [f.toi, ['z', 'x', 'y']]));
    expect((await q(f.owner, 'select id from org_favorites($1)', [f.toi])).map((r) => r.id)).toEqual(['z', 'x', 'y']);
    await db.as(f.owner, (c) => c.query('select org_set_hidden($1, null, $2, $3, true)', [f.h, 'live_category', ['1']]));
    await db.as(f.owner, (c) => c.query('select org_set_hidden($1, $2, $3, $4, true)', [f.h, f.toi, 'channel', ['z']]));
    // y disparait de la copie
    await db.as(f.tv, (c) => c.query('select publish_live_channels($1, $2, $3, 0, true, $4::jsonb)', [f.h, '2', 'g', chans(['z'])]));
    const st = Object.fromEntries((await q(f.owner, 'select id, state from org_favorites($1)', [f.toi])).map((r) => [r.id, r.state]));
    expect(st).toEqual({ x: 'categorie-masquee', y: 'n-existe-plus', z: 'masquee' });
    await db.as(f.tv, (c) => c.query('select org_set_favorite($1, $2, false)', [f.toi, 'z']));
    expect((await q(f.owner, 'select id from org_favorites($1)', [f.toi])).map((r) => r.id)).toEqual(['x', 'y']);
  });

  it('playlists_crud_et_ordre', async () => {
    const f = await foyer();
    await publish(f.tv, f.h, ['1'], { '1': ['c1', 'c2'] });
    const a = (await q(f.tv, 'select org_playlist_create($1, $2) id', [f.toi, 'Matchs']))[0].id as string;
    const b = (await q(f.tv, 'select org_playlist_create($1, $2) id', [f.toi, 'Series']))[0].id as string;
    await db.as(f.tv, (c) => c.query('select org_playlist_rename($1, $2)', [b, 'Séries']));
    await db.as(f.tv, (c) => c.query('select org_playlists_reorder($1, $2::uuid[])', [f.toi, [b, a]]));
    await db.as(f.tv, (c) => c.query('select org_playlist_set_items($1, $2)', [a, ['c2', 'c1']]));
    let r = await q(f.owner, 'select * from org_playlists($1)', [f.toi]);
    expect(r.map((x) => [x.id, x.name, x.position, x.channel_ids])).toEqual([[b, 'Séries', 0, []], [a, 'Matchs', 1, ['c2', 'c1']]]);
    await db.as(f.tv, (c) => c.query('select org_playlist_set_items($1, $2)', [a, ['c1']]));
    await db.as(f.tv, (c) => c.query('select org_playlist_delete($1)', [b]));
    r = await q(f.owner, 'select * from org_playlists($1)', [f.toi]);
    expect(r.map((x) => [x.id, x.channel_ids])).toEqual([[a, ['c1']]]);
    await expect(q(f.tv, 'select org_playlist_create($1, $2)', [f.toi, '  '])).rejects.toMatchObject({ code: '23514' });
    await expect(q(f.tv, 'select org_playlist_rename($1, $2)', [a, ''])).rejects.toMatchObject({ code: '23514' });
  });

  it('playlists_items_noms_et_etats', async () => {
    const f = await foyer();
    await publish(f.tv, f.h, ['1', '2'], { '1': ['x'], '2': ['y', 'z', 'w'] });
    const a = (await q(f.tv, 'select org_playlist_create($1, $2) id', [f.toi, 'Mix']))[0].id as string;
    await db.as(f.tv, (c) => c.query('select org_playlist_set_items($1, $2)', [a, ['z', 'x', 'y', 'w']]));
    let r = (await q(f.owner, 'select * from org_playlists($1)', [f.toi]))[0];
    expect(r.channel_ids).toEqual(['z', 'x', 'y', 'w']);
    expect(r.items.map((i: { id: string; category_id: string; state: string }) => [i.id, i.category_id, i.state]))
      .toEqual([['z', '2', 'ok'], ['x', '1', 'ok'], ['y', '2', 'ok'], ['w', '2', 'ok']]);
    expect(typeof r.items[0].name).toBe('string');
    await db.as(f.owner, (c) => c.query('select org_set_hidden($1, null, $2, $3, true)', [f.h, 'live_category', ['1']]));
    await db.as(f.owner, (c) => c.query('select org_set_hidden($1, $2, $3, $4, true)', [f.h, f.toi, 'channel', ['z']]));
    // y disparait de la copie (reste z, w)
    await db.as(f.tv, (c) => c.query('select publish_live_channels($1, $2, $3, 0, true, $4::jsonb)', [f.h, '2', 'g', chans(['z', 'w'])]));
    r = (await q(f.owner, 'select * from org_playlists($1)', [f.toi]))[0];
    expect(r.items.map((i: { id: string; state: string }) => [i.id, i.state]))
      .toEqual([['z', 'masquee'], ['x', 'categorie-masquee'], ['y', 'n-existe-plus'], ['w', 'ok']]);
    expect(r.items[2].name).toBeNull();
    expect(r.channel_ids).toEqual(['z', 'x', 'y', 'w']);
  });

  it('recherche_normalisee_et_limitee', async () => {
    const f = await foyer();
    const ids = Array.from({ length: 300 }, (_, i) => i);
    await publish(f.tv, f.h, ['1', '2'], { '1': ids, '2': ['p'] }, { '1': ids.map((i) => `RDS ${i}`), '2': ['Promo 50% off'] });
    const r = await q(f.owner, 'select * from org_search($1, $2, null, 500)', [f.h, 'rds']);
    expect(r).toHaveLength(200);
    expect(r[0].category_id).toBe('1');
    const pct = await q(f.owner, 'select * from org_search($1, $2, null, 50)', [f.h, '%']);
    expect(pct.map((x) => x.id)).toEqual(['p']);
    expect(await q(f.owner, 'select * from org_search($1, $2, null, 50)', [f.h, '_'])).toHaveLength(0);
    expect(await q(f.owner, 'select * from org_search($1, $2, null, 50)', [f.h, '\\'])).toHaveLength(0);
    const lim = await q(f.owner, 'select * from org_search($1, $2, null, 7)', [f.h, 'rds 1']);
    expect(lim).toHaveLength(7);
  });

  it('org_channels_pagine_avec_plafond_et_etats', async () => {
    const f = await foyer();
    const ids = Array.from({ length: 1200 }, (_, i) => i);
    await publish(f.tv, f.h, ['1'], { '1': ids });
    expect(await q(f.owner, 'select * from org_channels($1, $2, null, 0, 5000)', [f.h, '1'])).toHaveLength(1000);
    await db.as(f.tv, (c) => c.query('select org_set_favorite($1, $2, true)', [f.toi, '3']));
    await db.as(f.owner, (c) => c.query('select org_set_hidden($1, null, $2, $3, true)', [f.h, 'channel', ['4']]));
    await db.as(f.owner, (c) => c.query('select org_set_hidden($1, $2, $3, $4, true)', [f.h, f.toi, 'channel', ['5']]));
    const r = await q(f.owner, 'select * from org_channels($1, $2, $3, 3, 4)', [f.h, '1', f.toi]);
    expect(r.map((x) => x.id)).toEqual(['3', '4', '5', '6']);
    expect(r.map((x) => [x.favorite, x.hidden_global, x.hidden_profile])).toEqual([
      [true, false, false], [false, true, false], [false, false, true], [false, false, false]]);
  });

  it('profile_prefs_masque_gagne_et_ordre', async () => {
    const f = await foyer();
    await publish(f.tv, f.h, ['a', 'b', 'c'], { a: ['x', 'y'] });
    let s = (await q(f.tv, 'select profile_prefs($1) p', [f.toi]))[0].p;
    expect(s).toMatchObject({ hidden: [], favorites: [], playlists: [], order: null });
    await db.as(f.owner, (c) => c.query('select org_set_hidden($1, null, $2, $3, true)', [f.h, 'live_category', ['a']]));
    await db.as(f.tv, (c) => c.query('select org_set_hidden($1, $2, $3, $4, true)', [f.h, f.toi, 'channel', ['y']]));
    await db.as(f.tv, (c) => c.query('select org_set_hidden($1, $2, $3, $4, true)', [f.h, f.soeur, 'channel', ['x']]));
    await db.as(f.tv, (c) => c.query('select org_set_favorite($1, $2, true)', [f.toi, 'y']));
    await db.as(f.tv, (c) => c.query('select org_set_favorite($1, $2, true)', [f.toi, 'x']));
    const pl = (await q(f.tv, 'select org_playlist_create($1, $2) id', [f.toi, 'Liste']))[0].id;
    await db.as(f.tv, (c) => c.query('select org_playlist_set_items($1, $2)', [pl, ['y', 'x']]));
    await db.as(f.owner, (c) => c.query('select org_set_category_order($1, null, $2)', [f.h, ['c', 'a']]));
    s = (await q(f.tv, 'select profile_prefs($1) p', [f.toi]))[0].p;
    const hidden = [...s.hidden].sort((u: any, v: any) => u.item_id.localeCompare(v.item_id));
    expect(hidden).toEqual([{ item_type: 'live_category', item_id: 'a' }, { item_type: 'channel', item_id: 'y' }]);
    expect(s.favorites).toEqual(['y', 'x']);
    expect(s.playlists).toEqual([{ id: pl, name: 'Liste', items: ['y', 'x'] }]);
    expect(s.order).toEqual(['c', 'a']);
    expect(Number(s.rev)).toBe(await rev(f.tv, f.h));
    await db.as(f.owner, (c) => c.query('select org_set_category_order($1, $2, $3)', [f.h, f.toi, ['b']]));
    expect((await q(f.tv, 'select profile_prefs($1) p', [f.toi]))[0].p.order).toEqual(['b']);
    expect((await q(f.tv, 'select profile_prefs($1) p', [f.soeur]))[0].p.order).toEqual(['c', 'a']);
  });

  it('revision_augmente_a_chaque_changement', async () => {
    const f = await foyer();
    await publish(f.tv, f.h, ['a', 'b'], { a: ['x'] });
    expect(await rev(f.owner, f.h)).toBe(0);
    const writes: [string, string, unknown[]][] = [
      [f.owner, 'select org_set_hidden($1, null, $2, $3, true)', [f.h, 'live_category', ['a']]],
      [f.owner, 'select org_hide_all_except($1, $2, $3)', [f.h, f.toi, ['a']]],
      [f.owner, 'select org_set_category_order($1, null, $2)', [f.h, ['b', 'a']]],
      [f.tv, 'select org_set_favorite($1, $2, true)', [f.toi, 'x']],
      [f.tv, 'select org_set_favorite($1, $2, true)', [f.toi, 'y']],
      [f.tv, 'select org_reorder_favorites($1, $2)', [f.toi, ['y', 'x']]],
      [f.tv, 'select org_playlist_create($1, $2)', [f.toi, 'L']],
    ];
    let last = 0;
    for (const [u, sql, params] of writes) {
      await db.as(u, (c) => c.query(sql, params));
      const now = await rev(f.owner, f.h);
      expect(now, sql).toBeGreaterThan(last);
      last = now;
    }
    const pl = (await q(f.tv, 'select id from org_playlists($1)', [f.toi]))[0].id;
    for (const [sql, params] of [
      ['select org_playlist_rename($1, $2)', [pl, 'M']],
      ['select org_playlist_set_items($1, $2)', [pl, ['x']]],
      ['select org_playlist_delete($1)', [pl]],
    ] as [string, unknown[]][]) {
      await db.as(f.tv, (c) => c.query(sql, params));
      const now = await rev(f.owner, f.h);
      expect(now, sql).toBeGreaterThan(last);
      last = now;
    }
    // Un appel sans effet (rien ne change) ne compte pas : ni réordonnancement identique, ni masquage déjà fait.
    await db.as(f.tv, (c) => c.query('select org_reorder_favorites($1, $2)', [f.toi, ['y', 'x']]));
    await db.as(f.owner, (c) => c.query('select org_set_hidden($1, null, $2, $3, true)', [f.h, 'live_category', ['a']]));
    expect(await rev(f.owner, f.h)).toBe(last);
    // Les lectures ne changent rien.
    await q(f.owner, 'select * from org_categories($1, null)', [f.h]);
    await q(f.tv, 'select profile_prefs($1)', [f.toi]);
    await q(f.tv, 'select * from org_favorites($1)', [f.toi]);
    await q(f.tv, 'select * from org_playlists($1)', [f.toi]);
    expect(await rev(f.owner, f.h)).toBe(last);
  });

  it('suppression_du_foyer_ne_casse_pas_les_declencheurs', async () => {
    const f = await foyer();
    await publish(f.tv, f.h, ['a'], { a: ['x'] });
    await db.as(f.tv, (c) => c.query('select org_set_favorite($1, $2, true)', [f.toi, 'x']));
    await db.as(f.owner, (c) => c.query('select org_set_hidden($1, null, $2, $3, true)', [f.h, 'live_category', ['a']]));
    await db.resetHouseholds();
    expect((await db.admin.query('select count(*)::int n from prefs_revision')).rows[0].n).toBe(0);
  });

  it('profil_d_un_autre_foyer_refuse_pour_les_fonctions_a_h_et_p', async () => {
    const f = await foyer();
    const g = await foyer(true);
    await expect(q(f.owner, 'select org_set_hidden($1, $2, $3, $4, true)', [f.h, g.toi, 'channel', ['x']])).rejects.toMatchObject({ message: 'acces-refuse' });
    await expect(q(f.owner, 'select * from org_categories($1, $2)', [f.h, g.toi])).rejects.toMatchObject({ message: 'acces-refuse' });
    await expect(q(f.owner, 'select org_set_category_order($1, $2, $3)', [f.h, g.toi, ['a']])).rejects.toMatchObject({ message: 'acces-refuse' });
  });

  it('type_d_element_invalide_refuse', async () => {
    const f = await foyer();
    await expect(q(f.owner, 'select org_set_hidden($1, null, $2, $3, true)', [f.h, 'movie', ['1']])).rejects.toMatchObject({ message: 'type-invalide' });
  });

  it('etranger_refuse', async () => {
    const f = await foyer();
    await publish(f.tv, f.h, ['a'], { a: ['x'] });
    const intrus = await db.newUser({ email: 'intrus-org@exemple.test' });
    const refus = { message: 'acces-refuse' };
    await expect(q(intrus, 'select * from org_categories($1, null)', [f.h])).rejects.toMatchObject(refus);
    await expect(q(intrus, 'select * from org_channels($1, $2, null, 0, 10)', [f.h, 'a'])).rejects.toMatchObject(refus);
    await expect(q(intrus, 'select * from org_search($1, $2, null, 10)', [f.h, 'x'])).rejects.toMatchObject(refus);
    await expect(q(intrus, 'select * from org_favorites($1)', [f.toi])).rejects.toMatchObject(refus);
    await expect(q(intrus, 'select * from org_playlists($1)', [f.toi])).rejects.toMatchObject(refus);
    await expect(q(intrus, 'select profile_prefs($1)', [f.toi])).rejects.toMatchObject(refus);
    await expect(q(intrus, 'select prefs_rev($1)', [f.h])).rejects.toMatchObject(refus);
    await expect(q(intrus, 'select global_hidden_live_categories($1)', [f.h])).rejects.toMatchObject(refus);
    await expect(q(intrus, 'select org_set_hidden($1, null, $2, $3, true)', [f.h, 'live_category', ['a']])).rejects.toMatchObject(refus);
    await expect(q(intrus, 'select org_set_hidden($1, $2, $3, $4, true)', [f.h, f.toi, 'channel', ['x']])).rejects.toMatchObject(refus);
    await expect(q(intrus, 'select org_set_favorite($1, $2, true)', [f.toi, 'x'])).rejects.toMatchObject(refus);
    await expect(q(intrus, 'select org_playlist_create($1, $2)', [f.toi, 'L'])).rejects.toMatchObject(refus);
    await expect(q(intrus, 'select org_set_category_order($1, null, $2)', [f.h, ['a']])).rejects.toMatchObject(refus);
    // anonyme sans foyer
    await expect(q(null as unknown as string, 'select prefs_rev($1)', [f.h])).rejects.toThrow();
  });
});
