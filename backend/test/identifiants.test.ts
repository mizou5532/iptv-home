import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { startDb, type Db } from './db';

let db: Db;
beforeAll(async () => { db = await startDb(); });
afterAll(async () => { await db?.stop(); });

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
});
