import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { startDb, type Db } from './db';

let db: Db;
beforeAll(async () => { db = await startDb(); });
afterAll(async () => { await db?.stop(); });
// Un seul foyer par base (create_household refuse le second) : on repart de zéro à chaque test.
beforeEach(async () => { await db.resetHouseholds(); });

// autre = true : foyer étranger inséré directement en base (create_household n'en accepte qu'un).
async function foyer(autre = false) {
  const owner = await db.newUser({ email: `g${Math.random()}@exemple.test` });
  const noms = ['Toi', 'Sœur'];
  const h = autre
    ? await db.seedHousehold(owner, noms)
    : (await db.as(owner, (c) => c.query(`select create_household('Maison', $1::text[]) id`, [noms]))).rows[0].id;
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

// key : identifiant stable de l'installation (par défaut, le nom de l'appareil).
const claim = (h: string, p: string | null, name: string, label: string, max: number, takeover = false, key = name) =>
  (c: any) => c.query('select claim_stream($1, $2, $3, $4, $5, $6, $7) r', [h, p, name, label, max, key, takeover]);
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
    const g = await foyer(true);
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
      `insert into active_streams (household_id, device_user_id, device_key, device_name, label) values ($1, $2, 'k', 'x', 'y')`, [f.h, f.pc]),
    )).rejects.toMatchObject({ code: '42501' });
  });

  it('deux demandes qui se chevauchent avec max 1 : la seconde attend le verrou puis voit « occupé »', async () => {
    const f = await foyer();
    const open = async (uid: string) => {
      const c = await db.admin.connect();
      await c.query('begin');
      await c.query('set local role authenticated');
      await c.query(`select set_config('request.jwt.claims', $1, true)`, [JSON.stringify({ sub: uid, role: 'authenticated' })]);
      return c;
    };
    const a = await open(f.tele);
    const b = await open(f.pc);
    try {
      const ra = await claim(f.h, null, 'Télé', 'A', 1)(a);
      expect(ra.rows[0].r.status).toBe('ok');
      let fini = false;
      const pb = claim(f.h, null, 'Ordinateur', 'B', 1)(b).then((r: any) => { fini = true; return r; });
      await new Promise((r) => setTimeout(r, 200));
      expect(fini).toBe(false); // bloquée sur le verrou du foyer
      await a.query('commit');
      const rb = await pb;
      expect(rb.rows[0].r.status).toBe('occupe');
      await b.query('commit');
    } finally {
      await a.query('rollback').catch(() => {});
      await b.query('rollback').catch(() => {});
      a.release();
      b.release();
    }
  });

  it('appareil révoqué : sa lecture disparaît, son battement échoue, la place est libre', async () => {
    const f = await foyer();
    const a = (await db.as(f.tele, claim(f.h, null, 'Télé', 'A', 1))).rows[0].r;
    await db.as(f.owner, (c) => c.query('select revoke_device($1, $2)', [f.h, f.tele]));
    expect((await db.admin.query('select count(*)::int n from active_streams where id = $1', [a.stream_id])).rows[0].n).toBe(0);
    expect((await db.as(f.tele, beat(a.stream_id))).rows[0].ok).toBe(false);
    expect((await db.as(f.pc, claim(f.h, null, 'Ordinateur', 'B', 1))).rows[0].r.status).toBe('ok');
  });

  it('un non-membre ne peut ni battre ni libérer (même avec une ligne résiduelle)', async () => {
    const f = await foyer();
    const intrus = await db.newUser({ anonymous: true });
    const id = (await db.admin.query(
      `insert into active_streams (household_id, device_user_id, device_key, device_name, label) values ($1, $2, 'k', 'x', 'y') returning id`,
      [f.h, intrus])).rows[0].id;
    expect((await db.as(intrus, beat(id))).rows[0].ok).toBe(false);
    await db.as(intrus, (c) => c.query('select release_stream($1)', [id]));
    expect((await db.admin.query('select count(*)::int n from active_streams where id = $1', [id])).rows[0].n).toBe(1);
  });

  it('la table est publiée en temps réel', async () => {
    const r = await db.admin.query(
      `select 1 from pg_publication_tables where pubname = 'supabase_realtime' and tablename = 'active_streams'`);
    expect(r.rowCount).toBe(1);
  });
});

