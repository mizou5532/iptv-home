import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { startDb, type Db } from './db';

let db: Db;
beforeAll(async () => { db = await startDb(); });
afterAll(async () => { await db?.stop(); });
beforeEach(async () => { await db.resetHouseholds(); });

async function foyer(owner: string): Promise<string> {
  const r = await db.as(owner, (c) => c.query(`select create_household('Maison', array['Toi','Sœur']) id`));
  return r.rows[0].id;
}

describe("onglet d'ouverture du profil", () => {
  it('un nouveau profil ouvre sur le direct', async () => {
    const a = await db.newUser();
    const h = await foyer(a);
    const r = await db.as(a, (c) => c.query('select home from profiles where household_id = $1', [h]));
    expect(r.rows.map((x) => x.home)).toEqual(['direct', 'direct']);
  });

  it('un membre choisit séries, films, sport ou direct', async () => {
    const a = await db.newUser();
    const h = await foyer(a);
    for (const v of ['series', 'films', 'sport', 'direct']) {
      const u = await db.as(a, (c) => c.query(`update profiles set home = $2 where household_id = $1 and name = 'Sœur'`, [h, v]));
      expect(u.rowCount).toBe(1);
      const r = await db.admin.query(`select home from profiles where household_id = $1 and name = 'Sœur'`, [h]);
      expect(r.rows[0].home).toBe(v);
    }
  });

  it('refuse une valeur inconnue ou vide', async () => {
    const a = await db.newUser();
    const h = await foyer(a);
    for (const v of ['mixte', 'radio', null]) {
      await expect(db.as(a, (c) => c.query(`update profiles set home = $2 where household_id = $1`, [h, v])))
        .rejects.toMatchObject({ code: expect.stringMatching(/^(23514|23502)$/) });
    }
  });

  it("un étranger ne change pas l'onglet d'un autre foyer", async () => {
    const a = await db.newUser();
    const b = await db.newUser();
    const h = await foyer(a);
    const u = await db.as(b, (c) => c.query(`update profiles set home = 'sport' where household_id = $1`, [h]));
    expect(u.rowCount).toBe(0);
  });
});
