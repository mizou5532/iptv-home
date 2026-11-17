import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { nextWindows, saveSeasons, seasonAt, seasonsOf, windowLabel } from '../src/seasons';

const fixture = JSON.parse(readFileSync(new URL('../../../fixtures/seasons.json', import.meta.url), 'utf8')) as { cases: [string, string | null][] };

describe('thèmes de saison', () => {
  it('mêmes dates que la télé (exemples communs)', () => {
    for (const [day, expected] of fixture.cases) expect(seasonAt(day), day).toBe(expected);
  });
  it('prochaines périodes et libellés', () => {
    const w = nextWindows('2026-11-17');
    expect(windowLabel(w.noel!)).toBe('du 1 au 30 déc.');
    expect(windowLabel(w.nouvel_an!)).toBe('du 31 déc. au 3 janv.');
    expect(windowLabel(w.aid_fitr!)).toBe('du 10 au 12 mars');
    expect(windowLabel(w.ramadan!)).toBe('du 8 févr. au 9 mars');
    expect(w.valentin).toBeUndefined(); // en 2027, la Saint-Valentin tombe pendant le Ramadan
    expect(windowLabel(w.halloween!)).toBe('du 24 au 31 oct.');
  });
  it('réglages lus prudemment et enregistrés', async () => {
    expect(seasonsOf({})).toEqual({ on: true, off: [], force: null });
    expect(seasonsOf({ seasons: { on: false, off: ['noel', 'pirate'], force: 'halloween' } })).toEqual({ on: false, off: ['noel'], force: 'halloween' });
    const calls: unknown[] = [];
    const db = { from: () => ({ select: async () => ({ data: [], error: null }), update: (v: object) => ({ eq: () => ({ select: async () => { calls.push(v); return { data: [{ id: 'h' }], error: null }; } }) }) }) };
    const next = await saveSeasons(db as never, 'h', { welcome_theme: 'aurore' }, { on: true, off: ['valentin'], force: null });
    expect(next).toEqual({ welcome_theme: 'aurore', seasons: { on: true, off: ['valentin'], force: null } });
  });
});
