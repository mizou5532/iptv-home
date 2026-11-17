// Logique pure de la fonction media-meta (testée avec vitest, sans Deno ni réseau).

export type Kind = 'movie' | 'series';

/** Minuscules, sans accents, seulement lettres/chiffres séparés d'un espace. */
export function norm(s: string): string {
  return s.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase()
    .replace(/&/g, ' and ').replace(/[^a-z0-9]+/g, ' ').trim();
}

const NOISE = new Set(['4k', 'uhd', 'fhd', 'hd', 'sd', 'hevc', 'h265', 'x265', 'hdr', 'hdr10', '1080p', '720p', '2160p',
  'multi', 'vostfr', 'vost', 'vf', 'vff', 'vfq', 'vo', 'truefrench', 'french', 'subfrench', 'dual', 'remux', 'web', 'webrip',
  'bluray', 'dolby', 'atmos', '3d', 'imax', 'netflix', 'nf', 'amzn', 'dsnp', 'hmax', 'appletv']);

/**
 * Nom du fournisseur → titre à chercher et année. Retire les préfixes de langue ou de qualité (« FR | », « |EN| »,
 * « 4K - »), les crochets, les mentions de qualité, et l'année entre parenthèses ou en fin de nom.
 * « FR| The Batman (2022) [MULTI] » → { title: "The Batman", year: 2022 }.
 */
export function cleanTitle(raw: string, year?: number | null): { title: string; year: number | null } {
  let s = raw.replace(/\s+/g, ' ').trim();
  // Préfixes « FR | », « |FR| », « [FR] », « FR: », « FR - », répétés (« 4K | FR | … »).
  for (let i = 0; i < 4; i++) {
    const next = s.replace(/^\s*[|[(]?\s*[A-Za-z0-9+]{1,6}\s*[|\])]\s*[-:|]?\s*/, '').replace(/^\s*[A-Z0-9+]{2,4}\s*[-:]\s+/, '');
    if (next === s || next.length < 2) break;
    s = next;
  }
  let y: number | null = year && year > 1880 && year < 2100 ? year : null;
  const paren = s.match(/[([]\s*((?:18|19|20)\d{2})\s*[)\]]/);
  if (paren) { y ??= Number(paren[1]); s = s.replace(paren[0], ' '); }
  const tail = s.match(/\s*[-–:]\s*((?:19|20)\d{2})\s*$/);
  if (tail && s.length - tail[0].length >= 2) { y ??= Number(tail[1]); s = s.slice(0, s.length - tail[0].length); }
  s = s.replace(/\[[^\]]*\]/g, ' ').replace(/\((?:[^)]*?(?:vf|vostfr|multi|4k|hd|fhd|uhd|version)[^)]*)\)/gi, ' ');
  // Saison collée au nom d'une série (« Breaking Bad S01 », « … Saison 2 »).
  s = s.replace(/\s+(?:S\d{1,2}(?:E\d{1,3})?|Saison\s*\d+|Season\s*\d+)\s*$/i, '');
  const words = s.split(/\s+/).filter((w) => !NOISE.has(w.toLowerCase().replace(/[^a-z0-9]/g, '')));
  s = words.join(' ').replace(/\s*[-|:]\s*$/, '').trim();
  return { title: s || raw.trim(), year: y };
}

/**
 * Recherches à essayer dans l'ordre : le titre nettoyé, puis, s'il finit par une année sans séparateur
 * (« Oppenheimer 2023 »), le titre sans elle avec cette année (« Blade Runner 2049 » reste essayé tel quel d'abord).
 */
export function variants(title: string, year: number | null): { title: string; year: number | null }[] {
  const out = [{ title, year }];
  const m = title.match(/^(.{2,}?)\s+((?:19|20)\d{2})$/);
  if (m && !year) out.push({ title: m[1]!, year: Number(m[2]) });
  return out;
}

/** Clé du cache : « movie:the batman:2022 ». */
export function keyOf(kind: Kind, title: string, year: number | null): string {
  return `${kind}:${norm(title)}:${year ?? ''}`;
}

export interface Candidate { id: number; title?: string; name?: string; original_title?: string; original_name?: string;
  release_date?: string; first_air_date?: string; popularity?: number; vote_count?: number }

