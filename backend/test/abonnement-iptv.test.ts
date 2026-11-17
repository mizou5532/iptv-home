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

describe('abonnement IPTV', () => {
  it('la télé rapporte, les membres lisent ; un nouveau rapport remplace l’ancien', async () => {
    const a = await db.newUser();
    const h = await foyer(a);
    await db.as(a, (c) => c.query(`select report_iptv_account($1, '2027-03-12T05:00:00Z', 'Active', 2)`, [h]));
    await db.as(a, (c) => c.query(`select report_iptv_account($1, '2027-04-12T05:00:00Z', '  Active  ', 999)`, [h]));
    const r = await db.as(a, (c) => c.query('select expires_at, status, max_connections from iptv_account'));
    expect(r.rows).toHaveLength(1);
    expect(r.rows[0].expires_at.toISOString()).toBe('2027-04-12T05:00:00.000Z');
    expect(r.rows[0].status).toBe('Active');
    expect(r.rows[0].max_connections).toBeNull();
  });

  it('un étranger ne peut ni écrire ni lire', async () => {
    const a = await db.newUser();
    const b = await db.newUser();
    const h = await foyer(a);
    await db.as(a, (c) => c.query(`select report_iptv_account($1, null, 'Active', 1)`, [h]));
    await expect(db.as(b, (c) => c.query(`select report_iptv_account($1, null, 'Active', 1)`, [h]))).rejects.toMatchObject({ code: '42501' });
    const r = await db.as(b, (c) => c.query('select * from iptv_account'));
    expect(r.rows).toHaveLength(0);
    await expect(db.as(a, (c) => c.query(`insert into iptv_account (household_id) values ($1)`, [h]))).rejects.toBeTruthy();
  });
});
