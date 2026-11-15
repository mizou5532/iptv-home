import { expect, test } from 'vitest';
import { TABS } from '../src/org/view';

test('l onglet Lecture est absent par defaut (RPC 0010 non livrees)', () => {
  expect(TABS.map((t) => t.id)).toEqual(['copies', 'categories', 'favorites', 'playlists']);
  expect(TABS.some((t) => t.id === 'playback')).toBe(false);
});

test('Films vit dans le selecteur de l onglet Categories, pas dans un nouvel onglet', () => {
  expect(TABS.filter((t) => t.id === 'categories')).toHaveLength(1);
  expect(TABS.some((t) => /film|movie/i.test(t.id))).toBe(false);
});
