import { describe, it, expect } from 'vitest';
import { languagesOf, languagesSummary, moveLang, saveLanguages } from '../src/languages';

describe('langues d’un profil', () => {
  it('par défaut : Québec, France, anglais, sous-titres anglais', () => {
    expect(languagesOf({})).toEqual({ order: ['qc', 'fr', 'en'], subtitles: 'en', prefer_4k: false, only_mine: false });
    expect(languagesSummary(languagesOf({}))).toBe('QC, FR, EN · sous-titres anglais');
  });
  it('lecture prudente, ordre et enregistrement', async () => {
    expect(languagesOf({ languages: { order: ['en', 'xx', 'en', 'qc'], subtitles: 'klingon', prefer_4k: true, only_mine: true } }))
      .toEqual({ order: ['en', 'qc'], subtitles: 'en', prefer_4k: true, only_mine: true });
    expect(moveLang(['qc', 'fr', 'en'], 'en', -1)).toEqual(['qc', 'en', 'fr']);
    expect(moveLang(['qc', 'fr'], 'qc', -1)).toEqual(['qc', 'fr']);
    expect(languagesSummary({ order: ['fr'], subtitles: 'off', prefer_4k: true, only_mine: false })).toBe('FR · sans sous-titres · 4K');
    expect(languagesSummary({ order: ['qc'], subtitles: 'en', prefer_4k: false, only_mine: true })).toBe('QC · sous-titres anglais · seulement ces langues');
    const calls: unknown[] = [];
    const db = { from: () => ({ update: (v: object) => ({ eq: () => ({ select: async () => { calls.push(v); return { data: [{ id: 'p' }], error: null }; } }) }) }) };
    expect(await saveLanguages(db as never, 'p', { avatar_v: 1 }, { order: ['en'], subtitles: 'fr', prefer_4k: false, only_mine: true }))
      .toEqual({ avatar_v: 1, languages: { order: ['en'], subtitles: 'fr', prefer_4k: false, only_mine: true } });
    await expect(saveLanguages(db as never, 'p', {}, { order: [], subtitles: 'en', prefer_4k: false, only_mine: false })).rejects.toThrow(/au moins/);
  });
});
