// Interface complète de l'onglet « ▶ Regarder » intégrée dans la page iPhone :
// - Fidèle à la télé pour chaque profil : thème & couleur, fond d'écran, sections visibles, onglet d'ouverture,
//   Reprendre, Ma liste, Vitrine + catégories filtrées & ordonnées, Favoris & Playlists Direct, fiches Films/Séries avec versions,
//   Sport en face-à-face avec logos et sources, Recherche et synchronisation de la progression.
import { avatarBadge } from './avatar-ui';
import type { Db, KV } from './flow';
import { escapeHtml as esc, icon } from './icons';
import { languagesOf } from './languages';
import { loadHouseholdSettings } from './look';
import { kindViewOf, loadContinue, loadLists, setInList, type ContinueItem, type ListItem } from './mediaview';
import { orgSetFavorite } from './org/api';
import { loadProfileHomes, type ProfileHome, type ProfilesDb } from './profiles';
import { sectionsOf } from './sections';
import { chosenShowcaseCategories } from './showcase';
import { SPORTS, sportViewOf, type SportKey } from './sportview';
import { teamsOf } from './teams';
import {
  buildSportBoardRequest,
  cleanChannelTitle,
  cleanMediaTitle,
  computeProfileWatchTheme,
  computeSeriesResumeAction,
  connectHomeServer,
  dedupeMediaItems,
  filterAndOrderCategories,
  filterAndSortSportMatches,
  formatLiveCategoryLabels,
  formatRating,
  groupLiveStreams,
  homeFetch,
  labelVersions,
  loadMovieProgress,
  loadProfilePrefs,
  loadSeriesProgress,
  matchStateKind,
  resolveMatchChannels,
  resolvePosterUrl,
  resolveSearchHitTargetId,
  saveWatchProgress,
  sportOfLeague,
  type EpisodeProgressRow,
  type HomeConnection,
  type HomeServerDb,
  type LiveDisplayItem,
  type LiveSnapshot,
  type MovieProgressRow,
  type ProfilePrefs,
  type SearchHit,
  type SnapEpg,
  type SnapMatch,
  type SnapMovie,
  type SnapMovieInfo,
  type SnapSeries,
  type SnapSeriesInfo,
  type SnapSportChannel,
  type SnapVersion,
  type SportBoardReply,
  type StreamOccupant,
  type StreamStartReply,
  type VersionsReply,
  type VodManifest,
} from './watch';

export type WatchSection = 'direct' | 'series' | 'movies' | 'sport';

export interface WatchMountOptions {
  db: HomeServerDb & ProfilesDb;
  base: string;
  householdId: string;
  selectedProfileId: string | null;
  onSelectProfile(id: string): void;
  demo?: boolean;
  store?: KV;
}

const defaultStore: KV = {
  getItem: (k) => { try { return localStorage.getItem(k); } catch { return null; } },
  setItem: (k, v) => { try { localStorage.setItem(k, v); } catch { /* ignore */ } },
  removeItem: (k) => { try { localStorage.removeItem(k); } catch { /* ignore */ } },
};

let activeConn: HomeConnection | null = null;
let activeSessionId: string | null = null;
let statusTimer: ReturnType<typeof setInterval> | null = null;
let cleanupWatchListeners: (() => void) | null = null;

function stopActiveStreamOnly(keepalive = false): void {
  if (statusTimer) {
    clearInterval(statusTimer);
    statusTimer = null;
  }
  if (activeConn && activeSessionId) {
    const conn = activeConn;
    const sid = activeSessionId;
    activeSessionId = null;
    void fetch(`${conn.baseUrl}/v1/stream/${sid}/stop`, {
      method: 'POST',
      ...(keepalive ? { keepalive: true } : {}),
      headers: {
        Authorization: `Bearer ${conn.token}`,
        'X-Home-Protocol': '1',
      },
    }).catch(() => {});
  }
}

export function clearProfileTheme(): void {
  document.body.classList.remove('watch-themed');
  delete document.body.dataset.watchLight;
  const props = ['--bg', '--surface', '--surface2', '--text', '--muted', '--accent', '--on-accent', '--border', '--w-radius'];
  for (const p of props) document.body.style.removeProperty(p);
  const meta = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]');
  if (meta) meta.content = '#0B0F17';
}

export function unmountWatch(): void {
  stopActiveStreamOnly();
  cleanupWatchListeners?.();
  cleanupWatchListeners = null;
  clearProfileTheme();
}

function sectionForHome(home: ProfileHome['home']): WatchSection {
  return home === 'films' ? 'movies' : home;
}

function formatDurationMin(durationS: number | null | undefined): string | null {
  if (!durationS || durationS <= 0) return null;
  const mins = Math.round(durationS / 60);
  if (mins >= 60) {
    const h = Math.floor(mins / 60);
    const m = mins % 60;
    return `${h} h ${String(m).padStart(2, '0')}`;
  }
  return `${mins} min`;
}

function formatMatchTime(startMs: number): string {
  const d = new Date(startMs);
  const now = new Date();
  const sameDay = d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate();
  const time = `${d.getHours()} h ${String(d.getMinutes()).padStart(2, '0')}`;
  if (sameDay) return time;
  const day = d.toLocaleDateString('fr-CA', { weekday: 'short', day: 'numeric', month: 'short' });
  return `${day} · ${time}`;
}

function posterHtml(cover: string | null | undefined, title: string, fallbackCovers?: (string | null | undefined)[]): string {
  const urls: string[] = [];
  for (const raw of [cover, ...(fallbackCovers ?? [])]) {
    const u = resolvePosterUrl(raw);
    if (u && !urls.includes(u)) urls.push(u);
  }
  const clean = cleanMediaTitle(title);
  const initial = (clean[0] ?? '?').toUpperCase();
  const imgTag = urls.length
    ? `<img src="${esc(urls[0]!)}" alt="${esc(clean)}" loading="lazy" decoding="async"${urls.length > 1 ? ` data-fb="${esc(urls.slice(1).join('|'))}"` : ''} onerror="const f=this.dataset.fb?this.dataset.fb.split('|'):[];if(f.length){const n=f.shift();this.dataset.fb=f.join('|');this.src=n;}else{this.remove();}">`
    : '';
  return `<div class="w-poster-box">
    <div class="w-poster-fallback"><span>${esc(initial)}</span><small>${esc(clean)}</small></div>
    ${imgTag}
  </div>`;
}

