// Logique et appels réseau de l'onglet « ▶ Regarder » sur téléphone / navigateur :
// connexion au serveur maison (`home_server.public_url`), jeton propre (`issue_web_server_token`),
// préférences & thèmes du profil (Supabase `profile_prefs`, `loadContinue`, `loadLists`, `series_progress`, progression),
// Direct, Séries, Films, Sport, Recherche et lancement des flux HLS.
import type { Db, KV } from './flow';
import { CORNERS, inkOn, lookOf, themeColors } from './look';
import type { ProfileHome } from './profiles';
import { sportViewOf, type SportKey, type SportView } from './sportview';
import { teamsOf, type SportTeam } from './teams';
import { dimOf, wallpaperUrl } from './wallpaper';

export interface HomeConnection {
  householdId: string;
  baseUrl: string;
  token: string;
  clientKey: string;
  serverVersion: string | null;
}

export interface SnapCategory {
  id: string;
  name: string;
  position?: number;
}

export interface SnapStream {
  id: string;
  name: string;
  categoryId: string;
  icon?: string | null;
  epgChannelId?: string | null;
  number?: number | null;
  position?: number;
  autoKey?: string | null;
  title?: string | null;
}

export interface LiveSnapshot {
  rev: string;
  account?: string;
  builtAt?: number;
  categories: SnapCategory[];
  streams: SnapStream[];
  hidden?: string[];
}

export interface SnapEpg {
  title: string;
  startMs: number;
  endMs: number;
  nowPlaying: boolean;
}

export interface VodCategory {
  id: string;
  name: string;
  position: number;
  rev: string;
}

export interface VodManifest {
  rev: string;
  categories: VodCategory[];
  hidden: string[];
}

export interface SnapMovie {
  id: string;
  name: string;
  categoryId: string;
  cover?: string | null;
  backdrop?: string | null;
  plot?: string | null;
  genre?: string | null;
  rating?: string | null;
  year?: string | null;
  extension?: string;
  durationS?: number | null;
}

export interface SnapSeries {
  id: string;
  name: string;
  categoryId: string;
  cover?: string | null;
  backdrop?: string | null;
  plot?: string | null;
  genre?: string | null;
  rating?: string | null;
  year?: string | null;
}

export interface SnapEpisode {
  id: string;
  season: number;
  number: number;
  title: string;
  extension: string;
  durationS?: number | null;
  cover?: string | null;
}

export interface SnapSeason {
  number: number;
  name: string;
  episodes: SnapEpisode[];
}

export interface SnapSeriesInfo {
  summary: SnapSeries;
  seasons: SnapSeason[];
}

export interface SnapMediaTech {
  width?: number | null;
  height?: number | null;
  hdr?: string | null;
  videoCodec?: string | null;
  audioCodec?: string | null;
  audioChannels?: number | null;
}

export interface SnapMovieInfo {
  summary: SnapMovie;
  tech?: SnapMediaTech | null;
}

export interface SnapVersionTag {
  lang?: string | null;
  uhd?: boolean;
  hdr?: boolean;
  source?: string | null;
}

export interface SnapVersion {
  id: string;
  categoryId: string;
  title: string;
  tag: SnapVersionTag;
  cover?: string | null;
  year?: string | null;
}

export interface VersionsReply {
  rev: string;
  groups: Record<string, SnapVersion[]>;
}

export interface SearchHit {
  id: string;
  title: string;
  categoryId: string;
  cover?: string | null;
  anchorSourceId?: string | null;
  versions?: SnapVersion[];
  note?: string | null;
}

export interface SnapMatch {
  id: string;
  league: string;
  startMs: number;
  state: string; // 'in' | 'pre' | 'post'
  home: string;
  away: string;
  followed?: boolean;
  broadcasters?: string[];
  homeId?: string;
  awayId?: string;
  homeAbbr?: string;
  awayAbbr?: string;
  homeLogo?: string | null;
  awayLogo?: string | null;
  homeScore?: number | null;
  awayScore?: number | null;
  detail?: string | null;
  homeColor?: string | null;
  awayColor?: string | null;
  homeShort?: string;
  awayShort?: string;
  period?: number | null;
}

