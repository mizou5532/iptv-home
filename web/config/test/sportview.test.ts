import { describe, it, expect } from 'vitest';
import { moveSport, moveTeam, saveSportView, sportViewOf, sportsOfTeams } from '../src/sportview';
import { affichePreview } from '../src/sportview-ui';
import type { SettingsDb } from '../src/avatar';

describe('réglages du sport', () => {
  it('par défaut : couleurs des équipes, ordre hockey → soccer, mes équipes d’abord, aide visible, affiche', () => {
    expect(sportViewOf({})).toEqual({ theme: 'equipes', order: ['hockey', 'football', 'basketball', 'soccer'], hidden: [], mine_first: true, featured: 'auto', hints: true, lang_order: ['qc', 'fr', 'us', 'ca'], layout: 'affiche', hide_scores: false });
  });
  it('valeurs inconnues ignorées, ordre complété', () => {
    const v = sportViewOf({ sport_view: { theme: 'neon', order: ['soccer', 'curling', 'soccer'], hidden: ['basketball', 'x'], mine_first: false, featured: 'direct', hints: false, lang_order: ['us', 'xx', 'us'], layout: 'mosaique', hide_scores: 'oui' } });
    expect(v).toEqual({ theme: 'equipes', order: ['soccer', 'hockey', 'football', 'basketball'], hidden: ['basketball'], mine_first: false, featured: 'direct', hints: false, lang_order: ['us', 'qc', 'fr', 'ca'], layout: 'affiche', hide_scores: false });
    expect(sportViewOf({ sport_view: { featured: 'mes_equipes', hide_scores: true } })).toMatchObject({ featured: 'mes_equipes', hide_scores: true });
    expect(sportViewOf({ sport_view: { featured: 'n_importe' } }).featured).toBe('auto');
  });
  it('disposition grille gardée, aperçu de la grille', () => {
    const v = sportViewOf({ sport_view: { layout: 'grille', theme: 'sombre' } });
    expect(v.layout).toBe('grille');
    expect(v.theme).toBe('sombre');
    expect(affichePreview(v, '#3366ff')).toContain('gridmini');
    expect(affichePreview({ ...v, layout: 'affiche' }, '#3366ff')).toContain('affmini-strip');
    expect(affichePreview({ ...v, hints: false }, '#3366ff')).not.toContain('maintenir OK');
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
    const next = await saveSportView(db, 'p', { avatar_v: 1 }, { ...view, theme: 'sombre', layout: 'grille' });
    expect(next).toEqual({ avatar_v: 1, sport_view: { ...view, theme: 'sombre', layout: 'grille' } });
    expect(calls).toEqual([{ settings: next }]);
  });
});