describe('deux ordinateurs sur le compte du propriétaire', () => {
  const n = async (h: string) => (await db.admin.query('select count(*)::int n from active_streams where household_id = $1', [h])).rows[0].n;

  it('max 1 : le second ordinateur voit « occupé » au lieu de chasser le premier', async () => {
    const f = await foyer();
    const a = (await db.as(f.owner, claim(f.h, f.soeur, 'PC soeur', 'Film', 1, false, 'pc-soeur'))).rows[0].r;
    expect(a.status).toBe('ok');
    const b = (await db.as(f.owner, claim(f.h, null, 'PC proprio', 'Match', 1, false, 'pc-proprio'))).rows[0].r;
    expect(b).toEqual({ status: 'occupe', occupants: [{ device_name: 'PC soeur', profile_name: 'Sœur', label: 'Film' }] });
    expect(await n(f.h)).toBe(1);
  });

  it('max 2 : les deux sont acceptés et les deux lectures restent', async () => {
    const f = await foyer();
    expect((await db.as(f.owner, claim(f.h, null, 'PC soeur', 'A', 2, false, 'pc-soeur'))).rows[0].r.status).toBe('ok');
    expect((await db.as(f.owner, claim(f.h, null, 'PC proprio', 'B', 2, false, 'pc-proprio'))).rows[0].r.status).toBe('ok');
    const r = await db.admin.query('select device_key from active_streams where household_id = $1 order by device_key', [f.h]);
    expect(r.rows).toEqual([{ device_key: 'pc-proprio' }, { device_key: 'pc-soeur' }]);
  });

  it('même clé qui relance : remplace sa propre lecture, pas celle de l’autre ordinateur', async () => {
    const f = await foyer();
    const a = (await db.as(f.owner, claim(f.h, null, 'PC soeur', 'A', 2, false, 'pc-soeur'))).rows[0].r;
    await db.as(f.owner, claim(f.h, null, 'PC proprio', 'B', 2, false, 'pc-proprio'));
    const a2 = (await db.as(f.owner, claim(f.h, null, 'PC soeur', 'A2', 2, false, 'pc-soeur'))).rows[0].r;
    expect(a2.status).toBe('ok');
    const r = await db.admin.query('select id, device_key, label from active_streams where household_id = $1 order by device_key', [f.h]);
    expect(r.rows.map((x) => [x.device_key, x.label])).toEqual([['pc-proprio', 'B'], ['pc-soeur', 'A2']]);
    expect(r.rows.find((x) => x.device_key === 'pc-soeur').id).toBe(a2.stream_id);
    expect(r.rows.some((x) => x.id === a.stream_id)).toBe(false);
  });

  it('clé vide, trop longue ou nulle refusée ; l’ancienne signature n’existe plus', async () => {
    const f = await foyer();
    await expect(db.as(f.owner, claim(f.h, null, 'PC', 'A', 1, false, ''))).rejects.toMatchObject({ code: '23514' });
    await expect(db.as(f.owner, claim(f.h, null, 'PC', 'A', 1, false, 'k'.repeat(65)))).rejects.toMatchObject({ code: '23514' });
    await expect(db.as(f.owner, (c) => c.query('select claim_stream($1, null, $2, $3, 1, null::text)', [f.h, 'PC', 'A'])))
      .rejects.toMatchObject({ code: '23502' });
    await expect(db.as(f.owner, (c) => c.query('select claim_stream($1, null, $2, $3, 1, false)', [f.h, 'PC', 'A'])))
      .rejects.toMatchObject({ code: '42883' });
    expect((await db.as(f.owner, claim(f.h, null, 'PC', 'A', 1, false, 'k'.repeat(64)))).rows[0].r.status).toBe('ok');
  });
});

describe('retrait et demande de lecture simultanés', () => {
  it('la demande attend le retrait puis est refusée : aucune lecture fantôme', async () => {
    const f = await foyer();
    const open = async (uid: string) => {
      const c = await db.admin.connect();
      await c.query('begin');
      await c.query('set local role authenticated');
      await c.query(`select set_config('request.jwt.claims', $1, true)`, [JSON.stringify({ sub: uid, role: 'authenticated' })]);
      return c;
    };
    const a = await open(f.owner);
    const b = await open(f.tele);
    try {
      await a.query('select revoke_device($1, $2)', [f.h, f.tele]);
      let fini = false;
      const pb = claim(f.h, null, 'Télé', 'A', 1)(b).then(
        (r: any) => { fini = true; return r; },
        (e: any) => { fini = true; throw e; },
      );
      pb.catch(() => {});
      await new Promise((r) => setTimeout(r, 200));
      expect(fini).toBe(false); // bloquée sur le verrou du foyer pris par revoke_device
      await a.query('commit');
      await expect(pb).rejects.toThrow('acces-refuse');
      await b.query('rollback');
      expect((await db.admin.query('select count(*)::int n from active_streams where household_id = $1', [f.h])).rows[0].n).toBe(0);
    } finally {
      await a.query('rollback').catch(() => {});
      await b.query('rollback').catch(() => {});
      a.release();
      b.release();
    }
  });
});
