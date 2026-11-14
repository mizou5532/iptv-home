import { expect, test } from 'vitest';
import type { Db } from '../src/flow';
import { loadGroupSnapshot, loadGroupSources, saveGroups, setGroupCopies } from '../src/org/groups-api';
const db = (fn: (n: string, a: any) => unknown) =>
  ({
    rpc: async (n: string, a: any) => ({ data: fn(n, a), error: null }),
  }) as Db;
test('snapshot pagine et recommence si revision change', async () => {
  let n = 0;
  const client = db((_f, a) => {
    n++;
    return {
      rev: n === 2 ? 2 : 1,
      rules_version: 1,
      groups: [],
      next_id: a.after_id ? null : 'next',
    };
  });
  expect(await loadGroupSnapshot(client, 'h')).toEqual({
    rev: 1,
    rulesVersion: 1,
    groups: [],
  });
  expect(n).toBe(4);
});
test('catalogue rejette trois lectures incoherentes', async () => {
  let n = 0;
  await expect(
    loadGroupSources(
      db((_f, a) => ({
        catalog_rev: ++n,
        channels: [],
        next_cursor: a.after_id ? null : { position: 0, id: 'x' },
      })),
      'h',
    ),
  ).rejects.toThrow('Réessayer');
  expect(n).toBe(6);
});
test('contrats ecriture et reglage', async () => {
  const calls: any[] = [];
  const client = db((n, a) => {
    calls.push([n, a]);
    return { rev: 4 };
  });
  expect(await saveGroups(client, 'h', 3, { removeIds: [], groups: [] })).toBe(4);
  await setGroupCopies(client, 'p', false);
  expect(calls).toEqual([
    ['live_group_edit', { h: 'h', expected_rev: 3, replacement: { remove_ids: [], groups: [] } }],
    ['set_group_copies', { p: 'p', enabled: false }],
  ]);
});
test('cache complet conserve apres panne de page suivante', async () => {
  const { groupCache } = await import('../src/org/groups-api');
  let fail = false;
  const client = db((_n, a) => {
    if (fail && a.after_id) throw new Error('network');
    return {
      rev: 1,
      rules_version: 1,
      groups: [],
      next_id: fail && !a.after_id ? 'next' : null,
    };
  });
  const old = await loadGroupSnapshot(client, 'h');
  fail = true;
  await expect(loadGroupSnapshot(client, 'h')).rejects.toThrow('Réessayer');
  expect(groupCache(client, 'h').snapshot).toBe(old);
});

test('source et correction gardent les identifiants et les champs SQL', async () => {
  const calls: unknown[] = [];
  const client = db((n, a) => {
    calls.push(a);
    if (n === 'live_catalog_page')
      return {
        catalog_rev: 9,
        channels: [
          { id: 'source-a', name: 'Nom HD', category_id: 'cat', category_name: 'Catégorie', position: 7 },
        ],
        next_cursor: null,
      };
    if (n === 'live_group_page')
      return {
        rev: 2,
        rules_version: 1,
        groups: [{ id: 'stable', title: 'Titre', source_ids: ['source-a'], blocked_auto_keys: ['nom'] }],
        next_id: null,
      };
    return { rev: 3 };
  });
  expect(await loadGroupSources(client, 'h')).toEqual([
    { id: 'source-a', name: 'Nom HD', categoryId: 'cat', categoryName: 'Catégorie', position: 0 },
  ]);
  const snapshot = await loadGroupSnapshot(client, 'h');
  expect(snapshot.groups).toEqual([
    { id: 'stable', title: 'Titre', sourceIds: ['source-a'], blockedAutoKeys: ['nom'] },
  ]);
  await saveGroups(client, 'h', 2, { removeIds: ['ancien'], groups: snapshot.groups });
  expect(calls.at(-1)).toEqual({
    h: 'h',
    expected_rev: 2,
    replacement: {
      remove_ids: ['ancien'],
      groups: [{ id: 'stable', title: 'Titre', source_ids: ['source-a'], blocked_auto_keys: ['nom'] }],
    },
  });
});

test('la position des copies est globale, dans l ordre categorie puis chaine, sur plusieurs pages', async () => {
  const calls: any[] = [];
  const client = db((n, a: any) => {
    calls.push(a);
    // Deux catégories dont les positions de chaînes repartent de 0.
    if (!a.after_id)
      return {
        catalog_rev: 1,
        channels: [
          { id: 'b0', name: 'B0', category_id: 'b', category_name: 'B', category_position: 0, position: 0 },
          { id: 'b1', name: 'B1', category_id: 'b', category_name: 'B', category_position: 0, position: 1 },
        ],
        next_cursor: { category_position: 0, position: 1, id: 'b1' },
      };
    return {
      catalog_rev: 1,
      channels: [
        { id: 'a0', name: 'A0', category_id: 'a', category_name: 'A', category_position: 1, position: 0 },
        { id: 'a1', name: 'A1', category_id: 'a', category_name: 'A', category_position: 1, position: 1 },
      ],
      next_cursor: null,
    };
  });
  const sources = await loadGroupSources(client, 'h');
  expect(sources.map((s) => [s.id, s.position])).toEqual([['b0', 0], ['b1', 1], ['a0', 2], ['a1', 3]]);
  expect(calls[1]).toMatchObject({ after_category_position: 0, after_position: 1, after_id: 'b1' });
});