export interface SnapSportChannel {
  groupId: string;
  title: string;
  categoryName: string;
  anchorSourceId: string;
  direct?: boolean;
}

export interface SnapSportCandidates {
  proposed?: SnapSportChannel[];
  all?: SnapSportChannel[];
  likely?: SnapSportChannel[];
  reasons?: Record<string, string>;
  proposedIds?: string[];
  allIds?: string[];
  likelyIds?: string[];
}

export interface SportBoardReply {
  rev: string;
  fetchedAtMs: number;
  matches: SnapMatch[];
  standings?: Record<string, string>;
  candidates: Record<string, SnapSportCandidates>;
  channels: Record<string, SnapSportChannel>;
  offline?: boolean;
}

export interface StreamOccupant {
  id: string;
  deviceName: string;
  profileName?: string | null;
  label?: string | null;
}

export interface StreamStartReply {
  status: 'ok' | 'busy' | 'unavailable' | 'not_found' | 'error';
  sessionId?: string | null;
  playlistPath?: string | null;
  durationS?: number | null;
  title?: string | null;
  occupants?: StreamOccupant[];
}

export interface HiddenPref {
  item_type: string;
  item_id: string;
}

export interface PlaylistPref {
  id: string;
  name: string;
  items: string[];
}

export interface ProfilePrefs {
  rev: number;
  group_copies: boolean;
  hidden: HiddenPref[];
  favorites: string[];
  playlists: PlaylistPref[];
  order: string[] | null;
  movie_order: string[] | null;
  series_order: string[] | null;
  movie_list: string[];
  series_list: string[];
}

export interface EpisodeProgressRow {
  item_id: string;
  season: number | null;
  episode: number | null;
  position_s: number;
  duration_s: number;
  watched: boolean;
}

export interface LiveDisplayItem {
  id: string;
  title: string;
  badge: string | null;
  icon: string | null;
  categoryId: string;
  copyIds: string[];
}

export interface ProfileWatchTheme {
  bg: string;
  metaBg: string;
  surface: string;
  surface2: string;
  text: string;
  muted: string;
  accent: string;
  onAccent: string;
  border: string;
  light: boolean;
  radiusPx: number;
  wallpaperUrl: string | null;
  wallpaperDim: number;
}

const CLIENT_KEY_STORAGE = 'iptv_web_client_key';
const TOKEN_STORAGE_PREFIX = 'iptv_web_token_';

/** Génère ou relit l'identifiant stable de ce téléphone/navigateur (gardé dans localStorage). */
export function getOrCreateClientKey(store: KV): string {
  const existing = store.getItem(CLIENT_KEY_STORAGE);
  if (existing && /^[0-9A-Za-z_-]{12,64}$/.test(existing)) return existing;
  const chars = '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ';
  let key = 'web_';
  for (let i = 0; i < 24; i++) {
    key += chars[Math.floor(Math.random() * chars.length)];
  }
  store.setItem(CLIENT_KEY_STORAGE, key);
  return key;
}

export type HomeServerDb = Db;

/** Connecte le téléphone au serveur maison (lit l'adresse HTTPS publique et obtient un jeton d'accès). */
export async function connectHomeServer(
  db: HomeServerDb,
  householdId: string,
  store: KV,
  forceNewToken = false,
): Promise<HomeConnection> {
  const r = await db.from('home_server').select('household_id, url, public_url, version, last_seen');
  if (r.error) throw new Error('Impossible de joindre le serveur maison.');
  const rows = Array.isArray(r.data) ? (r.data as Record<string, unknown>[]) : [];
  const row = rows.find((x) => !x.household_id || x.household_id === householdId) ?? rows[0];
  if (!row) {
    throw new Error('Le serveur maison n’est pas encore associé au foyer.');
  }
  const publicUrl = typeof row.public_url === 'string' && row.public_url.startsWith('https://') ? row.public_url.replace(/\/+$/, '') : '';
  const lanUrl = typeof row.url === 'string' ? row.url.replace(/\/+$/, '') : '';
  const baseUrl = publicUrl || lanUrl;
  if (!baseUrl) {
    throw new Error('Adresse du serveur maison introuvable.');
  }
  const clientKey = getOrCreateClientKey(store);
  const tokenKey = TOKEN_STORAGE_PREFIX + householdId;
  let token = !forceNewToken ? store.getItem(tokenKey) : null;
  if (!token) {
    const tr = await db.rpc('issue_web_server_token', { h: householdId, client_key: clientKey });
    if (tr.error || typeof tr.data !== 'string' || !tr.data) {
      throw new Error('Impossible d’autoriser ce téléphone auprès du serveur maison.');
    }
    token = tr.data;
    store.setItem(tokenKey, token);
  }
  return {
    householdId,
    baseUrl,
    token,
    clientKey,
    serverVersion: typeof row.version === 'string' ? row.version : null,
  };
}

