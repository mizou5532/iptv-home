import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { startDb, type Db } from './db';

let db: Db;
beforeAll(async () => { db = await startDb(); });
afterAll(async () => { await db?.stop(); });
beforeEach(async () => { await db.resetHouseholds(); });

interface Ctx { owner: string; tv: string; h: string; toi: string; soeur: string }

async function foyer(): Promise<Ctx> {
  const owner = await db.newUser({ email: `p${Math.random()}@exemple.test` });
  const h = (await db.as(owner, (c) => c.query(`select create_household('Maison', array['Toi', 'Sœur']) id`))).rows[0].id as string;
  const tv = await db.newUser({ anonymous: true });
  await db.admin.query(`insert into household_members (household_id, user_id, role) values ($1, $2, 'device')`, [h, tv]);
  const p = await db.admin.query('select id, name from profiles where household_id = $1', [h]);
  const id = (n: string) => p.rows.find((r) => r.name === n).id as string;
  return { owner, tv, h, toi: id('Toi'), soeur: id('Sœur') };
}

const q = async <T = any>(u: string, sql: string, params: unknown[] = []) =>
  (await db.as(u, (c) => c.query(sql, params))).rows as T[];
const BASE = Date.now() - 3600_000;
const T = (n: number) => new Date(BASE + n * 1000).toISOString();
const ep = (u: string, p: string, id: string, serie: string, at: string, cat = 'c1') =>
  q(u, 'select record_progress($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12) ok',
    [p, 'episode', id, 30, 100, at, serie, 1, 1, `Série ${serie}`, '/c.jpg', cat]);
const film = (u: string, p: string, id: string, at: string, cat = 'v1') =>
  q(u, 'select record_movie_progress($1, $2, $3, $4, $5, $6, $7, $8) ok', [p, id, 30, 100, at, `Film ${id}`, null, cat]);
const enCours = async (u: string, p: string) => (await q(u, 'select coalesce(series_id, item_id) id from continue_watching($1, 50)', [p])).map((r) => r.id);
const filmsEnCours = async (u: string, p: string) => (await q(u, 'select item_id from continue_movies($1, 50)', [p])).map((r) => r.item_id);
const titres = (...ids: string[]) => JSON.stringify(ids.map((id, i) => ({ id, name: `Titre ${id}`, norm: `titre ${id}`, cover: null, cat: i % 2 ? 'c2' : 'c1', year: '2020', rating: String(i) })));

describe('retirer de Reprendre', () => {
  it('une série retirée disparaît, revient si on la relance, et la progression est gardée', async () => {
    const f = await foyer();
    await ep(f.tv, f.toi, 'e1', 's1', T(1));
    await ep(f.tv, f.toi, 'e2', 's1', T(2));
    await ep(f.tv, f.toi, 'e9', 's2', T(3));
    expect(await enCours(f.owner, f.toi)).toEqual(['s2', 's1']);
    expect(await q(f.owner, `select dismiss_continue($1, 'series', array['s1']) n`, [f.toi])).toEqual([{ n: 2 }]);
    expect(await enCours(f.owner, f.toi)).toEqual(['s2']);
    expect(await enCours(f.tv, f.toi)).toEqual(['s2']);
    // Progression gardée.
    expect((await db.admin.query(`select position_s from watch_progress where item_id = 'e2'`)).rows[0].position_s).toBe(30);
    // Une ancienne progression qui arrive en retard ne la fait pas revenir ; une nouvelle lecture, si.
    await ep(f.tv, f.toi, 'e1', 's1', T(1.5));
    expect(await enCours(f.owner, f.toi)).toEqual(['s2']);
    await ep(f.tv, f.toi, 'e3', 's1', new Date(Date.now() + 2000).toISOString());
    expect(await enCours(f.owner, f.toi)).toEqual(['s1', 's2']);
  });

  it('un film retiré disparaît et peut être remis ; un autre profil ou un étranger ne touche à rien', async () => {
    const f = await foyer();
    await film(f.tv, f.toi, 'm1', T(1));
    await film(f.tv, f.toi, 'm2', T(2));
    await film(f.tv, f.soeur, 'm1', T(3));
    expect(await q(f.tv, `select dismiss_continue($1, 'movie', array['m1']) n`, [f.toi])).toEqual([{ n: 1 }]);
    expect(await filmsEnCours(f.owner, f.toi)).toEqual(['m2']);
    expect(await enCours(f.owner, f.toi)).toEqual(['m2']);
    expect(await filmsEnCours(f.owner, f.soeur)).toEqual(['m1']);
    await q(f.owner, `select dismiss_continue($1, 'movie', array['m1'], false)`, [f.toi]);
    expect(await filmsEnCours(f.owner, f.toi)).toEqual(['m2', 'm1']);
    await expect(q(f.owner, `select dismiss_continue($1, 'chaine', array['m1'])`, [f.toi])).rejects.toMatchObject({ message: 'type-invalide' });
    const autre = await db.newUser();
    await expect(q(autre, `select dismiss_continue($1, 'movie', array['m2'])`, [f.toi])).rejects.toMatchObject({ code: '42501' });
  });

  it('une horloge de télé en avance ne fait pas revenir le titre retiré', async () => {
    const f = await foyer();
    await film(f.tv, f.toi, 'm1', new Date(Date.now() + 4 * 60_000).toISOString());
    await q(f.owner, `select dismiss_continue($1, 'movie', array['m1'])`, [f.toi]);
    expect(await filmsEnCours(f.owner, f.toi)).toEqual([]);
  });
});

