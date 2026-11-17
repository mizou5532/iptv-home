import { describe, it, expect } from 'vitest';
import { languagesOf, languagesSummary, moveLang, saveLanguages } from '../src/languages';

describe('langues d’un profil', () => {
  it('par défaut : Québec, France, anglais, sous-titres anglais', () => {
    expect(languagesOf({})).toEqual({ order: ['qc', 'fr', 'en'], subtitles: 'en', prefer_4k: false });
    expect(languagesSummary(languagesOf({}))).toBe('QC, FR, EN · sous-titres anglais');
  });
  it('lecture prudente, ordre et enregistrement', async () => {
    expect(languagesOf({ languages: { order: ['en', 'xx', 'en', 'qc'], subtitles: 'klingon', prefer_4k: true } }))
      .toEqual({ order: ['en', 'qc'], subtitles: 'en', prefer_4k: true });
    expect(moveLang(['qc', 'fr', 'en'], 'en', -1)).toEqual(['qc', 'en', 'fr']);
    expect(moveLang(['qc', 'fr'], 'qc', -1)).toEqual(['qc', 'fr']);
    expect(languagesSummary({ order: ['fr'], subtitles: 'off', prefer_4k: true })).toBe('FR · sans sous-titres · 4K');
    const calls: unknown[] = [];
    const db = { from: () => ({ update: (v: object) => ({ eq: () => ({ select: async () => { calls.push(v); return { data: [{ id: 'p' }], error: null }; } }) }) }) };
    expect(await saveLanguages(db as never, 'p', { avatar_v: 1 }, { order: ['en'], subtitles: 'fr', prefer_4k: false }))
      .toEqual({ avatar_v: 1, languages: { order: ['en'], subtitles: 'fr', prefer_4k: false } });
    await expect(saveLanguages(db as never, 'p', {}, { order: [], subtitles: 'en', prefer_4k: false })).rejects.toThrow(/au moins/);
  });
});
