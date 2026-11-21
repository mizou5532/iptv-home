// Logique et appels réseau de l'onglet « ▶ Regarder » sur téléphone / navigateur :
// connexion au serveur maison via son tunnel HTTPS public (`home_server.public_url`), jeton propre à ce téléphone
// (`issue_web_server_token`), lecture catalogue Direct / Films / Séries / Sport / Recherche, et lancement des flux HLS.
import type { Db, KV } from './flow';

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
}

export interface SnapStream {
  id: string;
  name: string;
  categoryId: string;
  icon?: string | null;
  epgId?: string | null;
  number?: number | null;
  baseTitle?: string | null;
}

export interface LiveSnapshot {
  rev: string;
  categories: SnapCategory[];
  streams: SnapStream[];
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
  rating?: string | null;
  year?: string | null;
}

export interface SnapSeries {
  id: string;
  name: string;
  categoryId: string;
  cover?: string | null;
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
}

export interface SnapSeason {
  number: number;
  name: string;
  episodes: SnapEpisode[];
}

export interface SnapSeriesInfo {
  summary: SnapSeries;
  plot?: string | null;
  seasons: SnapSeason[];
}

export interface SnapMovieInfo {
  summary: SnapMovie & { extension?: string; durationS?: number | null };
  plot?: string | null;
  durationS?: number | null;
}

export interface SearchHit {
  id: string;
  title: string;
  subtitle?: string | null;
  categoryId: string;
  cover?: string | null;
  anchorSourceId?: string | null;
}

export interface SportMatchDto {
  id: string;
  league: string;
  homeName: string;
  awayName: string;
  homeScore?: string | null;
  awayScore?: string | null;
  state: string;
  detail?: string | null;
  startMs: number;
  proposedIds: string[];
  allIds: string[];
}

export interface SportChannelDto {
  id: string;
  title: string;
  anchorSourceId: string;
}

export interface SportBoardReply {
  matches: SportMatchDto[];
  channels: Record<string, SportChannelDto>;
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
