import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { startDb, type Db } from './db';

let db: Db;
beforeAll(async () => { db = await startDb(); });
afterAll(async () => { await db?.stop(); });
beforeEach(async () => { await db.resetHouseholds(); });

async function maison(owner: string) {
  const r = await db.as(owner, (c) => c.query(`select create_household('Maison', $1::text[]) id`, [['Hamza', 'Maha']]));
  const h = r.rows[0].id as string;
  await db.as(owner, (c) => c.query('select set_xtream_credentials($1, $2, $3, $4)', [h, 'http://serveur.exemple.test', 'principal', 'mdp']));
  const p = (await db.admin.query('select id, name from profiles where household_id = $1 order by name', [h])).rows as { id: string; name: string }[];
  const hamza = p.find((x) => x.name === 'Hamza')!.id, maha = p.find((x) => x.name === 'Maha')!.id;
  await db.admin.query(`insert into watch_progress (profile_id, item_type, item_id, position_s, duration_s, client_updated_at, movie_name)
    values ($1, 'movie', 'm1', 600, 7200, now() - interval '1 day', 'Training Day')`, [hamza]);
  await db.admin.query(`insert into favorites (profile_id, item_type, item_id) values ($1, 'channel', '100')`, [maha]);
  await db.admin.query(`insert into hidden_items (household_id, profile_id, item_type, item_id) values ($1, null, 'live_category', '9')`, [h]);
  return { h, hamza, maha };
}
const list = (u: string, h: string) => db.as(u, (c) => c.query('select id, reason, summary from household_backups where household_id = $1 order by created_at', [h])).then((r) => r.rows);

