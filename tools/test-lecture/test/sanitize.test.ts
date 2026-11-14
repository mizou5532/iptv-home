import { it, expect } from 'vitest';
import { sanitize } from '../src/sanitize';

it('retire identifiants en clair et encodés', () => {
  const t = 'GET http://x/movie/jean%40x/a%26b%23c/1.mkv par jean@x avec a&b#c';
  expect(sanitize(t, { username: 'jean@x', password: 'a&b#c' }))
    .toBe('GET http://x/movie/***/***/1.mkv par *** avec ***');
});

it('ignore une valeur vide', () => {
  expect(sanitize('abc', { username: '', password: '' })).toBe('abc');
});
