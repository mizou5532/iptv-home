import { describe, it, expect } from 'vitest';
import { pageLoader } from '../src/org/channels';
import { stateLabel, reorder } from '../src/org/favorites';
import { validName, addTo } from '../src/org/playlists';
import { orgSetFavorite, orgReorderFavorites, orgPlaylistSetItems, orgPlaylists } from '../src/org/api';
import type { Channel } from '../src/org/api';
import type { Db } from '../src/flow';

const ch = (i: number): Channel => ({ id: `s${i}`, name: `Chaîne ${i}`, number: i, favorite: false, hiddenGlobal: false, hiddenProfile: false });

function fakeDb(data: unknown = null) {
  const calls: { fn: string; args?: object }[] = [];
  const db: Db = {
    async rpc(fn, args) { calls.push({ fn, args }); return { data, error: null }; },
    from() { return { select: async () => ({ data: [], error: null }) }; },
  };
  return { db, calls };
}

describe('pageLoader', () => {
  it('pageLoader_charge_par_pages_et_pas_deux_fois', async () => {
    const asked: number[][] = [];
    const loader = pageLoader(async (off, lim) => {
      asked.push([off, lim]);
      return Array.from({ length: Math.max(0, Math.min(lim, 1200 - off)) }, (_, i) => ch(off + i));
    }, 500);
    expect(loader.total()).toBeNull();
    expect(loader.get(0)).toBeUndefined();
    await Promise.all([loader.ensure(0, 10), loader.ensure(5, 20)]);
    expect(asked).toEqual([[0, 500]]);
    expect(loader.get(499)?.id).toBe('s499');
    expect(loader.get(500)).toBeUndefined();
    await loader.ensure(480, 520);
    await loader.ensure(0, 600);
    expect(asked).toEqual([[0, 500], [500, 500]]);
    await loader.ensure(1100, 1150);
    expect(asked).toEqual([[0, 500], [500, 500], [1000, 500]]);
    expect(loader.total()).toBe(1200);
    expect(loader.get(1199)?.id).toBe('s1199');
    await loader.ensure(1300, 1400);
    expect(asked.length).toBe(3);
  });

  it('pageLoader_echec_non_memorise', async () => {
    let n = 0;
    const loader = pageLoader(async () => { if (n++ === 0) throw new Error('réseau'); return [ch(0)]; }, 500);
    await expect(loader.ensure(0, 1)).rejects.toThrow('réseau');
    await loader.ensure(0, 1);
    expect(loader.get(0)?.id).toBe('s0');
    expect(loader.total()).toBe(1);
  });
});

describe('favoris', () => {
  it('stateLabel_quatre_etats', () => {
    expect(stateLabel('ok')).toBe('');
    expect(stateLabel('categorie-masquee')).toBe('Catégorie masquée');
    expect(stateLabel('masquee')).toBe('Masquée');
    expect(stateLabel('n-existe-plus')).toBe("N'existe plus");
  });
  it('reorder_favoris', () => {
    const ids = ['a', 'b', 'c'];
    expect(reorder(ids, 2, 0)).toEqual(['c', 'a', 'b']);
    expect(reorder(ids, 0, 1)).toEqual(['b', 'a', 'c']);
    expect(reorder(ids, 0, -1)).toEqual(['a', 'b', 'c']);
    expect(reorder(ids, 1, 9)).toEqual(['a', 'c', 'b']);
    expect(ids).toEqual(['a', 'b', 'c']);
  });
});

describe('playlists', () => {
  it('validName_bornes', () => {
    expect(validName('')).toBeNull();
    expect(validName('   ')).toBeNull();
    expect(validName('  Matchs  ')).toBe('Matchs');
    expect(validName('a'.repeat(40))).toBe('a'.repeat(40));
    expect(validName('a'.repeat(41))).toBeNull();
    expect(validName(`  ${'a'.repeat(40)}  `)).toBe('a'.repeat(40));
  });
  it('addTo_sans_doublon', () => {
    expect(addTo(['a', 'b'], 'c')).toEqual(['a', 'b', 'c']);
    expect(addTo(['a', 'b'], 'a')).toEqual(['a', 'b']);
    const base = ['a'];
    addTo(base, 'z');
    expect(base).toEqual(['a']);
  });
});

describe('api favoris et playlists', () => {
  it('orgSetFavorite_puis_orgReorderFavorites_arguments', async () => {
    const { db, calls } = fakeDb();
    await orgSetFavorite(db, 'p1', 's9', true);
    await orgReorderFavorites(db, 'p1', ['s9', 's2']);
    expect(calls).toEqual([
      { fn: 'org_set_favorite', args: { p: 'p1', channel: 's9', on_: true } },
      { fn: 'org_reorder_favorites', args: { p: 'p1', ids: ['s9', 's2'] } },
    ]);
  });
  it('orgPlaylistSetItems_ordre_conserve', async () => {
    const { db, calls } = fakeDb();
    await orgPlaylistSetItems(db, 'pl1', ['s3', 's1', 's2']);
    expect(calls).toEqual([{ fn: 'org_playlist_set_items', args: { id: 'pl1', channel_ids: ['s3', 's1', 's2'] } }]);
  });
  it('orgPlaylists_items_noms_et_etats', async () => {
    const { db } = fakeDb([{ id: 'pl1', name: 'Mix', position: 0, channel_ids: ['a', 'b'], items: [
      { id: 'a', name: 'RDS', number: 3, category_id: 'c1', state: 'ok' },
      { id: 'b', name: null, number: null, category_id: null, state: 'n-existe-plus' }] }]);
    const [pl] = await orgPlaylists(db, 'p1');
    expect(pl.channelIds).toEqual(['a', 'b']);
    expect(pl.items).toEqual([
      { id: 'a', name: 'RDS', number: 3, categoryId: 'c1', state: 'ok' },
      { id: 'b', name: '', number: null, categoryId: '', state: 'n-existe-plus' }]);
  });
});
