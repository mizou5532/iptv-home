import { describe, it, expect } from 'vitest';
import { backgroundOf, inkOn, loadHouseholdSettings, PALETTE, setProfileLook, setWelcomeTheme, welcomeOf, type HouseholdDb } from '../src/look';
import type { DbResult } from '../src/flow';

function fakeDb(rows: object[] = [], result: DbResult = { data: [{ id: 'x' }], error: null }) {
  const calls: unknown[] = [];
  const db: HouseholdDb = { from: (table) => ({
    select: async () => ({ data: rows, error: null }),
    update: (values) => ({ eq: (c, v) => ({ select: async () => { calls.push([table, values, c, v]); return result; } }) }),
  }) };
  return { db, calls };
}

describe('personnalisation', () => {
  it('couleur et fond d’un profil', async () => {
    const { db, calls } = fakeDb();
    await setProfileLook(db as never, 'p1', { color: '#af1e2d', theme: 'clair' });
    expect(calls).toEqual([['profiles', { color: '#AF1E2D', theme: 'clair' }, 'id', 'p1']]);
    await expect(setProfileLook(db as never, 'p1', { color: 'rouge' })).rejects.toThrow(/Couleur/);
    await expect(setProfileLook(db as never, 'p1', { theme: 'fun' as never })).rejects.toThrow(/Fond/);
    expect(backgroundOf(undefined)).toBe('sombre'); expect(backgroundOf('clair')).toBe('clair');
  });
  it('texte lisible sur la couleur, palette valide', () => {
    expect(inkOn('#F4B6C8')).toBe('#0B0F17'); expect(inkOn('#AF1E2D')).toBe('#FFFFFF');
    expect(PALETTE.every((p) => /^#[0-9A-F]{6}$/.test(p.color))).toBe(true);
  });
  it('thème de l’écran d’accueil (maison)', async () => {
    const { db, calls } = fakeDb([{ id: 'h1', settings: { welcome_theme: 'aurore', x: 1 } }, { id: 'h2', settings: {} }]);
    const s = await loadHouseholdSettings(db, 'h1');
    expect(welcomeOf(s)).toBe('aurore'); expect(welcomeOf({})).toBe('doux');
    expect(await setWelcomeTheme(db, 'h1', s, 'doux')).toEqual({ welcome_theme: 'doux', x: 1 });
    expect(calls).toEqual([['households', { settings: { welcome_theme: 'doux', x: 1 } }, 'id', 'h1']]);
    const refused = fakeDb([], { data: [], error: null });
    await expect(setWelcomeTheme(refused.db, 'h1', {}, 'aurore')).rejects.toThrow(/propriétaire/);
  });
});
