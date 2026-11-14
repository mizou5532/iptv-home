import { afterAll, beforeAll, beforeEach, expect, it } from 'vitest';
import { startDb, type Db } from './db';
let db: Db;
beforeAll(async () => {
  db = await startDb();
});
afterAll(async () => {
  await db?.stop();
});
beforeEach(async () => {
  await db.resetHouseholds();
});
async function setup() {
  const owner = await db.newUser();
  const h = await db.seedHousehold(owner, ['Un', 'Deux']);
  await db.admin.query(`insert into live_catalog_category(household_id,id,name,position) values ($1,'c','Sport',0)`, [h]);
  await db.admin.query(`insert into live_catalog_channel select $1,i::text,'c','Canal '||i,'canal',i,i from generate_series(1,1005) i`, [h]);
  return { owner, h };
}
const id = (n: number) => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`;
const group = (n: number, s = [String(n)]) => ({ id: id(n), title: 'Sport', source_ids: s, blocked_auto_keys: ['sport'] });
const edit = (u: string, h: string, rev: number, groups: unknown[], remove_ids: string[] = []) => db.as(u, c => c.query('select live_group_edit($1,$2,$3) r', [h, rev, { groups, remove_ids }])).then(r => r.rows[0].r);
const page = (u: string | null, h: string, after: string | null = null) => db.as(u, c => c.query('select live_group_page($1,$2,1000) r', [h, after])).then(r => r.rows[0].r);
it('conflit_revision_ne_perd_aucune_correction', async () => {
  const { owner, h } = await setup();
  expect(await edit(owner, h, 0, [group(1)])).toEqual({ rev: 1 });
  await expect(edit(owner, h, 0, [group(2)])).rejects.toThrow('conflit-revision');
  expect((await page(owner, h)).groups).toEqual([group(1)]);
});
it('source_unique_dans_un_foyer_et_fusion_atomique', async () => {
  const { owner, h } = await setup();
  await edit(owner, h, 0, [group(1), group(2)]);
  await expect(edit(owner, h, 1, [group(3, ['1'])])).rejects.toThrow();
  expect((await page(owner, h)).rev).toBe(1);
  await edit(owner, h, 1, [group(3, ['1', '2'])], [id(1), id(2)]);
  expect((await page(owner, h)).groups).toEqual([group(3, ['1', '2'])]);
});
it('membre_lecture_proprietaire_ecriture_etranger_anon_refuses', async () => {
  const { owner, h } = await setup();
  const member = await db.newUser(), stranger = await db.newUser();
  await db.admin.query(`insert into household_members(household_id,user_id,role) values ($1,$2,'device')`, [h, member]);
  await edit(owner, h, 0, [group(1)]);
  expect((await page(member, h)).rev).toBe(1);
  await expect(edit(member, h, 1, [group(2)])).rejects.toThrow('acces-refuse');
  await expect(page(stranger, h)).rejects.toThrow('acces-refuse');
  await expect(page(null, h)).rejects.toThrow();
  await expect(db.as(member, c => c.query('delete from live_group_source'))).rejects.toThrow();
});
it('pagination_1005_groupes_et_chaines_revision_modifiee', async () => {
  const { owner, h } = await setup();
  await edit(owner, h, 0, Array.from({ length: 1005 }, (_, i) => group(i + 1)));
  const a = await page(owner, h), b = await page(owner, h, a.next_id);
  expect(a.groups).toHaveLength(1000);
  expect(b.groups).toHaveLength(5);
  expect(b.next_id).toBeNull();
  const catalog = async (pos: number | null = null, key: string | null = null) => db.as(owner, c => c.query('select live_catalog_page($1,$2,$3,1000) r', [h, pos, key])).then(r => r.rows[0].r);
  const ca = await catalog(), cb = await catalog(ca.next_cursor.position, ca.next_cursor.id);
  expect(ca.channels).toHaveLength(1000);
  expect(cb.channels).toHaveLength(5);
  expect(cb.next_cursor).toBeNull();
  expect(Object.keys(ca.channels[0]).sort()).toEqual(['category_id', 'category_name', 'id', 'name', 'number', 'position']);
  await db.admin.query(`update live_catalog_channel set name='Changement' where household_id=$1 and id='1'`, [h]);
  expect((await catalog(ca.next_cursor.position, ca.next_cursor.id)).catalog_rev).toBeGreaterThan(ca.catalog_rev);
  await edit(owner, h, 1, [{ ...group(1), title: 'Autre' }]);
  expect((await page(owner, h, a.next_id)).rev).toBe(2);
});
it('sources_disparues_conservees_nouvelles_inconnues_et_groupe_vide_refuses', async () => {
  const { owner, h } = await setup();
  await edit(owner, h, 0, [group(1)]);
  await db.admin.query(`delete from live_catalog_channel where household_id=$1 and id='1'`, [h]);
  await edit(owner, h, 1, [{ ...group(1), title: 'Gardé' }], [id(1)]);
  await expect(edit(owner, h, 2, [group(2, ['inventee'])])).rejects.toThrow('source-inconnue');
  await expect(edit(owner, h, 2, [group(2, [])])).rejects.toThrow();
  expect((await page(owner, h)).groups[0].blocked_auto_keys).toEqual(['sport']);
});
it('reglage_independant_profils_revision_preferences_et_favoris_inchanges', async () => {
  const { owner, h } = await setup();
  const ps = (await db.admin.query('select id from profiles where household_id=$1 order by position', [h])).rows;
  const prefs = (p: string) => db.as(owner, c => c.query('select profile_prefs($1) r', [p])).then(r => r.rows[0].r);
  await db.as(owner, c => c.query("select org_set_favorite($1,'1',true)", [ps[0].id]));
  const before = await prefs(ps[0].id);
  expect(before.group_copies).toBe(true);
  await db.as(owner, c => c.query('select set_group_copies($1,false)', [ps[0].id]));
  const after = await prefs(ps[0].id);
  expect(after.group_copies).toBe(false);
  expect(after.rev).toBeGreaterThan(before.rev);
  expect(after.favorites).toEqual(before.favorites);
  expect((await prefs(ps[1].id)).group_copies).toBe(true);
  await edit(owner, h, 0, [group(1)]);
  expect((await prefs(ps[0].id)).rev).toBeGreaterThan(after.rev);
  const other = await db.newUser(), oh = await db.seedHousehold(other, ['Autre']);
  const p = (await db.admin.query('select id from profiles where household_id=$1', [oh])).rows[0].id;
  await expect(db.as(owner, c => c.query('select set_group_copies($1,false)', [p]))).rejects.toThrow('acces-refuse');
});
it('editions_concurrentes_une_seule_gagne', async () => {
  const { owner, h } = await setup();
  const outcomes = await Promise.allSettled([edit(owner, h, 0, [group(1)]), edit(owner, h, 0, [group(2)])]);
  expect(outcomes.filter(r => r.status === 'fulfilled')).toHaveLength(1);
  expect(outcomes.filter(r => r.status === 'rejected')).toHaveLength(1);
  const failure = outcomes.find(r => r.status === 'rejected');
  expect(failure?.status === 'rejected' && failure.reason.message).toBe('conflit-revision');
  expect((await page(owner, h)).rev).toBe(1);
});
it('pages_bornees_et_curseur_exact_et_restauration_partielle', async () => {
  const { owner, h } = await setup();
  await edit(owner, h, 0, [group(1), group(2)]);
  await edit(owner, h, 1, [], [id(1)]);
  expect((await page(owner, h)).groups).toEqual([group(2)]);
  for (const lim of [0, 1001, null]) {
    await expect(db.as(owner, c => c.query('select live_group_page($1,null,$2)', [h, lim]))).rejects.toThrow('page-invalide');
    await expect(db.as(owner, c => c.query('select live_catalog_page($1,null,null,$2)', [h, lim]))).rejects.toThrow('page-invalide');
  }
  const r = await db.as(owner, c => c.query("select live_catalog_page($1,5,'5',1000) r", [h]));
  expect(r.rows[0].r.channels).toHaveLength(1000);
  expect(r.rows[0].r.next_cursor).toBeNull();
});

it('publication_comptee_et_transactions_annulees_sans_revision_fantome', async () => {
  const { owner, h } = await setup();
  const catalog = () => db.as(owner, c => c.query('select live_catalog_page($1,null,null) r', [h])).then(r => r.rows[0].r);
  const first = await catalog();
  await db.as(owner, c => c.query(`select publish_live_channels($1,'c','fictive',0,true,$2)`, [h, JSON.stringify([{ id: '1', name: 'Sport FHD', norm: 'sport fhd', number: 1, position: 0 }])]));
  const second = await catalog();
  expect(second.catalog_rev).toBeGreaterThan(first.catalog_rev);
  expect(second.channels).toHaveLength(1);
  await expect(db.as(owner, async c => {
    await c.query(`select publish_live_channels($1,'c','autre',0,true,'[]')`, [h]);
    throw new Error('interruption-fictive');
  })).rejects.toThrow('interruption-fictive');
  expect(await catalog()).toEqual(second);
  await db.as(owner, c => c.query(`select publish_live_categories($1,'[{"id":"autre","name":"Autre","position":0}]')`, [h]));
  const third = await catalog();
  expect(third.catalog_rev).toBeGreaterThan(second.catalog_rev);
  expect(third.channels).toEqual([]);
});

it('rpc_et_tables_isoles_par_foyer_et_anon_sans_privileges', async () => {
  const { owner, h } = await setup();
  await edit(owner, h, 0, [group(1)]);
  const stranger = await db.newUser();
  const p = (await db.admin.query('select id from profiles where household_id=$1 limit 1', [h])).rows[0].id;
  for (const u of [stranger, null]) {
    for (const [sql, args] of [
      ['select live_catalog_page($1,null,null)', [h]],
      ['select live_group_edit($1,1,$2)', [h, { groups: [], remove_ids: [] }]],
      ['select set_group_copies($1,false)', [p]],
    ] as const) {
      await expect(db.as(u, c => c.query(sql, [...args]))).rejects.toThrow();
    }
  }
  for (const table of ['live_group_override', 'live_group_source', 'live_group_revision', 'live_catalog_revision']) {
    expect((await db.as(stranger, c => c.query(`select * from ${table}`))).rows).toEqual([]);
    await expect(db.as(null, c => c.query(`select * from ${table}`))).rejects.toThrow();
    await expect(db.as(owner, c => c.query(`delete from ${table}`))).rejects.toThrow();
  }
});
