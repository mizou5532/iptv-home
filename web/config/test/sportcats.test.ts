import { describe, it, expect } from 'vitest';
import { chosenSportCategories, filterCategories, isDefaultSportCategory, saveSportCategories } from '../src/sportcats';
import type { HouseholdDb } from '../src/look';

const cats = [
  { id: '1', name: 'CA| GENERAL FR', position: 0 },
  { id: '2', name: 'US| ESPN+ PPV', position: 1 },
  { id: '3', name: 'US| ESPN+ PPV ⱽᴵᴾ', position: 2 },
  { id: '4', name: 'US| NHL TEAM PPV', position: 3 },
  { id: '5', name: 'CA| SPORTS FR', position: 4 },
];

describe('catégories du sport', () => {
  it('même règle par défaut que la télé', () => {
    expect(['US| NHL TEAM PPV', 'US| ESPN+ PPV', 'CA| TSN+ PPV', 'US| NBA PASS PPV ⁸ᴷ', 'FR| DAZN LA LIGA PPV', 'CA| SPORTSNET+ PPV'].every(isDefaultSportCategory)).toBe(true);
    expect(['US| NBA PASS VIP', 'AR| BEIN PPV', 'CA| GENERAL FR', 'US| NFL PPV REPLAY', 'US| MLS PPV ⁽ᴮᴷ⁾', 'US| ESPN+ PPV ⱽᴵᴾ'].some(isDefaultSportCategory)).toBe(false);
  });
  it('choix enregistré sinon automatique ; recherche avec cochées d’abord', () => {
    expect(chosenSportCategories({}, cats)).toEqual({ ids: ['2', '4'], auto: true });
    expect(chosenSportCategories({ sport_categories: ['5', 3] }, cats)).toEqual({ ids: ['5'], auto: false });
    expect(filterCategories(cats, 'ppv', new Set(['4'])).map((c) => c.id)).toEqual(['4', '2', '3']);
    expect(filterCategories(cats, 'général', new Set()).map((c) => c.id)).toEqual(['1']);
  });
  it('enregistre sans perdre les autres réglages ; automatique retire la clé', async () => {
    let stored: Record<string, unknown> = { welcome_theme: 'aurore' };
    const db = {
      from: () => ({
        select: async () => ({ data: [{ id: 'h', settings: stored }], error: null }),
        update: (v: { settings: Record<string, unknown> }) => ({ eq: () => ({ select: async () => { stored = v.settings; return { data: [{ id: 'h' }], error: null }; } }) }),
      }),
    } as unknown as HouseholdDb;
    expect(await saveSportCategories(db, 'h', ['2', '2', '5'])).toEqual({ welcome_theme: 'aurore', sport_categories: ['2', '5'] });
    expect(await saveSportCategories(db, 'h', null)).toEqual({ welcome_theme: 'aurore' });
  });
});