describe('titres du catalogue', () => {
  it('la télé publie par lots, la publication suivante remplace, un lot vide ne supprime rien', async () => {
    const f = await foyer();
    expect(await q(f.tv, `select publish_catalog_titles($1, 'series', 100, $2::jsonb) n`, [f.h, titres('a', 'b')])).toEqual([{ n: 2 }]);
    await q(f.tv, `select publish_catalog_titles($1, 'series', 100, $2::jsonb, true)`, [f.h, titres('c')]);
    expect((await db.admin.query(`select item_id from catalog_title order by 1`)).rows.map((r) => r.item_id)).toEqual(['a', 'b', 'c']);
    await q(f.tv, `select publish_catalog_titles($1, 'series', 200, $2::jsonb, true)`, [f.h, titres('b', 'd')]);
    expect((await db.admin.query(`select item_id from catalog_title order by 1`)).rows.map((r) => r.item_id)).toEqual(['b', 'd']);
    await q(f.tv, `select publish_catalog_titles($1, 'series', 300, '[]'::jsonb, true)`, [f.h]);
    expect((await db.admin.query(`select count(*)::int n from catalog_title`)).rows[0].n).toBe(2);
    // Une publication plus ancienne n'écrase pas une plus récente.
    await q(f.tv, `select publish_catalog_titles($1, 'series', 150, $2::jsonb)`, [f.h, JSON.stringify([{ id: 'b', name: 'Vieux', norm: 'vieux' }])]);
    expect((await db.admin.query(`select name from catalog_title where item_id = 'b'`)).rows[0].name).toBe('Titre b');
    await expect(q(f.tv, `select publish_catalog_titles($1, 'chaine', 1, '[]'::jsonb)`, [f.h])).rejects.toMatchObject({ message: 'type-invalide' });
    const autre = await db.newUser({ anonymous: true });
    await expect(q(autre, `select publish_catalog_titles($1, 'series', 1, '[]'::jsonb)`, [f.h])).rejects.toMatchObject({ code: '42501' });
    expect(await q(autre, 'select * from catalog_title')).toEqual([]);
  });

  it('la recherche ignore les catégories masquées, met les débuts de titre en premier et signale Ma liste', async () => {
    const f = await foyer();
    const rows = [
      { id: 's1', name: 'The Office', norm: 'the office', cat: 'c1', rating: '8.1' },
      { id: 's2', name: 'Office Space', norm: 'office space', cat: 'c1', rating: '7' },
      { id: 's3', name: 'Office caché', norm: 'office cache', cat: 'c2', rating: '9' },
      { id: 's4', name: '100% Offices', norm: '100% offices', cat: 'c1' },
    ];
    await q(f.tv, `select publish_catalog_titles($1, 'series', 1, $2::jsonb, true)`, [f.h, JSON.stringify(rows)]);
    await q(f.owner, `select org_set_hidden($1, $2, 'series_category', array['c2'], true)`, [f.h, f.toi]);
    await q(f.owner, `select org_set_series_list($1, 's1', true)`, [f.toi]);
    const cherche = async (p: string, s: string) => (await q(f.owner, `select search_catalog($1, 'series', $2) r`, [p, s]))[0].r as any[];
    const toi = await cherche(f.toi, 'office');
    expect(toi.map((r) => r.id)).toEqual(['s2', 's1', 's4']);
    expect(toi.find((r) => r.id === 's1').in_list).toBe(true);
    expect((await cherche(f.soeur, 'office')).map((r) => r.id)).toEqual(['s3', 's2', 's1', 's4']);
    expect(await cherche(f.toi, 'o')).toEqual([]);
    expect((await cherche(f.toi, '0%')).map((r) => r.id)).toEqual(['s4']);
    expect(await cherche(f.toi, '_')).toEqual([]);
  });

  it('Ma liste avec noms et affiches, titres choisis par identifiants', async () => {
    const f = await foyer();
    await q(f.tv, `select publish_catalog_titles($1, 'series', 1, $2::jsonb, true)`, [f.h, titres('a', 'b')]);
    await q(f.owner, `select org_set_series_list($1, 'b', true)`, [f.toi]);
    await q(f.owner, `select org_set_series_list($1, 'zz', true)`, [f.toi]);
    await q(f.tv, `select org_set_movie_list($1, 'm1', true, 'Mon film', null, 'v1')`, [f.toi]);
    const l = (await q(f.owner, 'select media_lists($1) r', [f.toi]))[0].r;
    expect(l.series.map((s: any) => [s.id, s.name, s.known])).toEqual([['b', 'Titre b', true], ['zz', null, false]]);
    expect(l.movies.map((s: any) => [s.id, s.name, s.known])).toEqual([['m1', 'Mon film', true]]);
    const by = (await q(f.owner, `select catalog_titles_by_ids($1, $2::jsonb) r`, [f.toi, JSON.stringify([{ kind: 'series', id: 'a' }, { kind: 'movie', id: 'a' }])]))[0].r;
    expect(by.map((t: any) => [t.kind, t.id, t.name])).toEqual([['series', 'a', 'Titre a']]);
  });
});
