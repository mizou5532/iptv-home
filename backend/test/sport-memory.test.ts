import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { startDb, type Db } from './db';

let db: Db;
beforeAll(async () => { db = await startDb(); });
afterAll(async () => { await db?.stop(); });
beforeEach(async () => { await db.resetHouseholds(); });

describe('mémoire des chaînes du sport partagée par profil', () => {
  it('partage preferred et learned entre deux télés, fusionne sans perte, et refuse les étrangers', async () => {
    const owner = await db.newUser();
    const h = (await db.as(owner, (c) => c.query(`select create_household('Maison', array['Toi']) id`))).rows[0].id;
    const p = (await db.as(owner, (c) => c.query(`select id from profiles where household_id = $1`, [h]))).rows[0].id;

    const tv1 = await db.newUser({ anonymous: true });
    const tv2 = await db.newUser({ anonymous: true });
    await db.admin.query(
      `insert into household_members (household_id, user_id, role, device_name) values ($1, $2, 'device', 'Salon'), ($1, $3, 'device', 'Chambre')`,
      [h, tv1, tv2],
    );

    // Télé 1 envoie sa mémoire locale (migration initiale) : Canadiens -> RDS, learned NHL = [rds, tvas].
    const m1 = (await db.as(tv1, (c) =>
      c.query(`select sport_merge_memory($1, $2::jsonb, $3::jsonb) m`, [
        p,
        JSON.stringify({ 'nhl:MTL': 'auto:ca:rds' }),
        JSON.stringify({ nhl: ['auto:ca:rds', 'auto:ca:tvas'] }),
      ]),
    )).rows[0].m;
    expect(m1.preferred).toEqual({ 'nhl:MTL': 'auto:ca:rds' });
    expect(m1.learned.nhl).toEqual(['auto:ca:rds', 'auto:ca:tvas']);

    // Télé 2 envoie sa mémoire locale différente : Barça -> x:500, Canadiens -> autre (ne doit PAS écraser Télé 1 !), learned NHL = [tvas, tsn].
    const m2 = (await db.as(tv2, (c) =>
      c.query(`select sport_merge_memory($1, $2::jsonb, $3::jsonb) m`, [
        p,
        JSON.stringify({ 'nhl:MTL': 'auto:ca:tsn', 'soccer:83': 'x:500' }),
        JSON.stringify({ nhl: ['auto:ca:tvas', 'auto:ca:tsn'] }),
      ]),
    )).rows[0].m;
    expect(m2.preferred).toEqual({ 'nhl:MTL': 'auto:ca:rds', 'soccer:83': 'x:500' });
    expect(new Set(m2.learned.nhl)).toEqual(new Set(['auto:ca:rds', 'auto:ca:tvas', 'auto:ca:tsn']));

    // Retrait volontaire d'étoile sur Télé 1 -> tombstone empêche une future fusion de la ressusciter.
    await db.as(tv1, (c) => c.query(`select sport_set_preferred($1, 'nhl:MTL', null)`, [p]));
    const afterClear = (await db.as(tv2, (c) =>
      c.query(`select sport_merge_memory($1, $2::jsonb, '{}'::jsonb) m`, [p, JSON.stringify({ 'nhl:MTL': 'auto:ca:old' })]),
    )).rows[0].m;
    expect(afterClear.preferred).toEqual({ 'soccer:83': 'x:500' });

    // Apprentissage borné à 6 max par ligue + oubli sur « Pas le bon match ».
    for (let i = 1; i <= 8; i++) {
      await db.as(tv1, (c) => c.query(`select sport_learn_channel($1, 'nba', $2)`, [p, `g${i}`]));
    }
    const nba = (await db.as(tv2, (c) => c.query(`select sport_memory_get($1) m`, [p]))).rows[0].m;
    expect(nba.learned.nba.length).toBe(6);

    await db.as(tv2, (c) => c.query(`select sport_forget_learned($1, 'nba', 'g8')`, [p]));
    const afterForget = (await db.as(tv1, (c) => c.query(`select sport_memory_get($1) m`, [p]))).rows[0].m;
    expect(afterForget.learned.nba).not.toContain('g8');

    // Sécurité : étranger et anon refusés, écriture directe sur table refusée.
    const stranger = await db.newUser();
    await expect(db.as(stranger, (c) => c.query(`select sport_memory_get($1)`, [p]))).rejects.toMatchObject({ code: '42501' });
    await expect(db.as(stranger, (c) => c.query(`select sport_set_preferred($1, 'nhl:MTL', 'x:1')`, [p]))).rejects.toMatchObject({ code: '42501' });
    await expect(db.as(null, (c) => c.query(`select sport_memory_get($1)`, [p]))).rejects.toMatchObject({ code: '42501' });
    await expect(db.as(tv1, (c) => c.query(`insert into sport_profile_preferred values ($1, 'a', 'b', now())`, [p]))).rejects.toMatchObject({ code: '42501' });
  });
});
