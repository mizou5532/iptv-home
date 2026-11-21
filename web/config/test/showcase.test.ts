import { describe, expect, it } from 'vitest';
import {
  chosenShowcaseCategories,
  filterShowcaseCategories,
  isDefaultShowcaseCategory,
  saveShowcaseCategories,
  type ShowcaseCategory,
} from '../src/showcase';

const movieCats: ShowcaseCategory[] = [
  { id: '1', name: 'FR - NOUVEAUTÉS', position: 0 },
  { id: '2', name: 'FR - 4K UHD', position: 1 },
  { id: '3', name: 'EN - NEW RELEASE', position: 2 },
  { id: '4', name: 'DOCU NICHE', position: 3 },
];

const seriesCats: ShowcaseCategory[] = [
  { id: '10', name: 'FRANCE SERIES', position: 0 },
  { id: '11', name: 'FRANCE ANIMATION SERIES ⁴ᴷ', position: 1 },
  { id: '12', name: 'DISNEY+ SERIES', position: 2 },
];

describe('Vitrine Films et Séries', () => {
  it('reproduit la règle automatique de Showcase.kt', () => {
    expect(isDefaultShowcaseCategory('movies', 'FR - NOUVEAUTÉS')).toBe(true);
    expect(isDefaultShowcaseCategory('movies', 'FR - 4K UHD')).toBe(false);
    expect(isDefaultShowcaseCategory('movies', 'EN - NEW RELEASE')).toBe(true);
    expect(isDefaultShowcaseCategory('movies', 'DOCU NICHE')).toBe(false);

    expect(isDefaultShowcaseCategory('series', 'FRANCE SERIES')).toBe(true);
    expect(isDefaultShowcaseCategory('series', 'FRANCE ANIMATION SERIES ⁴ᴷ')).toBe(false);
    expect(isDefaultShowcaseCategory('series', 'DISNEY+ SERIES')).toBe(false);
  });

  it('choisit le choix manuel si valide, sinon le choix automatique', () => {
    expect(chosenShowcaseCategories({}, 'movies', movieCats)).toEqual({ ids: ['1', '3'], auto: true });
    expect(chosenShowcaseCategories({ showcase_movies: ['4', '999'] }, 'movies', movieCats)).toEqual({ ids: ['4'], auto: false });
    // Si toutes les catégories choisies ont disparu chez le fournisseur, retombe en automatique.
    expect(chosenShowcaseCategories({ showcase_movies: ['999'] }, 'movies', movieCats)).toEqual({ ids: ['1', '3'], auto: true });
    expect(chosenShowcaseCategories({ showcase_series: ['12'] }, 'series', seriesCats)).toEqual({ ids: ['12'], auto: false });
  });

  it('filtre sans accents et met les cochées en tête', () => {
    const out = filterShowcaseCategories(movieCats, 'nouveautes', new Set(['4']));
    expect(out.map((c) => c.id)).toEqual(['1']);
    const ordered = filterShowcaseCategories(movieCats, '', new Set(['4']));
    expect(ordered.map((c) => c.id)).toEqual(['4', '1', '2', '3']);
  });

  it('enregistre un choix personnalisé ou revient en automatique, et refuse une liste vide', async () => {
    let stored: Record<string, unknown> = { welcome_theme: 'aurore' };
    const db = {
      from: () => ({
        select: async () => ({ data: [{ id: 'h1', settings: stored }], error: null }),
        update: (v: { settings: Record<string, unknown> }) => ({
          eq: () => ({
            select: async () => {
              stored = v.settings;
              return { data: [{ id: 'h1' }], error: null };
            },
          }),
        }),
      }),
    };
    await expect(saveShowcaseCategories(db as never, 'h1', 'movies', [])).rejects.toThrow('au moins une catégorie');

    const saved = await saveShowcaseCategories(db as never, 'h1', 'movies', ['1', '4', '1']);
    expect(saved).toEqual({ welcome_theme: 'aurore', showcase_movies: ['1', '4'] });

    const reset = await saveShowcaseCategories(db as never, 'h1', 'movies', null);
    expect(reset).toEqual({ welcome_theme: 'aurore' });
  });
});
