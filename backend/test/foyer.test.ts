import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { startDb, type Db } from './db';

let db: Db;
beforeAll(async () => { db = await startDb(); });
afterAll(async () => { await db?.stop(); });
// Un seul foyer par base (create_household refuse le second) : on repart de zéro à chaque test.
beforeEach(async () => { await db.resetHouseholds(); });

async function foyer(owner: string, noms = ['Toi', 'Sœur', 'Frère']): Promise<string> {
  const r = await db.as(owner, (c) => c.query(`select create_household('Maison', $1::text[]) id`, [noms]));
  return r.rows[0].id;
}

describe('foyer et profils', () => {
  it('crée le foyer, le propriétaire et les profils', async () => {
    const owner = await db.newUser({ email: 'proprio@exemple.test' });
    const h = await foyer(owner);
    const profils = await db.as(owner, (c) => c.query('select name, position from profiles where household_id = $1 order by position', [h]));
    expect(profils.rows).toEqual([{ name: 'Toi', position: 0 }, { name: 'Sœur', position: 1 }, { name: 'Frère', position: 2 }]);
    const membres = await db.as(owner, (c) => c.query('select role from household_members where household_id = $1', [h]));
    expect(membres.rows).toEqual([{ role: 'owner' }]);
  });

  it('un autre utilisateur ne voit rien du foyer', async () => {
    const a = await db.newUser();
    const b = await db.newUser();
    await foyer(a);
    for (const t of ['households', 'profiles', 'household_members']) {
      const r = await db.as(b, (c) => c.query(`select * from ${t}`));
      expect(r.rows).toHaveLength(0);
    }
  });

  it('sans connexion : rien', async () => {
    const a = await db.newUser();
    await foyer(a);
    for (const t of ['households', 'profiles', 'household_members']) {
      const r = await db.as(null, (c) => c.query(`select * from ${t}`));
      expect(r.rows).toHaveLength(0);
    }
  });

  it('un non-membre ne peut pas ajouter de profil au foyer', async () => {
    const a = await db.newUser();
    const b = await db.newUser();
    const h = await foyer(a);
    await expect(
      db.as(b, (c) => c.query(`insert into profiles (id, household_id, name) values (gen_random_uuid(), $1, 'Intrus')`, [h])),
    ).rejects.toMatchObject({ code: '42501' });
  });

  it('anonyme ne peut pas créer de foyer', async () => {
    const anon = await db.newUser({ anonymous: true });
    await expect(foyer(anon)).rejects.toThrow('acces-refuse');
  });

  it('non connecté ne peut pas créer de foyer', async () => {
    await expect(db.as(null, (c) => c.query(`select create_household('X', array['A'])`))).rejects.toThrow(/acces-refuse|permission denied/);
  });

  it('nom de profil vide refusé', async () => {
    const a = await db.newUser();
    const h = await foyer(a);
    await expect(
      db.as(a, (c) => c.query(`insert into profiles (id, household_id, name) values (gen_random_uuid(), $1, '  ')`, [h])),
    ).rejects.toMatchObject({ code: '23514' });
  });

  it('nom de profil NULL refusé', async () => {
    const a = await db.newUser();
    const h = await foyer(a);
    await expect(
      db.as(a, (c) => c.query(`insert into profiles (id, household_id) values (gen_random_uuid(), $1)`, [h])),
    ).rejects.toMatchObject({ code: '23502' });
  });

  it('un membre gère les profils, seul le propriétaire modifie le foyer', async () => {
    const a = await db.newUser();
    const h = await foyer(a);
    await db.as(a, (c) => c.query(`update households set name = 'Chez nous' where id = $1`, [h]));
    const r = await db.as(a, (c) => c.query('select name from households where id = $1', [h]));
    expect(r.rows).toEqual([{ name: 'Chez nous' }]);
    // un appareil membre (non propriétaire) ne peut pas renommer le foyer
    const dev = await db.newUser();
    await db.admin.query(`insert into household_members (household_id, user_id, role) values ($1, $2, 'device')`, [h, dev]);
    const u = await db.as(dev, (c) => c.query(`update households set name = 'Pirate' where id = $1`, [h]));
    expect(u.rowCount).toBe(0);
    const p = await db.as(dev, (c) => c.query(`insert into profiles (id, household_id, name) values (gen_random_uuid(), $1, 'Neveu') returning id`, [h]));
    expect(p.rows).toHaveLength(1);
  });
});

