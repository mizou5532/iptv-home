import { describe, it, expect } from 'vitest';
import { moveSport, moveTeam, saveSportView, sportViewOf, sportsOfTeams } from '../src/sportview';
import type { SettingsDb } from '../src/avatar';

describe('réglages du sport', () => {
  it('par défaut : couleurs des équipes, ordre hockey → soccer, mes équipes d’abord, aide visible', () => {
    expect(sportViewOf({})).toEqual({ theme: 'equipes', order: ['hockey', 'football', 'basketball', 'soccer'], hidden: [], mine_first: true, featured: 'mes_equipes', hints: true, lang_order: ['qc', 'fr', 'us', 'ca'] });
  });
  it('valeurs inconnues ignorées, ordre complété', () => {
    const v = sportViewOf({ sport_view: { theme: 'neon', order: ['soccer', 'curling', 'soccer'], hidden: ['basketball', 'x'], mine_first: false, featured: 'direct', hints: false, lang_order: ['us', 'xx', 'us'] } });
    expect(v).toEqual({ theme: 'equipes', order: ['soccer', 'hockey', 'football', 'basketball'], hidden: ['basketball'], mine_first: false, featured: 'direct', hints: false, lang_order: ['us', 'qc', 'fr', 'ca'] });
  });
  it('monter et descendre', () => {
    expect(moveSport(['hockey', 'football', 'basketball', 'soccer'], 'soccer', -1)).toEqual(['hockey', 'football', 'soccer', 'basketball']);
    expect(moveSport(['hockey', 'football'], 'hockey', -1)).toEqual(['hockey', 'football']);
    const a = { league: 'nhl', id: '1', name: 'A' }, b = { league: 'nfl', id: '2', name: 'B' };
    expect(moveTeam([a, b], 1, -1)).toEqual([b, a]);
    expect(moveTeam([a, b], 1, 1)).toEqual([a, b]);
  });
  it('sports des équipes favorites (jamais masqués)', () => {
    expect(sportsOfTeams({})).toEqual(['hockey', 'soccer']);
    expect(sportsOfTeams({ sport_teams: [{ league: 'nfl', id: '23', name: 'Steelers' }] })).toEqual(['football']);
  });
  it('enregistre en gardant le reste', async () => {
    const calls: unknown[] = [];
    const db: SettingsDb = { from: () => ({ update: (v) => ({ eq: () => ({ select: async () => { calls.push(v); return { data: [{ id: 'p' }], error: null }; } }) }) }) };
    const view = sportViewOf({});
    const next = await saveSportView(db, 'p', { avatar_v: 1 }, { ...view, theme: 'sombre' });
    expect(next).toEqual({ avatar_v: 1, sport_view: { ...view, theme: 'sombre' } });
    expect(calls).toEqual([{ settings: next }]);
  });
});