/** Appel authentifié au serveur maison (`/v1/...`), avec renouvellement automatique du jeton en cas de 401. */
export async function homeFetch<T>(
  db: HomeServerDb,
  conn: HomeConnection,
  store: KV,
  path: string,
  postBody?: unknown,
  fetcher: typeof fetch = fetch,
): Promise<T> {
  const doCall = async (token: string) =>
    fetcher(`${conn.baseUrl}${path}`, {
      method: postBody !== undefined ? 'POST' : 'GET',
      headers: {
        Authorization: `Bearer ${token}`,
        'X-Home-Protocol': '1',
        ...(postBody !== undefined ? { 'Content-Type': 'application/json' } : {}),
      },
      body: postBody !== undefined ? JSON.stringify(postBody) : undefined,
    });

  let resp = await doCall(conn.token);
  if (resp.status === 401) {
    const fresh = await connectHomeServer(db, conn.householdId, store, true);
    conn.token = fresh.token;
    conn.baseUrl = fresh.baseUrl;
    resp = await doCall(conn.token);
  }
  if (!resp.ok) {
    throw new Error(`Le serveur maison n’a pas pu répondre (${resp.status}).`);
  }
  if (resp.status === 204) return undefined as T;
  return (await resp.json()) as T;
}

const strList = (v: unknown): string[] => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : []);

/** Lit les préférences du profil sur Supabase (`hidden`, `favorites`, `playlists`, ordres et listes). */
export async function loadProfilePrefs(db: Db, profileId: string): Promise<ProfilePrefs> {
  const { data, error } = await db.rpc('profile_prefs', { p: profileId });
  if (error || !data || typeof data !== 'object') {
    return {
      rev: 0,
      group_copies: true,
      hidden: [],
      favorites: [],
      playlists: [],
      order: null,
      movie_order: null,
      series_order: null,
      movie_list: [],
      series_list: [],
    };
  }
  const raw = data as Record<string, unknown>;
  const hidden = Array.isArray(raw.hidden)
    ? (raw.hidden as unknown[])
        .filter((x): x is Record<string, unknown> => !!x && typeof x === 'object')
        .map((x) => ({ item_type: String(x.item_type ?? ''), item_id: String(x.item_id ?? '') }))
        .filter((x) => x.item_type !== '' && x.item_id !== '')
    : [];
  const playlists = Array.isArray(raw.playlists)
    ? (raw.playlists as unknown[])
        .filter((x): x is Record<string, unknown> => !!x && typeof x === 'object')
        .map((x) => ({ id: String(x.id ?? ''), name: String(x.name ?? ''), items: strList(x.items) }))
        .filter((x) => x.id !== '')
    : [];
  return {
    rev: typeof raw.rev === 'number' ? raw.rev : 0,
    group_copies: raw.group_copies !== false,
    hidden,
    favorites: strList(raw.favorites),
    playlists,
    order: Array.isArray(raw.order) ? strList(raw.order) : null,
    movie_order: Array.isArray(raw.movie_order) ? strList(raw.movie_order) : null,
    series_order: Array.isArray(raw.series_order) ? strList(raw.series_order) : null,
    movie_list: strList(raw.movie_list),
    series_list: strList(raw.series_list),
  };
}

