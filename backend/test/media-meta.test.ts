import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { startDb, type Db } from './db';

let db: Db;
beforeAll(async () => { db = await startDb(); });
afterAll(async () => { await db?.stop(); });
beforeEach(async () => { await db.resetHouseholds(); await db.admin.query('delete from media_meta'); });

describe('fiches TMDB (media_meta)', () => {
  it('lisibles par un membre de foyer, jamais écrites par lui ; invisibles sans foyer', async () => {
    await db.admin.query(`insert into media_meta (key, kind, query_title, status, tmdb_id, imdb_rating) values ('movie:dune:2021', 'movie', 'Dune', 'ok', 438631, 8.0)`);
    const owner = await db.newUser();
    await db.as(owner, (c) => c.query(`select create_household('Maison', array['Toi'])`));
    const r = await db.as(owner, (c) => c.query('select key, imdb_rating from media_meta'));
    expect(r.rows).toEqual([{ key: 'movie:dune:2021', imdb_rating: '8.0' }]);
    await expect(db.as(owner, (c) => c.query(`insert into media_meta (key, kind, query_title, status) values ('x', 'movie', 'x', 'ok')`))).rejects.toMatchObject({ code: '42501' });
    const u = await db.as(owner, (c) => c.query(`update media_meta set imdb_rating = 1`));
    expect(u.rowCount).toBe(0);
    const stranger = await db.newUser({ anonymous: true });
    expect((await db.as(stranger, (c) => c.query('select key from media_meta'))).rows).toEqual([]);
  });
});
