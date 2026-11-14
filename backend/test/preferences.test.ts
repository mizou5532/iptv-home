import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { startDb, type Db } from './db';

let db: Db;
beforeAll(async () => { db = await startDb(); });
afterAll(async () => { await db?.stop(); });
// Un seul foyer par base (create_household refuse le second) : on repart de zéro à chaque test.
beforeEach(async () => { await db.resetHouseholds(); });

interface Foyer { owner: string; device: string; h: string; toi: string; soeur: string; frere: string }

// autre = true : foyer étranger inséré directement en base (create_household n'en accepte qu'un).
async function foyer(autre = false): Promise<Foyer> {
  const owner = await db.newUser({ email: `p${Math.random()}@exemple.test` });
  const noms = ['Toi', 'Sœur', 'Frère'];
  const h = autre
    ? await db.seedHousehold(owner, noms)
    : (await db.as(owner, (c) => c.query(`select create_household('Maison', $1::text[]) id`, [noms]))).rows[0].id;
  const device = await db.newUser({ anonymous: true });
  await db.admin.query(`insert into household_members (household_id, user_id, role) values ($1, $2, 'device')`, [h, device]);
  const p = await db.admin.query('select id, name from profiles where household_id = $1', [h]);
  const id = (n: string) => p.rows.find((r) => r.name === n).id as string;
  return { owner, device, h, toi: id('Toi'), soeur: id('Sœur'), frere: id('Frère') };
}

const fav = (p: string, t = 'channel', i = '1') =>
  (c: any) => c.query('insert into favorites (profile_id, item_type, item_id) values ($1, $2, $3)', [p, t, i]);

describe('favoris', () => {
  it('un membre ajoute un favori à un profil du foyer', async () => {
    const f = await foyer();
    await db.as(f.device, fav(f.soeur));
    const r = await db.as(f.owner, (c) => c.query('select item_type, item_id from favorites where profile_id = $1', [f.soeur]));
    expect(r.rows).toEqual([{ item_type: 'channel', item_id: '1' }]);
  });

  it("un membre d'un autre foyer ne voit rien et ne peut pas insérer", async () => {
    const f = await foyer();
    const g = await foyer(true);
    await db.as(f.owner, fav(f.soeur));
    expect((await db.as(g.owner, (c) => c.query('select * from favorites'))).rows).toHaveLength(0);
    await expect(db.as(g.owner, fav(f.soeur, 'movie', '2'))).rejects.toMatchObject({ code: '42501' });
  });

  it('refuse un type de catégorie et un profil inexistant', async () => {
    const f = await foyer();
    await expect(db.as(f.owner, fav(f.toi, 'live_category'))).rejects.toMatchObject({ code: '23514' });
    await expect(db.as(f.owner, fav('00000000-0000-0000-0000-0000000000aa'))).rejects.toMatchObject({ code: '42501' });
  });
});

describe('masquage', () => {
  const hide = (h: string, p: string | null, i = 'x') => (c: any) =>
    c.query(`insert into hidden_items (household_id, profile_id, item_type, item_id) values ($1, $2, 'channel', $3)`, [h, p, i]);

  it('global : refusé à un appareil, accepté au propriétaire, doublon → 23505', async () => {
    const f = await foyer();
    await expect(db.as(f.device, hide(f.h, null))).rejects.toMatchObject({ code: '42501' });
    await db.as(f.owner, hide(f.h, null));
    await expect(db.as(f.owner, hide(f.h, null))).rejects.toMatchObject({ code: '23505' });
    expect((await db.as(f.device, (c) => c.query('select * from hidden_items'))).rows).toHaveLength(1);
  });

  it('un appareil masque pour un profil ; pas pour un profil étranger', async () => {
    const f = await foyer();
    const g = await foyer(true);
    await db.as(f.device, hide(f.h, f.soeur));
    await expect(db.as(g.owner, hide(g.h, f.soeur))).rejects.toMatchObject({ code: '42501' });
    await expect(db.as(g.owner, hide(f.h, f.soeur, 'y'))).rejects.toMatchObject({ code: '42501' });
    // profil d'un autre foyer rattaché à mon foyer
    await expect(db.as(f.owner, hide(f.h, g.soeur, 'z'))).rejects.toMatchObject({ code: '42501' });
  });

  it('hidden_for(sœur) = global + sœur, pas le frère ; refus hors foyer', async () => {
    const f = await foyer();
    const g = await foyer(true);
    await db.as(f.owner, hide(f.h, null, 'global'));
    await db.as(f.owner, hide(f.h, f.soeur, 'soeur'));
    await db.as(f.owner, hide(f.h, f.frere, 'frere'));
    const r = await db.as(f.device, (c) => c.query('select item_id from hidden_for($1) order by item_id', [f.soeur]));
    expect(r.rows.map((x) => x.item_id)).toEqual(['global', 'soeur']);
    await expect(db.as(g.owner, (c) => c.query('select * from hidden_for($1)', [f.soeur]))).rejects.toMatchObject({ code: '42501' });
    await expect(db.as(null, (c) => c.query('select * from hidden_for($1)', [f.soeur]))).rejects.toMatchObject({ code: '42501' });
  });
});

