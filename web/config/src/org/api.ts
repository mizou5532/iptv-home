// Appels du serveur pour l'onglet « Organiser ». p = null : « Tout le monde ».
import { friendly, type Db } from '../flow';
import { searchNorm } from './norm';
import type { CategoryKind } from './categories';

const ORG_MESSAGES: Record<string, string> = {
  'acces-refuse': "Tu n'as pas le droit de modifier cette sélection.",
  'categorie-inconnue': "Cette catégorie n'existe plus. Recharge la liste.",
  'type-invalide': 'Action non reconnue. Recharge la page.',
};

async function call(db: Db, fn: string, args: object): Promise<unknown> {
  const { data, error } = await db.rpc(fn, args);
  if (error) {
    for (const key of Object.keys(ORG_MESSAGES)) if (error.message.includes(key)) throw new Error(ORG_MESSAGES[key]);
    throw new Error(friendly(error.message));
  }
  return data;
}

export interface Category {
  id: string; name: string; providerPosition: number;
  hiddenGlobal: boolean; hiddenProfile: boolean; channelCount: number;
}
export interface Channel {
  id: string; name: string; number: number | null;
  favorite: boolean; hiddenGlobal: boolean; hiddenProfile: boolean;
}
export interface SearchHit extends Channel { categoryId: string; categoryName: string }
export interface FavoriteRow { id: string; name: string; number: number | null; categoryId: string; state: string }
export interface PlaylistItem { id: string; name: string; number: number | null; categoryId: string; state: string }
export interface Playlist { id: string; name: string; position: number; channelIds: string[]; items: PlaylistItem[] }

type Row = Record<string, unknown>;
const rows = (d: unknown): Row[] => (Array.isArray(d) ? (d as Row[]) : []);
const toChannel = (r: Row): Channel => ({
  id: String(r.id), name: String(r.name), number: (r.number as number | null) ?? null,
  favorite: Boolean(r.favorite), hiddenGlobal: Boolean(r.hidden_global), hiddenProfile: Boolean(r.hidden_profile),
});

// Une seule mécanique pour les trois sections (direct, séries 0011, films 0012) : seuls les noms changent.
// Chaque RPC de liste rend UN tableau jsonb (pas un jeu de lignes : PostgREST couperait à 1 000 catégories).
// Séries et films n'ont pas de comptage de chaînes : channelCount vaut alors 0.
export type CategoryHiddenType = 'live_category' | 'series_category' | 'vod_category';
export const CATEGORY_RPC: Record<CategoryKind, { list: string; hideAllExcept: string; setOrder: string; hiddenType: CategoryHiddenType }> = {
  live: { list: 'org_categories', hideAllExcept: 'org_hide_all_except', setOrder: 'org_set_category_order', hiddenType: 'live_category' },
  series: { list: 'org_series_categories', hideAllExcept: 'org_series_hide_all_except', setOrder: 'org_set_series_category_order', hiddenType: 'series_category' },
  movies: { list: 'org_movie_categories', hideAllExcept: 'org_movie_hide_all_except', setOrder: 'org_set_movie_category_order', hiddenType: 'vod_category' },
};
export async function orgKindCategories(db: Db, kind: CategoryKind, h: string, p: string | null): Promise<Category[]> {
  return rows(await call(db, CATEGORY_RPC[kind].list, { h, p })).map((r) => ({
    id: String(r.id), name: String(r.name), providerPosition: Number(r.provider_position ?? 0),
    hiddenGlobal: Boolean(r.hidden_global), hiddenProfile: Boolean(r.hidden_profile),
    channelCount: Number(r.channel_count ?? 0),
  }));
}
export async function orgKindHideAllExcept(db: Db, kind: CategoryKind, h: string, p: string | null, keep: string[]): Promise<number> {
  return Number(await call(db, CATEGORY_RPC[kind].hideAllExcept, { h, p, keep }));
}
export async function orgKindSetOrder(db: Db, kind: CategoryKind, h: string, p: string | null, ids: string[]): Promise<void> {
  await call(db, CATEGORY_RPC[kind].setOrder, { h, p, ids });
}
// Noms par section, gardés pour les appels directs et les tests existants.
type Scoped<R, A extends unknown[] = []> = (db: Db, h: string, p: string | null, ...rest: A) => Promise<R>;
const lister = (kind: CategoryKind): Scoped<Category[]> => (db, h, p) => orgKindCategories(db, kind, h, p);
const hider = (kind: CategoryKind): Scoped<number, [string[]]> => (db, h, p, keep) => orgKindHideAllExcept(db, kind, h, p, keep);
const orderer = (kind: CategoryKind): Scoped<void, [string[]]> => (db, h, p, ids) => orgKindSetOrder(db, kind, h, p, ids);
export const orgCategories = lister('live');
export const orgHideAllExcept = hider('live');
export const orgSetCategoryOrder = orderer('live');
export const orgSeriesCategories = lister('series');
export const orgSeriesHideAllExcept = hider('series');
export const orgSetSeriesCategoryOrder = orderer('series');
export const orgMovieCategories = lister('movies');
export const orgMovieHideAllExcept = hider('movies');
export const orgSetMovieCategoryOrder = orderer('movies');

