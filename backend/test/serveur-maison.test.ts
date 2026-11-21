import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { startDb, type Db } from './db';

let db: Db;
beforeAll(async () => { db = await startDb(); });
afterAll(async () => { await db?.stop(); });
beforeEach(async () => { await db.resetHouseholds(); });

const URL = 'https://192.168.1.20:8443';
const PIN = 'sha256/AbCdEfGhIjKlMnOpQrStUvWxYz0123456789+/AbCdE=';

async function maison(email: string) {
  const owner = await db.newUser({ email });
  const h = (await db.as(owner, (c) => c.query(`select create_household('Maison', $1::text[]) id`, [['Toi']]))).rows[0].id as string;
  const tv = await db.newUser({ anonymous: true });
  const serveur = await db.newUser({ anonymous: true });
  await db.admin.query(`insert into household_members (household_id, user_id, role, device_name) values ($1, $2, 'device', 'TV Salon'), ($1, $3, 'device', 'Serveur maison')`, [h, tv, serveur]);
  return { owner, h, tv, serveur };
}
const register = (u: string | null, h: string, url = URL, pin = PIN, version: string | null = '0.1.0') =>
  db.as(u, (c) => c.query('select register_home_server($1, $2, $3, $4)', [h, url, pin, version]));
const issue = (u: string | null, h: string) =>
  db.as(u, (c) => c.query('select issue_home_server_token($1) t', [h])).then((r) => r.rows[0].t as string);
const check = (u: string | null, h: string, token: string) =>
  db.as(u, (c) => c.query('select check_home_server_token($1, $2) id', [h, token])).then((r) => r.rows[0].id as string | null);

describe('serveur maison', () => {
  it('le serveur s’enregistre ; un membre lit l’adresse et l’empreinte ; un étranger ne lit rien', async () => {
    const { owner, h, tv, serveur } = await maison('proprio-sm1@exemple.test');
    await register(serveur, h);
    for (const u of [owner, tv]) {
      const rows = await db.as(u, (c) => c.query('select url, pin, version, server_user_id from home_server')).then((r) => r.rows);
      expect(rows).toEqual([{ url: URL, pin: PIN, version: '0.1.0', server_user_id: serveur }]);
    }
    const autre = await db.newUser();
    expect(await db.as(autre, (c) => c.query('select * from home_server')).then((r) => r.rows)).toHaveLength(0);
    // Réenregistrement : remplace la ligne.
    await register(serveur, h, 'https://192.168.1.21:8443', PIN, '0.2.0');
    const un = await db.as(owner, (c) => c.query('select url, version from home_server')).then((r) => r.rows);
    expect(un).toEqual([{ url: 'https://192.168.1.21:8443', version: '0.2.0' }]);
    // Aucune écriture directe, aucune lecture des jetons.
    await expect(db.as(tv, (c) => c.query(`update home_server set url = 'https://x'`))).rejects.toThrow();
    await expect(db.as(owner, (c) => c.query('select * from home_server_tokens'))).rejects.toThrow();
  });

  it('refus : non membre, propriétaire anonyme, adresse http, empreinte sans sha256/', async () => {
    const { owner, h, serveur } = await maison('proprio-sm2@exemple.test');
    const autre = await db.newUser();
    await expect(register(autre, h)).rejects.toThrow('acces-refuse');
    await expect(register(null, h)).rejects.toThrow();
    await expect(register(owner, h)).rejects.toThrow('acces-refuse');
    await expect(register(serveur, h, 'http://192.168.1.20:8080')).rejects.toThrow('adresse-invalide');
    await expect(register(serveur, h, URL, 'AbCdEf=')).rejects.toThrow('empreinte-invalide');
  });

  it('la télé obtient un jeton ; le serveur le vérifie et reçoit l’identifiant de la télé ; un faux jeton rend null', async () => {
    const { h, tv, serveur } = await maison('proprio-sm3@exemple.test');
    await register(serveur, h);
    const token = await issue(tv, h);
    expect(token).toMatch(/^[0-9a-f]{64}$/);
    expect(await check(serveur, h, token)).toBe(tv);
    expect(await check(serveur, h, 'f'.repeat(64))).toBeNull();
    expect(await check(serveur, h, '')).toBeNull();
    // Le jeton n'est jamais stocké en clair.
    const stocke = await db.admin.query(`select encode(token_hash, 'hex') hx from home_server_tokens`).then((r) => r.rows);
    expect(stocke).toHaveLength(1);
    expect(stocke[0].hx).not.toBe(token);
    const autre = await db.newUser();
    await expect(issue(autre, h)).rejects.toThrow('acces-refuse');
  });

  it('un nouveau jeton remplace l’ancien', async () => {
    const { h, tv, serveur } = await maison('proprio-sm4@exemple.test');
    await register(serveur, h);
    const un = await issue(tv, h);
    const deux = await issue(tv, h);
    expect(deux).not.toBe(un);
    expect(await check(serveur, h, un)).toBeNull();
    expect(await check(serveur, h, deux)).toBe(tv);
  });

  it('jeton refusé après revoke_device', async () => {
    const { owner, h, tv, serveur } = await maison('proprio-sm5@exemple.test');
    await register(serveur, h);
    const token = await issue(tv, h);
    expect(await check(serveur, h, token)).toBe(tv);
    await db.as(owner, (c) => c.query('select revoke_device($1, $2)', [h, tv]));
    expect(await check(serveur, h, token)).toBeNull();
  });

  it('seul le serveur enregistré peut vérifier un jeton (une autre télé du foyer reçoit acces-refuse)', async () => {
    const { owner, h, tv, serveur } = await maison('proprio-sm6@exemple.test');
    const tv2 = await db.newUser({ anonymous: true });
    await db.admin.query(`insert into household_members (household_id, user_id, role, device_name) values ($1, $2, 'device', 'TV Chambre')`, [h, tv2]);
    const token = await issue(tv, h);
    // Aucun serveur enregistré : personne ne vérifie.
    await expect(check(serveur, h, token)).rejects.toThrow('acces-refuse');
    await register(serveur, h);
    await expect(check(tv2, h, token)).rejects.toThrow('acces-refuse');
    await expect(check(tv, h, token)).rejects.toThrow('acces-refuse');
    await expect(check(owner, h, token)).rejects.toThrow('acces-refuse');
    await expect(check(null, h, token)).rejects.toThrow();
    expect(await check(serveur, h, token)).toBe(tv);
  });
});
