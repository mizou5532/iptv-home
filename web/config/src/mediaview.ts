// Séries et films d'un profil (page iPhone, « Séries et films ») : Reprendre, Ma liste, bannière et rangées des
// accueils de la télé. Réglages dans profiles.settings.media_view (MediaView côté Kotlin : mêmes noms de champs) ;
// Reprendre et Ma liste passent par les fonctions du serveur. La télé relit les réglages à l'ouverture de l'onglet.
import type { SettingsDb } from './avatar';
import { friendly, type Db } from './flow';
import { searchNorm } from './org/norm';

export type MediaKind = 'series' | 'movies';
export type BannerMode = 'auto' | 'fixe';

export interface PinnedTitle { id: string; category?: string | null }
export interface RowSetting { key: string; on: boolean }
export interface KindView {
  banner_mode: BannerMode;
  banner_seconds: number;
  banner_max: number;
  banner_pinned: PinnedTitle[];
  /** Sources de la bannière, dans l'ordre ; seules celles présentes sont utilisées. */
  banner_sources: string[];
  never: string[];
  rows: RowSetting[];
}

export const SECONDS = [6, 10, 15, 20] as const;
export const MAXES = [3, 5, 8] as const;
export const PIN_MAX = 5;
export const NEVER_MAX = 200;

export const SOURCES: Record<MediaKind, { id: string; label: string }[]> = {
  series: [
    { id: 'reprendre', label: 'La série en cours' },
    { id: 'nouveaux', label: 'Nouveaux épisodes' },
    { id: 'suggestions', label: 'Suggestions' },
    { id: 'ma_liste', label: 'Ma liste' },
  ],
  movies: [
    { id: 'reprendre', label: 'Le film en cours' },
    { id: 'suggestions', label: 'Suggestions' },
    { id: 'ma_liste', label: 'Ma liste' },
    { id: 'nouveautes', label: 'Ajoutés récemment' },
  ],
};
export const ROWS: Record<MediaKind, { id: string; label: string }[]> = {
  series: [
    { id: 'reprendre', label: 'Reprendre' },
    { id: 'ma_liste', label: 'Ma liste' },
    { id: 'nouveaux', label: 'Nouveaux épisodes dans tes séries' },
    { id: 'categories', label: 'Catégories' },
  ],
  movies: [
    { id: 'reprendre', label: 'Reprendre' },
    { id: 'ma_liste', label: 'Ma liste' },
    { id: 'nouveautes', label: 'Ajoutés récemment' },
    { id: 'categories', label: 'Catégories' },
  ],
};

type Raw = Record<string, unknown>;
const obj = (v: unknown): Raw => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Raw) : {});
const strs = (v: unknown): string[] => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string' && x !== '') : []);

/**
 * Réglages d'un type, complétés : valeurs inconnues ramenées aux valeurs par défaut, rangées et sources toujours
 * complètes (une source absente est décochée : elle reste à la fin de la liste). Séries : l'ancienne série épinglée
 * (`pinned_series`) est reprise si rien n'est épinglé.
 */
export function kindViewOf(settings: Record<string, unknown>, kind: MediaKind): KindView {
  const raw = obj(obj(settings.media_view)[kind]);
  const known = SOURCES[kind].map((s) => s.id);
  const rowIds = ROWS[kind].map((r) => r.id);
  const sources = Array.isArray(raw.banner_sources) ? [...new Set(strs(raw.banner_sources).filter((s) => known.includes(s)))] : known;
  const rowsIn = Array.isArray(raw.rows) ? (raw.rows as unknown[]).map(obj).filter((r) => rowIds.includes(String(r.key))) : [];
  const rows: RowSetting[] = [...new Map(rowsIn.map((r) => [String(r.key), { key: String(r.key), on: r.on !== false }])).values()];
  for (const id of rowIds) if (!rows.some((r) => r.key === id)) rows.push({ key: id, on: true });
  let pinned: PinnedTitle[] = (Array.isArray(raw.banner_pinned) ? (raw.banner_pinned as unknown[]).map(obj) : [])
    .filter((p) => typeof p.id === 'string' && p.id !== '')
    .map((p) => ({ id: String(p.id), category: typeof p.category === 'string' ? p.category : null }));
  if (kind === 'series' && !pinned.length && typeof settings.pinned_series === 'string' && settings.pinned_series) {
    pinned = [{ id: settings.pinned_series, category: typeof settings.pinned_series_category === 'string' ? settings.pinned_series_category : null }];
  }
  const seconds = Number(raw.banner_seconds);
  const max = Number(raw.banner_max);
  return {
    banner_mode: raw.banner_mode === 'fixe' ? 'fixe' : 'auto',
    banner_seconds: (SECONDS as readonly number[]).includes(seconds) ? seconds : 10,
    banner_max: (MAXES as readonly number[]).includes(max) ? max : 5,
    banner_pinned: [...new Map(pinned.map((p) => [p.id, p])).values()].slice(0, PIN_MAX),
    banner_sources: sources,
    never: [...new Set(strs(raw.never))].slice(0, NEVER_MAX),
    rows,
  };
}

