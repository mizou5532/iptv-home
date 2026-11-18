import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { startDb, type Db } from './db';

let db: Db;
beforeAll(async () => { db = await startDb(); });
afterAll(async () => { await db?.stop(); });
beforeEach(async () => { await db.resetHouseholds(); });

async function maison(owner: string) {
  const r = await db.as(owner, (c) => c.query(`select create_household('Maison', $1::text[]) id`, [['Hamza', 'Maha']]));
  const h = r.rows[0].id as string;
  await db.admin.query(`insert into live_catalog_category (household_id, id, name, position) values ($1, '1', 'CA| SPORTS', 0), ($1, '2', 'AR| NEWS', 1)`, [h]);
  await db.admin.query(`insert into live_catalog_channel (household_id, id, category_id, name, norm, position) values
    ($1, '100', '1', 'RDS HD', 'rds hd', 0), ($1, '101', '1', 'TSN 1', 'tsn 1', 1), ($1, '200', '2', 'Al Jazeera', 'al jazeera', 2)`, [h]);
  return h;
}
const key = (owner: string, h: string) => db.as(owner, (c) => c.query(`select ai_create_access($1, 'Claude') k`, [h])).then((r) => r.rows[0].k as string);
const call = (token: string, tool: string, args: object = {}) =>
  db.as(null as unknown as string, (c) => c.query('select ai_call($1, $2, $3) r', [token, tool, JSON.stringify(args)])).then((r) => r.rows[0].r);

describe('accès pour les IA', () => {
  it('clé créée par le propriétaire seulement ; lecture des profils, catégories et chaînes', async () => {
    const owner = await db.newUser({ email: 'proprio-ia1@exemple.test' });
    const h = await maison(owner);
    const tv = await db.newUser({ anonymous: true });
    await db.admin.query(`insert into household_members (household_id, user_id, role, device_name) values ($1, $2, 'device', 'Télé')`, [h, tv]);
    await expect(key(tv, h)).rejects.toThrow('acces-refuse');
    const k = await key(owner, h);
    expect(k).toMatch(/^[0-9a-f]{64}$/);
    expect((await call(k, 'profils')).map((p: { nom: string }) => p.nom).sort()).toEqual(['Hamza', 'Maha']);
    expect((await call(k, 'categories', { section: 'direct', recherche: 'sport' })).map((c: { id: string }) => c.id)).toEqual(['1']);
    expect((await call(k, 'chaines', { categorie: '1' })).map((c: { nom: string }) => c.nom)).toEqual(['RDS HD', 'TSN 1']);
    // La clé n'est jamais gardée en clair.
    expect((await db.admin.query(`select count(*)::int n from ai_access where token_hash = $1`, [k])).rows[0].n).toBe(0);
  });

  it('masquer pour Maha, playlist, favoris, journal ; sauvegarde « avant IA » ; annuler remet l’organisation', async () => {
    const owner = await db.newUser({ email: 'proprio-ia2@exemple.test' });
    const h = await maison(owner);
    const k = await key(owner, h);
    await call(k, 'masquer', { section: 'direct', sorte: 'categorie', ids: ['2'], profil: 'maha' });
    const cats = await call(k, 'categories', { section: 'direct' });
    expect(cats.find((c: { id: string }) => c.id === '2').masquee_pour).toEqual(['Maha']);
    const pl = await call(k, 'playlist_creer', { profil: 'Hamza', nom: 'Hockey', chaines: ['100', '101'] });
    expect(pl.chaines).toBe(2);
    await call(k, 'favoris_ajouter', { profil: 'Hamza', chaines: ['100'] });
    expect((await call(k, 'playlists', { profil: 'Hamza' }))[0].chaines.map((c: { nom: string }) => c.nom)).toEqual(['RDS HD', 'TSN 1']);
    expect((await call(k, 'journal')).length).toBe(3);
    expect((await db.admin.query(`select count(*)::int n from household_backups where household_id = $1 and reason = 'avant_ia'`, [h])).rows[0].n).toBe(1);
    await call(k, 'annuler');
    expect((await db.admin.query(`select count(*)::int n from hidden_items where household_id = $1`, [h])).rows[0].n).toBe(0);
    expect((await db.admin.query(`select count(*)::int n from playlists p join profiles r on r.id = p.profile_id where r.household_id = $1`, [h])).rows[0].n).toBe(0);
  });

  it('clé coupée ou fausse : refus ; erreurs claires', async () => {
    const owner = await db.newUser({ email: 'proprio-ia3@exemple.test' });
    const h = await maison(owner);
    const k = await key(owner, h);
    await expect(call('mauvaise', 'profils')).rejects.toThrow('cle-invalide');
    await expect(call(k, 'masquer', { section: 'radio', sorte: 'categorie', ids: ['1'] })).rejects.toThrow('section-ou-sorte-inconnue');
    await expect(call(k, 'masquer', { section: 'direct', sorte: 'categorie', ids: ['1'], profil: 'Inconnu' })).rejects.toThrow('profil-inconnu');
    const id = (await db.as(owner, (c) => c.query('select id from ai_access where household_id = $1', [h]))).rows[0].id;
    await db.as(owner, (c) => c.query('select ai_revoke_access($1, $2)', [h, id]));
    await expect(call(k, 'profils')).rejects.toThrow('cle-invalide');
  });

  it('annuler : seulement dans l’heure, et jamais par-dessus une sauvegarde manuelle plus récente', async () => {
    const owner = await db.newUser({ email: 'proprio-ia4@exemple.test' });
    const h = await maison(owner);
    const k = await key(owner, h);
    await call(k, 'masquer', { section: 'direct', sorte: 'categorie', ids: ['2'] });
    await db.as(owner, (c) => c.query('select backup_now($1)', [h]));
    await expect(call(k, 'annuler')).rejects.toThrow('rien-a-annuler');
    await db.admin.query(`delete from household_backups where household_id = $1 and reason = 'manuelle'`, [h]);
    await db.admin.query(`update household_backups set created_at = now() - interval '2 hours' where household_id = $1`, [h]);
    await expect(call(k, 'annuler')).rejects.toThrow('rien-a-annuler');
  });
});