describe('un seul foyer, une seule famille', () => {
  it('un second create_household (autre utilisateur) : foyer-existe-deja', async () => {
    await foyer(await db.newUser({ email: 'premier@exemple.test' }));
    const intrus = await db.newUser({ email: 'inconnu@exemple.test' });
    await expect(foyer(intrus)).rejects.toMatchObject({ message: 'foyer-existe-deja', code: '42501' });
    const r = await db.admin.query('select count(*)::int n from households');
    expect(r.rows[0].n).toBe(1);
    expect((await db.admin.query('select 1 from household_members where user_id = $1', [intrus])).rowCount).toBe(0);
  });

  it('plus de 10 profils : trop-de-profils', async () => {
    const a = await db.newUser();
    const onze = Array.from({ length: 11 }, (_, i) => `P${i + 1}`);
    await expect(foyer(a, onze)).rejects.toThrow('trop-de-profils');
    expect((await db.admin.query('select count(*)::int n from households')).rows[0].n).toBe(0);
    // 10 profils restent acceptés
    await foyer(a, onze.slice(0, 10));
    expect((await db.admin.query('select count(*)::int n from profiles')).rows[0].n).toBe(10);
  });

  it('nom du foyer vide ou trop long refusé (1 à 40 caractères)', async () => {
    const a = await db.newUser();
    await expect(db.as(a, (c) => c.query(`select create_household('   ', array['Toi'])`))).rejects.toMatchObject({ code: '23514' });
    await expect(db.as(a, (c) => c.query(`select create_household($1, array['Toi'])`, ['x'.repeat(41)]))).rejects.toMatchObject({ code: '23514' });
    const h = await foyer(a);
    await expect(db.as(a, (c) => c.query(`update households set name = '' where id = $1`, [h]))).rejects.toMatchObject({ code: '23514' });
  });
});

describe('suppression de profil réservée au propriétaire', () => {
  it('un appareil ou un membre non propriétaire ne supprime rien ; le propriétaire oui', async () => {
    const a = await db.newUser();
    const h = await foyer(a);
    const dev = await db.newUser({ anonymous: true });
    await db.admin.query(`insert into household_members (household_id, user_id, role) values ($1, $2, 'device')`, [h, dev]);
    const soeur = (await db.admin.query(`select id from profiles where household_id = $1 and name = 'Sœur'`, [h])).rows[0].id;
    const d = await db.as(dev, (c) => c.query('delete from profiles where id = $1', [soeur]));
    expect(d.rowCount).toBe(0);
    expect((await db.admin.query('select 1 from profiles where id = $1', [soeur])).rowCount).toBe(1);
    const o = await db.as(a, (c) => c.query('delete from profiles where id = $1', [soeur]));
    expect(o.rowCount).toBe(1);
    expect((await db.admin.query('select 1 from profiles where id = $1', [soeur])).rowCount).toBe(0);
  });
});

describe('droits de base', () => {
  it('non connecté : is_member et is_owner refusés (42501)', async () => {
    const a = await db.newUser();
    const h = await foyer(a);
    for (const f of ['is_member', 'is_owner']) {
      await expect(db.as(null, (c) => c.query(`select public.${f}($1)`, [h])))
        .rejects.toMatchObject({ code: '42501', message: expect.stringMatching(/permission denied/) });
    }
    expect((await db.as(a, (c) => c.query('select is_member($1) m, is_owner($1) o', [h]))).rows).toEqual([{ m: true, o: true }]);
  });

  it('ni truncate, ni references, ni trigger ; lecture et écriture explicites pour authenticated', async () => {
    const tables = ['households', 'household_members', 'profiles', 'xtream_credentials', 'favorites', 'playlists',
      'playlist_items', 'hidden_items', 'category_order', 'watch_progress', 'active_streams', 'pairing_codes'];
    for (const t of tables) {
      for (const role of ['anon', 'authenticated']) {
        for (const priv of ['truncate', 'references', 'trigger']) {
          const r = await db.admin.query('select has_table_privilege($1, $2, $3) ok', [role, `public.${t}`, priv]);
          expect(r.rows[0].ok, `${role} ${priv} ${t}`).toBe(false);
        }
      }
    }
    // Les droits explicites survivent même si le projet ne les accorde pas par défaut.
    const g = await db.admin.query(`
      select table_name, string_agg(privilege_type, ',' order by privilege_type) privs
        from information_schema.role_table_grants
       where grantee = 'authenticated' and table_schema = 'public'
       group by table_name order by table_name`);
    for (const t of tables.filter((x) => x !== 'pairing_codes')) {
      expect(g.rows.find((r) => r.table_name === t)?.privs, t).toBe('DELETE,INSERT,SELECT,UPDATE');
    }
  });
});