describe('ordre des catégories', () => {
  const order = (h: string, p: string | null, s = 'live') => (c: any) =>
    c.query(`insert into category_order (household_id, profile_id, section, category_ids) values ($1, $2, $3, array['a','b'])`, [h, p, s]);

  it('global réservé au propriétaire, unicité, section valide', async () => {
    const f = await foyer();
    await expect(db.as(f.device, order(f.h, null))).rejects.toMatchObject({ code: '42501' });
    await db.as(f.owner, order(f.h, null));
    await expect(db.as(f.owner, order(f.h, null))).rejects.toMatchObject({ code: '23505' });
    await db.as(f.device, order(f.h, f.soeur));
    await expect(db.as(f.owner, order(f.h, f.toi, 'autre'))).rejects.toMatchObject({ code: '23514' });
  });

  it('invisible hors du foyer', async () => {
    const f = await foyer();
    const g = await foyer(true);
    await db.as(f.owner, order(f.h, null));
    expect((await db.as(g.owner, (c) => c.query('select * from category_order'))).rows).toHaveLength(0);
  });
});

describe('listes', () => {
  it('un membre crée une liste et des éléments ; un étranger non', async () => {
    const f = await foyer();
    const g = await foyer(true);
    const pl = (await db.as(f.device, (c) => c.query(`insert into playlists (profile_id, name) values ($1, 'Soirée') returning id`, [f.soeur]))).rows[0].id;
    await db.as(f.device, (c) => c.query(`insert into playlist_items (playlist_id, item_type, item_id) values ($1, 'movie', '9')`, [pl]));
    expect((await db.as(g.owner, (c) => c.query('select * from playlists'))).rows).toHaveLength(0);
    expect((await db.as(g.owner, (c) => c.query('select * from playlist_items'))).rows).toHaveLength(0);
    await expect(
      db.as(g.owner, (c) => c.query(`insert into playlist_items (playlist_id, item_type, item_id) values ($1, 'movie', '10')`, [pl])),
    ).rejects.toMatchObject({ code: '42501' });
    await expect(
      db.as(f.device, (c) => c.query(`insert into playlists (profile_id, name) values ($1, '   ')`, [f.soeur])),
    ).rejects.toMatchObject({ code: '23514' });
  });
});

describe('suppression du profil de la sœur', () => {
  it('efface ses données, garde celles du frère', async () => {
    const f = await foyer();
    const plIds: Record<string, string> = {};
    for (const p of [f.soeur, f.frere]) {
      await db.as(f.owner, fav(p));
      const pl = (await db.as(f.owner, (c) => c.query(`insert into playlists (profile_id, name) values ($1, 'L') returning id`, [p]))).rows[0].id;
      plIds[p] = pl;
      await db.as(f.owner, (c) => c.query(`insert into playlist_items (playlist_id, item_type, item_id) values ($1, 'series', '1')`, [pl]));
      await db.as(f.owner, (c) => c.query(`insert into hidden_items (household_id, profile_id, item_type, item_id) values ($1, $2, 'channel', 'h')`, [f.h, p]));
      await db.as(f.owner, (c) => c.query(`insert into category_order (household_id, profile_id, section, category_ids) values ($1, $2, 'vod', array['a'])`, [f.h, p]));
    }
    await db.as(f.owner, (c) => c.query('delete from profiles where id = $1', [f.soeur]));
    const count = async (t: string, col: string, p: string) =>
      Number((await db.admin.query(`select count(*) n from ${t} where ${col} = $1`, [p])).rows[0].n);
    for (const [t, col] of [['favorites', 'profile_id'], ['playlists', 'profile_id'], ['hidden_items', 'profile_id'], ['category_order', 'profile_id']]) {
      expect(await count(t, col, f.soeur), `${t} soeur`).toBe(0);
      expect(await count(t, col, f.frere), `${t} frere`).toBe(1);
    }
    const items = await db.admin.query(`select count(*) n from playlist_items pi join playlists p on p.id = pi.playlist_id where p.profile_id = $1`, [f.frere]);
    expect(Number(items.rows[0].n)).toBe(1);
    const soeurItems = await db.admin.query('select count(*) n from playlist_items where playlist_id = $1', [plIds[f.soeur]]);
    expect(Number(soeurItems.rows[0].n)).toBe(0);
  });
});
