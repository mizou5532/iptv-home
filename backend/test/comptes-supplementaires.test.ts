import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { startDb, type Db } from './db';

let db: Db;
beforeAll(async () => { db = await startDb(); });
afterAll(async () => { await db?.stop(); });
beforeEach(async () => { await db.resetHouseholds(); });

async function foyer(owner: string): Promise<string> {
  const r = await db.as(owner, (c) => c.query(`select create_household('Maison', $1::text[]) id`, [['Toi']]));
  const h = r.rows[0].id;
  await db.as(owner, (c) => c.query('select set_xtream_credentials($1, $2, $3, $4)', [h, 'http://serveur.exemple.test', 'principal', 'mdp']));
  return h;
}
const add = (u: string, h: string, name: string, p = 'mdp2') =>
  db.as(u, (c) => c.query('select add_extra_account($1, $2, $3) id', [h, name, p])).then((r) => r.rows[0].id as string);
async function tele(h: string): Promise<string> {
  const tv = await db.newUser({ anonymous: true });
  await db.admin.query(`insert into household_members (household_id, user_id, role, device_name) values ($1, $2, 'device', 'Télé')`, [h, tv]);
  return tv;
}
const rows = (u: string, h: string) => db.as(u, (c) => c.query(
  'select position, username, check_status, check_reason from xtream_extra_accounts where household_id = $1 order by position', [h])).then((r) => r.rows);

describe('comptes IPTV supplémentaires', () => {
  it('le propriétaire ajoute des comptes : places 2, 3… à vérifier ; la télé les lit', async () => {
    const owner = await db.newUser({ email: 'proprio-cs1@exemple.test' });
    const h = await foyer(owner);
    await add(owner, h, ' second ');
    await add(owner, h, 'troisieme');
    const tv = await tele(h);
    expect(await rows(tv, h)).toEqual([
      { position: 2, username: 'second', check_status: 'a_verifier', check_reason: null },
      { position: 3, username: 'troisieme', check_status: 'a_verifier', check_reason: null },
    ]);
  });

  it('refus : appareil, non-membre, doublon, compte principal, vide, plus de 4, sans compte principal', async () => {
    const owner = await db.newUser({ email: 'proprio-cs2@exemple.test' });
    const h = await foyer(owner);
    const tv = await tele(h);
    await expect(add(tv, h, 'x')).rejects.toThrow('acces-refuse');
    const autre = await db.newUser();
    await expect(add(autre, h, 'x')).rejects.toThrow('acces-refuse');
    expect(await db.as(autre, (c) => c.query('select * from xtream_extra_accounts')).then((r) => r.rows)).toHaveLength(0);
    await expect(add(owner, h, 'principal')).rejects.toThrow('compte-deja-ajoute');
    await add(owner, h, 'a');
    await expect(add(owner, h, 'a')).rejects.toThrow('compte-deja-ajoute');
    await expect(add(owner, h, ' ', 'p')).rejects.toThrow('identifiants-incomplets');
    await add(owner, h, 'b'); await add(owner, h, 'c'); await add(owner, h, 'd');
    await expect(add(owner, h, 'e')).rejects.toThrow('trop-de-comptes');
    await expect(db.as(owner, (c) => c.query(
      `insert into xtream_extra_accounts (household_id, position, username, password) values ($1, 7, 'z', 'z')`, [h]))).rejects.toThrow();
  });

  it('sans compte principal : refusé', async () => {
    const owner = await db.newUser({ email: 'proprio-cs3@exemple.test' });
    const r = await db.as(owner, (c) => c.query(`select create_household('Maison', $1::text[]) id`, [['Toi']]));
    await expect(add(owner, r.rows[0].id, 'a')).rejects.toThrow('compte-principal-absent');
  });

  it('retrait : la place libérée est reprise', async () => {
    const owner = await db.newUser({ email: 'proprio-cs4@exemple.test' });
    const h = await foyer(owner);
    const a = await add(owner, h, 'a');
    await add(owner, h, 'b');
    const tv = await tele(h);
    await expect(db.as(tv, (c) => c.query('select remove_extra_account($1, $2)', [h, a]))).rejects.toThrow('acces-refuse');
    await db.as(owner, (c) => c.query('select remove_extra_account($1, $2)', [h, a]));
    await add(owner, h, 'c');
    expect((await rows(owner, h)).map((r) => [r.position, r.username])).toEqual([[2, 'c'], [3, 'b']]);
  });

  it('la télé rapporte la vérification ; un non-membre ne peut pas ; raison inconnue refusée', async () => {
    const owner = await db.newUser({ email: 'proprio-cs5@exemple.test' });
    const h = await foyer(owner);
    const a = await add(owner, h, 'a');
    const b = await add(owner, h, 'b');
    const tv = await tele(h);
    await db.as(tv, (c) => c.query('select report_extra_account($1, $2, true, null, $3)', [h, a, '2027-01-01T00:00:00Z']));
    await db.as(tv, (c) => c.query(`select report_extra_account($1, $2, false, 'catalogue', null)`, [h, b]));
    await expect(db.as(tv, (c) => c.query(`select report_extra_account($1, $2, false, 'bof', null)`, [h, b]))).rejects.toThrow('raison-inconnue');
    const autre = await db.newUser();
    await expect(db.as(autre, (c) => c.query('select report_extra_account($1, $2, true, null, null)', [h, b]))).rejects.toThrow('acces-refuse');
    expect((await rows(owner, h)).map((r) => [r.username, r.check_status, r.check_reason])).toEqual([['a', 'ok', null], ['b', 'refuse', 'catalogue']]);
  });

  it('compte principal changé : tout est à revérifier ; même valeurs : rien ne change', async () => {
    const owner = await db.newUser({ email: 'proprio-cs6@exemple.test' });
    const h = await foyer(owner);
    const a = await add(owner, h, 'a');
    const tv = await tele(h);
    await db.as(tv, (c) => c.query('select report_extra_account($1, $2, true, null, null)', [h, a]));
    await db.as(owner, (c) => c.query('select set_xtream_credentials($1, $2, $3, $4)', [h, 'http://serveur.exemple.test', 'principal', 'mdp']));
    expect((await rows(owner, h))[0].check_status).toBe('ok');
    await db.as(owner, (c) => c.query('select set_xtream_credentials($1, $2, $3, $4)', [h, 'http://autre.exemple.test', 'principal', 'mdp']));
    expect((await rows(owner, h))[0].check_status).toBe('a_verifier');
  });
});

