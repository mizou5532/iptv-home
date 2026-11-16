import { describe, it, expect } from 'vitest';
import { DEFAULT_TEAMS, fromCatalog, MAX_TEAMS, saveTeams, teamsOf, withTeam, withoutTeam } from '../src/teams';
import catalog from '../src/teams-data.json';
import type { SettingsDb } from '../src/avatar';

describe('équipes favorites', () => {
  it('sans choix : Canadiens et Barça ; liste vide choisie = aucune', () => {
    expect(teamsOf({})).toEqual({ teams: DEFAULT_TEAMS, chosen: false });
    expect(teamsOf({ sport_teams: [] })).toEqual({ teams: [], chosen: true });
    expect(teamsOf({ sport_teams: [{ league: 'nba', id: '13', name: 'Lakers' }, { bad: 1 }] }).teams).toHaveLength(1);
  });
  it('ajout sans doublon, limite, retrait', () => {
    const lak = { league: 'nba', id: '13', name: 'Lakers' };
    expect(withTeam(DEFAULT_TEAMS, lak)).toHaveLength(3);
    expect(withTeam([lak], lak)).toEqual([lak]);
    expect(() => withTeam(Array.from({ length: MAX_TEAMS }, (_, i) => ({ league: 'nba', id: String(i), name: 'x' })), lak)).toThrow();
    expect(withoutTeam(DEFAULT_TEAMS, DEFAULT_TEAMS[0]).map((t) => t.id)).toEqual(['83']);
  });
  it('catalogue : ligues et équipes connues, couleur au format #', () => {
    const nhl = catalog.find((l) => l.key === 'nhl')!;
    const mtl = nhl.teams.find((t) => t.abbr === 'MTL')!;
    expect(fromCatalog('nhl', mtl)).toMatchObject({ league: 'nhl', id: '10', abbr: 'MTL', color: '#c41230' });
    expect(catalog.map((l) => l.key)).toEqual(expect.arrayContaining(['nhl', 'nba', 'nfl', 'esp.1', 'eng.1']));
  });
  it('enregistre dans les réglages en gardant le reste', async () => {
    const calls: unknown[] = [];
    const db: SettingsDb = { from: () => ({ update: (v) => ({ eq: () => ({ select: async () => { calls.push(v); return { data: [{ id: 'p' }], error: null }; } }) }) }) };
    const next = await saveTeams(db, 'p', { avatar_v: 1 }, DEFAULT_TEAMS);
    expect(next).toEqual({ avatar_v: 1, sport_teams: DEFAULT_TEAMS });
    expect(calls).toEqual([{ settings: next }]);
  });
});
