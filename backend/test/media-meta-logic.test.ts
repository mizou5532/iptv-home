import { describe, expect, it } from 'vitest';
import { cleanTitle, frenchText, keyOf, variants, norm, omdbRating, pickCandidate, pickImage, staleness } from '../supabase/functions/media-meta/logic';

describe('media-meta : nettoyage des noms du fournisseur', () => {
  it.each([
    ['FR| The Batman (2022) [MULTI]', null, 'The Batman', 2022],
    ['|EN| Breaking Bad', null, 'Breaking Bad', null],
    ['4K | FR | Dune: Part Two (2024)', null, 'Dune: Part Two', 2024],
    ['FR - Intouchables - 2011', null, 'Intouchables', 2011],
    ['Oppenheimer 4K HDR', 2023, 'Oppenheimer', 2023],
    ['Les Simpson S01', null, 'Les Simpson', null],
    ['1917 (2019)', null, '1917', 2019],
    ['Blade Runner 2049', null, 'Blade Runner 2049', null],
    ['La Casa de Papel (VF)', null, 'La Casa de Papel', null],
  ])('%s', (raw, year, title, y) => {
    expect(cleanTitle(raw, year)).toEqual({ title, year: y });
  });
  it('année collée au titre : deux essais', () => {
    expect(variants('Oppenheimer 2023', null)).toEqual([{ title: 'Oppenheimer 2023', year: null }, { title: 'Oppenheimer', year: 2023 }]);
    expect(variants('Dune', 2021)).toEqual([{ title: 'Dune', year: 2021 }]);
  });
  it('clé normalisée', () => {
    expect(keyOf('movie', 'Amélie & Co: Le Film!', 2001)).toBe('movie:amelie and co le film:2001');
    expect(norm('  Ça, c’est Paris ')).toBe('ca c est paris');
  });
});

describe('media-meta : choix des résultats TMDB', () => {
  const results = [
    { id: 1, title: 'The Batman', release_date: '2004-09-11', vote_count: 300 },
    { id: 2, title: 'The Batman', release_date: '2022-03-01', vote_count: 9000 },
    { id: 3, title: 'Batman Begins', release_date: '2005-06-10', vote_count: 20000 },
  ];
  it('titre exact et bonne année', () => expect(pickCandidate(results, 'The Batman', 2022)?.id).toBe(2));
  it('sans année : le plus connu au titre exact', () => expect(pickCandidate(results, 'The Batman', null)?.id).toBe(2));
  it('titre original accepté', () => expect(pickCandidate([{ id: 9, title: 'La Casa de Papel', original_name: 'Money Heist' }], 'Money Heist', null)?.id).toBe(9));
  it('rien qui ressemble : null', () => expect(pickCandidate(results, 'Totoro', null)).toBeNull());
  it('images : langue voulue, sans texte d’abord pour les fonds, logos PNG seulement', () => {
    const imgs = [{ file_path: '/en.jpg', iso_639_1: 'en', vote_average: 9 }, { file_path: '/clean.jpg', iso_639_1: null, vote_average: 5 }, { file_path: '/clean2.jpg', iso_639_1: null, vote_average: 7 }];
    expect(pickImage(imgs, [null, 'fr', 'en'])).toBe('/clean2.jpg');
    expect(pickImage([{ file_path: '/l.svg', iso_639_1: 'fr' }, { file_path: '/l.png', iso_639_1: 'en' }], ['fr', 'en'], (p) => p.endsWith('.png'))).toBe('/l.png');
    expect(pickImage(undefined, ['fr'])).toBeNull();
  });
  it('texte français : Québec d’abord, puis France, sinon la fiche', () => {
    const tr = [{ iso_639_1: 'fr', iso_3166_1: 'FR', data: { title: 'Very Bad Trip', overview: 'Résumé France' } }, { iso_639_1: 'fr', iso_3166_1: 'CA', data: { title: 'Lendemain de veille', overview: '' } }];
    expect(frenchText(tr, 'The Hangover', 'EN')).toEqual({ title: 'Lendemain de veille', overview: 'Résumé France' });
    expect(frenchText([], 'The Hangover', null)).toEqual({ title: 'The Hangover', overview: null });
  });
  it('note OMDb', () => {
    expect(omdbRating({ Response: 'True', imdbRating: '8.4', imdbVotes: '1,234,567' })).toEqual({ rating: 8.4, votes: 1234567 });
    expect(omdbRating({ Response: 'True', imdbRating: 'N/A', imdbVotes: 'N/A' })).toEqual({ rating: null, votes: null });
    expect(omdbRating({ Response: 'False' })).toEqual({ rating: null, votes: null });
  });
  it('fraîcheur', () => {
    const now = Date.parse('2026-11-17T00:00:00Z');
    expect(staleness(undefined, now)).toEqual({ meta: true, rating: true });
    expect(staleness({ status: 'ok', fetched_at: '2026-11-01T00:00:00Z', rating_at: '2026-11-15T00:00:00Z', imdb_id: 'tt1' }, now)).toEqual({ meta: false, rating: false });
    expect(staleness({ status: 'ok', fetched_at: '2026-11-01T00:00:00Z', rating_at: '2026-11-01T00:00:00Z', imdb_id: 'tt1' }, now)).toEqual({ meta: false, rating: true });
    expect(staleness({ status: 'not_found', fetched_at: '2026-11-01T00:00:00Z', rating_at: null, imdb_id: null }, now)).toEqual({ meta: true, rating: false });
  });
});