describe('attribution d’un compte au moment de regarder', () => {
  const claim = (u: string, h: string, key: string, label = 'TSN 1') =>
    db.as(u, (c) => c.query('select claim_account($1, null, $2, $3, $4) r', [h, 'Télé ' + key, label, key])).then((r) => r.rows[0].r);
  async function pret(owner: string, h: string, ...names: string[]) {
    const tv0 = await tele(h);
    for (const n of names) {
      const id = await add(owner, h, n);
      await db.as(tv0, (c) => c.query('select report_extra_account($1, $2, true, null, null)', [h, id]));
    }
  }

  it('première télé : compte principal ; deuxième : compte 2 ; troisième : occupé avec qui regarde', async () => {
    const owner = await db.newUser({ email: 'proprio-ca1@exemple.test' });
    const h = await foyer(owner);
    await pret(owner, h, 'second');
    const [a, b, c] = [await tele(h), await tele(h), await tele(h)];
    expect((await claim(a, h, 'a')).position).toBe(1);
    expect((await claim(b, h, 'b')).position).toBe(2);
    const r = await claim(c, h, 'c', 'Film');
    expect(r.status).toBe('occupe');
    expect(r.occupants.map((o: { label: string }) => o.label)).toEqual(['TSN 1', 'TSN 1']);
  });

  it('un compte à vérifier ou refusé n’est jamais donné', async () => {
    const owner = await db.newUser({ email: 'proprio-ca2@exemple.test' });
    const h = await foyer(owner);
    await add(owner, h, 'pas-verifie');
    const [a, b] = [await tele(h), await tele(h)];
    expect((await claim(a, h, 'a')).position).toBe(1);
    expect((await claim(b, h, 'b')).status).toBe('occupe');
  });

  it('rendu (release) ou sans signe de vie 45 s : le compte redevient libre ; une télé redemande = même place', async () => {
    const owner = await db.newUser({ email: 'proprio-ca3@exemple.test' });
    const h = await foyer(owner);
    await add(owner, h, 'pas-encore-verifie'); // un compte en plus (pas encore utilisable) : la réservation s'applique
    const [a, b] = [await tele(h), await tele(h)];
    const first = await claim(a, h, 'a');
    expect((await claim(a, h, 'a')).position).toBe(1); // sa propre réservation remplacée, pas doublée
    const again = await claim(a, h, 'a');
    expect((await claim(b, h, 'b')).status).toBe('occupe');
    await db.as(a, (c) => c.query('select release_stream($1)', [again.stream_id]));
    expect((await claim(b, h, 'b')).position).toBe(1);
    await db.admin.query(`update active_streams set last_heartbeat = now() - interval '46 seconds' where household_id = $1`, [h]);
    expect((await claim(a, h, 'a')).position).toBe(1);
    expect(first.status).toBe('ok');
  });

  it('sans compte supplémentaire : jamais refusé ni réservé (comme avant)', async () => {
    const owner = await db.newUser({ email: 'proprio-ca5@exemple.test' });
    const h = await foyer(owner);
    const [a, b] = [await tele(h), await tele(h)];
    expect(await claim(a, h, 'a')).toMatchObject({ status: 'ok', position: 1, stream_id: null });
    expect(await claim(b, h, 'b')).toMatchObject({ status: 'ok', position: 1, stream_id: null });
  });

  it('compte principal à 2 connexions : deux télés dessus, la troisième prend le compte 2', async () => {
    const owner = await db.newUser({ email: 'proprio-ca6@exemple.test' });
    const h = await foyer(owner);
    await pret(owner, h, 'second');
    const tv0 = await tele(h);
    await db.as(tv0, (c) => c.query('select report_iptv_account($1, null, $2, 2)', [h, 'Active']));
    const [a, b, c] = [await tele(h), await tele(h), await tele(h)];
    expect((await claim(a, h, 'a')).position).toBe(1);
    expect((await claim(b, h, 'b')).position).toBe(1);
    expect((await claim(c, h, 'c')).position).toBe(2);
  });

  it('non-membre refusé', async () => {
    const owner = await db.newUser({ email: 'proprio-ca4@exemple.test' });
    const h = await foyer(owner);
    const autre = await db.newUser();
    await expect(claim(autre, h, 'x')).rejects.toThrow('acces-refuse');
  });
});