/** Progression des épisodes d'une série pour ce profil. */
export async function loadSeriesProgress(db: Db, profileId: string, seriesId: string): Promise<EpisodeProgressRow[]> {
  const { data, error } = await db.rpc('series_progress', { p: profileId, series_id: seriesId });
  if (error || !Array.isArray(data)) return [];
  return (data as Record<string, unknown>[]).map((r) => ({
    item_id: String(r.item_id ?? ''),
    season: typeof r.season === 'number' ? r.season : null,
    episode: typeof r.episode === 'number' ? r.episode : null,
    position_s: typeof r.position_s === 'number' ? r.position_s : 0,
    duration_s: typeof r.duration_s === 'number' ? r.duration_s : 0,
    watched: r.watched === true,
  }));
}

/** Enregistre la progression d'un film ou d'un épisode (synchronisée aussitôt avec toutes les télés). */
export async function saveWatchProgress(
  db: Db,
  profileId: string,
  entry: {
    itemType: 'movie' | 'episode';
    itemId: string;
    positionS: number;
    durationS: number;
    seriesId?: string | null;
    season?: number | null;
    episode?: number | null;
    title?: string | null;
    cover?: string | null;
    categoryId?: string | null;
  },
): Promise<void> {
  const cleanCover = entry.cover && /^https?:\/\//i.test(entry.cover) ? entry.cover : null;
  const nowIso = new Date().toISOString();
  const pos = Math.max(0, Math.floor(entry.positionS));
  const dur = Math.max(0, Math.floor(entry.durationS));
  if (entry.itemType === 'movie') {
    await db.rpc('record_movie_progress', {
      p: profileId,
      item_id: entry.itemId,
      position_s: pos,
      duration_s: dur,
      client_updated_at: nowIso,
      movie_name: entry.title ?? null,
      movie_cover: cleanCover,
      movie_category: entry.categoryId ?? null,
    });
    return;
  }
  await db.rpc('record_progress', {
    p: profileId,
    item_type: 'episode',
    item_id: entry.itemId,
    position_s: pos,
    duration_s: dur,
    client_updated_at: nowIso,
    series_id: entry.seriesId ?? null,
    season: entry.season ?? null,
    episode: entry.episode ?? null,
    series_name: entry.title ?? null,
    series_cover: cleanCover,
    series_category: entry.categoryId ?? null,
  });
}

/** Nettoie un nom de chaîne (« CA| TVA SPORTS FHD » → « TVA SPORTS » + badge « FHD »). */
export function cleanChannelTitle(raw: string): { title: string; badge: string | null } {
  let s = raw.replace(/^\s*\|?\s*[A-Za-z]{2,3}\s*[|:]\s*/, '').trim();
  let badge: string | null = null;
  if (/\b(4K|UHD|2160P)\b|[⁴ᴷ]/i.test(s)) badge = '4K';
  else if (/\b(FHD|1080P)\b/i.test(s)) badge = 'FHD';
  else if (/\b(HD|720P)\b|[ᴴᴰ]/i.test(s)) badge = 'HD';
  else if (/\bSD\b|[ˢᴰ]/i.test(s)) badge = 'SD';
  s = s
    .replace(/\b(4K|UHD|2160P|FHD|1080P|HD|720P|SD|HEVC)\b/gi, '')
    .replace(/[⁴³ᴷᴰᴼᴸᴮʸᴴᴿⱽᵒˡᵇʸᶦˢˢ]+/g, '')
    .replace(/\s+/g, ' ')
    .trim();
  return { title: s || raw, badge };
}

