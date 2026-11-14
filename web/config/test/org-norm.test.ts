import { describe, it, expect } from 'vitest';
import { searchNorm } from '../src/org/norm';

describe('searchNorm', () => {
  const vectors: [string, string][] = [
    ['Télé-Québec HD', 'tele-quebec hd'],
    ['  RDS   2 ', 'rds 2'],
    ['ÇA Sport', 'ca sport'],
    ['Cœur Æther', 'coeur aether'],
    ['Šport Čt', 'sport ct'],
    ['Žena TV', 'zena tv'],
    ['A B', 'a b'],
    ['', ''],
  ];
  for (const [input, expected] of vectors) {
    it(`vecteur ${JSON.stringify(input)}`, () => { expect(searchNorm(input)).toBe(expected); });
  }
  it('espace insecable et BOM compactes', () => {
    expect(searchNorm('﻿A  B')).toBe('a b');
  });
});
