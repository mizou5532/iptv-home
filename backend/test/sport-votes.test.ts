import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { startDb, type Db } from './db';

let db: Db;
beforeAll(async () => { db = await startDb(); });
afterAll(async () => { await db?.stop(); });
beforeEach(async () => { await db.resetHouseholds(); });

describe('mémoire du sport (votes de la maison)', () => {
  it('une télé du foyer vote, le score est borné, un étranger ne voit ni ne vote', async () => {
    const owner = await db.newUser();
    const h = (await db.as(owner, (c) => c.query(`select create_household('Maison', array['Toi']) id`))).rows[0].id;
    const tv = await db.newUser({ anonymous: true });
    await db.admin.query(`insert into household_members (household_id, user_id, role, device_name) values ($1, $2, 'device', 'Télé')`, [h, tv]);
    const vote = (u: string, d: number) => db.as(u, (c) => c.query(`select sport_vote($1, 'nhl:MTL', 'rds hd', $2) s`, [h, d]));
    expect((await vote(tv, 1)).rows[0].s).toBe(1);
    expect((await vote(tv, 1)).rows[0].s).toBe(2);
    for (let i = 0; i < 5; i++) await vote(tv, -3);
    expect((await vote(tv, 0)).rows[0].s).toBe(-9);
    await expect(vote(tv, 5)).rejects.toMatchObject({ code: '22023' });
    const r = await db.as(owner, (c) => c.query('select team_key, channel_key, score from sport_channel_vote'));
    expect(r.rows).toEqual([{ team_key: 'nhl:MTL', channel_key: 'rds hd', score: -9 }]);
    const other = await db.newUser();
    await expect(vote(other, 1)).rejects.toMatchObject({ code: '42501' });
    expect((await db.as(other, (c) => c.query('select * from sport_channel_vote'))).rows).toEqual([]);
    await expect(db.as(tv, (c) => c.query(`insert into sport_channel_vote values ($1, 'x', 'y', 9, now())`, [h]))).rejects.toMatchObject({ code: '42501' });
  });
});
