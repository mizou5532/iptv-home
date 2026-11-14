import { it, expect } from 'vitest';
import { summarize, type RawCatalog } from '../src/summary';

const m = (id: number, ext: string | null) => ({ stream_id: id, name: `Film ${id}`, container_extension: ext });
const l = (id: number) => ({ stream_id: id, name: `Chaîne ${id}` });
const si = (name: string, eps: [number, string][]) => ({
  info: { name },
  episodes: { '1': eps.map(([id, ext]) => ({ id: String(id), title: `É${id}`, container_extension: ext })) },
});
const raw = (p: Partial<RawCatalog>): RawCatalog => ({
  serverUrl: 'http://tv.exemple.com:8080', userInfo: {}, live: [], movies: [], series: [], seriesInfos: [], ...p,
});

it('compte les extensions, tolère vide/majuscules', () => {
  const s = summarize(raw({ movies: [m(1, 'mp4'), m(2, 'MKV'), m(3, ''), m(4, 'mkv'), m(5, 'mkv')] }));
  expect(s.movieExtensions).toEqual({ mp4: 1, mkv: 3, inconnu: 1 });
  expect(s.samples.filter((x) => x.kind === 'movie').map((x) => x.id)).toEqual(['1', '2', '4', '3']);
});

it('extension absente (null) → inconnu', () => {
  expect(summarize(raw({ movies: [m(1, null)] })).movieExtensions).toEqual({ inconnu: 1 });
});

it('lit max_connections et le protocole', () => {
  const s = summarize(raw({
    userInfo: { user_info: { max_connections: '1', allowed_output_formats: ['m3u8', 'ts'] } },
    serverUrl: 'http://tv.exemple.com:8080',
  }));
  expect(s.maxConnections).toBe(1);
  expect(s.allowedOutputFormats).toEqual(['m3u8', 'ts']);
  expect(s.serverProtocol).toBe('http');
});

it('3 échantillons direct en m3u8, 4 épisodes max, extensions variées préférées', () => {
  const s = summarize(raw({
    live: [l(1), l(2), l(3), l(4), l(5)],
    series: [{}, {}, {}],
    seriesInfos: [si('A', [[11, 'mkv'], [12, 'mkv'], [13, 'mp4']]), si('B', [[21, 'mp4'], [22, 'mp4'], [23, 'mp4']]), si('C', [[31, 'avi'], [32, 'avi'], [33, 'avi']])],
  }));
  expect(s.samples.filter((x) => x.kind === 'live')).toEqual([
    { kind: 'live', id: '1', ext: 'm3u8', label: 'Chaîne 1' },
    { kind: 'live', id: '2', ext: 'm3u8', label: 'Chaîne 2' },
    { kind: 'live', id: '3', ext: 'm3u8', label: 'Chaîne 3' },
  ]);
  const eps = s.samples.filter((x) => x.kind === 'series');
  expect(eps.map((x) => x.id)).toEqual(['11', '13', '21', '22']);
  expect(s.episodeExtensions).toEqual({ mkv: 2, mp4: 4, avi: 3 });
  expect(s.counts).toEqual({ live: 5, movies: 0, series: 3 });
});

it('max 6 films, 2 par extension', () => {
  const movies = [1, 2, 3].map((i) => m(i, 'mp4')).concat([4, 5, 6].map((i) => m(i, 'mkv')), [7, 8].map((i) => m(i, 'avi')), [9].map((i) => m(i, 'ts')));
  const ids = summarize(raw({ movies })).samples.filter((x) => x.kind === 'movie').map((x) => x.id);
  expect(ids).toEqual(['1', '2', '4', '5', '7', '8']);
});
