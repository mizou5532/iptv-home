// Interface complète de l'onglet « ▶ Regarder » intégrée dans la page iPhone :
// - Choix du profil (Hamza, Maha, Mahmoud…)
// - Lecteur vidéo HLS collé en haut + mini-lecteur flottant quand on change de sous-onglet + Plein écran & PiP iOS
// - Partage des comptes IPTV avec les télés (fenêtre « Prendre la main » si tous les comptes sont occupés)
// - 4 sections : 📺 Direct, 🍿 Séries, 🎬 Films, 🏒 Sport + 🔍 Recherche instantanée
import { avatarBadge } from './avatar-ui';
import type { KV } from './flow';
import { escapeHtml as esc, icon } from './icons';
import { loadProfileHomes, type ProfileHome, type ProfilesDb } from './profiles';
import {
  cleanChannelTitle,
  connectHomeServer,
  homeFetch,
  isSeparatorStream,
  type HomeConnection,
  type HomeServerDb,
  type LiveSnapshot,
  type SearchHit,
  type SnapMovie,
  type SnapSeries,
  type SnapSeriesInfo,
  type SportBoardReply,
  type StreamOccupant,
  type StreamStartReply,
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

export function unmountWatch(): void {
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
      headers: {
        Authorization: `Bearer ${conn.token}`,
        'X-Home-Protocol': '1',
      },
    }).catch(() => {});
  }
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

  let section: WatchSection = 'direct';
  let query = '';
  let searchHits: SearchHit[] | null = null;
  let searching = false;

  // Données chargées à la demande par section
  let liveSnap: LiveSnapshot | null = null;
  let selectedLiveCat = '';
  let movieManifest: VodManifest | null = null;
  let selectedMovieCat = '';
  let moviesInCat: SnapMovie[] = [];
  let seriesManifest: VodManifest | null = null;
  let selectedSeriesCat = '';
  let seriesInCat: SnapSeries[] = [];
  let openedSeries: SnapSeriesInfo | null = null;
  let selectedSeason = 1;
  let sportBoard: SportBoardReply | null = null;

  // État du lecteur vidéo
  let playingTitle: string | null = null;
  let playingSubtitle: string | null = null;
  let playingUrl: string | null = null;
  let playerLoading = false;
  let playerError: string | null = null;
  let busyOccupants: StreamOccupant[] | null = null;
  let lastPlayReq: { kind: 'live' | 'movie' | 'episode'; id: string; seriesId?: string; season?: number; episodeNum?: number; label: string } | null = null;

  root.innerHTML = `
    <div class="watch-shell">
      <div class="watch-top" style="display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:wrap;margin-bottom:12px;">
        <div id="w-profiles" style="display:flex;gap:6px;overflow-x:auto;padding-bottom:2px;"></div>
        <div style="flex:1;min-width:170px;max-width:280px;">
          <input type="search" id="w-search" placeholder="Rechercher chaîne, film, série…" aria-label="Rechercher" style="margin:0;padding:8px 12px;border-radius:999px;font-size:14px;">
        </div>
      </div>

      <div id="w-player-slot"></div>

      <div class="home-picker" id="w-tabs" role="group" aria-label="Sections à regarder" style="margin-bottom:14px;">
        <button type="button" data-wsec="direct" aria-pressed="true">${icon('direct')}<span>Direct</span></button>
        <button type="button" data-wsec="series" aria-pressed="false">${icon('series')}<span>Séries</span></button>
        <button type="button" data-wsec="movies" aria-pressed="false">${icon('films')}<span>Films</span></button>
        <button type="button" data-wsec="sport" aria-pressed="false">${icon('sport')}<span>Sport</span></button>
      </div>

      <div id="w-body"></div>
    </div>
  `;

  const profilesBox = root.querySelector<HTMLElement>('#w-profiles')!;
  const playerSlot = root.querySelector<HTMLElement>('#w-player-slot')!;
  const tabsBox = root.querySelector<HTMLElement>('#w-tabs')!;
  const bodyBox = root.querySelector<HTMLElement>('#w-body')!;
  const searchInput = root.querySelector<HTMLInputElement>('#w-search')!;

  const paintProfiles = () => {
    profilesBox.innerHTML = profiles
      .map(
        (p) => `<button type="button" class="small${profile?.id === p.id ? '' : ' secondary'}" data-wprof="${esc(p.id)}" style="display:inline-flex;align-items:center;gap:6px;padding:4px 10px;border-radius:999px;margin:0;">
          ${avatarBadge(opts.base, p, 22)}<span>${esc(p.name)}</span>
        </button>`,
      )
      .join('');
  };

  const paintTabs = () => {
    tabsBox.querySelectorAll<HTMLButtonElement>('[data-wsec]').forEach((b) => {
      b.setAttribute('aria-pressed', String(b.dataset.wsec === section));
    });
  };

  const paintPlayer = () => {
    if (!playerLoading && !playingUrl && !playerError && !busyOccupants) {
      playerSlot.innerHTML = '';
      return;
    }
    if (busyOccupants) {
      playerSlot.innerHTML = `<div class="card" style="margin-bottom:14px;border:1px solid var(--accent, #34d399);">
        <h3>Tous les comptes IPTV sont utilisés</h3>
        <p class="hint">Choisis sur quel écran prendre la main :</p>
        <div style="display:flex;flex-direction:column;gap:8px;margin:10px 0;">
          ${busyOccupants
            .map(
              (o) => `<div style="display:flex;align-items:center;justify-content:space-between;gap:8px;padding:8px 10px;background:rgba(255,255,255,0.04);border-radius:10px;">
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
      playerSlot.innerHTML = `<div class="card" style="margin-bottom:14px;text-align:center;padding:22px;">
        <p class="loading" role="status" style="margin:0;">Ouverture de <strong>${esc(playingTitle ?? 'la vidéo')}</strong>…</p>
        <p class="hint" style="margin:6px 0 0;">Préparation du flux vidéo par le serveur maison (2 à 5 s)…</p>
      </div>`;
      return;
    }
    if (playerError) {
      playerSlot.innerHTML = `<div class="card" style="margin-bottom:14px;">
        <p class="msg error" style="margin:0 0 10px;">${esc(playerError)}</p>
        <div class="tools">
          <button type="button" class="small" data-retry-play>Réessayer</button>
          <button type="button" class="small secondary" data-close-player>Fermer</button>
        </div>
      </div>`;
      return;
    }
    if (playingUrl) {
      const existingVideo = playerSlot.querySelector<HTMLVideoElement>('#w-video');
      if (existingVideo && existingVideo.dataset.src === playingUrl) {
        const tEl = playerSlot.querySelector<HTMLElement>('#w-now-title');
        if (tEl) tEl.textContent = playingTitle ?? '';
        return;
      }
      playerSlot.innerHTML = `<div class="card" style="margin-bottom:14px;padding:10px;position:sticky;top:8px;z-index:20;background:#0d1512;border:1px solid rgba(52,211,153,0.35);box-shadow:0 10px 28px rgba(0,0,0,0.55);">
        <div style="display:flex;align-items:center;justify-content:space-between;gap:8px;margin-bottom:8px;">
          <div style="min-width:0;">
            <strong id="w-now-title" style="display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">${esc(playingTitle ?? '')}</strong>
            ${playingSubtitle ? `<small style="color:var(--muted,#8da399);">${esc(playingSubtitle)}</small>` : ''}
          </div>
          <div style="display:flex;gap:6px;flex-shrink:0;">
            <button type="button" class="small secondary" data-pip title="Image dans l'image" style="margin:0;padding:5px 9px;">⧉ PiP</button>
            <button type="button" class="small" data-fs title="Plein écran" style="margin:0;padding:5px 10px;">⛶ Plein écran</button>
            <button type="button" class="small secondary" data-close-player title="Arrêter" style="margin:0;padding:5px 9px;">⏹</button>
          </div>
        </div>
        ${
          opts.demo
            ? `<div style="height:170px;border-radius:10px;background:linear-gradient(135deg,#0f172a,#1e3a8a);display:flex;flex-direction:column;align-items:center;justify-content:center;color:#fff;">
                <span style="background:#dc2626;padding:2px 10px;border-radius:999px;font-size:12px;font-weight:700;">● APERÇU DÉMO</span>
                <strong style="margin-top:8px;font-size:16px;">${esc(playingTitle ?? '')}</strong>
                <small style="opacity:0.8;">En mode connecté, la vraie vidéo HLS joue ici.</small>
              </div>`
            : `<video id="w-video" data-src="${esc(playingUrl)}" src="${esc(playingUrl)}" controls playsinline autoplay style="width:100%;max-height:240px;border-radius:10px;background:#000;display:block;"></video>`
        }
      </div>`;
    }
  };

  const playStream = async (
    req: { kind: 'live' | 'movie' | 'episode'; id: string; seriesId?: string; season?: number; episodeNum?: number; label: string },
    takeover?: string,
  ) => {
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
        startS: 0,
      });
      if (!root.isConnected) return;
      playerLoading = false;
      if (reply.status === 'busy') {
        busyOccupants = reply.occupants ?? [];
        paintPlayer();
        return;
      }
      if (reply.status !== 'ok' || !reply.playlistPath || !reply.sessionId) {
        playerError =
          reply.status === 'unavailable'
            ? 'Cette source ne répond pas chez le fournisseur. Essaie une autre chaîne ou une autre copie.'
            : 'Impossible d’ouvrir ce flux pour l’instant.';
        paintPlayer();
        return;
      }
      activeSessionId = reply.sessionId;
      if (reply.title) playingTitle = reply.title;
      playingUrl = `${conn.baseUrl}${reply.playlistPath}`;
      paintPlayer();
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

  const loadSectionData = async () => {
    bodyBox.innerHTML = '<p class="loading" role="status">Chargement…</p>';
    try {
      if (opts.demo) {
        paintBody();
        return;
      }
      if (!conn) return;
      if (section === 'direct' && !liveSnap) {
        liveSnap = await homeFetch<LiveSnapshot>(opts.db, conn, store, '/v1/live');
        selectedLiveCat = liveSnap.categories[0]?.id ?? '';
      } else if (section === 'movies' && !movieManifest) {
        movieManifest = await homeFetch<VodManifest>(opts.db, conn, store, '/v1/movies/manifest');
        selectedMovieCat = movieManifest.categories[0]?.id ?? '';
        if (selectedMovieCat) {
          const r = await homeFetch<{ items: SnapMovie[] }>(opts.db, conn, store, `/v1/movies/category/${selectedMovieCat}`);
          moviesInCat = r.items ?? [];
        }
      } else if (section === 'series' && !seriesManifest) {
        seriesManifest = await homeFetch<VodManifest>(opts.db, conn, store, '/v1/series/manifest');
        selectedSeriesCat = seriesManifest.categories[0]?.id ?? '';
        if (selectedSeriesCat) {
          const r = await homeFetch<{ items: SnapSeries[] }>(opts.db, conn, store, `/v1/series/category/${selectedSeriesCat}`);
          seriesInCat = r.items ?? [];
        }
      } else if (section === 'sport' && !sportBoard) {
        sportBoard = await homeFetch<SportBoardReply>(opts.db, conn, store, '/v1/sport/board', {
          profileId: profile?.id ?? '',
          teams: (profile?.settings?.sport_teams as unknown[]) ?? [],
          langOrder: null,
          hiddenCategories: [],
          hiddenChannels: [],
          favorites: [],
          groupCopies: true,
          forceRefresh: false,
        });
      }
      if (!root.isConnected) return;
      paintBody();
    } catch (e) {
      if (!root.isConnected) return;
      bodyBox.innerHTML = `<div class="card"><p class="msg error">${esc((e as Error).message)}</p>
        <button type="button" class="small" data-reload-sec>Réessayer</button></div>`;
    }
  };

  const paintBody = () => {
    if (query.trim().length >= 2) {
      if (searching) {
        bodyBox.innerHTML = '<p class="loading" role="status">Recherche…</p>';
        return;
      }
      const hits = searchHits ?? [];
      if (!hits.length) {
        bodyBox.innerHTML = `<p class="hint">Aucun résultat pour « ${esc(query)} ».</p>`;
        return;
      }
      bodyBox.innerHTML = `<div style="display:flex;flex-direction:column;gap:8px;">
        ${hits
          .map((h) => {
            const clean = cleanChannelTitle(h.title);
            return `<button type="button" class="setting-row" data-hit-id="${esc(h.anchorSourceId || h.id)}" data-hit-title="${esc(clean.title)}" style="text-align:left;">
              <span><strong>${esc(clean.title)}</strong>${h.subtitle ? `<small>${esc(h.subtitle)}</small>` : ''}</span>
              <span class="small" style="margin:0;padding:4px 10px;border-radius:999px;">▶ Lire</span>
            </button>`;
          })
          .join('')}
      </div>`;
      return;
    }

    if (opts.demo) {
      bodyBox.innerHTML = `<div class="card">
        <h3>📺 Chaînes en direct (Démo)</h3>
        <div style="display:flex;flex-direction:column;gap:8px;margin-top:10px;">
          <button type="button" class="setting-row" data-play-live="101" data-play-title="TVA SPORTS"><span><strong>TVA SPORTS</strong><small>LNH : Canadiens vs Bruins · En direct</small></span><span>▶</span></button>
          <button type="button" class="setting-row" data-play-live="102" data-play-title="RDS"><span><strong>RDS</strong><small>Le 5 à 7 · FHD</small></span><span>▶</span></button>
          <button type="button" class="setting-row" data-play-live="103" data-play-title="CANAL+ FOOT"><span><strong>CANAL+ FOOT</strong><small>Ligue des Champions · 4K</small></span><span>▶</span></button>
        </div>
      </div>`;
      return;
    }

    if (section === 'direct') {
      const cats = liveSnap?.categories ?? [];
      const streams = (liveSnap?.streams ?? []).filter((s) => s.categoryId === selectedLiveCat && !isSeparatorStream(s.name));
      bodyBox.innerHTML = `
        <div style="display:flex;gap:6px;overflow-x:auto;padding-bottom:10px;margin-bottom:8px;">
          ${cats
            .map(
              (c) => `<button type="button" class="small${c.id === selectedLiveCat ? '' : ' secondary'}" data-lcat="${esc(c.id)}" style="margin:0;white-space:nowrap;border-radius:999px;padding:6px 12px;">
                ${esc(c.name.replace(/^\s*\|?\s*[A-Za-z]{2,3}\s*[|:]\s*/, ''))}
              </button>`,
            )
            .join('')}
        </div>
        <div style="display:flex;flex-direction:column;gap:8px;">
          ${streams
            .slice(0, 200)
            .map((s) => {
              const c = cleanChannelTitle(s.name);
              return `<button type="button" class="setting-row" data-play-live="${esc(s.id)}" data-play-title="${esc(c.title)}" style="text-align:left;">
                <span><strong>${esc(c.title)}</strong>${c.badge ? `<small>${esc(c.badge)}</small>` : ''}</span>
                <span style="font-weight:700;color:var(--accent,#34d399);">▶ Regarder</span>
              </button>`;
            })
            .join('')}
          ${streams.length === 0 ? '<p class="hint">Aucune chaîne dans cette catégorie.</p>' : ''}
        </div>
      `;
      return;
    }

    if (section === 'series') {
      if (openedSeries) {
        const seasons = openedSeries.seasons ?? [];
        const curSeason = seasons.find((s) => s.number === selectedSeason) ?? seasons[0];
        const eps = curSeason?.episodes ?? [];
        bodyBox.innerHTML = `
          <div class="card" style="margin-bottom:12px;">
            <button type="button" class="small secondary" data-back-series style="margin:0 0 10px;">← Retour aux séries</button>
            <h2 style="margin:0 0 4px;">${esc(openedSeries.summary.name)}</h2>
            ${openedSeries.plot ? `<p class="hint" style="margin:4px 0 10px;">${esc(openedSeries.plot)}</p>` : ''}
            <div style="display:flex;gap:6px;overflow-x:auto;padding-bottom:6px;">
              ${seasons
                .map(
                  (s) => `<button type="button" class="small${s.number === (curSeason?.number ?? 1) ? '' : ' secondary'}" data-season="${s.number}" style="margin:0;white-space:nowrap;border-radius:999px;">
                    ${esc(s.name || `Saison ${s.number}`)}
                  </button>`,
                )
                .join('')}
            </div>
          </div>
          <div style="display:flex;flex-direction:column;gap:8px;">
            ${eps
              .map(
                (ep) => `<button type="button" class="setting-row" data-play-ep="${esc(ep.id)}" data-ep-season="${ep.season}" data-ep-num="${ep.number}" data-ep-title="${esc(`${openedSeries!.summary.name} · S${ep.season} É${ep.number}`)}" style="text-align:left;">
                  <span><strong>${ep.number} · ${esc(ep.title || `Épisode ${ep.number}`)}</strong>${ep.durationS ? `<small>${Math.round(ep.durationS / 60)} min</small>` : ''}</span>
                  <span style="font-weight:700;color:var(--accent,#34d399);">▶ Lire</span>
                </button>`,
              )
              .join('')}
          </div>
        `;
        return;
      }
      const cats = seriesManifest?.categories ?? [];
      bodyBox.innerHTML = `
        <div style="display:flex;gap:6px;overflow-x:auto;padding-bottom:10px;margin-bottom:8px;">
          ${cats
            .map(
              (c) => `<button type="button" class="small${c.id === selectedSeriesCat ? '' : ' secondary'}" data-scat="${esc(c.id)}" style="margin:0;white-space:nowrap;border-radius:999px;padding:6px 12px;">
                ${esc(c.name)}
              </button>`,
            )
            .join('')}
        </div>
        <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(135px,1fr));gap:10px;">
          ${seriesInCat
            .slice(0, 120)
            .map(
              (s) => `<button type="button" class="setting-tile" data-open-series="${esc(s.id)}" style="text-align:left;display:flex;flex-direction:column;align-items:flex-start;gap:6px;padding:10px;">
                <strong style="display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;">${esc(s.name)}</strong>
                <small>${esc([s.year, s.rating ? `★ ${s.rating}` : null].filter(Boolean).join(' · ') || 'Série')}</small>
              </button>`,
            )
            .join('')}
        </div>
      `;
      return;
    }

    if (section === 'movies') {
      const cats = movieManifest?.categories ?? [];
      bodyBox.innerHTML = `
        <div style="display:flex;gap:6px;overflow-x:auto;padding-bottom:10px;margin-bottom:8px;">
          ${cats
            .map(
              (c) => `<button type="button" class="small${c.id === selectedMovieCat ? '' : ' secondary'}" data-mcat="${esc(c.id)}" style="margin:0;white-space:nowrap;border-radius:999px;padding:6px 12px;">
                ${esc(c.name)}
              </button>`,
            )
            .join('')}
        </div>
        <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(135px,1fr));gap:10px;">
          ${moviesInCat
            .slice(0, 120)
            .map(
              (m) => `<button type="button" class="setting-tile" data-play-movie="${esc(m.id)}" data-movie-title="${esc(m.name)}" style="text-align:left;display:flex;flex-direction:column;align-items:flex-start;gap:6px;padding:10px;">
                <strong style="display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;">${esc(m.name)}</strong>
                <small>${esc([m.year, m.rating ? `★ ${m.rating}` : null].filter(Boolean).join(' · ') || '▶ Lire le film')}</small>
              </button>`,
            )
            .join('')}
        </div>
      `;
      return;
    }

    if (section === 'sport') {
      const matches = sportBoard?.matches ?? [];
      const channels = sportBoard?.channels ?? {};
      if (!matches.length) {
        bodyBox.innerHTML = '<p class="hint">Aucun match programmé en ce moment.</p>';
        return;
      }
      bodyBox.innerHTML = `<div style="display:flex;flex-direction:column;gap:10px;">
        ${matches
          .slice(0, 60)
          .map((m) => {
            const chId = m.proposedIds?.[0] ?? m.allIds?.[0];
            const ch = chId ? channels[chId] : null;
            const live = m.state === 'in';
            const score = m.homeScore && m.awayScore ? `${m.awayScore} – ${m.homeScore}` : m.detail || '';
            return `<div class="card" style="margin:0;padding:12px;">
              <div style="display:flex;align-items:center;justify-content:space-between;gap:8px;margin-bottom:6px;">
                <span style="font-size:12px;font-weight:700;color:${live ? '#ef4444' : 'var(--muted,#8da399)'};">${live ? '● EN DIRECT' : esc(m.detail || m.league.toUpperCase())}</span>
                <small>${esc(m.league.toUpperCase())}</small>
              </div>
              <div style="display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:10px;">
                <strong>${esc(m.awayName)} vs ${esc(m.homeName)}</strong>
                ${score ? `<span style="font-weight:700;">${esc(score)}</span>` : ''}
              </div>
              ${
                ch
                  ? `<button type="button" class="small" data-play-live="${esc(ch.anchorSourceId)}" data-play-title="${esc(`${m.awayName} vs ${m.homeName} (${ch.title})`)}" style="margin:0;width:100%;">
                      ▶ Regarder sur ${esc(ch.title)}
                    </button>`
                  : '<small class="hint">Aucune chaîne associée pour ce match.</small>'
              }
            </div>`;
          })
          .join('')}
      </div>`;
    }
  };

  let searchTimer: ReturnType<typeof setTimeout> | null = null;
  searchInput.addEventListener('input', () => {
    query = searchInput.value;
    if (searchTimer) clearTimeout(searchTimer);
    if (query.trim().length < 2) {
      searchHits = null;
      searching = false;
      paintBody();
      return;
    }
    searching = true;
    paintBody();
    searchTimer = setTimeout(async () => {
      if (opts.demo || !conn) {
        searching = false;
        searchHits = [{ id: '101', title: `Résultat démo : ${query}`, categoryId: '1', subtitle: 'Démo' }];
        paintBody();
        return;
      }
      try {
        const sec = section === 'movies' ? 'movies' : section === 'series' ? 'series' : 'live';
        const r = await homeFetch<{ results: SearchHit[] }>(opts.db, conn, store, '/v1/search', {
          section: sec,
          query: query.trim(),
          hiddenCategories: [],
          hiddenChannels: [],
          onlyMine: false,
          limit: 40,
        });
        searching = false;
        searchHits = r.results ?? [];
        paintBody();
      } catch {
        searching = false;
        searchHits = [];
        paintBody();
      }
    }, 280);
  });

  root.addEventListener('click', async (e) => {
    const btn = (e.target as HTMLElement).closest<HTMLButtonElement>('button');
    if (!btn) return;

    if (btn.dataset.wprof) {
      profile = profiles.find((p) => p.id === btn.dataset.wprof) ?? profile;
      if (profile) opts.onSelectProfile(profile.id);
      sportBoard = null;
      paintProfiles();
      if (section === 'sport') void loadSectionData();
      return;
    }
    if (btn.dataset.wsec) {
      section = btn.dataset.wsec as WatchSection;
      openedSeries = null;
      query = '';
      searchInput.value = '';
      searchHits = null;
      paintTabs();
      void loadSectionData();
      return;
    }
    if (btn.dataset.lcat) {
      selectedLiveCat = btn.dataset.lcat;
      paintBody();
      return;
    }
    if (btn.dataset.mcat && conn) {
      selectedMovieCat = btn.dataset.mcat;
      bodyBox.innerHTML = '<p class="loading">Chargement…</p>';
      const r = await homeFetch<{ items: SnapMovie[] }>(opts.db, conn, store, `/v1/movies/category/${selectedMovieCat}`);
      moviesInCat = r.items ?? [];
      paintBody();
      return;
    }
    if (btn.dataset.scat && conn) {
      selectedSeriesCat = btn.dataset.scat;
      bodyBox.innerHTML = '<p class="loading">Chargement…</p>';
      const r = await homeFetch<{ items: SnapSeries[] }>(opts.db, conn, store, `/v1/series/category/${selectedSeriesCat}`);
      seriesInCat = r.items ?? [];
      paintBody();
      return;
    }
    if (btn.dataset.openSeries && conn) {
      bodyBox.innerHTML = '<p class="loading">Chargement de la série…</p>';
      openedSeries = await homeFetch<SnapSeriesInfo>(opts.db, conn, store, `/v1/series/info/${btn.dataset.openSeries}`);
      selectedSeason = openedSeries.seasons?.[0]?.number ?? 1;
      paintBody();
      return;
    }
    if (btn.dataset.backSeries !== undefined) {
      openedSeries = null;
      paintBody();
      return;
    }
    if (btn.dataset.season) {
      selectedSeason = Number(btn.dataset.season);
      paintBody();
      return;
    }
    if (btn.dataset.playLive) {
      void playStream({ kind: 'live', id: btn.dataset.playLive, label: btn.dataset.playTitle || 'Direct' });
      return;
    }
    if (btn.dataset.playMovie) {
      void playStream({ kind: 'movie', id: btn.dataset.playMovie, label: btn.dataset.movieTitle || 'Film' });
      return;
    }
    if (btn.dataset.playEp && openedSeries) {
      void playStream({
        kind: 'episode',
        id: btn.dataset.playEp,
        seriesId: openedSeries.summary.id,
        season: Number(btn.dataset.epSeason || 1),
        episodeNum: Number(btn.dataset.epNum || 1),
        label: btn.dataset.epTitle || openedSeries.summary.name,
      });
      return;
    }
    if (btn.dataset.hitId) {
      const id = btn.dataset.hitId;
      const title = btn.dataset.hitTitle || 'Vidéo';
      if (section === 'movies') void playStream({ kind: 'movie', id, label: title });
      else if (section === 'series' && conn) {
        query = '';
        searchInput.value = '';
        searchHits = null;
        openedSeries = await homeFetch<SnapSeriesInfo>(opts.db, conn, store, `/v1/series/info/${id}`);
        selectedSeason = openedSeries.seasons?.[0]?.number ?? 1;
        paintBody();
      } else {
        void playStream({ kind: 'live', id, label: title });
      }
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
      unmountWatch();
      activeConn = conn;
      playingUrl = null;
      playerLoading = false;
      playerError = null;
      busyOccupants = null;
      paintPlayer();
      return;
    }
    if (btn.dataset.fs !== undefined) {
      const v = playerSlot.querySelector<HTMLVideoElement & { webkitEnterFullscreen?: () => void }>('#w-video');
      if (v?.requestFullscreen) void v.requestFullscreen();
      else if (v?.webkitEnterFullscreen) v.webkitEnterFullscreen();
      return;
    }
    if (btn.dataset.pip !== undefined) {
      const v = playerSlot.querySelector<HTMLVideoElement & { requestPictureInPicture?: () => Promise<void>; webkitSetPresentationMode?: (m: string) => void }>('#w-video');
      if (typeof v?.requestPictureInPicture === 'function') void v.requestPictureInPicture();
      else if (typeof v?.webkitSetPresentationMode === 'function') v.webkitSetPresentationMode('picture-in-picture');
      return;
    }
    if (btn.dataset.reloadSec !== undefined) {
      void loadSectionData();
    }
  });

  paintProfiles();
  paintTabs();
  void loadSectionData();
}
