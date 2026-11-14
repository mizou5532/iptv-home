import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { startDb, type Db } from './db';

let db: Db;
beforeAll(async () => { db = await startDb(); });
afterAll(async () => { await db?.stop(); });

async function foyer(owner: string): Promise<string> {
  const r = await db.as(owner, (c) => c.query(`select create_household('Maison', $1::text[]) id`, [['Toi']]));
  const h = r.rows[0].id;
  await db.as(owner, (c) => c.query(`select set_xtream_credentials($1, 'http://serveur.exemple.test', 'u', 'p')`, [h]));
  return h;
}
const code = (tv: string, nom = 'Télé du salon'): Promise<string> =>
  db.as(tv, (c) => c.query(`select create_pairing_code($1) code`, [nom])).then((r) => r.rows[0].code);
const approve = (u: string, h: string, c: string) =>
  db.as(u, (cl) => cl.query('select approve_pairing($1, $2) n', [h, c])).then((r) => r.rows[0].n);

describe('appairage de la télé', () => {
  it('la télé anonyme obtient un code, l’ordinateur le valide, la télé rejoint le foyer', async () => {
    const owner = await db.newUser({ email: 'proprio-ap1@exemple.test' });
    const h = await foyer(owner);
    const tv = await db.newUser({ anonymous: true });
    const c = await code(tv);
    expect(c).toMatch(/^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$/);
    expect(await approve(owner, h, c)).toBe('Télé du salon');
    expect((await db.as(tv, (cl) => cl.query('select id from my_households()'))).rows).toEqual([{ id: h }]);
    expect((await db.as(tv, (cl) => cl.query('select username from xtream_credentials'))).rowCount).toBe(1);
    const m = await db.admin.query(`select role, device_name from household_members where user_id = $1`, [tv]);
    expect(m.rows).toEqual([{ role: 'device', device_name: 'Télé du salon' }]);
  });

  it('code tapé en minuscules avec un espace', async () => {
    const owner = await db.newUser({ email: 'proprio-ap2@exemple.test' });
    const h = await foyer(owner);
    const tv = await db.newUser({ anonymous: true });
    const c = await code(tv);
    expect(await approve(owner, h, c.slice(0, 3).toLowerCase() + ' ' + c.slice(3).toLowerCase())).toBe('Télé du salon');
  });

  it('nouveau code : remplace les anciens codes non utilisés du même appareil', async () => {
    const tv = await db.newUser({ anonymous: true });
    await code(tv);
    await code(tv);
    const r = await db.admin.query('select count(*)::int n from pairing_codes where device_user_id = $1', [tv]);
    expect(r.rows[0].n).toBe(1);
  });

  it('code expiré', async () => {
    const owner = await db.newUser({ email: 'proprio-ap3@exemple.test' });
    const h = await foyer(owner);
    const tv = await db.newUser({ anonymous: true });
    const c = await code(tv);
    await db.admin.query(`update pairing_codes set expires_at = now() - interval '1 minute' where code = $1`, [c]);
    await expect(approve(owner, h, c)).rejects.toThrow('code-expire');
  });

  it('code inconnu', async () => {
    const owner = await db.newUser({ email: 'proprio-ap4@exemple.test' });
    const h = await foyer(owner);
    await expect(approve(owner, h, 'ZZZZZZ')).rejects.toThrow('code-invalide');
  });

  it('code à usage unique', async () => {
    const owner = await db.newUser({ email: 'proprio-ap5@exemple.test' });
    const h = await foyer(owner);
    const tv = await db.newUser({ anonymous: true });
    const c = await code(tv);
    await approve(owner, h, c);
    await expect(approve(owner, h, c)).rejects.toThrow('code-invalide');
  });

  it('un non-membre ne peut pas valider un code pour ce foyer', async () => {
    const owner = await db.newUser({ email: 'proprio-ap6@exemple.test' });
    const h = await foyer(owner);
    const autre = await db.newUser();
    const tv = await db.newUser({ anonymous: true });
    const c = await code(tv);
    await expect(approve(autre, h, c)).rejects.toThrow('acces-refuse');
    const r = await db.admin.query('select claimed_at from pairing_codes where code = $1', [c]);
    expect(r.rows[0].claimed_at).toBeNull();
  });

  it('pairing_codes inaccessible en direct', async () => {
    const tv = await db.newUser({ anonymous: true });
    await code(tv);
    const r = await db.as(tv, (c) => c.query('select * from pairing_codes'));
    expect(r.rows).toHaveLength(0);
  });

  it('un non-membre ne peut pas s’insérer dans household_members', async () => {
    const owner = await db.newUser({ email: 'proprio-ap7@exemple.test' });
    const h = await foyer(owner);
    const intrus = await db.newUser();
    await expect(db.as(intrus, (c) => c.query(
      `insert into household_members (household_id, user_id, role) values ($1, $2, 'device')`, [h, intrus],
    ))).rejects.toMatchObject({ code: '42501' });
  });
});

describe('retrait d’un appareil', () => {
  it('appareil retiré : perd l’accès tout de suite', async () => {
    const owner = await db.newUser({ email: 'proprio-ap8@exemple.test' });
    const h = await foyer(owner);
    const tv = await db.newUser({ anonymous: true });
    await approve(owner, h, await code(tv));
    await db.as(owner, (c) => c.query('select revoke_device($1, $2)', [h, tv]));
    for (const q of ['select * from xtream_credentials', 'select * from profiles', 'select * from my_households()']) {
      expect((await db.as(tv, (c) => c.query(q))).rows).toHaveLength(0);
    }
  });

  it('seul le propriétaire retire un appareil', async () => {
    const owner = await db.newUser({ email: 'proprio-ap9@exemple.test' });
    const h = await foyer(owner);
    const tv = await db.newUser({ anonymous: true });
    await approve(owner, h, await code(tv));
    const autre = await db.newUser();
    await expect(db.as(autre, (c) => c.query('select revoke_device($1, $2)', [h, tv]))).rejects.toThrow('acces-refuse');
    await expect(db.as(tv, (c) => c.query('select revoke_device($1, $2)', [h, tv]))).rejects.toThrow('acces-refuse');
  });

  it('le propriétaire ne peut pas se retirer lui-même', async () => {
    const owner = await db.newUser({ email: 'proprio-ap10@exemple.test' });
    const h = await foyer(owner);
    await expect(db.as(owner, (c) => c.query('select revoke_device($1, $2)', [h, owner]))).rejects.toThrow('acces-refuse');
  });
});
