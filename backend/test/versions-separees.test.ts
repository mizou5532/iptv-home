import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { startDb, type Db } from './db';

let db: Db;
beforeAll(async () => { db = await startDb(); });
afterAll(async () => { await db?.stop(); });
beforeEach(async () => { await db.resetHouseholds(); });

async function foyer(owner: string): Promise<string> {
  const r = await db.as(owner, (c) => c.query(`select create_household('Maison', array['Toi']) id`));
  return r.rows[0].id;
}
const splits = async (u: string, h: string) =>
  (await db.as(u, (c) => c.query(`select settings -> 'version_splits' s from households where id = $1`, [h]))).rows[0].s;

describe('versions séparées', () => {
  it('séparer, réunir, sans doublon ; les autres réglages restent', async () => {
    const a = await db.newUser();
    const h = await foyer(a);
    await db.as(a, (c) => c.query(`update households set settings = '{"seasons":{"on":true}}' where id = $1`, [h]));
    await db.as(a, (c) => c.query(`select set_version_split($1, 'movie', '123', true)`, [h]));
    await db.as(a, (c) => c.query(`select set_version_split($1, 'movie', '123', true)`, [h]));
    await db.as(a, (c) => c.query(`select set_version_split($1, 'series', '9', true)`, [h]));
    expect(await splits(a, h)).toEqual(['movie:123', 'series:9']);
    await db.as(a, (c) => c.query(`select set_version_split($1, 'movie', '123', false)`, [h]));
    expect(await splits(a, h)).toEqual(['series:9']);
    const s = await db.as(a, (c) => c.query(`select settings -> 'seasons' s from households where id = $1`, [h]));
    expect(s.rows[0].s).toEqual({ on: true });
  });

  it('valeurs invalides et étrangers refusés', async () => {
    const a = await db.newUser();
    const b = await db.newUser();
    const h = await foyer(a);
    await expect(db.as(a, (c) => c.query(`select set_version_split($1, 'live', '1', true)`, [h]))).rejects.toMatchObject({ code: '22023' });
    await expect(db.as(a, (c) => c.query(`select set_version_split($1, 'movie', 'a b', true)`, [h]))).rejects.toMatchObject({ code: '22023' });
    await expect(db.as(b, (c) => c.query(`select set_version_split($1, 'movie', '1', true)`, [h]))).rejects.toMatchObject({ code: '42501' });
  });
});