describe('sauvegardes de la maison', () => {
  it('sauvegarder maintenant : copie lisible par le propriétaire seulement, sans identifiants IPTV', async () => {
    const owner = await db.newUser({ email: 'proprio-sv1@exemple.test' });
    const { h } = await maison(owner);
    await db.as(owner, (c) => c.query('select backup_now($1)', [h]));
    const rows = await list(owner, h);
    expect(rows.map((r) => r.reason)).toEqual(['manuelle']);
    expect(rows[0].summary).toMatchObject({ profils: 2, progression: 1, favoris: 1, masquages: 1 });
    const data = (await db.as(owner, (c) => c.query('select data::text t from household_backups where household_id = $1', [h]))).rows[0].t as string;
    expect(data).not.toContain('mdp');
    expect(data).not.toContain('principal');
    const tv = await db.newUser({ anonymous: true });
    await db.admin.query(`insert into household_members (household_id, user_id, role, device_name) values ($1, $2, 'device', 'Télé')`, [h, tv]);
    expect(await list(tv, h)).toHaveLength(0);
    await expect(db.as(tv, (c) => c.query('select backup_now($1)', [h]))).rejects.toThrow('acces-refuse');
    const other = await db.newUser();
    await expect(db.as(other, (c) => c.query('select backup_restore($1, $2, $3)', [h, rows[0].id, ['progression']]))).rejects.toThrow('acces-refuse');
  });

  it('copie automatique avant un changement d’identifiants IPTV et avant la suppression d’un profil', async () => {
    const owner = await db.newUser({ email: 'proprio-sv2@exemple.test' });
    const { h, maha } = await maison(owner);
    await db.as(owner, (c) => c.query('select set_xtream_credentials($1, $2, $3, $4)', [h, 'http://serveur.exemple.test', 'principal', 'mdp']));
    expect(await list(owner, h)).toHaveLength(0); // mêmes identifiants : rien
    await db.as(owner, (c) => c.query('select set_xtream_credentials($1, $2, $3, $4)', [h, 'http://autre.exemple.test', 'nouveau', 'mdp2']));
    await db.admin.query('delete from profiles where id = $1', [maha]);
    expect((await list(owner, h)).map((r) => r.reason)).toEqual(['avant_identifiants', 'avant_suppression_profil']);
  });

  it('profil supprimé puis restauré : profil, favoris et progression reviennent ; la restauration est sauvegardée avant', async () => {
    const owner = await db.newUser({ email: 'proprio-sv3@exemple.test' });
    const { h, hamza, maha } = await maison(owner);
    await db.as(owner, (c) => c.query('select backup_now($1)', [h]));
    const saved = (await list(owner, h))[0].id;
    await db.admin.query('delete from profiles where id = $1', [maha]);
    await db.admin.query('delete from watch_progress where profile_id = $1', [hamza]);
    const r = await db.as(owner, (c) => c.query('select backup_restore($1, $2, $3) r', [h, saved, ['profils', 'progression', 'organisation']]));
    expect(r.rows[0].r).toMatchObject({ profils: 2, progression: 1, organisation: true });
    expect((await db.admin.query('select name from profiles where household_id = $1 order by name', [h])).rows.map((x) => x.name)).toEqual(['Hamza', 'Maha']);
    expect((await db.admin.query('select item_id from favorites where profile_id = $1', [maha])).rows).toEqual([{ item_id: '100' }]);
    expect((await db.admin.query('select position_s from watch_progress where profile_id = $1', [hamza])).rows).toEqual([{ position_s: 600 }]);
    expect((await list(owner, h)).map((x) => x.reason)).toContain('avant_restauration');
  });

  it('progression : une position plus récente n’est jamais remplacée par une plus ancienne', async () => {
    const owner = await db.newUser({ email: 'proprio-sv4@exemple.test' });
    const { h, hamza } = await maison(owner);
    await db.as(owner, (c) => c.query('select backup_now($1)', [h]));
    const saved = (await list(owner, h))[0].id;
    await db.admin.query(`update watch_progress set position_s = 3000, client_updated_at = now() where profile_id = $1`, [hamza]);
    await db.as(owner, (c) => c.query('select backup_restore($1, $2, $3)', [h, saved, ['progression']]));
    expect((await db.admin.query('select position_s from watch_progress where profile_id = $1', [hamza])).rows).toEqual([{ position_s: 3000 }]);
  });

  it('organisation remplacée par celle de la copie ; fichier d’une autre maison refusé', async () => {
    const owner = await db.newUser({ email: 'proprio-sv5@exemple.test' });
    const { h } = await maison(owner);
    await db.as(owner, (c) => c.query('select backup_now($1)', [h]));
    const data = (await db.as(owner, (c) => c.query('select data from household_backups where household_id = $1', [h]))).rows[0].data;
    await db.admin.query(`insert into hidden_items (household_id, profile_id, item_type, item_id) values ($1, null, 'live_category', '77')`, [h]);
    await db.as(owner, (c) => c.query('select backup_restore_file($1, $2, $3)', [h, data, ['organisation']]));
    expect((await db.admin.query('select item_id from hidden_items where household_id = $1', [h])).rows.map((x) => x.item_id)).toEqual(['9']);
    await expect(db.as(owner, (c) => c.query('select backup_restore_file($1, $2, $3)', [h, { ...data, household_id: '00000000-0000-0000-0000-000000000001' }, ['organisation']])))
      .rejects.toThrow('sauvegarde-invalide');
    await expect(db.as(owner, (c) => c.query('select backup_restore_file($1, $2, $3)', [h, data, ['tout']]))).rejects.toThrow('parties-invalides');
  });

  it('nuit : rien de nouveau si rien n’a changé ; gardées 30 nuits', async () => {
    const owner = await db.newUser({ email: 'proprio-sv6@exemple.test' });
    const { h, hamza } = await maison(owner);
    await db.admin.query(`select backup_all_households('nuit')`);
    await db.admin.query(`select backup_all_households('nuit')`);
    expect(await list(owner, h)).toHaveLength(1);
    for (let i = 0; i < 35; i++) {
      await db.admin.query(`update watch_progress set position_s = $2 where profile_id = $1`, [hamza, 700 + i]);
      await db.admin.query(`select backup_all_households('nuit')`);
    }
    expect((await list(owner, h)).length).toBe(30);
  });
});
