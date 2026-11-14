import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { startDb, type Db } from './db';

let db: Db;
beforeAll(async () => { db = await startDb(); });
afterAll(async () => { await db?.stop(); });

async function foyer() {
  const owner = await db.newUser({ email: `g${Math.random()}@exemple.test` });
  const h = (await db.as(owner, (c) => c.query(`select create_household('Maison', array['Toi','Sœur']) id`))).rows[0].id;
  const addDevice = async (name: string) => {
    const u = await db.newUser({ anonymous: true });
    await db.admin.query(
      `insert into household_members (household_id, user_id, role, device_name) values ($1, $2, 'device', $3)`,
      [h, u, name],
    );
    return u;
  };
  const tele = await addDevice('Télé du salon');
  const pc = await addDevice('Ordinateur');
  const p = await db.admin.query('select id, name from profiles where household_id = $1', [h]);
  const soeur = p.rows.find((r) => r.name === 'Sœur').id as string;
  return { owner, h, tele, pc, soeur, addDevice };
}

const claim = (h: string, p: string | null, name: string, label: string, max: number, takeover = false) =>
  (c: any) => c.query('select claim_stream($1, $2, $3, $4, $5, $6) r', [h, p, name, label, max, takeover]);
const beat = (id: string) => (c: any) => c.query('select heartbeat_stream($1) ok', [id]);

describe('qui regarde', () => {
  it('la télé lit, l’ordinateur voit qui occupe le flux', async () => {
    const f = await foyer();
    const a = (await db.as(f.tele, claim(f.h, f.soeur, 'Télé du salon', 'Le Roi Lion', 1))).rows[0].r;
    expect(a.status).toBe('ok');
    const b = (await db.as(f.pc, claim(f.h, null, 'Ordinateur', 'Match', 1))).rows[0].r;
    expect(b).toEqual({
      status: 'occupe',
      occupants: [{ device_name: 'Télé du salon', profile_name: 'Sœur', label: 'Le Roi Lion' }],
    });
    expect((await db.admin.query('select count(*)::int n from active_streams where household_id = $1', [f.h])).rows[0].n).toBe(1);
  });

  it('prise de la main : ok, et la télé est prévenue', async () => {
    const f = await foyer();
    const a = (await db.as(f.tele, claim(f.h, f.soeur, 'Télé du salon', 'Le Roi Lion', 1))).rows[0].r;
    expect((await db.as(f.tele, beat(a.stream_id))).rows[0].ok).toBe(true);
    const b = (await db.as(f.pc, claim(f.h, null, 'Ordinateur', 'Match', 1, true))).rows[0].r;
    expect(b.status).toBe('ok');
    expect((await db.as(f.tele, beat(a.stream_id))).rows[0].ok).toBe(false);
    expect((await db.as(f.pc, beat(b.stream_id))).rows[0].ok).toBe(true);
  });

  it('une lecture périmée (60 s) ne bloque plus', async () => {
    const f = await foyer();
    await db.as(f.tele, claim(f.h, null, 'Télé du salon', 'Vieux', 1));
    await db.admin.query(`update active_streams set last_heartbeat = now() - interval '60 seconds' where household_id = $1`, [f.h]);
    expect((await db.as(f.pc, claim(f.h, null, 'Ordinateur', 'Neuf', 1))).rows[0].r.status).toBe('ok');
    expect((await db.admin.query('select count(*)::int n from active_streams where household_id = $1', [f.h])).rows[0].n).toBe(1);
  });

  it('max_connections = 2 : deux lectures acceptées, la troisième non', async () => {
    const f = await foyer();
    const t = await f.addDevice('Tablette');
    expect((await db.as(f.tele, claim(f.h, null, 'Télé', 'A', 2))).rows[0].r.status).toBe('ok');
    expect((await db.as(f.pc, claim(f.h, null, 'Ordinateur', 'B', 2))).rows[0].r.status).toBe('ok');
    expect((await db.as(t, claim(f.h, null, 'Tablette', 'C', 2))).rows[0].r.status).toBe('occupe');
  });

  it('max_connections < 1 est traité comme 1', async () => {
    const f = await foyer();
    await db.as(f.tele, claim(f.h, null, 'Télé', 'A', 0));
    expect((await db.as(f.pc, claim(f.h, null, 'Ordinateur', 'B', 0))).rows[0].r.status).toBe('occupe');
  });

  it('un même appareil qui relance remplace sa lecture sans se bloquer', async () => {
    const f = await foyer();
    await db.as(f.tele, claim(f.h, null, 'Télé', 'A', 1));
    expect((await db.as(f.tele, claim(f.h, null, 'Télé', 'B', 1))).rows[0].r.status).toBe('ok');
    expect((await db.admin.query('select count(*)::int n from active_streams where household_id = $1', [f.h])).rows[0].n).toBe(1);
  });

  it('refuse un non-membre et un profil d’un autre foyer', async () => {
    const f = await foyer();
    const g = await foyer();
    const intrus = await db.newUser({ anonymous: true });
    await expect(db.as(intrus, claim(f.h, null, 'X', 'Y', 1))).rejects.toThrow('acces-refuse');
    await expect(db.as(f.tele, claim(f.h, g.soeur, 'X', 'Y', 1))).rejects.toThrow('acces-refuse');
  });

  it('heartbeat et release ne concernent que son propre appareil', async () => {
    const f = await foyer();
    const a = (await db.as(f.tele, claim(f.h, null, 'Télé', 'A', 2))).rows[0].r;
    expect((await db.as(f.pc, beat(a.stream_id))).rows[0].ok).toBe(false);
    await db.as(f.pc, (c) => c.query('select release_stream($1)', [a.stream_id]));
    expect((await db.admin.query('select count(*)::int n from active_streams where id = $1', [a.stream_id])).rows[0].n).toBe(1);
    await db.as(f.tele, (c) => c.query('select release_stream($1)', [a.stream_id]));
    expect((await db.admin.query('select count(*)::int n from active_streams where id = $1', [a.stream_id])).rows[0].n).toBe(0);
  });

  it('lecture réservée aux membres, écriture directe interdite', async () => {
    const f = await foyer();
    await db.as(f.tele, claim(f.h, null, 'Télé', 'A', 1));
    const intrus = await db.newUser({ anonymous: true });
    expect((await db.as(f.pc, (c) => c.query('select * from active_streams'))).rows).toHaveLength(1);
    expect((await db.as(intrus, (c) => c.query('select * from active_streams'))).rows).toHaveLength(0);
    await expect(db.as(f.pc, (c) => c.query(
      `insert into active_streams (household_id, device_user_id, device_name, label) values ($1, $2, 'x', 'y')`, [f.h, f.pc]),
    )).rejects.toThrow();
  });

  it('deux demandes simultanées avec max 1 : une seule gagne', async () => {
    const f = await foyer();
    const rs = await Promise.all([
      db.as(f.tele, claim(f.h, null, 'Télé', 'A', 1)),
      db.as(f.pc, claim(f.h, null, 'Ordinateur', 'B', 1)),
    ]);
    const st = rs.map((r) => r.rows[0].r.status).sort();
    expect(st).toEqual(['occupe', 'ok']);
  });

  it('la table est publiée en temps réel', async () => {
    const r = await db.admin.query(
      `select 1 from pg_publication_tables where pubname = 'supabase_realtime' and tablename = 'active_streams'`);
    expect(r.rowCount).toBe(1);
  });
});
