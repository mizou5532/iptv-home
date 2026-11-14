import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { startDb, type Db } from './db';

let db: Db;
beforeAll(async () => { db = await startDb(); });
afterAll(async () => { await db?.stop(); });
// Un seul foyer par base (create_household refuse le second) : on repart de zéro à chaque test.
beforeEach(async () => { await db.resetHouseholds(); });

async function foyer(owner: string): Promise<string> {
  const r = await db.as(owner, (c) => c.query(`select create_household('Maison', $1::text[]) id`, [['Toi']]));
  return r.rows[0].id;
}
const set = (u: string, h: string, s = 'http://serveur.exemple.test:8080', n = 'utilisateur-test', p = 'mdp-test') =>
  db.as(u, (c) => c.query('select set_xtream_credentials($1, $2, $3, $4)', [h, s, n, p]));

describe('identifiants Xtream du foyer', () => {
  it('le propriétaire enregistre puis relit (valeurs rognées, remplacement)', async () => {
    const owner = await db.newUser({ email: 'proprio-id@exemple.test' });
    const h = await foyer(owner);
    await set(owner, h, '  http://serveur.exemple.test  ', ' utilisateur-test ', ' mdp-test ');
    let r = await db.as(owner, (c) => c.query('select server, username, password from xtream_credentials where household_id = $1', [h]));
    expect(r.rows).toEqual([{ server: 'http://serveur.exemple.test', username: 'utilisateur-test', password: 'mdp-test' }]);
    await set(owner, h, 'http://autre.exemple.test', 'u2', 'p2');
    r = await db.as(owner, (c) => c.query('select server from xtream_credentials where household_id = $1', [h]));
    expect(r.rows).toEqual([{ server: 'http://autre.exemple.test' }]);
  });

  it('un appareil membre relit mais ne peut pas écrire', async () => {
    const owner = await db.newUser({ email: 'proprio-id2@exemple.test' });
    const h = await foyer(owner);
    await set(owner, h);
    const tv = await db.newUser({ anonymous: true });
    await db.admin.query(`insert into household_members (household_id, user_id, role, device_name) values ($1, $2, 'device', 'Télé')`, [h, tv]);
    const r = await db.as(tv, (c) => c.query('select username from xtream_credentials where household_id = $1', [h]));
    expect(r.rows).toEqual([{ username: 'utilisateur-test' }]);
    await expect(set(tv, h)).rejects.toThrow('acces-refuse');
  });

  it('un non-membre lit 0 ligne et ne peut pas écrire', async () => {
    const owner = await db.newUser({ email: 'proprio-id3@exemple.test' });
    const h = await foyer(owner);
    await set(owner, h);
    const autre = await db.newUser();
    const r = await db.as(autre, (c) => c.query('select * from xtream_credentials'));
    expect(r.rows).toHaveLength(0);
    await expect(set(autre, h)).rejects.toThrow('acces-refuse');
  });

  it('insert direct refusé par la RLS', async () => {
    const owner = await db.newUser({ email: 'proprio-id4@exemple.test' });
    const h = await foyer(owner);
    await expect(db.as(owner, (c) => c.query(
      `insert into xtream_credentials (household_id, server, username, password) values ($1, 'a', 'b', 'c')`, [h],
    ))).rejects.toThrow();
  });

  it('valeur vide : identifiants-incomplets', async () => {
    const owner = await db.newUser({ email: 'proprio-id5@exemple.test' });
    const h = await foyer(owner);
    await expect(set(owner, h, 'http://serveur.exemple.test', 'utilisateur-test', '  ')).rejects.toThrow('identifiants-incomplets');
  });

  it('un appareil ne modifie ni ne supprime les identifiants en direct', async () => {
    const owner = await db.newUser({ email: 'proprio-id6@exemple.test' });
    const h = await foyer(owner);
    await set(owner, h);
    const tv = await db.newUser({ anonymous: true });
    await db.admin.query(`insert into household_members (household_id, user_id, role, device_name) values ($1, $2, 'device', 'Télé')`, [h, tv]);
    const u = await db.as(tv, (c) => c.query(`update xtream_credentials set password = 'vole' where household_id = $1`, [h]));
    expect(u.rowCount).toBe(0);
    const d = await db.as(tv, (c) => c.query('delete from xtream_credentials where household_id = $1', [h]));
    expect(d.rowCount).toBe(0);
    const r = await db.admin.query('select server, username, password from xtream_credentials where household_id = $1', [h]);
    expect(r.rows).toEqual([{ server: 'http://serveur.exemple.test:8080', username: 'utilisateur-test', password: 'mdp-test' }]);
  });

  it('non connecté : lit 0 ligne et ne peut pas appeler set_xtream_credentials', async () => {
    const owner = await db.newUser({ email: 'proprio-id7@exemple.test' });
    const h = await foyer(owner);
    await set(owner, h);
    expect((await db.as(null, (c) => c.query('select * from xtream_credentials'))).rows).toHaveLength(0);
    await expect(db.as(null, (c) => c.query(`select set_xtream_credentials($1, 'a', 'b', 'c')`, [h])))
      .rejects.toMatchObject({ code: '42501', message: expect.stringMatching(/permission denied/) });
  });

  it('le propriétaire d’un autre foyer ne lit ni n’écrit les identifiants du foyer A', async () => {
    const ownerA = await db.newUser({ email: 'proprio-a@exemple.test' });
    const a = await foyer(ownerA);
    await set(ownerA, a);
    const ownerB = await db.newUser({ email: 'proprio-b@exemple.test' });
    const b = await db.seedHousehold(ownerB, ['Moi']);
    await set(ownerB, b, 'http://b.exemple.test', 'ub', 'pb');
    const r = await db.as(ownerB, (c) => c.query('select household_id from xtream_credentials'));
    expect(r.rows).toEqual([{ household_id: b }]);
    await expect(set(ownerB, a, 'http://pirate.exemple.test', 'x', 'y')).rejects.toMatchObject({ code: '42501', message: 'acces-refuse' });
    const ra = await db.admin.query('select server from xtream_credentials where household_id = $1', [a]);
    expect(ra.rows).toEqual([{ server: 'http://serveur.exemple.test:8080' }]);
  });
});