/** Sources dans l'ordre d'affichage du réglage : les cochées (dans leur ordre), puis les décochées. */
export function sourceRows(view: KindView, kind: MediaKind): { id: string; label: string; on: boolean }[] {
  const all = SOURCES[kind];
  const on = view.banner_sources.map((id) => all.find((s) => s.id === id)!).filter(Boolean);
  return [...on.map((s) => ({ ...s, on: true })), ...all.filter((s) => !view.banner_sources.includes(s.id)).map((s) => ({ ...s, on: false }))];
}

/** Déplace [id] d'une place (-1 vers le haut, 1 vers le bas). */
export function move<T>(list: T[], at: number, dir: -1 | 1): T[] {
  const j = at + dir;
  if (at < 0 || j < 0 || j >= list.length) return list;
  const next = [...list];
  [next[at], next[j]] = [next[j], next[at]];
  return next;
}

/** Enregistre les réglages d'un type ; les autres clés du profil sont gardées. Séries : l'ancienne épingle est retirée. */
export async function saveKindView(db: SettingsDb, profileId: string, settings: Record<string, unknown>, kind: MediaKind, view: KindView): Promise<Record<string, unknown>> {
  const mv = obj(settings.media_view);
  const next: Record<string, unknown> = { ...settings, media_view: { ...mv, [kind]: view } };
  if (kind === 'series') { delete next.pinned_series; delete next.pinned_series_category; }
  const r = await db.from('profiles').update({ settings: next }).eq('id', profileId).select('id');
  if (r.error) throw new Error('Impossible d’enregistrer. Réessaie dans un instant.');
  if (!Array.isArray(r.data) || r.data.length === 0) throw new Error('Ce profil n’existe plus. Recharge la page.');
  return next;
}

// ---- Titres : Reprendre, Ma liste, recherche ----

export interface Title { id: string; name: string; cover: string | null; category: string | null; year?: string | null }
export interface ContinueItem extends Title {
  /** « S2 · É4 · 23 min restantes », « 1 h 10 restantes »… */
  detail: string;
  progress: number | null;
  duration_s: number;
  /** Série : épisode en cours (pour « Épisode vu »). */
  episode?: { item_id: string; season: number | null; episode: number | null; duration_s: number };
}
export interface ListItem extends Title { known: boolean }
export interface Hit extends Title { inList: boolean }

const MESSAGES: Record<string, string> = {
  'acces-refuse': 'Tu n’as pas le droit de modifier ce profil.',
  'type-invalide': 'Action non reconnue. Recharge la page.',
  'titres-invalides': 'Action non reconnue. Recharge la page.',
};
async function call(db: Db, fn: string, args: object): Promise<unknown> {
  const { data, error } = await db.rpc(fn, args);
  if (error) {
    for (const key of Object.keys(MESSAGES)) if (error.message.includes(key)) throw new Error(MESSAGES[key]);
    throw new Error(friendly(error.message));
  }
  return data;
}
const rows = (d: unknown): Raw[] => (Array.isArray(d) ? (d as unknown[]).map(obj) : []);
const str = (v: unknown): string | null => (typeof v === 'string' && v !== '' ? v : null);
const num = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null);

/** Une affiche n'est montrée que si c'est une adresse complète (celles du fournisseur ne gardent que leur chemin). */
export function coverUrl(cover: string | null): string | null {
  return cover && /^https?:\/\//i.test(cover) ? cover.replace(/^http:\/\//i, 'https://') : null;
}

function remaining(pos: number, dur: number): string | null {
  if (dur <= 0) return null;
  const min = Math.ceil(Math.max(0, dur - pos) / 60);
  if (min <= 0) return 'presque fini';
  return min >= 60 ? `${Math.floor(min / 60)} h ${String(min % 60).padStart(2, '0')} restantes` : `${min} min restantes`;
}

