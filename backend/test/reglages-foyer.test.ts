import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { startDb, type Db } from './db';

let db: Db;
beforeAll(async () => { db = await startDb(); });
afterAll(async () => { await db?.stop(); });
beforeEach(async () => { await db.resetHouseholds(); });

describe('réglages du foyer', () => {
  it('le propriétaire choisit le thème, une télé du foyer le lit mais ne peut pas le changer', async () => {
    const owner = await db.newUser();
    const h = (await db.as(owner, (c) => c.query(`select create_household('Maison', array['Toi']) id`))).rows[0].id;
    expect((await db.admin.query('select settings from households where id = $1', [h])).rows[0].settings).toEqual({});
    const u = await db.as(owner, (c) => c.query(`update households set settings = '{"welcome_theme":"aurore"}' where id = $1`, [h]));
    expect(u.rowCount).toBe(1);
    const tv = await db.newUser({ anonymous: true });
    await db.admin.query(`insert into household_members (household_id, user_id, role, device_name) values ($1, $2, 'device', 'Télé')`, [h, tv]);
    const r = await db.as(tv, (c) => c.query('select settings from households where id = $1', [h]));
    expect(r.rows[0].settings).toEqual({ welcome_theme: 'aurore' });
    const w = await db.as(tv, (c) => c.query(`update households set settings = '{}' where id = $1`, [h]));
    expect(w.rowCount).toBe(0);
  });
  it('refuse autre chose qu’un objet', async () => {
    const owner = await db.newUser();
    const h = (await db.as(owner, (c) => c.query(`select create_household('Maison', array['Toi']) id`))).rows[0].id;
    await expect(db.as(owner, (c) => c.query(`update households set settings = '[]' where id = $1`, [h]))).rejects.toMatchObject({ code: '23514' });
  });
});
