import type { Sample } from './summary';

/** Échantillons envoyés à la sonde : 1 direct (en ts), 2 films MKV + 1 MP4, 2 épisodes (MKV d'abord). Au plus 6. */
export function pickProbeSamples(samples: Sample[]): Sample[] {
  const byKind = (k: Sample['kind']) => samples.filter((s) => s.kind === k);
  const mkvFirst = (list: Sample[]) => [...list.filter((s) => s.ext === 'mkv'), ...list.filter((s) => s.ext !== 'mkv')];
  const live = byKind('live').slice(0, 1).map((s) => ({ ...s, ext: 'ts' }));
  const movies = byKind('movie');
  const films = [...movies.filter((s) => s.ext === 'mkv').slice(0, 2), ...movies.filter((s) => s.ext === 'mp4').slice(0, 1)];
  const episodes = mkvFirst(byKind('series')).slice(0, 2);
  return [...live, ...films, ...episodes].slice(0, 6);
}

/** Depuis une page https, un fournisseur http est toujours bloqué par le navigateur (contenu mixte). */
export const browserPlaybackPointless = (pageProtocol: string, serverProtocol: 'http' | 'https'): boolean =>
  pageProtocol === 'https:' && serverProtocol === 'http';
