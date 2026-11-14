import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { startDb, type Db } from './db';

let db: Db;
beforeAll(async () => { db = await startDb(); });
afterAll(async () => { await db?.stop(); });
// Un seul foyer par base (create_household refuse le second) : on repart de zéro à chaque test.
beforeEach(async () => { await db.resetHouseholds(); });

// autre = true : foyer étranger inséré directement en base (create_household n'en accepte qu'un).
async function foyer(autre = false) {
  const owner = await db.newUser({ email: `g${Math.random()}@exemple.test` });
  const noms = ['Toi', 'Sœur'];
  const h = autre
    ? await db.seedHousehold(owner, noms)
    : (await db.as(owner, (c) => c.query(`select create_household('Maison', $1::text[]) id`, [noms]))).rows[0].id;
  const device = await db.newUser({ anonymous: true });
  await db.admin.query(`insert into household_members (household_id, user_id, role) values ($1, $2, 'device')`, [h, device]);
  const p = await db.admin.query('select id, name from profiles where household_id = $1', [h]);
  const id = (n: string) => p.rows.find((r) => r.name === n).id as string;
  return { owner, device, toi: id('Toi'), soeur: id('Sœur') };
}

const T = (n: number) => new Date(Date.UTC(2026, 10, 14, 12, 0, n)).toISOString();

const rec = (
  p: string, type: string, id: string, pos: number, dur: number, at: string,
  serie: string | null = null, s: number | null = null, e: number | null = null,
) => (c: any) =>
  c.query('select record_progress($1, $2, $3, $4, $5, $6, $7, $8, $9) ok', [p, type, id, pos, dur, at, serie, s, e]);

const lire = (p: string, id: string) => (c: any) =>
  c.query('select position_s, watched from watch_progress where profile_id = $1 and item_id = $2', [p, id]);

describe('record_progress', () => {
  it('écrit puis relit la progression', async () => {
    const f = await foyer();
    const r = await db.as(f.device, rec(f.soeur, 'episode', 'e1', 120, 2600, T(1), 's1', 1, 3));
    expect(r.rows[0].ok).toBe(true);
    const row = (await db.as(f.owner, (c) => c.query('select * from watch_progress where profile_id = $1', [f.soeur]))).rows[0];
    expect(row).toMatchObject({ item_type: 'episode', item_id: 'e1', series_id: 's1', season: 1, episode: 3, position_s: 120, duration_s: 2600, watched: false });
  });

  it('ignore une écriture plus ancienne ou égale arrivée en retard', async () => {
    const f = await foyer();
    await db.as(f.device, rec(f.soeur, 'movie', 'm1', 500, 6000, T(20)));
    expect((await db.as(f.device, rec(f.soeur, 'movie', 'm1', 10, 6000, T(10)))).rows[0].ok).toBe(false);
    expect((await db.as(f.device, rec(f.soeur, 'movie', 'm1', 11, 6000, T(20)))).rows[0].ok).toBe(false);
    expect((await db.as(f.owner, lire(f.soeur, 'm1'))).rows[0].position_s).toBe(500);
    expect((await db.as(f.device, rec(f.soeur, 'movie', 'm1', 600, 6000, T(30)))).rows[0].ok).toBe(true);
    expect((await db.as(f.owner, lire(f.soeur, 'm1'))).rows[0].position_s).toBe(600);
  });

  it('deux appareils en parallèle : la plus récente gagne', async () => {
    const f = await foyer();
    await Promise.all([
      db.as(f.device, rec(f.soeur, 'movie', 'm2', 100, 6000, T(5))),
      db.as(f.owner, rec(f.soeur, 'movie', 'm2', 900, 6000, T(9))),
    ]);
    expect((await db.as(f.owner, lire(f.soeur, 'm2'))).rows[0].position_s).toBe(900);
  });

  it('vu à 90 %, et le reste même relu au début', async () => {
    const f = await foyer();
    await db.as(f.device, rec(f.soeur, 'movie', 'm3', 5399, 6000, T(1)));
    expect((await db.as(f.owner, lire(f.soeur, 'm3'))).rows[0].watched).toBe(false);
    await db.as(f.device, rec(f.soeur, 'movie', 'm3', 5400, 6000, T(2)));
    expect((await db.as(f.owner, lire(f.soeur, 'm3'))).rows[0].watched).toBe(true);
    await db.as(f.device, rec(f.soeur, 'movie', 'm3', 0, 6000, T(3)));
    const row = (await db.as(f.owner, lire(f.soeur, 'm3'))).rows[0];
    expect(row).toEqual({ position_s: 0, watched: true });
  });

  it("refuse un profil d'un autre foyer", async () => {
    const f = await foyer();
    const g = await foyer(true);
    await expect(db.as(g.owner, rec(f.soeur, 'movie', 'm4', 1, 10, T(1)))).rejects.toMatchObject({ code: '42501' });
  });

  it("refuse l'écriture directe", async () => {
    const f = await foyer();
    await expect(db.as(f.owner, (c) => c.query(
      `insert into watch_progress (profile_id, item_type, item_id, position_s, duration_s, client_updated_at)
       values ($1, 'movie', 'x', 1, 10, now())`, [f.soeur]))).rejects.toMatchObject({ code: '42501' });
    await db.as(f.owner, rec(f.soeur, 'movie', 'y', 1, 10, T(1)));
    await db.as(f.owner, (c) => c.query(`update watch_progress set position_s = 9 where profile_id = $1`, [f.soeur]));
    expect((await db.as(f.owner, lire(f.soeur, 'y'))).rows[0].position_s).toBe(1);
    await db.as(f.owner, (c) => c.query(`delete from watch_progress where profile_id = $1`, [f.soeur]));
    expect((await db.as(f.owner, lire(f.soeur, 'y'))).rows).toHaveLength(1);
  });

  it('lecture : un autre foyer ne voit rien', async () => {
    const f = await foyer();
    const g = await foyer(true);
    await db.as(f.owner, rec(f.soeur, 'movie', 'm5', 1, 10, T(1)));
    expect((await db.as(g.owner, (c) => c.query('select * from watch_progress'))).rows).toHaveLength(0);
  });
});