/** Titres en cours (le plus récent d'abord), comme sur la télé ; les retirés n'y sont pas. */
export async function loadContinue(db: Db, profileId: string, kind: MediaKind): Promise<ContinueItem[]> {
  const data = await call(db, kind === 'series' ? 'continue_watching' : 'continue_movies', { p: profileId, max_rows: 60 });
  return rows(data).filter((r) => (kind === 'series' ? r.item_type === 'episode' : r.item_type === 'movie')).map((r) => {
    const pos = num(r.position_s) ?? 0; const dur = num(r.duration_s) ?? 0;
    const watched = r.watched === true;
    if (kind === 'series') {
      const s = num(r.season); const e = num(r.episode);
      const ep = s != null && e != null ? `S${s} · É${e}` : null;
      return {
        id: String(r.series_id ?? r.item_id), name: str(r.series_name) ?? 'Série', cover: str(r.series_cover), category: str(r.series_category),
        detail: [ep, watched ? 'épisode fini' : remaining(pos, dur)].filter(Boolean).join(' · '),
        progress: !watched && dur > 0 ? Math.min(1, pos / dur) : null, duration_s: dur,
        episode: { item_id: String(r.item_id), season: s, episode: e, duration_s: dur },
      };
    }
    return {
      id: String(r.item_id), name: str(r.movie_name) ?? 'Film', cover: str(r.movie_cover), category: str(r.movie_category),
      detail: remaining(pos, dur) ?? '', progress: dur > 0 ? Math.min(1, pos / dur) : null, duration_s: dur,
    };
  });
}

/** Retire des titres de Reprendre (la position est gardée ; relancer le titre le fait revenir). */
export async function dismissContinue(db: Db, profileId: string, kind: MediaKind, ids: string[]): Promise<void> {
  if (!ids.length) return;
  await call(db, 'dismiss_continue', { p: profileId, kind: kind === 'series' ? 'series' : 'movie', ids, dismissed: true });
}

/** Film : marqué vu (il quitte Reprendre). Série : l'épisode en cours est marqué vu (la télé propose le suivant). */
export async function markWatched(db: Db, profileId: string, kind: MediaKind, item: ContinueItem): Promise<void> {
  if (kind === 'movies') {
    await call(db, 'set_movie_watched', { p: profileId, item_id: item.id, watched: true, movie_name: item.name, movie_cover: item.cover, movie_category: item.category, duration_s: item.duration_s || null });
    return;
  }
  const e = item.episode;
  if (!e) return;
  await call(db, 'set_watched', { p: profileId, watched: true, items: [{
    item_id: e.item_id, series_id: item.id, season: e.season, episode: e.episode, duration_s: e.duration_s,
    series_name: item.name, series_cover: item.cover, series_category: item.category,
  }] });
}

export async function loadLists(db: Db, profileId: string): Promise<Record<MediaKind, ListItem[]>> {
  const data = obj(await call(db, 'media_lists', { p: profileId }));
  const map = (d: unknown, fallback: string): ListItem[] => rows(d).map((r) => ({
    id: String(r.id), name: str(r.name) ?? fallback, cover: str(r.cover), category: str(r.category_id), year: str(r.year), known: r.known === true,
  }));
  return { series: map(data.series, 'Série inconnue'), movies: map(data.movies, 'Film inconnu') };
}

export async function setInList(db: Db, profileId: string, kind: MediaKind, t: Title, on: boolean): Promise<void> {
  if (kind === 'series') await call(db, 'org_set_series_list', { p: profileId, series_id: t.id, on_: on });
  else await call(db, 'org_set_movie_list', { p: profileId, movie_id: t.id, on_: on, movie_name: t.name, movie_cover: t.cover, movie_category: t.category });
}

export async function reorderList(db: Db, profileId: string, kind: MediaKind, ids: string[]): Promise<void> {
  await call(db, kind === 'series' ? 'org_reorder_series_list' : 'org_reorder_movie_list', { p: profileId, ids });
}

/** Recherche dans les titres que la télé a publiés (catégories visibles du profil seulement). */
export async function searchTitles(db: Db, profileId: string, kind: MediaKind, query: string): Promise<Hit[]> {
  const q = searchNorm(query);
  if (q.length < 2) return [];
  const data = await call(db, 'search_catalog', { p: profileId, kind: kind === 'series' ? 'series' : 'movie', q, max_rows: 30 });
  return rows(data).map((r) => ({ id: String(r.id), name: str(r.name) ?? '', cover: str(r.cover), category: str(r.category_id), year: str(r.year), inList: r.in_list === true }));
}

/** Noms des titres épinglés et écartés (catalogue publié par la télé). */
export async function titlesByIds(db: Db, profileId: string, kind: MediaKind, ids: string[]): Promise<Map<string, Title>> {
  if (!ids.length) return new Map();
  const k = kind === 'series' ? 'series' : 'movie';
  const data = await call(db, 'catalog_titles_by_ids', { p: profileId, items: ids.map((id) => ({ kind: k, id })) });
  return new Map(rows(data).map((r) => [String(r.id), { id: String(r.id), name: str(r.name) ?? '', cover: str(r.cover), category: str(r.category_id), year: str(r.year) }]));
}