export async function orgChannels(db: Db, h: string, cat: string, p: string | null, off: number, lim: number): Promise<Channel[]> {
  return rows(await call(db, 'org_channels', { h, category: cat, p, off, lim })).map(toChannel);
}

// Le serveur attend une requête déjà normalisée.
export async function orgSearch(db: Db, h: string, q: string, p: string | null, lim: number): Promise<SearchHit[]> {
  return rows(await call(db, 'org_search', { h, q: searchNorm(q), p, lim })).map((r) => ({
    ...toChannel(r), categoryId: String(r.category_id), categoryName: String(r.category_name),
  }));
}

export async function orgSetHidden(db: Db, h: string, p: string | null, type: CategoryHiddenType | 'channel', ids: string[], hidden: boolean): Promise<number> {
  return Number(await call(db, 'org_set_hidden', { h, p, item_type: type, ids, hidden }));
}

// Favoris et listes (écrans de la tâche 7) : toujours pour un profil.
export async function orgFavorites(db: Db, p: string): Promise<FavoriteRow[]> {
  return rows(await call(db, 'org_favorites', { p })).map((r) => ({
    id: String(r.id), name: String(r.name ?? ''), number: (r.number as number | null) ?? null,
    categoryId: String(r.category_id ?? ''), state: String(r.state),
  }));
}
export async function orgSetFavorite(db: Db, p: string, channel: string, on: boolean): Promise<void> {
  await call(db, 'org_set_favorite', { p, channel, on_: on });
}
export async function orgReorderFavorites(db: Db, p: string, ids: string[]): Promise<void> {
  await call(db, 'org_reorder_favorites', { p, ids });
}
export async function orgPlaylists(db: Db, p: string): Promise<Playlist[]> {
  return rows(await call(db, 'org_playlists', { p })).map((r) => ({
    id: String(r.id), name: String(r.name), position: Number(r.position ?? 0),
    channelIds: Array.isArray(r.channel_ids) ? (r.channel_ids as string[]) : [],
    items: (Array.isArray(r.items) ? (r.items as Row[]) : []).map((i) => ({
      id: String(i.id), name: String(i.name ?? ''), number: (i.number as number | null) ?? null,
      categoryId: String(i.category_id ?? ''), state: String(i.state ?? 'ok'),
    })),
  }));
}
export async function orgPlaylistCreate(db: Db, p: string, name: string): Promise<string> {
  return String(await call(db, 'org_playlist_create', { p, name }));
}
export async function orgPlaylistRename(db: Db, id: string, name: string): Promise<void> {
  await call(db, 'org_playlist_rename', { id, name });
}
export async function orgPlaylistDelete(db: Db, id: string): Promise<void> {
  await call(db, 'org_playlist_delete', { id });
}
export async function orgPlaylistsReorder(db: Db, p: string, ids: string[]): Promise<void> {
  await call(db, 'org_playlists_reorder', { p, ids });
}
export async function orgPlaylistSetItems(db: Db, id: string, channelIds: string[]): Promise<void> {
  await call(db, 'org_playlist_set_items', { id, channel_ids: channelIds });
}