/** Vrai si la ligne est un séparateur de catégorie (« ##### SPORTS ##### »). */
export function isSeparatorStream(name: string): boolean {
  return /^[#=\-*~\s]{3,}.*[#=\-*~\s]{3,}$/.test(name.trim());
}

/**
 * Répare et passe en HTTPS les affiches et logos de chaînes pour Safari iOS :
 * - `cmc.exchange-cdn.com/images/(series|movies)/<hash>` (502 en HTTPS) → `https://image.tmdb.org/t/p/w342/<hash>`
 * - TMDB en HTTP/original/autre domaine → `https://image.tmdb.org/t/p/w342/...`
 * - `http://photo-tmdb.com/...` → `https://photo-tmdb.com/...`
 */
export function resolvePosterUrl(cover: string | null | undefined): string | null {
  if (!cover || typeof cover !== 'string') return null;
  const s = cover.trim();
  if (!s) return null;
  const cmc = /^https?:\/\/cmc\.exchange-cdn\.com(?::\d+)?\/images\/(?:series|movies)\/([A-Za-z0-9_-]+\.(?:jpg|jpeg|png|webp))$/i.exec(s);
  if (cmc) return `https://image.tmdb.org/t/p/w342/${cmc[1]}`;
  const tmdb = /^https?:\/\/(?:image\.tmdb\.org|www\.themoviedb\.org|media\.themoviedb\.org)\/t\/p\/[^/]+\/(.+)$/i.exec(s);
  if (tmdb) return `https://image.tmdb.org/t/p/w342/${tmdb[1]}`;
  if (/^\/[A-Za-z0-9_-]+\.(?:jpg|jpeg|png|webp)$/i.test(s)) return `https://image.tmdb.org/t/p/w342${s}`;
  if (/^https:\/\//i.test(s)) return s;
  if (/^http:\/\//i.test(s)) return `https://${s.slice(7)}`;
  return null;
}

const TITLE_PREFIX = /^\s*[A-Za-z0-9+]{1,6}(?:-[A-Za-z0-9+]{1,6}){0,3}\s+-\s+/;
const TITLE_RANK = /^\s*\d{1,3}\.\s+/;
const TITLE_TRAILING = /(\s*\((?:19|20)\d\d\)|\s*\([A-Z]{2}\)|\s*\[[^\]]{1,12}\])+\s*$/;

/** Nettoie un titre de film ou série comme sur la télé (« NF - Mindhunter (US) » → « Mindhunter »). */
export function cleanMediaTitle(raw: string): string {
  let t = raw
    .replace(TITLE_PREFIX, '')
    .replace(TITLE_RANK, '')
    .replace(TITLE_PREFIX, '')
    .replace(TITLE_TRAILING, '');
  t = t.replace(/\s+/g, ' ').trim();
  return t || raw.trim();
}

/** Formate la note si > 0, sinon `null` (masque les « ★ 0 »). */
export function formatRating(rating: string | number | null | undefined): string | null {
  const n = typeof rating === 'number' ? rating : typeof rating === 'string' ? parseFloat(rating) : NaN;
  if (!Number.isFinite(n) || n <= 0) return null;
  return (Math.round(n * 10) / 10).toFixed(1).replace(/\.0$/, '');
}

/** Filtre les catégories masquées et applique l'ordre personnalisé du profil. */
export function filterAndOrderCategories<T extends { id: string; name: string; position?: number }>(
  cats: T[],
  hiddenIds: Set<string>,
  customOrder: string[] | null,
): T[] {
  const visible = cats.filter((c) => !hiddenIds.has(c.id));
  if (!customOrder || !customOrder.length) return visible;
  const rank = new Map(customOrder.map((id, i) => [id, i]));
  return [...visible].sort((a, b) => {
    const ra = rank.get(a.id) ?? 1_000_000;
    const rb = rank.get(b.id) ?? 1_000_000;
    if (ra !== rb) return ra - rb;
    return (a.position ?? 0) - (b.position ?? 0);
  });
}

/** Déduplique les versions dans une grille (même titre nettoyé + année), en privilégiant les items avec affiche. */
export function dedupeMediaItems<T extends { id: string; name: string; year?: string | null; cover?: string | null }>(items: T[]): T[] {
  const out: T[] = [];
  const byKey = new Map<string, number>();
  for (const item of items) {
    const key = `${cleanMediaTitle(item.name).toLowerCase()}|${(item.year ?? '').trim()}`;
    const existingIdx = byKey.get(key);
    if (existingIdx === undefined) {
      byKey.set(key, out.length);
      out.push(item);
    } else if (!resolvePosterUrl(out[existingIdx]!.cover) && resolvePosterUrl(item.cover)) {
      out[existingIdx] = item;
    }
  }
  return out;
}

/** Filtre séparateurs/chaînes masquées et regroupe les copies si `groupCopies` est activé. */
export function groupLiveStreams(
  streams: SnapStream[],
  hiddenChannelIds: Set<string>,
  groupCopies: boolean,
): LiveDisplayItem[] {
  const out: LiveDisplayItem[] = [];
  const byKey = new Map<string, LiveDisplayItem>();
  for (const s of streams) {
    if (hiddenChannelIds.has(s.id) || isSeparatorStream(s.name)) continue;
    const c = cleanChannelTitle(s.name);
    const icon = resolvePosterUrl(s.icon);
    if (!groupCopies) {
      out.push({ id: s.id, title: c.title, badge: c.badge, icon, categoryId: s.categoryId, copyIds: [s.id] });
      continue;
    }
    const key = (s.autoKey && s.autoKey.trim()) || c.title.toLowerCase();
    const prev = byKey.get(key);
    if (!prev) {
      const row: LiveDisplayItem = {
        id: s.id,
        title: c.title,
        badge: c.badge,
        icon,
        categoryId: s.categoryId,
        copyIds: [s.id],
      };
      byKey.set(key, row);
      out.push(row);
    } else {
      prev.copyIds.push(s.id);
      if (!prev.icon && icon) prev.icon = icon;
      if (!prev.badge && c.badge) prev.badge = c.badge;
    }
  }
  return out;
}

/** Construit la requête `POST /v1/sport/board` exactement au format `SportBoardRequest` de `HomeProtocol.kt`. */
export function buildSportBoardRequest(
  profile: ProfileHome,
  prefs: ProfilePrefs,
  offsetSeconds: number,
  forceRefresh = false,
): {
  profileId: string;
  offsetSeconds: number;
  teams: SportTeam[];
  view: SportView;
  hiddenCategories: string[];
  hiddenChannels: string[];
  favorites: string[];
  groupCopies: boolean;
  forceRefresh: boolean;
} {
  return {
    profileId: profile.id,
    offsetSeconds,
    teams: teamsOf(profile.settings).teams,
    view: sportViewOf(profile.settings),
    hiddenCategories: prefs.hidden.filter((h) => h.item_type === 'live_category').map((h) => h.item_id),
    hiddenChannels: prefs.hidden.filter((h) => h.item_type === 'channel').map((h) => h.item_id),
    favorites: prefs.favorites,
    groupCopies: prefs.group_copies,
    forceRefresh,
  };
}

/** Résout les chaînes candidates d'un match à partir de `board.channels` (`proposedIds`/`allIds`). */
export function resolveMatchChannels(
  board: SportBoardReply,
  matchId: string,
): { proposed: SnapSportChannel[]; all: SnapSportChannel[] } {
  const c = board.candidates?.[matchId];
  if (!c) return { proposed: [], all: [] };
  const table = board.channels ?? {};
  const fromIds = (ids?: string[]) => (ids ?? []).map((id) => table[id]).filter((ch): ch is SnapSportChannel => !!ch);
  const proposed = c.proposedIds?.length ? fromIds(c.proposedIds) : (c.proposed ?? []);
  const all = c.allIds?.length ? fromIds(c.allIds) : (c.all ?? []);
  return { proposed, all: all.length ? all : proposed };
}

export function sportOfLeague(league: string): SportKey {
  const l = league.toLowerCase();
  if (l === 'nhl') return 'hockey';
  if (l === 'nfl') return 'football';
  if (l === 'nba') return 'basketball';
  return 'soccer';
}

/** Filtre et trie les matchs selon les préférences Sport du profil (`order`, `hidden`, `mine_first`). */
export function filterAndSortSportMatches(
  matches: SnapMatch[],
  view: SportView,
  teamSports: SportKey[],
  filter: 'all' | 'mine' | SportKey,
): SnapMatch[] {
  const hiddenSet = new Set(view.hidden.filter((s) => !teamSports.includes(s)));
  const sportRank = new Map(view.order.map((s, i) => [s, i]));
  const stateRank = (st: string) => (st === 'in' ? 0 : st === 'pre' ? 1 : 2);
  return matches
    .filter((m) => {
      const sp = sportOfLeague(m.league);
      if (hiddenSet.has(sp)) return false;
      if (filter === 'mine') return m.followed === true;
      if (filter !== 'all') return sp === filter;
      return true;
    })
    .sort((a, b) => {
      const sr = stateRank(a.state) - stateRank(b.state);
      if (sr !== 0) return sr;
      if (view.mine_first && Boolean(a.followed) !== Boolean(b.followed)) return a.followed ? -1 : 1;
      const or = (sportRank.get(sportOfLeague(a.league)) ?? 9) - (sportRank.get(sportOfLeague(b.league)) ?? 9);
      if (a.state !== 'post') {
        const tm = a.startMs - b.startMs;
        if (tm !== 0) return tm;
        return or;
      }
      return b.startMs - a.startMs;
    });
}

function mixHexWhite(hex: string, pct: number): string {
  const clean = /^#[0-9a-fA-F]{6}$/.test(hex) ? hex : '#8FB4FF';
  const n = parseInt(clean.slice(1), 16);
  const r = Math.round(((n >> 16) & 255) * pct + 255 * (1 - pct));
  const g = Math.round(((n >> 8) & 255) * pct + 255 * (1 - pct));
  const b = Math.round((n & 255) * pct + 255 * (1 - pct));
  return `#${[r, g, b].map((c) => c.toString(16).padStart(2, '0')).join('')}`;
}

/** Calcule les couleurs et le fond d'écran du profil pour l'onglet « ▶ Regarder ». */
export function computeProfileWatchTheme(profile: ProfileHome, base: string): ProfileWatchTheme {
  const look = lookOf(profile.settings, profile.theme);
  const accent = profile.color || '#8FB4FF';
  const tc = themeColors(look, accent);
  const metaBg = tc.bg.startsWith('#') ? tc.bg : mixHexWhite(accent, 0.12);
  const hasWall = profile.settings.wallpaper === true && !!base;
  const version = typeof profile.settings.wallpaper_v === 'number' ? profile.settings.wallpaper_v : null;
  return {
    bg: metaBg,
    metaBg,
    surface: tc.light ? 'rgba(255, 255, 255, 0.84)' : 'rgba(22, 28, 40, 0.86)',
    surface2: tc.light ? 'rgba(255, 255, 255, 0.96)' : 'rgba(255, 255, 255, 0.11)',
    text: tc.text,
    muted: tc.light ? '#5E4B56' : '#A3ADBD',
    accent,
    onAccent: inkOn(accent),
    border: tc.light ? 'rgba(43, 29, 38, 0.13)' : 'rgba(255, 255, 255, 0.11)',
    light: tc.light,
    radiusPx: CORNERS.find((c) => c.id === look.corners)?.px ?? 7,
    wallpaperUrl: hasWall ? wallpaperUrl(base, profile.id, version) : null,
    wallpaperDim: dimOf(profile.settings),
  };
}

/**
 * Identifiant réel d'un résultat `/v1/search` :
 * - Direct : `anchorSourceId` (chaîne principale).
 * - Films / Séries regroupés (`id` en `v:titre|année` et `anchorSourceId` nul) : meilleure version selon `langOrder`.
 */
export function resolveSearchHitTargetId(hit: SearchHit, langOrder?: string[]): string {
  if (hit.anchorSourceId) return hit.anchorSourceId;
  const vs = hit.versions;
  if (vs && vs.length > 0) {
    if (langOrder && langOrder.length > 0) {
      let best = vs[0]!;
      let bestRank = langOrder.indexOf(best.tag?.lang ?? '');
      if (bestRank < 0) bestRank = 999;
      for (const v of vs) {
        let r = langOrder.indexOf(v.tag?.lang ?? '');
        if (r < 0) r = 999;
        if (r < bestRank) {
          best = v;
          bestRank = r;
        }
      }
      return best.id;
    }
    return vs[0]!.id;
  }
  return hit.id;
}
