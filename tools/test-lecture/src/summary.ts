import type { StreamKind } from './xtream';

export interface Sample { kind: StreamKind; id: string; ext: string; label: string }

export interface CatalogSummary {
  serverProtocol: 'http' | 'https';
  maxConnections: number | null;
  allowedOutputFormats: string[];
  counts: { live: number; movies: number; series: number };
  movieExtensions: Record<string, number>;
  episodeExtensions: Record<string, number>;
  samples: Sample[];
}

export interface RawCatalog {
  serverUrl: string;
  userInfo: unknown;
  live: unknown[];
  movies: unknown[];
  series: unknown[];
  seriesInfos: unknown[];
}

type Obj = Record<string, unknown>;
const obj = (v: unknown): Obj => (v && typeof v === 'object' ? (v as Obj) : {});
const str = (v: unknown): string => (v === null || v === undefined ? '' : String(v));
const normExt = (v: unknown): string => str(v).trim().toLowerCase() || 'inconnu';

function count(exts: string[]): Record<string, number> {
  const out: Record<string, number> = {};
  for (const e of exts) out[e] = (out[e] ?? 0) + 1;
  return out;
}

export function summarize(raw: RawCatalog): CatalogSummary {
  const ui = obj(obj(raw.userInfo).user_info);
  const max = Number.parseInt(str(ui.max_connections), 10);
  const formats = Array.isArray(ui.allowed_output_formats) ? ui.allowed_output_formats.map(str) : [];

  const samples: Sample[] = [];

  for (const ch of raw.live.slice(0, 3).map(obj)) {
    samples.push({ kind: 'live', id: str(ch.stream_id), ext: 'm3u8', label: str(ch.name) });
  }

  // Films : jusqu'à 2 par extension (dans l'ordre où chaque extension apparaît), 6 au total.
  const byExt = new Map<string, Obj[]>();
  for (const mv of raw.movies.map(obj)) {
    const e = normExt(mv.container_extension);
    const list = byExt.get(e) ?? [];
    if (list.length < 2) list.push(mv);
    byExt.set(e, list);
  }
  const movieSamples = [...byExt.entries()].flatMap(([ext, list]) =>
    list.map((mv) => ({ kind: 'movie' as const, id: str(mv.stream_id), ext, label: str(mv.name) })));
  samples.push(...movieSamples.slice(0, 6));

  // Épisodes : jusqu'à 2 par série, 4 au total, en préférant des extensions différentes.
  const allEpisodeExts: string[] = [];
  const episodeSamples: Sample[] = [];
  for (const info of raw.seriesInfos.map(obj)) {
    const name = str(obj(info.info).name);
    const eps = Object.values(obj(info.episodes)).flatMap((v) => (Array.isArray(v) ? v.map(obj) : []));
    allEpisodeExts.push(...eps.map((e) => normExt(e.container_extension)));
    if (eps.length === 0) continue;
    const first = eps[0];
    const firstExt = normExt(first.container_extension);
    const second = eps.slice(1).find((e) => normExt(e.container_extension) !== firstExt) ?? eps[1];
    for (const e of [first, second]) {
      if (!e) continue;
      episodeSamples.push({ kind: 'series', id: str(e.id), ext: normExt(e.container_extension), label: `${name} — ${str(e.title)}` });
    }
  }
  samples.push(...episodeSamples.slice(0, 4));

  return {
    serverProtocol: raw.serverUrl.trim().toLowerCase().startsWith('https:') ? 'https' : 'http',
    maxConnections: Number.isFinite(max) ? max : null,
    allowedOutputFormats: formats,
    counts: { live: raw.live.length, movies: raw.movies.length, series: raw.series.length },
    movieExtensions: count(raw.movies.map((mv) => normExt(obj(mv).container_extension))),
    episodeExtensions: count(allEpisodeExts),
    samples,
  };
}