/** Meilleur résultat de recherche TMDB, ou null si aucun titre ne ressemble vraiment. */
export function pickCandidate(results: Candidate[], title: string, year: number | null): Candidate | null {
  const want = norm(title);
  const wantWords = new Set(want.split(' ').filter((w) => w.length > 1));
  let best: { c: Candidate; score: number } | null = null;
  for (const c of results.slice(0, 20)) {
    const names = [c.title, c.name, c.original_title, c.original_name].filter((x): x is string => !!x).map(norm);
    const exact = names.includes(want);
    const overlap = Math.max(0, ...names.map((n) => {
      const ws = n.split(' ').filter((w) => w.length > 1);
      const common = ws.filter((w) => wantWords.has(w)).length;
      return common / Math.max(ws.length, wantWords.size, 1);
    }));
    if (!exact && overlap < 0.6) continue;
    const cy = Number((c.release_date || c.first_air_date || '').slice(0, 4)) || null;
    let score = (exact ? 10 : overlap * 6) + Math.log10((c.vote_count ?? 0) + 1);
    if (year && cy) score += cy === year ? 6 : Math.abs(cy - year) <= 1 ? 3 : -4;
    if (!best || score > best.score) best = { c, score };
  }
  return best?.c ?? null;
}

export interface TmdbImage { file_path: string; iso_639_1: string | null; vote_average?: number; width?: number }

/** Image préférée : langues dans l'ordre voulu (null = sans texte), puis la mieux notée. */
export function pickImage(list: TmdbImage[] | undefined, langs: (string | null)[], accept: (p: string) => boolean = () => true): string | null {
  const ok = (list ?? []).filter((i) => i.file_path && accept(i.file_path));
  for (const l of langs) {
    const of = ok.filter((i) => (i.iso_639_1 ?? null) === l).sort((a, b) => (b.vote_average ?? 0) - (a.vote_average ?? 0));
    if (of.length) return of[0]!.file_path;
  }
  return null;
}

export interface Translation { iso_639_1: string; iso_3166_1: string; data?: { title?: string; name?: string; overview?: string } }

/**
 * Titre et résumé français. Titre : celui de France d'abord (les titres québécois de TMDB sont souvent des
 * sous-titres ajoutés : « Breaking Bad : Le chimiste » ; titre de France vide = titre original), puis québécois ; résumé : québécois d'abord, puis France ;
 * sinon ceux de la fiche.
 */
export function frenchText(translations: Translation[] | undefined, fallbackTitle: string | null, fallbackOverview: string | null, originalTitle: string | null = null) {
  const fr = (translations ?? []).filter((t) => t.iso_639_1 === 'fr');
  const order = (first: string) => [...fr].sort((a, b) => (a.iso_3166_1 === first ? -1 : 0) - (b.iso_3166_1 === first ? -1 : 0));
  // Traduction de France présente mais sans titre = même titre que l'original (« Breaking Bad », « The Batman »).
  const france = fr.find((t) => t.iso_3166_1 === 'FR');
  const franceTitle = (france?.data?.title || france?.data?.name)?.trim() || (france && originalTitle && /^[\p{Script=Latin}\d\s\p{P}\p{S}]+$/u.test(originalTitle) ? originalTitle : null);
  const title = franceTitle || order('CA').map((t) => t.data?.title || t.data?.name).find((x) => !!x?.trim()) || fallbackTitle;
  const overview = order('CA').map((t) => t.data?.overview).find((x) => !!x?.trim()) || fallbackOverview;
  return { title: title || null, overview: overview || null };
}

/** Note IMDb d'OMDb (« 8.4 », « 1,234,567 ») ; « N/A » → null. */
export function omdbRating(body: { Response?: string; imdbRating?: string; imdbVotes?: string } | null) {
  if (!body || body.Response === 'False') return { rating: null, votes: null };
  const r = Number(body.imdbRating);
  const v = Number((body.imdbVotes ?? '').replace(/,/g, ''));
  return { rating: Number.isFinite(r) && r > 0 ? Math.round(r * 10) / 10 : null, votes: Number.isFinite(v) && v > 0 ? v : null };
}

const DAY = 86_400_000;
/** Faut-il relire TMDB (fiche) ou OMDb (note) ? Fiche : 60 j ; introuvable : 7 j ; note : 7 j. */
export function staleness(row: { status: string; fetched_at: string; rating_at: string | null; imdb_id: string | null } | undefined, now: number) {
  if (!row) return { meta: true, rating: true };
  const age = now - Date.parse(row.fetched_at);
  if (row.status === 'not_found') return { meta: age > 7 * DAY, rating: false };
  return { meta: age > 60 * DAY, rating: !!row.imdb_id && (!row.rating_at || now - Date.parse(row.rating_at) > 7 * DAY) };
}
