import { describe, expect, it } from 'vitest';
import { reunite, splitsOf } from '../src/splits';

describe('versions séparées', () => {
  it('lit la liste, ignore le reste et les doublons', () => {
    expect(splitsOf({})).toEqual([]);
    expect(splitsOf({ version_splits: ['movie:12', 'series:9', 'movie:12', 'live:3', 7, 'movie:a b'] }))
      .toEqual([{ kind: 'movie', id: '12' }, { kind: 'series', id: '9' }]);
  });
  it('réunir appelle la fonction serveur', async () => {
    const calls: unknown[] = [];
    const db = { rpc: async (fn: string, args: object) => { calls.push([fn, args]); return { data: null, error: null }; }, from: () => ({ select: async () => ({ data: [], error: null }) }) };
    await reunite(db as never, 'h', { kind: 'movie', id: '12' });
    expect(calls).toEqual([['set_version_split', { h: 'h', kind: 'movie', item_id: '12', split: false }]]);
    const bad = { ...db, rpc: async () => ({ data: null, error: { message: 'x' } }) };
    await expect(reunite(bad as never, 'h', { kind: 'movie', id: '12' })).rejects.toThrow(/réunir/);
  });
});