describe('suppression de profil', () => {
  it('supprime la progression du profil sans toucher aux autres', async () => {
    const f = await foyer();
    await db.as(f.owner, rec(f.soeur, 'movie', 'm1', 10, 100, T(1)));
    await db.as(f.owner, rec(f.toi, 'movie', 'm1', 10, 100, T(1)));
    await db.as(f.owner, (c) => c.query('delete from profiles where id = $1', [f.soeur]));
    const q = (p: string) => db.admin.query('select 1 from watch_progress where profile_id = $1', [p]);
    expect((await q(f.soeur)).rows).toHaveLength(0);
    expect((await q(f.toi)).rows).toHaveLength(1);
  });
});

describe('continue_watching', () => {
  const cw = (p: string, n?: number) => (c: any) =>
    n === undefined
      ? c.query('select item_id from continue_watching($1)', [p])
      : c.query('select item_id from continue_watching($1, $2)', [p, n]);

  it('un seul épisode par série (le plus récent), trié du plus récent au plus ancien', async () => {
    const f = await foyer();
    await db.as(f.device, rec(f.soeur, 'episode', 'e1', 100, 2600, T(1), 's1', 1, 1));
    await db.as(f.device, rec(f.soeur, 'episode', 'e2', 50, 2600, T(5), 's1', 1, 2));
    await db.as(f.device, rec(f.soeur, 'movie', 'm1', 70, 6000, T(3)));
    const r = await db.as(f.owner, cw(f.soeur));
    expect(r.rows.map((x) => x.item_id)).toEqual(['e2', 'm1']);
  });

  it('exclut les vus et les positions nulles', async () => {
    const f = await foyer();
    await db.as(f.device, rec(f.soeur, 'movie', 'vu', 5800, 6000, T(1)));
    await db.as(f.device, rec(f.soeur, 'movie', 'zero', 0, 6000, T(2)));
    await db.as(f.device, rec(f.soeur, 'movie', 'ok', 10, 6000, T(3)));
    expect((await db.as(f.owner, cw(f.soeur))).rows.map((x) => x.item_id)).toEqual(['ok']);
  });

  it('respecte max_rows', async () => {
    const f = await foyer();
    for (let i = 1; i <= 3; i++) await db.as(f.device, rec(f.soeur, 'movie', `m${i}`, 10, 100, T(i)));
    expect((await db.as(f.owner, cw(f.soeur, 2))).rows.map((x) => x.item_id)).toEqual(['m3', 'm2']);
  });

  it("un ancien épisode abandonné ne ressurgit pas : la dernière ligne de la série gagne, même vue", async () => {
    const f = await foyer();
    await db.as(f.device, rec(f.soeur, 'episode', 'e3', 100, 2600, T(1), 's1', 1, 3));
    await db.as(f.device, rec(f.soeur, 'episode', 'e5', 2500, 2600, T(5), 's1', 1, 5));
    const r = await db.as(f.owner, (c) => c.query('select item_id, watched from continue_watching($1)', [f.soeur]));
    expect(r.rows).toEqual([{ item_id: 'e5', watched: true }]);
  });

  it("un id de série ne collisionne pas avec un id de film", async () => {
    const f = await foyer();
    await db.as(f.device, rec(f.soeur, 'episode', 'e1', 10, 2600, T(1), 'x', 1, 1));
    await db.as(f.device, rec(f.soeur, 'movie', 'x', 10, 6000, T(2)));
    expect((await db.as(f.owner, cw(f.soeur))).rows.map((r) => r.item_id)).toEqual(['x', 'e1']);
  });

  it("refuse un profil d'un autre foyer", async () => {
    const f = await foyer();
    const g = await foyer(true);
    await expect(db.as(g.owner, cw(f.soeur))).rejects.toMatchObject({ code: '42501' });
  });
});
