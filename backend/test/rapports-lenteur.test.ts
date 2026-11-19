import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { startDb, type Db } from './db';

let db: Db;
beforeAll(async () => { db = await startDb(); });
afterAll(async () => { await db?.stop(); });
beforeEach(async () => { await db.resetHouseholds(); });

async function maison(email: string) {
  const owner = await db.newUser({ email });
  const h = (await db.as(owner, (c) => c.query(`select create_household('Maison', $1::text[]) id`, [['Toi']]))).rows[0].id as string;
  const tv = await db.newUser({ anonymous: true });
  await db.admin.query(`insert into household_members (household_id, user_id, role, device_name) values ($1, $2, 'device', 'TV Salon')`, [h, tv]);
  return { owner, h, tv };
}
const send = (u: string | null, h: string, kind: string, data: unknown, version = '0.40.0', summary = 'Écran figé 3 s') =>
  db.as(u, (c) => c.query('select report_diagnostic($1, $2, $3, $4, $5::jsonb) id', [h, kind, version, summary, JSON.stringify(data)])).then((r) => r.rows[0].id);

describe('rapports de lenteur', () => {
  it('la télé envoie ; le propriétaire lit ; la version de l’appareil est notée', async () => {
    const { owner, h, tv } = await maison('proprio-rl1@exemple.test');
    const id = await send(tv, h, 'manuel', { memoire: { libre_mo: 120 }, blocages: [{ duree_ms: 3200 }] });
    expect(Number(id)).toBeGreaterThan(0);
    const rows = await db.as(owner, (c) => c.query('select kind, app_version, summary, device_name, data from diagnostic_reports')).then((r) => r.rows);
    expect(rows).toEqual([{ kind: 'manuel', app_version: '0.40.0', summary: 'Écran figé 3 s', device_name: 'TV Salon', data: { memoire: { libre_mo: 120 }, blocages: [{ duree_ms: 3200 }] } }]);
    const m = await db.as(owner, (c) => c.query(`select app_version from household_members where user_id = $1`, [tv])).then((r) => r.rows[0]);
    expect(m.app_version).toBe('0.40.0');
    // Une télé ne lit pas les rapports (propriétaire seulement).
    expect(await db.as(tv, (c) => c.query('select * from diagnostic_reports')).then((r) => r.rows)).toHaveLength(0);
  });

  it('refus : autre maison, anonyme, type inconnu, rapport invalide ou trop gros', async () => {
    const { h, tv } = await maison('proprio-rl2@exemple.test');
    const autre = await db.newUser();
    await expect(send(autre, h, 'manuel', {})).rejects.toThrow('acces-refuse');
    await expect(send(null, h, 'manuel', {})).rejects.toThrow();
    await expect(send(tv, h, 'n_importe', {})).rejects.toThrow('type-inconnu');
    await expect(send(tv, h, 'manuel', [1, 2])).rejects.toThrow('rapport-invalide');
    await expect(send(tv, h, 'manuel', { x: 'a'.repeat(800_001) })).rejects.toThrow('rapport-trop-gros');
  });

  it('limites : 30 par heure, 12 automatiques par jour ; texte et version raccourcis', async () => {
    const { owner, h, tv } = await maison('proprio-rl3@exemple.test');
    for (let i = 0; i < 12; i++) await send(tv, h, 'auto', { i });
    await expect(send(tv, h, 'auto', {})).rejects.toThrow('trop-de-rapports');
    for (let i = 0; i < 18; i++) await send(tv, h, 'manuel', { i });
    await expect(send(tv, h, 'manuel', {})).rejects.toThrow('trop-de-rapports');
    await db.admin.query(`update diagnostic_reports set created_at = now() - interval '2 hours'`);
    await send(tv, h, 'manuel', {}, 'v'.repeat(50), 's'.repeat(500));
    const last = await db.as(owner, (c) => c.query('select app_version, summary from diagnostic_reports order by id desc limit 1')).then((r) => r.rows[0]);
    expect(last.app_version).toHaveLength(20);
    expect(last.summary).toHaveLength(300);
  });

  it('300 derniers rapports gardés par maison', async () => {
    const { owner, h, tv } = await maison('proprio-rl4@exemple.test');
    await db.admin.query(`insert into diagnostic_reports (household_id, device_user_id, kind, data, created_at)
      select $1, $2, 'manuel', '{}'::jsonb, now() - (g || ' hours')::interval from generate_series(2, 320) g`, [h, tv]);
    await send(tv, h, 'manuel', { dernier: true });
    const n = await db.as(owner, (c) => c.query('select count(*)::int n from diagnostic_reports')).then((r) => r.rows[0].n);
    expect(n).toBe(300);
    const newest = await db.as(owner, (c) => c.query('select data from diagnostic_reports order by created_at desc limit 1')).then((r) => r.rows[0].data);
    expect(newest).toEqual({ dernier: true });
  });

  it('version de l’app : notée pour l’appareil qui la donne, refusée hors de la maison', async () => {
    const { owner, h, tv } = await maison('proprio-rl5@exemple.test');
    await db.as(tv, (c) => c.query('select report_app_version($1, $2)', [h, ' 0.40.0 ']));
    const rows = await db.as(owner, (c) => c.query('select role, app_version, app_version_at is not null as quand from household_members order by role')).then((r) => r.rows);
    expect(rows).toEqual([{ role: 'device', app_version: '0.40.0', quand: true }, { role: 'owner', app_version: null, quand: false }]);
    const autre = await db.newUser();
    await expect(db.as(autre, (c) => c.query('select report_app_version($1, $2)', [h, 'x']))).rejects.toThrow('acces-refuse');
  });
});
