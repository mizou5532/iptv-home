import { describe, it, expect } from 'vitest';
import { backgroundOf, inkOn, lookOf, setThemeLook, themeColors, THEMES, loadHouseholdSettings, PALETTE, setProfileLook, setWelcomeTheme, welcomeOf, type HouseholdDb } from '../src/look';
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
  it('thème complet : lu, calculé comme la télé, enregistré avec la colonne theme', async () => {
    expect(lookOf({}, 'clair')).toEqual({ preset: 'doux', corners: 'arrondis' });
    expect(lookOf({}, 'sombre')).toEqual({ preset: 'nuit', corners: 'arrondis' });
    expect(lookOf({ look: { preset: 'perso', background: '#3a0d0d', corners: 'ronds' } }, 'clair')).toEqual({ preset: 'perso', background: '#3A0D0D', corners: 'ronds' });
    expect(lookOf({ look: { preset: 'perso', background: 'rouge' } }, 'clair').preset).toBe('doux');
    expect(lookOf({ look: { preset: 'inconnu' } }, 'sombre').preset).toBe('nuit');
    expect(themeColors({ preset: 'sable' }, '#AF1E2D')).toEqual({ bg: '#F3ECDF', surface: '#FFFFFF', text: '#2B1D26', light: true });
    expect(themeColors({ preset: 'perso', background: '#FFFFFF' }, '#AF1E2D').surface).toBe('rgba(0,0,0,.05)');
    expect(themeColors({ preset: 'foret' }, '#AF1E2D').light).toBe(false);
    expect(THEMES.map((t) => t.id)).toEqual(['nuit', 'doux', 'minuit', 'foret', 'prune', 'charbon', 'sable', 'ciel']);
    const { db, calls } = fakeDb();
    const next = await setThemeLook(db as never, 'p1', { avatar_v: 2 }, { preset: 'sable', corners: 'carres' }, '#AF1E2D');
    expect(next).toEqual({ avatar_v: 2, look: { preset: 'sable', corners: 'carres' } });
    expect(calls).toEqual([['profiles', { settings: next, theme: 'clair' }, 'id', 'p1']]);
    await expect(setThemeLook(db as never, 'p1', {}, { preset: 'perso', background: 'x' }, '#AF1E2D')).rejects.toThrow(/fond/);
  });
  it('texte lisible sur la couleur, palette valide', () => {
    expect(inkOn('#F4B6C8')).toBe('#0B0F17'); expect(inkOn('#AF1E2D')).toBe('#FFFFFF');
    expect(PALETTE.every((p) => /^#[0-9A-F]{6}$/.test(p.color))).toBe(true);
  });
  it('thème de l’écran d’accueil (maison)', async () => {
    const { db, calls } = fakeDb([{ id: 'h1', settings: { welcome_theme: 'aurore', x: 1 } }, { id: 'h2', settings: {} }]);
    const s = await loadHouseholdSettings(db, 'h1');
    expect(welcomeOf(s)).toBe('aurore'); expect(welcomeOf({})).toBe('doux');
    expect(welcomeOf({ welcome_theme: 'doux_nuit' })).toBe('doux_nuit'); expect(welcomeOf({ welcome_theme: 'inconnu' })).toBe('doux');
    expect(await setWelcomeTheme(db, 'h1', s, 'doux')).toEqual({ welcome_theme: 'doux', x: 1 });
    expect(calls).toEqual([['households', { settings: { welcome_theme: 'doux', x: 1 } }, 'id', 'h1']]);
    expect((await setWelcomeTheme(db, 'h1', s, 'doux_nuit')).welcome_theme).toBe('doux_nuit');
    const refused = fakeDb([], { data: [], error: null });
    await expect(setWelcomeTheme(refused.db, 'h1', {}, 'aurore')).rejects.toThrow(/propriétaire/);
  });
});