export async function mountWatch(root: HTMLElement, opts: WatchMountOptions): Promise<void> {
  unmountWatch();
  const store = opts.store ?? defaultStore;
  root.innerHTML = '<p class="loading" role="status">Connexion au serveur maison…</p>';

  let profiles: ProfileHome[] = [];
  try {
    profiles = await loadProfileHomes(opts.db);
  } catch {
    if (!root.isConnected) return;
    root.innerHTML = '<p class="msg error">Impossible de charger les profils.</p>';
    return;
  }
  if (!root.isConnected) return;
  let profile = profiles.find((p) => p.id === opts.selectedProfileId) ?? profiles[0] ?? null;
  if (profile) opts.onSelectProfile(profile.id);

  let conn: HomeConnection | null = null;
  if (!opts.demo) {
    try {
      conn = await connectHomeServer(opts.db, opts.householdId, store);
      activeConn = conn;
    } catch (e) {
      if (!root.isConnected) return;
      root.innerHTML = `<div class="page-intro"><p class="eyebrow">SUR TON TÉLÉPHONE</p><h1>Regarder la télé</h1></div>
        <div class="card"><p class="msg error">${esc((e as Error).message)}</p>
        <p class="hint">Vérifie que le serveur maison est allumé. Il publie automatiquement son adresse HTTPS au démarrage.</p>
        <button type="button" data-retry>Réessayer</button></div>`;
      root.querySelector('[data-retry]')?.addEventListener('click', () => void mountWatch(root, opts));
      return;
    }
  }

  const allowedSectionsFor = (p: ProfileHome | null): WatchSection[] =>
    p ? sectionsOf(p.settings).map(sectionForHome) : ['direct', 'series', 'movies', 'sport'];

  const initialAllowed = allowedSectionsFor(profile);
  const preferredStart = profile ? sectionForHome(profile.home) : 'direct';
  let section: WatchSection = initialAllowed.includes(preferredStart) ? preferredStart : initialAllowed[0] ?? 'direct';
  let query = '';
  let searchHits: SearchHit[] | null = null;
  let searching = false;

  // Contexte profil & maison
  let householdSettings: Record<string, unknown> | null = null;
  let loadedProfileId: string | null = null;
  let prefs: ProfilePrefs = {
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
  let seriesContinue: ContinueItem[] = [];
  let moviesContinue: ContinueItem[] = [];
  let mySeriesList: ListItem[] = [];
  let myMoviesList: ListItem[] = [];

  // Données catalogue serveur maison
  let liveSnap: LiveSnapshot | null = null;
  let selectedLiveCat = '';
  let movieManifest: VodManifest | null = null;
  let selectedMovieCat = '';
  let moviesInCat: SnapMovie[] = [];
  let showAllMovieCats = false;
  let seriesManifest: VodManifest | null = null;
  let selectedSeriesCat = '';
  let seriesInCat: SnapSeries[] = [];
  let showAllSeriesCats = false;

  // Fiches détaillées, caches & versions
  const seriesInfoCache = new Map<string, SnapSeriesInfo>();
  const movieInfoCache = new Map<string, SnapMovieInfo>();
  let detailSeq = 0;
  let playSeq = 0;
  let detailLoading = false;
  let seasonChosenManually = false;

  let openedSeries: SnapSeriesInfo | null = null;
  let openedSeriesVersions: SnapVersion[] = [];
  let openedSeriesProgress: EpisodeProgressRow[] = [];
  let selectedSeason = 1;

  let openedMovie: SnapMovieInfo | null = null;
  let openedMovieVersions: SnapVersion[] = [];
  let openedMovieProgress: MovieProgressRow | null = null;

  // Sport
  let sportBoard: SportBoardReply | null = null;
  let sportFilter: 'all' | 'live' | 'mine' | SportKey = 'all';
  let revealedMatches = new Set<string>();
  let expandedMatchSources: string | null = null;

  // Lecteur vidéo
  interface PlaySourceOption {
    id: string;
    label: string;
  }
  interface PlayRequest {
    kind: 'live' | 'movie' | 'episode';
    id: string;
    seriesId?: string;
    seriesTitle?: string;
    season?: number;
    episodeNum?: number;
    label: string;
    cover?: string | null;
    categoryId?: string | null;
    durationS?: number | null;
    startS?: number;
    sources?: PlaySourceOption[];
    sourceIndex?: number;
  }

  let playingTitle: string | null = null;
  let playingSubtitle: string | null = null;
  let playingUrl: string | null = null;
  let playerLoading = false;
  let playerError: string | null = null;
  let busyOccupants: StreamOccupant[] | null = null;
  let lastPlayReq: PlayRequest | null = null;
  let lastSavedProgressAt = 0;

  root.innerHTML = `
    <div id="w-wall" class="w-wallpaper" hidden aria-hidden="true"></div>
    <div class="watch-shell">
      <div class="watch-top">
        <div id="w-profiles" class="w-profiles-bar"></div>
        <div class="w-search-wrap">
          <input type="search" id="w-search" placeholder="Rechercher chaîne, film, série…" aria-label="Rechercher">
        </div>
      </div>

      <div id="w-player-slot"></div>
      <div class="home-picker" id="w-tabs" role="group" aria-label="Sections à regarder"></div>
      <div id="w-body"></div>
    </div>
  `;

  const wallEl = root.querySelector<HTMLElement>('#w-wall')!;
  const profilesBox = root.querySelector<HTMLElement>('#w-profiles')!;
  const playerSlot = root.querySelector<HTMLElement>('#w-player-slot')!;
  const tabsBox = root.querySelector<HTMLElement>('#w-tabs')!;
  const bodyBox = root.querySelector<HTMLElement>('#w-body')!;
  const searchInput = root.querySelector<HTMLInputElement>('#w-search')!;

  const onPageHide = () => {
    stopActiveStreamOnly(true);
  };
  window.addEventListener('pagehide', onPageHide);
  cleanupWatchListeners = () => {
    window.removeEventListener('pagehide', onPageHide);
  };

  const applyTheme = () => {
    if (!profile) return;
    const th = computeProfileWatchTheme(profile, opts.base);
    document.body.classList.add('watch-themed');
    document.body.dataset.watchLight = th.light ? '1' : '0';
    document.body.style.setProperty('--bg', th.bg);
    document.body.style.setProperty('--surface', th.surface);
    document.body.style.setProperty('--surface2', th.surface2);
    document.body.style.setProperty('--text', th.text);
    document.body.style.setProperty('--muted', th.muted);
    document.body.style.setProperty('--accent', th.accent);
    document.body.style.setProperty('--on-accent', th.onAccent);
    document.body.style.setProperty('--border', th.border);
    document.body.style.setProperty('--w-radius', `${th.radiusPx}px`);
    const meta = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]');
    if (meta) meta.content = th.metaBg;

    const showWall = th.wallpaperUrl && (section === 'series' || section === 'movies');
    if (showWall) {
      const veilOpacity = Math.max(0.45, Math.min(0.88, th.wallpaperDim / 100 + 0.25));
      wallEl.hidden = false;
      wallEl.innerHTML = `<img src="${esc(th.wallpaperUrl!)}" alt=""><span style="background:${esc(th.metaBg)};opacity:${veilOpacity.toFixed(2)};"></span>`;
    } else {
      wallEl.hidden = true;
      wallEl.innerHTML = '';
    }
  };

  const paintProfiles = () => {
    profilesBox.innerHTML = profiles
      .map(
        (p) => `<button type="button" class="small${profile?.id === p.id ? '' : ' secondary'}" data-wprof="${esc(p.id)}" style="display:inline-flex;align-items:center;gap:6px;padding:4px 10px;border-radius:999px;margin:0;white-space:nowrap;">
          ${avatarBadge(opts.base, p, 22)}<span>${esc(p.name)}</span>
        </button>`,
      )
      .join('');
  };

  const SECTION_META: Record<WatchSection, { label: string; icon: 'direct' | 'series' | 'films' | 'sport' }> = {
    direct: { label: 'Direct', icon: 'direct' },
    series: { label: 'Séries', icon: 'series' },
    movies: { label: 'Films', icon: 'films' },
    sport: { label: 'Sport', icon: 'sport' },
  };

  const paintTabs = () => {
    const allowed = allowedSectionsFor(profile);
    if (!allowed.includes(section)) section = allowed[0] ?? 'direct';
    tabsBox.style.gridTemplateColumns = `repeat(${Math.max(1, allowed.length)}, minmax(0, 1fr))`;
    tabsBox.innerHTML = allowed
      .map(
        (id) => `<button type="button" data-wsec="${id}" aria-pressed="${String(id === section)}">${icon(SECTION_META[id].icon)}<span>${SECTION_META[id].label}</span></button>`,
      )
      .join('');
  };

  const flushVideoProgress = (force = false) => {
    if (opts.demo || !profile || !lastPlayReq || lastPlayReq.kind === 'live') return;
    const v = playerSlot.querySelector<HTMLVideoElement>('#w-video');
    if (!v || !Number.isFinite(v.currentTime)) return;
    const now = Date.now();
    if (!force && now - lastSavedProgressAt < 18_000) return;
    const pos = Math.max(0, (lastPlayReq.startS ?? 0) + Math.floor(v.currentTime));
    const dur = lastPlayReq.durationS && lastPlayReq.durationS > 0 ? lastPlayReq.durationS : (Number.isFinite(v.duration) && v.duration > 0 ? Math.floor(v.duration) : 0);
    if (pos <= 0 && !force) return;
    lastSavedProgressAt = now;
    void saveWatchProgress(opts.db, profile.id, {
      itemType: lastPlayReq.kind,
      itemId: lastPlayReq.id,
      positionS: pos,
      durationS: dur,
      seriesId: lastPlayReq.seriesId ?? null,
      season: lastPlayReq.season ?? null,
      episode: lastPlayReq.episodeNum ?? null,
      title: lastPlayReq.kind === 'episode' ? (lastPlayReq.seriesTitle ?? lastPlayReq.label) : lastPlayReq.label,
      cover: lastPlayReq.cover ?? null,
      categoryId: lastPlayReq.categoryId ?? null,
    }).catch(() => {});
  };

  const paintPlayer = () => {
    if (!playerLoading && !playingUrl && !playerError && !busyOccupants) {
      playerSlot.innerHTML = '';
      return;
    }
    if (busyOccupants) {
      playerSlot.innerHTML = `<div class="card w-player-card">
        <h3>Tous les comptes IPTV sont utilisés</h3>
        <p class="hint">Choisis sur quel écran prendre la main :</p>
        <div class="w-busy-list">
          ${busyOccupants
            .map(
              (o) => `<div class="w-busy-row">
                <div><strong>${esc(o.profileName || o.deviceName)}</strong><br><small>${esc([o.deviceName, o.label].filter(Boolean).join(' · '))}</small></div>
                <button type="button" class="small" data-takeover="${esc(o.id)}" style="margin:0;">Prendre la main</button>
              </div>`,
            )
            .join('')}
        </div>
        <button type="button" class="small secondary" data-close-player style="margin:0;">Annuler</button>
      </div>`;
      return;
    }
    if (playerLoading) {
      playerSlot.innerHTML = `<div class="card w-player-card" style="text-align:center;padding:20px;">
        <p class="loading" role="status" style="margin:0;">Ouverture de <strong>${esc(playingTitle ?? 'la vidéo')}</strong>…</p>
        <p class="hint" style="margin:6px 0 0;">Préparation du flux vidéo par le serveur maison (2 à 6 s)…</p>
      </div>`;
      return;
    }
    if (playerError) {
      playerSlot.innerHTML = `<div class="card w-player-card">
        <p class="msg error" style="margin:0 0 10px;">${esc(playerError)}</p>
        <div class="tools">
          <button type="button" class="small" data-retry-play>Réessayer</button>
          ${lastPlayReq?.sources && lastPlayReq.sources.length > 1 ? '<button type="button" class="small secondary" data-next-source>↻ Autre source</button>' : ''}
          <button type="button" class="small secondary" data-close-player>Fermer</button>
        </div>
      </div>`;
      return;
    }
    if (playingUrl) {
      const existingVideo = playerSlot.querySelector<HTMLVideoElement>('#w-video');
      if (existingVideo && existingVideo.dataset.src === playingUrl) {
        const tEl = playerSlot.querySelector<HTMLElement>('#w-now-title');
        const subEl = playerSlot.querySelector<HTMLElement>('#w-now-sub');
        if (tEl) tEl.textContent = playingTitle ?? '';
        if (subEl) subEl.textContent = playingSubtitle ?? '';
        return;
      }
      const hasMoreSources = (lastPlayReq?.sources?.length ?? 0) > 1;
      const poster = resolvePosterUrl(lastPlayReq?.cover);
      playerSlot.innerHTML = `<div class="card w-player-card">
        <div class="w-player-bar">
          <div class="w-player-meta">
            <strong id="w-now-title">${esc(playingTitle ?? '')}</strong>
            <small id="w-now-sub">${esc(playingSubtitle ?? '')}</small>
          </div>
          <div class="w-player-actions">
            ${hasMoreSources ? '<button type="button" class="small secondary" data-next-source title="Essayer une autre source">↻ Source</button>' : ''}
            <button type="button" class="small secondary" data-close-player>⏹ Arrêter</button>
          </div>
        </div>
        ${
          opts.demo
            ? `<div class="w-demo-player">
                <span>● APERÇU DÉMO</span>
                <strong>${esc(playingTitle ?? '')}</strong>
                <small>En mode connecté, la vraie vidéo HLS joue ici.</small>
              </div>`
            : `<video id="w-video" data-src="${esc(playingUrl)}" src="${esc(playingUrl)}"${poster ? ` poster="${esc(poster)}"` : ''} controls playsinline autoplay preload="auto"></video>`
        }
      </div>`;
      const video = playerSlot.querySelector<HTMLVideoElement>('#w-video');
      if (video) {
        lastSavedProgressAt = Date.now();
        const tryPlay = () => {
          if (video.paused) void video.play().catch(() => {});
        };
        video.addEventListener('loadedmetadata', tryPlay);
        video.addEventListener('canplay', tryPlay, { once: true });
        video.addEventListener('error', () => {
          if (!root.isConnected || !playingUrl) return;
          if (video.error?.code === 1) return;
          console.warn('[watch] video error', video.error?.code, video.error?.message);
          playerError = 'La lecture de cette vidéo a échoué dans ce navigateur. Essaie une autre source.';
          playingUrl = null;
          paintPlayer();
        });
        video.addEventListener('timeupdate', () => flushVideoProgress(false));
        video.addEventListener('pause', () => flushVideoProgress(true));
        video.addEventListener('ended', () => flushVideoProgress(true));
      }
    }
  };

  const playStream = async (req: PlayRequest, takeover?: string) => {
    flushVideoProgress(true);
    const seq = ++playSeq;
    lastPlayReq = req;
    busyOccupants = null;
    playerError = null;
    playingTitle = req.label;
    playingSubtitle = req.kind === 'live' ? 'En direct · Serveur maison' : req.kind === 'movie' ? 'Film' : 'Série';
    if (opts.demo) {
      playingUrl = 'demo://stream';
      playerLoading = false;
      paintPlayer();
      return;
    }
    if (!conn) return;
    playerLoading = true;
    playingUrl = null;
    paintPlayer();
    window.scrollTo(0, 0);
    try {
      const reply = await homeFetch<StreamStartReply>(opts.db, conn, store, '/v1/stream/start', {
        kind: req.kind,
        id: req.id,
        seriesId: req.seriesId ?? null,
        season: req.season ?? null,
        episodeNum: req.episodeNum ?? null,
        profileId: profile?.id ?? null,
        deviceName: `iPhone · ${profile?.name ?? 'Famille'}`,
        deviceKey: conn.clientKey,
        label: req.label,
        takeover: takeover ?? null,
        startS: req.startS ?? 0,
      });
      if (!root.isConnected || seq !== playSeq) return;
      playerLoading = false;
      if (reply.status === 'busy') {
        busyOccupants = reply.occupants ?? [];
        paintPlayer();
        return;
      }
      if (reply.status !== 'ok' || !reply.playlistPath || !reply.sessionId) {
        playerError =
          reply.status === 'unavailable'
            ? 'Cette source ne répond pas chez le fournisseur. Essaie une autre source ou une autre copie.'
            : 'Impossible d’ouvrir ce flux pour l’instant.';
        paintPlayer();
        return;
      }
      activeSessionId = reply.sessionId;
      if (reply.durationS && (!req.durationS || req.durationS <= 0)) req.durationS = reply.durationS;
      if (reply.title && req.kind !== 'live') playingTitle = cleanMediaTitle(reply.title);
      playingUrl = `${conn.baseUrl}${reply.playlistPath}`;
      paintPlayer();

      // Guide EPG discret pour les chaînes Direct
      if (req.kind === 'live') {
        void homeFetch<{ entries?: SnapEpg[] }>(opts.db, conn, store, `/v1/epg/${encodeURIComponent(req.id)}?limit=2&full=0`)
          .then((epg) => {
            const nowShow = epg.entries?.find((x) => x.nowPlaying) ?? epg.entries?.[0];
            if (nowShow?.title && lastPlayReq?.id === req.id && playingUrl) {
              playingSubtitle = `En direct · ${nowShow.title}`;
              paintPlayer();
            }
          })
          .catch(() => {});
      }

      if (statusTimer) clearInterval(statusTimer);
      statusTimer = setInterval(async () => {
        if (!conn || !activeSessionId) return;
        try {
          const st = await homeFetch<{ active: boolean; takenOverBy?: string | null }>(
            opts.db,
            conn,
            store,
            `/v1/stream/${activeSessionId}/status`,
          );
          if (st.takenOverBy) {
            flushVideoProgress(true);
            activeSessionId = null;
            playingUrl = null;
            playerError = `${st.takenOverBy} a pris la main sur le compte IPTV.`;
            paintPlayer();
          }
        } catch {
          /* ignore transient error */
        }
      }, 6000);
    } catch (e) {
      if (!root.isConnected) return;
      playerLoading = false;
      playerError = (e as Error).message;
      paintPlayer();
    }
  };

  const ensureProfileContext = async () => {
    if (opts.demo) return;
    if (!householdSettings) {
      householdSettings = await loadHouseholdSettings(opts.db, opts.householdId).catch(() => ({}));
    }
    if (profile && loadedProfileId !== profile.id) {
      const pid = profile.id;
      const [pPrefs, sCont, mCont, lists] = await Promise.all([
        loadProfilePrefs(opts.db as unknown as Db, pid),
        loadContinue(opts.db as unknown as Db, pid, 'series').catch(() => [] as ContinueItem[]),
        loadContinue(opts.db as unknown as Db, pid, 'movies').catch(() => [] as ContinueItem[]),
        loadLists(opts.db as unknown as Db, pid).catch(() => ({ series: [] as ListItem[], movies: [] as ListItem[] })),
      ]);
      if (!root.isConnected || profile?.id !== pid) return;
      prefs = pPrefs;
      seriesContinue = sCont;
      moviesContinue = mCont;
      mySeriesList = lists.series;
      myMoviesList = lists.movies;
      loadedProfileId = pid;
      selectedLiveCat = '';
      selectedMovieCat = '';
      selectedSeriesCat = '';
    }
  };

  const visibleMovieCategories = () => {
    const all = filterAndOrderCategories(
      movieManifest?.categories ?? [],
      new Set(prefs.hidden.filter((h) => h.item_type === 'vod_category').map((h) => h.item_id)),
      prefs.movie_order,
    );
    if (showAllMovieCats || !householdSettings) return { shown: all, allCount: all.length, showcaseOnly: false };
    const chosen = chosenShowcaseCategories(householdSettings, 'movies', all);
    const chosenSet = new Set(chosen.ids);
    const showcase = all.filter((c) => chosenSet.has(c.id));
    return { shown: showcase.length ? showcase : all, allCount: all.length, showcaseOnly: showcase.length > 0 && showcase.length < all.length };
  };

  const visibleSeriesCategories = () => {
    const all = filterAndOrderCategories(
      seriesManifest?.categories ?? [],
      new Set(prefs.hidden.filter((h) => h.item_type === 'series_category').map((h) => h.item_id)),
      prefs.series_order,
    );
    if (showAllSeriesCats || !householdSettings) return { shown: all, allCount: all.length, showcaseOnly: false };
    const chosen = chosenShowcaseCategories(householdSettings, 'series', all);
    const chosenSet = new Set(chosen.ids);
    const showcase = all.filter((c) => chosenSet.has(c.id));
    return { shown: showcase.length ? showcase : all, allCount: all.length, showcaseOnly: showcase.length > 0 && showcase.length < all.length };
  };

  const findSeriesSummary = (id: string): SnapSeries => {
    const cached = seriesInfoCache.get(id)?.summary;
    if (cached) return cached;
    const inCat = seriesInCat.find((x) => x.id === id);
    if (inCat) return inCat;
    const inCont = seriesContinue.find((x) => x.id === id);
    if (inCont) return { id, name: inCont.name, categoryId: inCont.category ?? '', cover: inCont.cover };
    const inList = mySeriesList.find((x) => x.id === id);
    if (inList) return { id, name: inList.name, categoryId: inList.category ?? '', cover: inList.cover, year: inList.year };
    const hit = searchHits?.find((h) => h.id === id || h.versions?.some((v) => v.id === id));
    if (hit) return { id, name: hit.title, categoryId: hit.categoryId, cover: hit.cover };
    return { id, name: 'Série', categoryId: '' };
  };

  const findMovieSummary = (id: string): SnapMovie => {
    const cached = movieInfoCache.get(id)?.summary;
    if (cached) return cached;
    const inCat = moviesInCat.find((x) => x.id === id);
    if (inCat) return inCat;
    const inCont = moviesContinue.find((x) => x.id === id);
    if (inCont) return { id, name: inCont.name, categoryId: inCont.category ?? '', cover: inCont.cover };
    const inList = myMoviesList.find((x) => x.id === id);
    if (inList) return { id, name: inList.name, categoryId: inList.category ?? '', cover: inList.cover, year: inList.year };
    const hit = searchHits?.find((h) => h.id === id || h.versions?.some((v) => v.id === id));
    if (hit) return { id, name: hit.title, categoryId: hit.categoryId, cover: hit.cover };
    return { id, name: 'Film', categoryId: '' };
  };

  const openSeriesDetail = (seriesId: string) => {
    if (!conn) return;
    const seq = ++detailSeq;
    openedMovie = null;
    openedMovieProgress = null;
    seasonChosenManually = false;

    const cached = seriesInfoCache.get(seriesId) ?? null;
    const contItem = seriesContinue.find((x) => x.id === seriesId) ?? null;
    openedSeries = cached ?? { summary: findSeriesSummary(seriesId), seasons: [] };
    openedSeriesVersions = [];
    openedSeriesProgress = [];
    detailLoading = !cached;

    if (cached) {
      const act = computeSeriesResumeAction(cached.seasons, [], contItem);
      selectedSeason = act?.episode.season ?? cached.seasons[0]?.number ?? 1;
    } else if (contItem?.episode?.season) {
      selectedSeason = contItem.episode.season;
    } else {
      selectedSeason = 1;
    }

    query = '';
    searchInput.value = '';
    searchHits = null;
    paintBody();
    window.scrollTo(0, 0);

    if (!cached) {
      void homeFetch<SnapSeriesInfo>(opts.db, conn, store, `/v1/series/info/${encodeURIComponent(seriesId)}`)
        .then((info) => {
          seriesInfoCache.set(seriesId, info);
          if (!root.isConnected || seq !== detailSeq || openedSeries?.summary.id !== seriesId) return;
          openedSeries = info;
          detailLoading = false;
          if (!seasonChosenManually) {
            const act = computeSeriesResumeAction(info.seasons, openedSeriesProgress, contItem);
            selectedSeason = act?.episode.season ?? info.seasons[0]?.number ?? 1;
          }
          paintBody();
        })
        .catch(() => {
          if (!root.isConnected || seq !== detailSeq) return;
          detailLoading = false;
          paintBody();
        });
    }

    void homeFetch<VersionsReply>(opts.db, conn, store, '/v1/versions/series', { ids: [seriesId] })
      .then((verReply) => {
        if (!root.isConnected || seq !== detailSeq || openedSeries?.summary.id !== seriesId) return;
        const vs = Object.values(verReply.groups ?? {})[0] ?? [];
        if (vs.length > 1) {
          openedSeriesVersions = vs;
          paintBody();
        }
      })
      .catch(() => {});

    if (profile && !opts.demo) {
      void loadSeriesProgress(opts.db as unknown as Db, profile.id, seriesId)
        .then((prog) => {
          if (!root.isConnected || seq !== detailSeq || openedSeries?.summary.id !== seriesId) return;
          openedSeriesProgress = prog;
          if (!seasonChosenManually && openedSeries.seasons.length > 0) {
            const act = computeSeriesResumeAction(openedSeries.seasons, prog, contItem);
            if (act) selectedSeason = act.episode.season;
          }
          paintBody();
        })
        .catch(() => {});
    }
  };

  const openMovieDetail = (movieId: string) => {
    if (!conn) return;
    const seq = ++detailSeq;
    openedSeries = null;
    openedSeriesProgress = [];

    const cached = movieInfoCache.get(movieId) ?? null;
    openedMovie = cached ?? { summary: findMovieSummary(movieId), tech: null };
    openedMovieVersions = [];
    openedMovieProgress = null;
    detailLoading = !cached;

    query = '';
    searchInput.value = '';
    searchHits = null;
    paintBody();
    window.scrollTo(0, 0);

    if (!cached) {
      void homeFetch<SnapMovieInfo>(opts.db, conn, store, `/v1/movies/info/${encodeURIComponent(movieId)}`)
        .then((info) => {
          movieInfoCache.set(movieId, info);
          if (!root.isConnected || seq !== detailSeq || openedMovie?.summary.id !== movieId) return;
          openedMovie = info;
          detailLoading = false;
          paintBody();
        })
        .catch(() => {
          if (!root.isConnected || seq !== detailSeq) return;
          detailLoading = false;
          paintBody();
        });
    }

    void homeFetch<VersionsReply>(opts.db, conn, store, '/v1/versions/movies', { ids: [movieId] })
      .then((verReply) => {
        if (!root.isConnected || seq !== detailSeq || openedMovie?.summary.id !== movieId) return;
        const vs = Object.values(verReply.groups ?? {})[0] ?? [];
        if (vs.length > 1) {
          openedMovieVersions = vs;
          paintBody();
        }
      })
      .catch(() => {});

    if (profile && !opts.demo) {
      void loadMovieProgress(opts.db as unknown as Db, profile.id, movieId)
        .then((row) => {
          if (!root.isConnected || seq !== detailSeq || openedMovie?.summary.id !== movieId) return;
          openedMovieProgress = row;
          paintBody();
        })
        .catch(() => {});
    }
  };

  const loadSectionData = async () => {
    bodyBox.innerHTML = '<p class="loading" role="status">Chargement…</p>';
    applyTheme();
    try {
      await ensureProfileContext();
      if (!root.isConnected) return;
      if (opts.demo) {
        paintBody();
        return;
      }
      if (!conn) return;

      if (section === 'direct') {
        if (!liveSnap) liveSnap = await homeFetch<LiveSnapshot>(opts.db, conn, store, '/v1/live');
        const hiddenLive = new Set(prefs.hidden.filter((h) => h.item_type === 'live_category').map((h) => h.item_id));
        const cats = filterAndOrderCategories(liveSnap.categories, hiddenLive, prefs.order);
        const validChoice =
          selectedLiveCat === '__fav__' ||
          (selectedLiveCat.startsWith('__pl__') && prefs.playlists.some((pl) => `__pl__${pl.id}` === selectedLiveCat)) ||
          cats.some((c) => c.id === selectedLiveCat);
        if (!selectedLiveCat || !validChoice) {
          selectedLiveCat = prefs.favorites.length > 0 ? '__fav__' : (cats[0]?.id ?? '');
        }
      } else if (section === 'movies') {
        if (!movieManifest) movieManifest = await homeFetch<VodManifest>(opts.db, conn, store, '/v1/movies/manifest');
        const { shown } = visibleMovieCategories();
        if (!selectedMovieCat || !shown.some((c) => c.id === selectedMovieCat)) {
          selectedMovieCat = shown[0]?.id ?? '';
          if (selectedMovieCat) {
            const r = await homeFetch<{ items: SnapMovie[] }>(opts.db, conn, store, `/v1/movies/category/${encodeURIComponent(selectedMovieCat)}`);
            moviesInCat = r.items ?? [];
          } else moviesInCat = [];
        }
      } else if (section === 'series') {
        if (!seriesManifest) seriesManifest = await homeFetch<VodManifest>(opts.db, conn, store, '/v1/series/manifest');
        const { shown } = visibleSeriesCategories();
        if (!selectedSeriesCat || !shown.some((c) => c.id === selectedSeriesCat)) {
          selectedSeriesCat = shown[0]?.id ?? '';
          if (selectedSeriesCat) {
            const r = await homeFetch<{ items: SnapSeries[] }>(opts.db, conn, store, `/v1/series/category/${encodeURIComponent(selectedSeriesCat)}`);
            seriesInCat = r.items ?? [];
          } else seriesInCat = [];
        }
      } else if (section === 'sport') {
        if (!sportBoard && profile) {
          const offsetSeconds = -new Date().getTimezoneOffset() * 60;
          sportBoard = await homeFetch<SportBoardReply>(
            opts.db,
            conn,
            store,
            '/v1/sport/board',
            buildSportBoardRequest(profile, prefs, offsetSeconds, false),
          );
        }
      }
      if (!root.isConnected) return;
      paintBody();
    } catch (e) {
      if (!root.isConnected) return;
      bodyBox.innerHTML = `<div class="card"><p class="msg error">${esc((e as Error).message)}</p>
        <button type="button" class="small" data-reload-sec>Réessayer</button></div>`;
    }
  };

  const renderContinueCarousel = (items: ContinueItem[], kind: 'series' | 'movie') => {
    if (!items.length) return '';
    return `<section class="w-row-section">
      <h3 class="w-sect-title">Reprendre</h3>
      <div class="w-carousel">
        ${items
          .slice(0, 20)
          .map((item) => {
            const pct = item.progress !== null ? Math.round(item.progress * 100) : null;
            return `<button type="button" class="w-poster-card w-carousel-card" data-${kind === 'series' ? 'open-series' : 'open-movie'}="${esc(item.id)}">
              ${posterHtml(item.cover, item.name)}
              ${pct !== null ? `<span class="w-pbar"><i style="width:${pct}%"></i></span>` : ''}
              <strong>${esc(cleanMediaTitle(item.name))}</strong>
              <small>${esc(item.detail || (kind === 'series' ? 'Série' : 'Film'))}</small>
            </button>`;
          })
          .join('')}
      </div>
    </section>`;
  };

  const renderMyListCarousel = (items: ListItem[], kind: 'series' | 'movie') => {
    if (!items.length) return '';
    return `<section class="w-row-section">
      <h3 class="w-sect-title">Ma liste</h3>
      <div class="w-carousel">
        ${items
          .slice(0, 30)
          .map(
            (item) => `<button type="button" class="w-poster-card w-carousel-card" data-${kind === 'series' ? 'open-series' : 'open-movie'}="${esc(item.id)}">
              ${posterHtml(item.cover, item.name)}
              <strong>${esc(cleanMediaTitle(item.name))}</strong>
              <small>${esc(item.year || (kind === 'series' ? 'Série' : 'Film'))}</small>
            </button>`,
          )
          .join('')}
      </div>
    </section>`;
  };

  const paintBody = () => {
    applyTheme();
    if (query.trim().length >= 2) {
      const secLabel = section === 'movies' ? 'Films' : section === 'series' ? 'Séries' : 'Direct';
      if (searching) {
        bodyBox.innerHTML = `<div class="w-search-bar-info"><span>Recherche dans <strong>${secLabel}</strong>…</span><button type="button" class="small secondary" data-clear-search>✕ Effacer</button></div>
          <p class="loading" role="status">Recherche…</p>`;
        return;
      }
      const hits = searchHits ?? [];
      const barHtml = `<div class="w-search-bar-info"><span>Résultats dans <strong>${secLabel}</strong> pour « ${esc(query.trim())} » (${hits.length})</span><button type="button" class="small secondary" data-clear-search>✕ Effacer</button></div>`;
      if (!hits.length) {
        bodyBox.innerHTML = `${barHtml}<p class="hint">Aucun résultat pour « ${esc(query.trim())} ».</p>`;
        return;
      }
      const isVod = section === 'movies' || section === 'series';
      const langOrder = profile ? languagesOf(profile.settings).order : undefined;
      if (isVod) {
        bodyBox.innerHTML = `${barHtml}<div class="w-poster-grid">
          ${hits
            .map((h) => {
              const badges = [...new Set((h.versions ?? []).map((v) => v.tag?.lang?.toUpperCase() || (v.tag?.uhd ? '4K' : '')).filter(Boolean))].slice(0, 4);
              return `<button type="button" class="w-poster-card" data-hit-id="${esc(resolveSearchHitTargetId(h, langOrder))}" data-hit-title="${esc(cleanMediaTitle(h.title))}">
                ${posterHtml(h.cover, h.title, (h.versions ?? []).map((v) => v.cover))}
                <strong>${esc(cleanMediaTitle(h.title))}</strong>
                <small>${esc(badges.join(' · ') || h.note || (section === 'movies' ? 'Film' : 'Série'))}</small>
              </button>`;
            })
            .join('')}
        </div>`;
      } else {
        bodyBox.innerHTML = `${barHtml}<div class="w-channel-list">
          ${hits
            .map((h) => {
              const clean = cleanChannelTitle(h.title);
              const logo = resolvePosterUrl(h.cover);
              return `<button type="button" class="w-channel-row" data-hit-id="${esc(resolveSearchHitTargetId(h, langOrder))}" data-hit-title="${esc(clean.title)}">
                ${logo ? `<img class="w-chan-logo" src="${esc(logo)}" alt="" loading="lazy" onerror="this.remove()">` : `<span class="w-chan-logo w-chan-letter">${esc((clean.title[0] ?? '▶').toUpperCase())}</span>`}
                <span class="w-chan-meta"><strong>${esc(clean.title)}</strong>${clean.badge || h.note ? `<small>${esc([clean.badge, h.note].filter(Boolean).join(' · '))}</small>` : ''}</span>
                <span class="w-play-pill">▶ Lire</span>
              </button>`;
            })
            .join('')}
        </div>`;
      }
      return;
    }

    if (opts.demo) {
      bodyBox.innerHTML = `<div class="card">
        <h3>📺 Aperçu connecté disponible avec votre compte</h3>
        <div class="w-channel-list" style="margin-top:10px;">
          <button type="button" class="w-channel-row" data-play-live="101" data-play-title="TVA SPORTS"><span class="w-chan-logo w-chan-letter">T</span><span class="w-chan-meta"><strong>TVA SPORTS</strong><small>LNH : Canadiens vs Bruins · En direct</small></span><span class="w-play-pill">▶</span></button>
          <button type="button" class="w-channel-row" data-play-live="102" data-play-title="RDS"><span class="w-chan-logo w-chan-letter">R</span><span class="w-chan-meta"><strong>RDS</strong><small>Le 5 à 7 · FHD</small></span><span class="w-play-pill">▶</span></button>
        </div>
      </div>`;
      return;
    }

    if (section === 'direct') {
      const hiddenLiveCats = new Set(prefs.hidden.filter((h) => h.item_type === 'live_category').map((h) => h.item_id));
      const hiddenChans = new Set(prefs.hidden.filter((h) => h.item_type === 'channel').map((h) => h.item_id));
      const cats = filterAndOrderCategories(liveSnap?.categories ?? [], hiddenLiveCats, prefs.order);
      const catLabels = formatLiveCategoryLabels(cats);
      const favSet = new Set(prefs.favorites);
      const allStreams = liveSnap?.streams ?? [];
      const byId = new Map(allStreams.map((s) => [s.id, s]));

      let displayItems: LiveDisplayItem[] = [];
      if (selectedLiveCat === '__fav__') {
        const favStreams = prefs.favorites.map((id) => byId.get(id)).filter((s): s is NonNullable<typeof s> => !!s);
        displayItems = groupLiveStreams(favStreams, hiddenChans, false);
      } else if (selectedLiveCat.startsWith('__pl__')) {
        const plId = selectedLiveCat.slice(6);
        const pl = prefs.playlists.find((x) => x.id === plId);
        const plStreams = (pl?.items ?? []).map((id) => byId.get(id)).filter((s): s is NonNullable<typeof s> => !!s);
        displayItems = groupLiveStreams(plStreams, hiddenChans, false);
      } else {
        const inCat = allStreams.filter((s) => s.categoryId === selectedLiveCat);
        displayItems = groupLiveStreams(inCat, hiddenChans, prefs.group_copies);
      }

      bodyBox.innerHTML = `
        <div class="w-cat-strip">
          ${prefs.favorites.length > 0 ? `<button type="button" class="small${selectedLiveCat === '__fav__' ? '' : ' secondary'}" data-lcat="__fav__">★ Favoris (${prefs.favorites.length})</button>` : ''}
          ${prefs.playlists
            .map(
              (pl) => `<button type="button" class="small${selectedLiveCat === `__pl__${pl.id}` ? '' : ' secondary'}" data-lcat="${esc(`__pl__${pl.id}`)}">☰ ${esc(pl.name)} (${pl.items.length})</button>`,
            )
            .join('')}
          ${cats
            .map(
              (c) => `<button type="button" class="small${c.id === selectedLiveCat ? '' : ' secondary'}" data-lcat="${esc(c.id)}">${esc(catLabels.get(c.id) ?? c.name)}</button>`,
            )
            .join('')}
        </div>
        <div class="w-channel-list">
          ${displayItems
            .slice(0, 220)
            .map((item) => {
              const isFav = item.copyIds.some((cid) => favSet.has(cid));
              const sub = [item.badge, item.copyIds.length > 1 ? `${item.copyIds.length} copies` : null].filter(Boolean).join(' · ');
              return `<div class="w-channel-item">
                <button type="button" class="w-channel-row" data-play-live="${esc(item.id)}" data-play-title="${esc(item.title)}" data-copy-ids="${esc(item.copyIds.join(','))}">
                  ${item.icon ? `<img class="w-chan-logo" src="${esc(item.icon)}" alt="" loading="lazy" onerror="this.remove()">` : `<span class="w-chan-logo w-chan-letter">${esc((item.title[0] ?? '▶').toUpperCase())}</span>`}
                  <span class="w-chan-meta"><strong>${esc(item.title)}</strong>${sub ? `<small>${esc(sub)}</small>` : ''}</span>
                  <span class="w-play-pill">▶</span>
                </button>
                <button type="button" class="w-fav-btn${isFav ? ' on' : ''}" data-toggle-fav="${esc(item.id)}" title="${isFav ? 'Retirer des favoris' : 'Ajouter aux favoris'}">${isFav ? '★' : '☆'}</button>
              </div>`;
            })
            .join('')}
          ${displayItems.length === 0 ? '<p class="hint">Aucune chaîne dans cette section.</p>' : ''}
        </div>
      `;
      return;
    }

    if (section === 'series') {
      if (openedSeries) {
        const sInfo = openedSeries;
        const cleanTitle = cleanMediaTitle(sInfo.summary.name);
        const rating = formatRating(sInfo.summary.rating);
        const inList = prefs.series_list.includes(sInfo.summary.id) || mySeriesList.some((x) => x.id === sInfo.summary.id);
        const seasons = sInfo.seasons ?? [];
        const curSeason = seasons.find((s) => s.number === selectedSeason) ?? seasons[0];
        const eps = curSeason?.episodes ?? [];
        const progByEp = new Map(openedSeriesProgress.map((r) => [r.item_id, r]));
        const contItem = seriesContinue.find((x) => x.id === sInfo.summary.id) ?? null;
        const action = computeSeriesResumeAction(seasons, openedSeriesProgress, contItem);

        // Si les épisodes chargent encore mais qu'on sait déjà quel épisode reprendre, on propose aussitôt le bouton ▶ Reprendre.
        let mainBtnHtml = '';
        if (action) {
          mainBtnHtml = `<button type="button" class="small" data-play-ep="${esc(action.episode.id)}" data-ep-season="${action.episode.season}" data-ep-num="${action.episode.number}" data-ep-title="${esc(`${cleanTitle} · S${action.episode.season} É${action.episode.number}`)}" data-ep-start="${action.positionS}" data-ep-dur="${action.episode.durationS ?? 0}">${esc(action.label)}</button>`;
          if (action.mode === 'resume' && action.positionS >= 30) {
            mainBtnHtml += `<button type="button" class="small secondary" data-play-ep="${esc(action.episode.id)}" data-ep-season="${action.episode.season}" data-ep-num="${action.episode.number}" data-ep-title="${esc(`${cleanTitle} · S${action.episode.season} É${action.episode.number}`)}" data-ep-start="0" data-ep-dur="${action.episode.durationS ?? 0}">Depuis le début</button>`;
          }
        } else if (contItem?.episode && contItem.progress !== null && contItem.progress > 0) {
          const ce = contItem.episode;
          const posS = Math.floor(contItem.progress * (contItem.duration_s || 0));
          const mins = posS >= 60 ? ` (${Math.floor(posS / 60)} min)` : '';
          mainBtnHtml = `<button type="button" class="small" data-play-ep="${esc(ce.item_id)}" data-ep-season="${ce.season ?? 1}" data-ep-num="${ce.episode ?? 1}" data-ep-title="${esc(`${cleanTitle} · S${ce.season ?? 1} É${ce.episode ?? 1}`)}" data-ep-start="${posS}" data-ep-dur="${ce.duration_s || 0}">${esc(`▶ Reprendre S${ce.season ?? 1} · É${ce.episode ?? 1}${mins}`)}</button>`;
        }

        bodyBox.innerHTML = `
          <div class="card w-detail">
            <button type="button" class="small secondary" data-back-series style="margin:0 0 12px;">← Retour aux séries</button>
            <div class="w-detail-head">
              ${posterHtml(sInfo.summary.cover, sInfo.summary.name)}
              <div class="w-detail-info">
                <h2>${esc(cleanTitle)}</h2>
                <p class="w-detail-sub">${esc([sInfo.summary.year, rating ? `★ ${rating}` : null, sInfo.summary.genre].filter(Boolean).join(' · '))}</p>
                <div class="tools" style="margin-top:10px;">
                  ${mainBtnHtml}
                  <button type="button" class="small${inList ? '' : ' secondary'}" data-list-toggle="series" data-list-id="${esc(sInfo.summary.id)}">${inList ? '✓ Dans ma liste' : '+ Ma liste'}</button>
                </div>
              </div>
            </div>
            ${sInfo.summary.plot ? `<p class="w-detail-plot">${esc(sInfo.summary.plot)}</p>` : ''}
            ${detailLoading && seasons.length === 0 ? '<p class="w-detail-loading" role="status">Chargement des saisons et épisodes…</p>' : ''}
            ${
              openedSeriesVersions.length > 1
                ? (() => {
                    const catNames = new Map((seriesManifest?.categories ?? []).map((c) => [c.id, c.name]));
                    const vLabels = labelVersions(openedSeriesVersions, catNames);
                    return `<div class="w-versions-wrap"><small>Versions disponibles :</small><div class="w-cat-strip" style="margin-top:4px;padding-bottom:2px;">
                    ${openedSeriesVersions
                      .map(
                        (v) => `<button type="button" class="small${v.id === sInfo.summary.id ? '' : ' secondary'}" data-open-series="${esc(v.id)}">${esc(vLabels.get(v.id) ?? cleanMediaTitle(v.title))}</button>`,
                      )
                      .join('')}
                  </div></div>`;
                  })()
                : ''
            }
          </div>
          ${
            seasons.length > 0
              ? `<div class="w-cat-strip">
                  ${seasons
                    .map(
                      (s) => `<button type="button" class="small${s.number === (curSeason?.number ?? 1) ? '' : ' secondary'}" data-season="${s.number}">${esc(s.name || `Saison ${s.number}`)}</button>`,
                    )
                    .join('')}
                </div>`
              : ''
          }
          <div class="w-channel-list">
            ${eps
              .map((ep) => {
                const pr = progByEp.get(ep.id);
                const isTarget = action?.episode.id === ep.id;
                const posS = pr && !pr.watched && pr.position_s >= 5 ? pr.position_s : 0;
                const durS = pr?.duration_s || ep.durationS || 0;
                const pct = posS > 0 && durS > 0 ? Math.min(100, Math.round((posS / durS) * 100)) : null;
                const durLabel = formatDurationMin(ep.durationS);
                const statusLabel = pr?.watched
                  ? '✓ Vu'
                  : posS > 0
                    ? `En cours · ${Math.floor(posS / 60)} min${durS > 0 ? ` / ${Math.round(durS / 60)} min` : ''}`
                    : durLabel;
                return `<button type="button" class="w-channel-row${isTarget ? ' w-ep-current' : ''}" data-play-ep="${esc(ep.id)}" data-ep-season="${ep.season}" data-ep-num="${ep.number}" data-ep-title="${esc(`${cleanTitle} · S${ep.season} É${ep.number}`)}" data-ep-start="${posS}" data-ep-dur="${ep.durationS ?? 0}">
                  <span class="w-ep-num">${ep.number}</span>
                  <span class="w-chan-meta">
                    <strong>${esc(cleanMediaTitle(ep.title) || `Épisode ${ep.number}`)}</strong>
                    ${statusLabel ? `<small>${esc(statusLabel)}</small>` : ''}
                    ${pct !== null ? `<span class="w-pbar" style="margin-top:4px;"><i style="width:${pct}%"></i></span>` : ''}
                  </span>
                  <span class="w-play-pill">▶</span>
                </button>`;
              })
              .join('')}
          </div>
        `;
        return;
      }

      const { shown: cats, allCount, showcaseOnly } = visibleSeriesCategories();
      const view = kindViewOf(profile?.settings ?? {}, 'series');
      const items = dedupeMediaItems(seriesInCat);
      const sectionsHtml: string[] = [];
      for (const r of view.rows) {
        if (!r.on) continue;
        if (r.key === 'reprendre') sectionsHtml.push(renderContinueCarousel(seriesContinue, 'series'));
        else if (r.key === 'ma_liste') sectionsHtml.push(renderMyListCarousel(mySeriesList, 'series'));
        else if (r.key === 'categories') {
          sectionsHtml.push(`
            <div class="w-cat-strip">
              ${cats
                .map(
                  (c) => `<button type="button" class="small${c.id === selectedSeriesCat ? '' : ' secondary'}" data-scat="${esc(c.id)}">${esc(c.name)}</button>`,
                )
                .join('')}
              ${showcaseOnly || showAllSeriesCats ? `<button type="button" class="small secondary" data-toggle-all-series>${showAllSeriesCats ? '★ Vitrine seulement' : `+ Toutes (${allCount})`}</button>` : ''}
            </div>
            <div class="w-poster-grid">
              ${items
                .slice(0, 120)
                .map((s) => {
                  const rating = formatRating(s.rating);
                  return `<button type="button" class="w-poster-card" data-open-series="${esc(s.id)}">
                    ${posterHtml(s.cover, s.name)}
                    <strong>${esc(cleanMediaTitle(s.name))}</strong>
                    <small>${esc([s.year, rating ? `★ ${rating}` : null].filter(Boolean).join(' · ') || 'Série')}</small>
                  </button>`;
                })
                .join('')}
            </div>
          `);
        }
      }
      bodyBox.innerHTML = sectionsHtml.join('') || '<p class="hint">Aucune série visible pour ce profil.</p>';
      return;
    }

    if (section === 'movies') {
      if (openedMovie) {
        const mInfo = openedMovie;
        const cleanTitle = cleanMediaTitle(mInfo.summary.name);
        const rating = formatRating(mInfo.summary.rating);
        const dur = formatDurationMin(mInfo.summary.durationS);
        const inList = prefs.movie_list.includes(mInfo.summary.id) || myMoviesList.some((x) => x.id === mInfo.summary.id);
        const cont = moviesContinue.find((x) => x.id === mInfo.summary.id);
        const resumeS =
          openedMovieProgress && !openedMovieProgress.watched && openedMovieProgress.position_s >= 5
            ? openedMovieProgress.position_s
            : cont && cont.progress !== null && cont.duration_s > 0
              ? Math.floor(cont.progress * cont.duration_s)
              : 0;
        const totalDurS = openedMovieProgress?.duration_s || mInfo.summary.durationS || cont?.duration_s || 0;
        const resumePct = resumeS > 0 && totalDurS > 0 ? Math.min(100, Math.round((resumeS / totalDurS) * 100)) : null;
        const resumeMin = Math.floor(resumeS / 60);
        const techBadge = mInfo.tech?.width && mInfo.tech.width >= 3200 ? '4K' : mInfo.tech?.hdr || null;

        bodyBox.innerHTML = `
          <div class="card w-detail">
            <button type="button" class="small secondary" data-back-movie style="margin:0 0 12px;">← Retour aux films</button>
            <div class="w-detail-head">
              ${posterHtml(mInfo.summary.cover, mInfo.summary.name)}
              <div class="w-detail-info">
                <h2>${esc(cleanTitle)}</h2>
                <p class="w-detail-sub">${esc([mInfo.summary.year, dur, rating ? `★ ${rating}` : null, techBadge, mInfo.summary.genre].filter(Boolean).join(' · '))}</p>
                ${resumePct !== null ? `<span class="w-pbar" style="margin-top:8px;"><i style="width:${resumePct}%"></i></span>` : ''}
                <div class="tools" style="margin-top:12px;">
                  <button type="button" class="small" data-play-movie="${esc(mInfo.summary.id)}" data-movie-title="${esc(cleanTitle)}" data-movie-start="${resumeS >= 5 ? resumeS : 0}" data-movie-dur="${mInfo.summary.durationS ?? 0}">
                    ${resumeMin > 0 ? `▶ Reprendre (${resumeMin} min)` : resumeS >= 5 ? '▶ Reprendre' : '▶ Regarder le film'}
                  </button>
                  ${resumeS >= 15 ? `<button type="button" class="small secondary" data-play-movie="${esc(mInfo.summary.id)}" data-movie-title="${esc(cleanTitle)}" data-movie-start="0" data-movie-dur="${mInfo.summary.durationS ?? 0}">Depuis le début</button>` : ''}
                  <button type="button" class="small${inList ? '' : ' secondary'}" data-list-toggle="movie" data-list-id="${esc(mInfo.summary.id)}">${inList ? '✓ Dans ma liste' : '+ Ma liste'}</button>
                </div>
              </div>
            </div>
            ${mInfo.summary.plot ? `<p class="w-detail-plot">${esc(mInfo.summary.plot)}</p>` : ''}
            ${detailLoading ? '<p class="w-detail-loading" role="status">Chargement des détails…</p>' : ''}
            ${
              openedMovieVersions.length > 1
                ? (() => {
                    const catNames = new Map((movieManifest?.categories ?? []).map((c) => [c.id, c.name]));
                    const vLabels = labelVersions(openedMovieVersions, catNames);
                    return `<div class="w-versions-wrap"><small>Autres versions :</small><div class="w-cat-strip" style="margin-top:4px;padding-bottom:2px;">
                    ${openedMovieVersions
                      .map(
                        (v) => `<button type="button" class="small${v.id === mInfo.summary.id ? '' : ' secondary'}" data-open-movie="${esc(v.id)}">${esc(vLabels.get(v.id) ?? cleanMediaTitle(v.title))}</button>`,
                      )
                      .join('')}
                  </div></div>`;
                  })()
                : ''
            }
          </div>
        `;
        return;
      }

      const { shown: cats, allCount, showcaseOnly } = visibleMovieCategories();
      const view = kindViewOf(profile?.settings ?? {}, 'movies');
      const items = dedupeMediaItems(moviesInCat);
      const sectionsHtml: string[] = [];
      for (const r of view.rows) {
        if (!r.on) continue;
        if (r.key === 'reprendre') sectionsHtml.push(renderContinueCarousel(moviesContinue, 'movie'));
        else if (r.key === 'ma_liste') sectionsHtml.push(renderMyListCarousel(myMoviesList, 'movie'));
        else if (r.key === 'categories') {
          sectionsHtml.push(`
            <div class="w-cat-strip">
              ${cats
                .map(
                  (c) => `<button type="button" class="small${c.id === selectedMovieCat ? '' : ' secondary'}" data-mcat="${esc(c.id)}">${esc(c.name)}</button>`,
                )
                .join('')}
              ${showcaseOnly || showAllMovieCats ? `<button type="button" class="small secondary" data-toggle-all-movies>${showAllMovieCats ? '★ Vitrine seulement' : `+ Toutes (${allCount})`}</button>` : ''}
            </div>
            <div class="w-poster-grid">
              ${items
                .slice(0, 120)
                .map((m) => {
                  const rating = formatRating(m.rating);
                  return `<button type="button" class="w-poster-card" data-open-movie="${esc(m.id)}">
                    ${posterHtml(m.cover, m.name)}
                    <strong>${esc(cleanMediaTitle(m.name))}</strong>
                    <small>${esc([m.year, rating ? `★ ${rating}` : null].filter(Boolean).join(' · ') || 'Film')}</small>
                  </button>`;
                })
                .join('')}
            </div>
          `);
        }
      }
      bodyBox.innerHTML = sectionsHtml.join('') || '<p class="hint">Aucun film visible pour ce profil.</p>';
      return;
    }

    if (section === 'sport') {
      const board = sportBoard;
      if (!board || !board.matches.length) {
        bodyBox.innerHTML = `<div class="card"><p class="hint">Aucun match programmé en ce moment.</p>
          <button type="button" class="small secondary" data-refresh-sport>Actualiser le sport</button></div>`;
        return;
      }
      const view = sportViewOf(profile?.settings ?? {});
      const userTeams = teamsOf(profile?.settings ?? {}).teams;
      const sportMap: Record<string, SportKey> = { nhl: 'hockey', nfl: 'football', nba: 'basketball' };
      const teamSports = [...new Set(userTeams.map((t) => sportMap[t.league.toLowerCase()] ?? 'soccer'))];
      const matches = filterAndSortSportMatches(board.matches, view, teamSports, sportFilter);
      const hiddenSports = new Set(view.hidden.filter((s) => !teamSports.includes(s)));
      const visibleSports = view.order.filter((s) => !hiddenSports.has(s));
      const liveCount = board.matches.filter((m) => matchStateKind(m.state) === 'live' && !hiddenSports.has(sportOfLeague(m.league))).length;

      const heroMatch =
        matches.find((m) => matchStateKind(m.state) === 'live' && m.followed) ??
        matches.find((m) => m.followed && matchStateKind(m.state) === 'upcoming') ??
        matches.find((m) => matchStateKind(m.state) === 'live') ??
        matches[0] ??
        null;

      const renderMatchHeaderBadge = (m: SnapMatch) => {
        const st = matchStateKind(m.state);
        if (st === 'live') return `<span class="w-live-badge">● EN DIRECT${m.detail ? ` · ${esc(m.detail)}` : ''}</span>`;
        if (st === 'final') return `<span class="w-time-badge">Terminé</span>`;
        return `<span class="w-time-badge">${esc(formatMatchTime(m.startMs))}</span>`;
      };

      const renderScoreOrToggle = (m: SnapMatch) => {
        if (m.homeScore == null || m.awayScore == null || matchStateKind(m.state) === 'upcoming') return '';
        if (view.hide_scores && !revealedMatches.has(m.id)) {
          return `<button type="button" class="small secondary w-reveal-btn" data-reveal-score="${esc(m.id)}">Voir le score</button>`;
        }
        return `<span class="w-vs-score">${m.awayScore} – ${m.homeScore}</span>`;
      };

      const renderHero = (m: SnapMatch | null) => {
        if (!m) return '';
        const { proposed, likely } = resolveMatchChannels(board, m.id);
        const choices = (likely.length ? likely : proposed).slice(0, 12);
        const primary = proposed[0] ?? choices[0] ?? null;
        const alts = choices.filter((x) => x.anchorSourceId !== primary?.anchorSourceId);
        const c1 = m.awayColor && view.theme === 'equipes' ? m.awayColor : profile?.color ?? '#1e3a8a';
        const c2 = m.homeColor && view.theme === 'equipes' ? m.homeColor : '#0f172a';
        const bgStyle =
          view.theme === 'sombre'
            ? ''
            : `background:linear-gradient(135deg, ${esc(c1)}38, ${esc(c2)}52), var(--surface);`;
        const awayLogo = resolvePosterUrl(m.awayLogo);
        const homeLogo = resolvePosterUrl(m.homeLogo);
        const sourcesOpen = expandedMatchSources === m.id;
        return `<div class="card w-sport-hero" style="${bgStyle}">
          <div class="w-sport-top">
            ${renderMatchHeaderBadge(m)}
            <small>${esc(m.league.toUpperCase())}${m.followed ? ' · ★' : ''}</small>
          </div>
          <div class="w-matchup">
            <div class="w-team">
              ${awayLogo ? `<img src="${esc(awayLogo)}" alt="${esc(m.away)}" loading="lazy">` : `<span class="w-team-abbr">${esc(m.awayAbbr || m.away.slice(0, 3))}</span>`}
              <strong>${esc(m.awayShort || m.away)}</strong>
            </div>
            <div class="w-vs-mid">${renderScoreOrToggle(m) || '<span class="w-vs-word">VS</span>'}</div>
            <div class="w-team">
              ${homeLogo ? `<img src="${esc(homeLogo)}" alt="${esc(m.home)}" loading="lazy">` : `<span class="w-team-abbr">${esc(m.homeAbbr || m.home.slice(0, 3))}</span>`}
              <strong>${esc(m.homeShort || m.home)}</strong>
            </div>
          </div>
          <div class="tools" style="margin-top:12px;">
            ${
              primary
                ? `<button type="button" class="small" data-play-sport="${esc(m.id)}" data-ch-id="${esc(primary.anchorSourceId)}" data-play-title="${esc(`${m.awayShort || m.away} vs ${m.homeShort || m.home}`)}">▶ Regarder sur ${esc(cleanChannelTitle(primary.title).title)}</button>`
                : '<small class="hint">Chaîne annoncée peu avant le match</small>'
            }
            ${alts.length > 0 ? `<button type="button" class="small secondary" data-toggle-sources="${esc(m.id)}">Autres sources (${alts.length})</button>` : ''}
          </div>
          ${
            sourcesOpen && alts.length > 0
              ? `<div class="w-sources-box">${alts
                  .map(
                    (ch) => {
                      const cc = cleanChannelTitle(ch.title);
                      const lbl = cc.badge ? `${cc.title} (${cc.badge})` : cc.title;
                      return `<button type="button" class="small secondary" data-play-sport="${esc(m.id)}" data-ch-id="${esc(ch.anchorSourceId)}" data-play-title="${esc(`${m.awayShort || m.away} vs ${m.homeShort || m.home} (${cc.title})`)}">▶ ${esc(lbl)}</button>`;
                    },
                  )
                  .join('')}</div>`
              : ''
          }
        </div>`;
      };

      bodyBox.innerHTML = `
        ${renderHero(heroMatch)}
        <div class="w-cat-strip">
          <button type="button" class="small${sportFilter === 'all' ? '' : ' secondary'}" data-spfilter="all">Tous</button>
          <button type="button" class="small${sportFilter === 'live' ? '' : ' secondary'}" data-spfilter="live">● En direct${liveCount > 0 ? ` (${liveCount})` : ''}</button>
          ${userTeams.length > 0 ? `<button type="button" class="small${sportFilter === 'mine' ? '' : ' secondary'}" data-spfilter="mine">★ Mes équipes</button>` : ''}
          ${visibleSports
            .map((sp) => {
              const info = SPORTS.find((x) => x.id === sp)!;
              return `<button type="button" class="small${sportFilter === sp ? '' : ' secondary'}" data-spfilter="${sp}">${info.icon} ${esc(info.label.split(' ')[0]!)}</button>`;
            })
            .join('')}
          <button type="button" class="small secondary" data-refresh-sport title="Actualiser">↻</button>
        </div>
        ${
          matches.length === 0
            ? `<p class="empty">${sportFilter === 'live' ? 'Aucun match en direct en ce moment.' : 'Aucun match pour ce filtre.'}</p>`
            : `<div class="w-sport-grid">
          ${matches
            .slice(0, 60)
            .map((m) => {
              const { proposed, likely } = resolveMatchChannels(board, m.id);
              const choices = (likely.length ? likely : proposed).slice(0, 12);
              const ch = proposed[0] ?? choices[0];
              const alts = choices.filter((x) => x.anchorSourceId !== ch?.anchorSourceId);
              const awayLogo = resolvePosterUrl(m.awayLogo);
              const homeLogo = resolvePosterUrl(m.homeLogo);
              const sourcesOpen = expandedMatchSources === m.id;
              return `<div class="card w-sport-card${m.followed ? ' followed' : ''}">
                <div class="w-sport-top">
                  ${renderMatchHeaderBadge(m)}
                  <small>${esc(m.league.toUpperCase())}${m.followed ? ' · ★' : ''}</small>
                </div>
                <div class="w-sport-teams-row">
                  <div class="w-sport-team-line">${awayLogo ? `<img src="${esc(awayLogo)}" alt="" loading="lazy">` : ''}<span>${esc(m.awayShort || m.away)}</span></div>
                  ${renderScoreOrToggle(m)}
                  <div class="w-sport-team-line right"><span>${esc(m.homeShort || m.home)}</span>${homeLogo ? `<img src="${esc(homeLogo)}" alt="" loading="lazy">` : ''}</div>
                </div>
                <div class="tools" style="margin-top:8px;">
                  ${
                    ch
                      ? `<button type="button" class="small" style="flex:1;" data-play-sport="${esc(m.id)}" data-ch-id="${esc(ch.anchorSourceId)}" data-play-title="${esc(`${m.awayShort || m.away} vs ${m.homeShort || m.home}`)}">▶ ${esc(cleanChannelTitle(ch.title).title)}</button>`
                      : '<small class="hint">Pas encore de chaîne</small>'
                  }
                  ${alts.length > 0 ? `<button type="button" class="small secondary" data-toggle-sources="${esc(m.id)}">+${alts.length}</button>` : ''}
                </div>
                ${
                  sourcesOpen && alts.length > 0
                    ? `<div class="w-sources-box">${alts
                        .map(
                          (alt) => {
                            const cc = cleanChannelTitle(alt.title);
                            const lbl = cc.badge ? `${cc.title} (${cc.badge})` : cc.title;
                            return `<button type="button" class="small secondary" data-play-sport="${esc(m.id)}" data-ch-id="${esc(alt.anchorSourceId)}" data-play-title="${esc(`${m.awayShort || m.away} vs ${m.homeShort || m.home} (${cc.title})`)}">▶ ${esc(lbl)}</button>`;
                          },
                        )
                        .join('')}</div>`
                    : ''
                }
              </div>`;
            })
            .join('')}
        </div>`
        }
      `;
    }
  };

  let searchTimer: ReturnType<typeof setTimeout> | null = null;
  let searchSeq = 0;
  const runSearch = async () => {
    const q = query.trim();
    if (q.length < 2) {
      searchSeq++;
      searchHits = null;
      searching = false;
      paintBody();
      return;
    }
    const sSeq = ++searchSeq;
    searching = true;
    paintBody();
    if (opts.demo || !conn) {
      searching = false;
      searchHits = [{ id: '101', title: `Résultat démo : ${q}`, categoryId: '1', note: 'Démo' }];
      paintBody();
      return;
    }
    try {
      await ensureProfileContext();
      if (!root.isConnected || sSeq !== searchSeq) return;
      const sec = section === 'movies' ? 'movies' : section === 'series' ? 'series' : 'live';
      const catType = sec === 'movies' ? 'vod_category' : sec === 'series' ? 'series_category' : 'live_category';
      const langPrefs = profile ? languagesOf(profile.settings) : { order: ['qc', 'fr', 'en'], subtitles: 'en', prefer_4k: false, only_mine: false };
      const r = await homeFetch<{ results: SearchHit[] }>(opts.db, conn, store, '/v1/search', {
        section: sec,
        query: q,
        hiddenCategories: prefs.hidden.filter((h) => h.item_type === catType).map((h) => h.item_id),
        hiddenChannels: sec === 'live' ? prefs.hidden.filter((h) => h.item_type === 'channel').map((h) => h.item_id) : [],
        groupCopies: prefs.group_copies,
        languages: {
          order: langPrefs.order,
          subtitles: langPrefs.subtitles,
          prefer_4k: langPrefs.prefer_4k,
          only_mine: langPrefs.only_mine,
        },
        limit: 40,
      });
      if (!root.isConnected || sSeq !== searchSeq) return;
      searching = false;
      searchHits = r.results ?? [];
      paintBody();
    } catch {
      if (!root.isConnected || sSeq !== searchSeq) return;
      searching = false;
      searchHits = [];
      paintBody();
    }
  };

  searchInput.addEventListener('input', () => {
    query = searchInput.value;
    if (searchTimer) clearTimeout(searchTimer);
    if (query.trim().length < 2) {
      void runSearch();
      return;
    }
    searching = true;
    paintBody();
    searchTimer = setTimeout(() => {
      void runSearch();
    }, 280);
  });

  root.addEventListener('click', async (e) => {
    const btn = (e.target as HTMLElement).closest<HTMLButtonElement>('button');
    if (!btn) return;

    if (btn.dataset.wprof) {
      profile = profiles.find((p) => p.id === btn.dataset.wprof) ?? profile;
      if (profile) {
        opts.onSelectProfile(profile.id);
        const allowed = allowedSectionsFor(profile);
        const preferred = sectionForHome(profile.home);
        section = allowed.includes(preferred) ? preferred : allowed[0] ?? 'direct';
      }
      loadedProfileId = null;
      detailSeq++;
      searchSeq++;
      detailLoading = false;
      searching = false;
      openedSeries = null;
      openedMovie = null;
      openedMovieProgress = null;
      sportBoard = null;
      sportFilter = 'all';
      expandedMatchSources = null;
      query = '';
      searchInput.value = '';
      searchHits = null;
      paintProfiles();
      paintTabs();
      void loadSectionData();
      return;
    }
    if (btn.dataset.wsec) {
      section = btn.dataset.wsec as WatchSection;
      detailSeq++;
      detailLoading = false;
      openedSeries = null;
      openedMovie = null;
      openedMovieProgress = null;
      paintTabs();
      if (section !== 'sport' && query.trim().length >= 2) {
        void runSearch();
      } else {
        searchSeq++;
        query = '';
        searchInput.value = '';
        searchHits = null;
        searching = false;
        void loadSectionData();
      }
      return;
    }
    if (btn.dataset.clearSearch !== undefined) {
      searchSeq++;
      query = '';
      searchInput.value = '';
      searchHits = null;
      searching = false;
      const needLoad =
        (section === 'direct' && !liveSnap) ||
        (section === 'movies' && !movieManifest) ||
        (section === 'series' && !seriesManifest);
      if (needLoad) void loadSectionData();
      else paintBody();
      return;
    }
    if (btn.dataset.lcat) {
      selectedLiveCat = btn.dataset.lcat;
      paintBody();
      return;
    }
    if (btn.dataset.toggleFav) {
      if (!profile || opts.demo) return;
      const cid = btn.dataset.toggleFav;
      const on = !prefs.favorites.includes(cid);
      prefs.favorites = on ? [...prefs.favorites, cid] : prefs.favorites.filter((x) => x !== cid);
      paintBody();
      void orgSetFavorite(opts.db as unknown as Db, profile.id, cid, on).catch(() => {});
      return;
    }
    if (btn.dataset.mcat && conn) {
      selectedMovieCat = btn.dataset.mcat;
      openedMovie = null;
      bodyBox.innerHTML = '<p class="loading">Chargement…</p>';
      const r = await homeFetch<{ items: SnapMovie[] }>(opts.db, conn, store, `/v1/movies/category/${encodeURIComponent(selectedMovieCat)}`);
      moviesInCat = r.items ?? [];
      paintBody();
      return;
    }
    if (btn.dataset.toggleAllMovies !== undefined) {
      showAllMovieCats = !showAllMovieCats;
      const { shown } = visibleMovieCategories();
      if (!shown.some((c) => c.id === selectedMovieCat) && shown[0] && conn) {
        selectedMovieCat = shown[0].id;
        const r = await homeFetch<{ items: SnapMovie[] }>(opts.db, conn, store, `/v1/movies/category/${encodeURIComponent(selectedMovieCat)}`);
        moviesInCat = r.items ?? [];
      }
      paintBody();
      return;
    }
    if (btn.dataset.scat && conn) {
      selectedSeriesCat = btn.dataset.scat;
      openedSeries = null;
      bodyBox.innerHTML = '<p class="loading">Chargement…</p>';
      const r = await homeFetch<{ items: SnapSeries[] }>(opts.db, conn, store, `/v1/series/category/${encodeURIComponent(selectedSeriesCat)}`);
      seriesInCat = r.items ?? [];
      paintBody();
      return;
    }
    if (btn.dataset.toggleAllSeries !== undefined) {
      showAllSeriesCats = !showAllSeriesCats;
      const { shown } = visibleSeriesCategories();
      if (!shown.some((c) => c.id === selectedSeriesCat) && shown[0] && conn) {
        selectedSeriesCat = shown[0].id;
        const r = await homeFetch<{ items: SnapSeries[] }>(opts.db, conn, store, `/v1/series/category/${encodeURIComponent(selectedSeriesCat)}`);
        seriesInCat = r.items ?? [];
      }
      paintBody();
      return;
    }
    if (btn.dataset.openSeries && conn) {
      section = 'series';
      paintTabs();
      void openSeriesDetail(btn.dataset.openSeries);
      return;
    }
    if (btn.dataset.backSeries !== undefined) {
      detailSeq++;
      detailLoading = false;
      openedSeries = null;
      if (!seriesManifest && !opts.demo) void loadSectionData();
      else paintBody();
      return;
    }
    if (btn.dataset.openMovie && conn) {
      section = 'movies';
      paintTabs();
      void openMovieDetail(btn.dataset.openMovie);
      return;
    }
    if (btn.dataset.backMovie !== undefined) {
      detailSeq++;
      detailLoading = false;
      openedMovie = null;
      openedMovieProgress = null;
      if (!movieManifest && !opts.demo) void loadSectionData();
      else paintBody();
      return;
    }
    if (btn.dataset.listToggle && btn.dataset.listId && profile && !opts.demo) {
      const kind = btn.dataset.listToggle === 'series' ? 'series' : 'movies';
      const id = btn.dataset.listId;
      if (kind === 'series' && openedSeries) {
        const on = !prefs.series_list.includes(id);
        prefs.series_list = on ? [...prefs.series_list, id] : prefs.series_list.filter((x) => x !== id);
        if (!on) mySeriesList = mySeriesList.filter((x) => x.id !== id);
        else if (!mySeriesList.some((x) => x.id === id)) {
          mySeriesList.unshift({ id, name: openedSeries.summary.name, cover: openedSeries.summary.cover ?? null, category: openedSeries.summary.categoryId, known: true });
        }
        paintBody();
        void setInList(opts.db as unknown as Db, profile.id, 'series', {
          id, name: openedSeries.summary.name, cover: openedSeries.summary.cover ?? null, category: openedSeries.summary.categoryId,
        }, on).catch(() => {});
      } else if (kind === 'movies' && openedMovie) {
        const on = !prefs.movie_list.includes(id);
        prefs.movie_list = on ? [...prefs.movie_list, id] : prefs.movie_list.filter((x) => x !== id);
        if (!on) myMoviesList = myMoviesList.filter((x) => x.id !== id);
        else if (!myMoviesList.some((x) => x.id === id)) {
          myMoviesList.unshift({ id, name: openedMovie.summary.name, cover: openedMovie.summary.cover ?? null, category: openedMovie.summary.categoryId, known: true });
        }
        paintBody();
        void setInList(opts.db as unknown as Db, profile.id, 'movies', {
          id, name: openedMovie.summary.name, cover: openedMovie.summary.cover ?? null, category: openedMovie.summary.categoryId,
        }, on).catch(() => {});
      }
      return;
    }
    if (btn.dataset.season) {
      seasonChosenManually = true;
      selectedSeason = Number(btn.dataset.season);
      paintBody();
      return;
    }
    if (btn.dataset.playLive) {
      const copyIds = (btn.dataset.copyIds || btn.dataset.playLive).split(',').filter(Boolean);
      const sources: PlaySourceOption[] = copyIds.map((id, i) => ({ id, label: `${btn.dataset.playTitle || 'Direct'}${copyIds.length > 1 ? ` (source ${i + 1})` : ''}` }));
      void playStream({ kind: 'live', id: btn.dataset.playLive, label: btn.dataset.playTitle || 'Direct', sources, sourceIndex: 0 });
      return;
    }
    if (btn.dataset.playSport && btn.dataset.chId && sportBoard) {
      const matchId = btn.dataset.playSport;
      const { proposed, likely } = resolveMatchChannels(sportBoard, matchId);
      const choices = (likely.length ? likely : proposed).slice(0, 12);
      const sources: PlaySourceOption[] = choices.map((c) => ({ id: c.anchorSourceId, label: `${btn.dataset.playTitle || 'Match'} (${cleanChannelTitle(c.title).title})` }));
      const idx = Math.max(0, sources.findIndex((s) => s.id === btn.dataset.chId));
      void playStream({ kind: 'live', id: btn.dataset.chId, label: sources[idx]?.label ?? btn.dataset.playTitle ?? 'Match', sources, sourceIndex: idx });
      return;
    }
    if (btn.dataset.toggleSources) {
      expandedMatchSources = expandedMatchSources === btn.dataset.toggleSources ? null : btn.dataset.toggleSources;
      paintBody();
      return;
    }
    if (btn.dataset.revealScore) {
      revealedMatches.add(btn.dataset.revealScore);
      paintBody();
      return;
    }
    if (btn.dataset.spfilter) {
      sportFilter = btn.dataset.spfilter as typeof sportFilter;
      paintBody();
      return;
    }
    if (btn.dataset.refreshSport !== undefined && conn && profile) {
      sportBoard = null;
      bodyBox.innerHTML = '<p class="loading" role="status">Actualisation des matchs…</p>';
      const offsetSeconds = -new Date().getTimezoneOffset() * 60;
      sportBoard = await homeFetch<SportBoardReply>(
        opts.db,
        conn,
        store,
        '/v1/sport/board',
        buildSportBoardRequest(profile, prefs, offsetSeconds, true),
      ).catch(() => null);
      paintBody();
      return;
    }
    if (btn.dataset.playMovie) {
      void playStream({
        kind: 'movie',
        id: btn.dataset.playMovie,
        label: btn.dataset.movieTitle || 'Film',
        cover: openedMovie?.summary.cover ?? null,
        categoryId: openedMovie?.summary.categoryId ?? null,
        durationS: Number(btn.dataset.movieDur || 0) || null,
        startS: Number(btn.dataset.movieStart || 0),
      });
      return;
    }
    if (btn.dataset.playEp && openedSeries) {
      const cleanName = cleanMediaTitle(openedSeries.summary.name);
      void playStream({
        kind: 'episode',
        id: btn.dataset.playEp,
        seriesId: openedSeries.summary.id,
        seriesTitle: openedSeries.summary.name,
        season: Number(btn.dataset.epSeason || 1),
        episodeNum: Number(btn.dataset.epNum || 1),
        label: btn.dataset.epTitle || cleanName,
        cover: openedSeries.summary.cover ?? null,
        categoryId: openedSeries.summary.categoryId,
        durationS: Number(btn.dataset.epDur || 0) || null,
        startS: Number(btn.dataset.epStart || 0),
      });
      return;
    }
    if (btn.dataset.hitId) {
      const id = btn.dataset.hitId;
      const title = btn.dataset.hitTitle || 'Vidéo';
      if (section === 'movies' && conn) void openMovieDetail(id);
      else if (section === 'series' && conn) void openSeriesDetail(id);
      else void playStream({ kind: 'live', id, label: title });
      return;
    }
    if (btn.dataset.nextSource !== undefined && lastPlayReq?.sources && lastPlayReq.sources.length > 1) {
      const nextIdx = ((lastPlayReq.sourceIndex ?? 0) + 1) % lastPlayReq.sources.length;
      const src = lastPlayReq.sources[nextIdx]!;
      void playStream({ ...lastPlayReq, id: src.id, label: src.label, sourceIndex: nextIdx });
      return;
    }
    if (btn.dataset.takeover && lastPlayReq) {
      void playStream(lastPlayReq, btn.dataset.takeover);
      return;
    }
    if (btn.dataset.retryPlay !== undefined && lastPlayReq) {
      void playStream(lastPlayReq);
      return;
    }
    if (btn.dataset.closePlayer !== undefined) {
      flushVideoProgress(true);
      playSeq++;
      stopActiveStreamOnly();
      playingUrl = null;
      playerLoading = false;
      playerError = null;
      busyOccupants = null;
      paintPlayer();
      return;
    }
    if (btn.dataset.reloadSec !== undefined) {
      void loadSectionData();
    }
  });

  applyTheme();
  paintProfiles();
  paintTabs();
  void loadSectionData();
}
