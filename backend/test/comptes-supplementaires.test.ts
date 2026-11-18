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

  it('sans compte supplémentaire : la vidéo est réservée aussi ; la deuxième télé voit qui regarde quoi', async () => {
    const owner = await db.newUser({ email: 'proprio-ca5@exemple.test' });
    const h = await foyer(owner);
    const [a, b] = [await tele(h), await tele(h)];
    const first = await claim(a, h, 'a', 'Série : Lost');
    expect(first).toMatchObject({ status: 'ok', position: 1 });
    expect(first.stream_id).toBeTruthy();
    const r = await claim(b, h, 'b');
    expect(r.status).toBe('occupe');
    expect(r.occupants).toHaveLength(1);
    expect(r.occupants[0]).toMatchObject({ id: first.stream_id, device_name: 'Télé a', label: 'Série : Lost' });
  });

  it('signe de vie : ce qui est regardé est mis à jour ; ancien appel sans libellé accepté', async () => {
    const owner = await db.newUser({ email: 'proprio-ca7@exemple.test' });
    const h = await foyer(owner);
    const [a, b] = [await tele(h), await tele(h)];
    const first = await claim(a, h, 'a', 'Direct : TSN 1');
    expect(await db.as(a, (c) => c.query('select heartbeat_stream($1, $2) r', [first.stream_id, 'Direct : RDS'])).then((x) => x.rows[0].r)).toBe(true);
    expect(await db.as(a, (c) => c.query('select heartbeat_stream($1) r', [first.stream_id])).then((x) => x.rows[0].r)).toBe(true);
    expect((await claim(b, h, 'b')).occupants[0].label).toBe('Direct : RDS');
    // Une autre télé ne peut ni confirmer ni renommer la réservation d'une autre.
    expect(await db.as(b, (c) => c.query('select heartbeat_stream($1, $2) r', [first.stream_id, 'x'])).then((x) => x.rows[0].r)).toBe(false);
  });

  it('prendre la main : la place est donnée, l’autre télé apprend qui l’a prise et le confirme', async () => {
    const owner = await db.newUser({ email: 'proprio-ca8@exemple.test' });
    const h = await foyer(owner);
    const [a, b, c] = [await tele(h), await tele(h), await tele(h)];
    const first = await claim(a, h, 'a', 'Film : Dune');
    const take = (u: string, key: string, id: string) =>
      db.as(u, (x) => x.query('select claim_account($1, null, $2, $3, $4, $5) r', [h, 'Télé ' + key, 'Direct', key, id])).then((r) => r.rows[0].r);
    const got = await take(b, 'b', first.stream_id);
    expect(got).toMatchObject({ status: 'ok', position: 1, replaced: first.stream_id });
    // La télé a : réservation perdue, elle apprend qui a pris la main.
    expect(await db.as(a, (x) => x.query('select heartbeat_stream($1) r', [first.stream_id])).then((r) => r.rows[0].r)).toBe(false);
    expect(await db.as(b, (x) => x.query('select takeover_done($1) r', [first.stream_id])).then((r) => r.rows[0].r)).toBe(false);
    // Une autre télé ne peut pas lire ni confirmer à sa place.
    expect(await db.as(c, (x) => x.query('select stream_takeover($1) r', [first.stream_id])).then((r) => r.rows[0].r)).toBeNull();
    expect(await db.as(a, (x) => x.query('select stream_takeover($1) r', [first.stream_id])).then((r) => r.rows[0].r)).toEqual({ device_name: 'Télé b', profile_name: null });
    expect(await db.as(b, (x) => x.query('select takeover_done($1) r', [first.stream_id])).then((r) => r.rows[0].r)).toBe(true);
    // Une réservation inconnue (déjà rendue) : simplement occupé, rien n'est pris.
    const r = await take(c, 'c', first.stream_id);
    expect(r.status).toBe('occupe');
    // Hors de la maison : impossible de prendre la main.
    const autre = await db.newUser();
    await expect(db.as(autre, (x) => x.query('select claim_account($1, null, $2, $3, $4, $5) r', [h, 'x', '', 'x', got.stream_id]))).rejects.toThrow('acces-refuse');
  });

  it('prendre la main : cas limites (réservation expirée, deux prises, sa propre télé, confirmation répétée)', async () => {
    const owner = await db.newUser({ email: 'proprio-ca10@exemple.test' });
    const h = await foyer(owner);
    const [a, b, c] = [await tele(h), await tele(h), await tele(h)];
    const take = (u: string, key: string, id: string | null) =>
      db.as(u, (x) => x.query('select claim_account($1, null, $2, $3, $4, $5) r', [h, 'Télé ' + key, 'Direct', key, id])).then((r) => r.rows[0].r);
    const first = await claim(a, h, 'a', 'Film');
    // Une réservation sans signe de vie depuis 45 s est libre : pas de prise de main, la place est simplement donnée.
    await db.admin.query(`update active_streams set last_heartbeat = now() - interval '50 seconds' where id = $1`, [first.stream_id]);
    const got = await take(b, 'b', first.stream_id);
    expect(got).toMatchObject({ status: 'ok', position: 1, replaced: null });
    expect(await db.as(a, (x) => x.query('select stream_takeover($1) r', [first.stream_id])).then((r) => r.rows[0].r)).toBeNull();
    // Deux télés prennent la main l'une après l'autre : la dernière l'a, chaque télé coupée sait par qui.
    const third = await take(c, 'c', got.stream_id);
    expect(third).toMatchObject({ status: 'ok', replaced: got.stream_id });
    const back = await take(b, 'b', third.stream_id);
    expect(back).toMatchObject({ status: 'ok', replaced: third.stream_id });
    expect(await db.as(b, (x) => x.query('select stream_takeover($1) r', [got.stream_id])).then((r) => r.rows[0].r)).toEqual({ device_name: 'Télé c', profile_name: null });
    expect(await db.as(c, (x) => x.query('select stream_takeover($1) r', [third.stream_id])).then((r) => r.rows[0].r)).toEqual({ device_name: 'Télé b', profile_name: null });
    // Confirmation répétée : même réponse.
    expect(await db.as(c, (x) => x.query('select stream_takeover($1) r', [third.stream_id])).then((r) => r.rows[0].r)).toEqual({ device_name: 'Télé b', profile_name: null });
    // Réservation inconnue : « arrêtée » (rien à attendre).
    expect(await db.as(b, (x) => x.query('select takeover_done($1) r', ['00000000-0000-0000-0000-000000000000'])).then((r) => r.rows[0].r)).toBe(true);
    // Sa propre réservation redemandée : remplacée, jamais « prise » à soi-même.
    const mine = await take(b, 'b', back.stream_id);
    expect(mine).toMatchObject({ status: 'ok', position: 1, replaced: null });
  });

  it('prendre la main avec le nom du profil : l’autre télé voit qui', async () => {
    const owner = await db.newUser({ email: 'proprio-ca11@exemple.test' });
    const r0 = await db.as(owner, (c) => c.query(`select create_household('Maison', $1::text[]) id`, [['Hamza', 'Maha']]));
    const h = r0.rows[0].id;
    await db.as(owner, (c) => c.query('select set_xtream_credentials($1, $2, $3, $4)', [h, 'http://serveur.exemple.test', 'principal', 'mdp']));
    const profiles = (await db.admin.query('select id, name from profiles where household_id = $1', [h])).rows as { id: string; name: string }[];
    const hamza = profiles.find((p) => p.name === 'Hamza')!.id;
    const maha = profiles.find((p) => p.name === 'Maha')!.id;
    const [a, b] = [await tele(h), await tele(h)];
    const first = await db.as(a, (x) => x.query('select claim_account($1, $2, $3, $4, $5) r', [h, maha, 'Télé du salon', 'Série · Lost', 'a'])).then((r) => r.rows[0].r);
    const busy = await db.as(b, (x) => x.query('select claim_account($1, $2, $3, $4, $5) r', [h, hamza, 'Télé de la chambre', 'Direct', 'b'])).then((r) => r.rows[0].r);
    expect(busy.occupants[0]).toMatchObject({ profile_name: 'Maha', device_name: 'Télé du salon', label: 'Série · Lost' });
    await db.as(b, (x) => x.query('select claim_account($1, $2, $3, $4, $5, $6) r', [h, hamza, 'Télé de la chambre', 'Direct', 'b', first.stream_id]));
    expect(await db.as(a, (x) => x.query('select stream_takeover($1) r', [first.stream_id])).then((r) => r.rows[0].r))
      .toEqual({ device_name: 'Télé de la chambre', profile_name: 'Hamza' });
    // Profil d'une autre maison : refusé.
    const other = await db.newUser();
    const oh = await db.seedHousehold(other, ['X']);
    const xp = (await db.admin.query('select id from profiles where household_id = $1', [oh])).rows[0].id;
    await expect(db.as(b, (x) => x.query('select claim_account($1, $2, $3, $4, $5) r', [h, xp, 'T', '', 'b']))).rejects.toThrow('acces-refuse');
  });

  it('prendre la main ne sert que si tout est pris : une place libre est donnée sans couper personne', async () => {
    const owner = await db.newUser({ email: 'proprio-ca9@exemple.test' });
    const h = await foyer(owner);
    await pret(owner, h, 'second');
    const [a, b] = [await tele(h), await tele(h)];
    const first = await claim(a, h, 'a');
    const got = await db.as(b, (x) => x.query('select claim_account($1, null, $2, $3, $4, $5) r', [h, 'Télé b', '', 'b', first.stream_id])).then((r) => r.rows[0].r);
    expect(got).toMatchObject({ status: 'ok', position: 2, replaced: null });
    expect(await db.as(a, (x) => x.query('select heartbeat_stream($1) r', [first.stream_id])).then((r) => r.rows[0].r)).toBe(true);
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

describe('vérification complète', () => {
  it('raisons films / séries acceptées ; un compte « ok » dont la date de fin est passée n’est jamais donné', async () => {
    const owner = await db.newUser({ email: 'proprio-vc1@exemple.test' });
    const h = await foyer(owner);
    const a = await add(owner, h, 'second');
    const tv = await tele(h);
    for (const r of ['films', 'series', 'films_series']) {
      await db.as(tv, (c) => c.query('select report_extra_account($1, $2, false, $3, null)', [h, a, r]));
    }
    await db.as(tv, (c) => c.query('select report_extra_account($1, $2, true, null, $3)', [h, a, '2020-01-01T00:00:00Z']));
    const [x, y] = [await tele(h), await tele(h)];
    const claim = (u: string, key: string) => db.as(u, (c) => c.query('select claim_account($1, null, $2, $3, $4) r', [h, 'Télé', '', key])).then((r) => r.rows[0].r);
    expect((await claim(x, 'x')).position).toBe(1);
    expect((await claim(y, 'y')).status).toBe('occupe');
    await db.as(tv, (c) => c.query('select report_extra_account($1, $2, true, null, $3)', [h, a, '2099-01-01T00:00:00Z']));
    expect((await claim(y, 'y')).position).toBe(2);
  });
});

describe('connexions en cours et message du fournisseur', () => {
  it('compte principal : relevé complet ; ancien appel à 4 valeurs toujours accepté', async () => {
    const owner = await db.newUser({ email: 'proprio-cm1@exemple.test' });
    const h = await foyer(owner);
    const tv = await tele(h);
    await db.as(tv, (c) => c.query('select report_iptv_account($1, null, $2, 1)', [h, 'Active']));
    await db.as(tv, (c) => c.query('select report_iptv_account($1, null, $2, 1, 2, $3)', [h, 'Active', '  Maintenance dimanche  ']));
    const r = await db.as(owner, (c) => c.query('select active_connections, provider_message from iptv_account where household_id = $1', [h]));
    expect(r.rows).toEqual([{ active_connections: 2, provider_message: 'Maintenance dimanche' }]);
    const autre = await db.newUser();
    await expect(db.as(autre, (c) => c.query('select report_iptv_account($1, null, $2, 1, 2, null)', [h, 'Active']))).rejects.toThrow('acces-refuse');
  });

  it('compte supplémentaire : relevé léger, la vérification ne change pas', async () => {
    const owner = await db.newUser({ email: 'proprio-cm2@exemple.test' });
    const h = await foyer(owner);
    const a = await add(owner, h, 'second');
    const tv = await tele(h);
    await db.as(tv, (c) => c.query('select report_extra_account($1, $2, true, null, $3)', [h, a, '2027-01-01T00:00:00Z']));
    await db.as(tv, (c) => c.query('select report_extra_status($1, $2, 1, $3, null)', [h, a, 'Bienvenue']));
    const r = await db.as(owner, (c) => c.query('select check_status, active_connections, provider_message, expires_at is not null as fin, status_at is not null as releve from xtream_extra_accounts where id = $1', [a]));
    expect(r.rows).toEqual([{ check_status: 'ok', active_connections: 1, provider_message: 'Bienvenue', fin: true, releve: true }]);
  });
});
