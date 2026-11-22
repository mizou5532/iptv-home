import { describe, expect, it, vi } from 'vitest';
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
  getOrCreateClientKey,
  groupLiveStreams,
  homeFetch,
  isSeparatorStream,
  labelVersions,
  resolveMatchChannels,
  resolvePosterUrl,
  resolveSearchHitTargetId,
  type HomeServerDb,
  type ProfilePrefs,
  type SportBoardReply,
} from '../src/watch';
import type { KV } from '../src/flow';
import type { ProfileHome } from '../src/profiles';
import { DEFAULT_TEAMS } from '../src/teams';
import { sportViewOf } from '../src/sportview';

function memStore(): KV {
  const m = new Map<string, string>();
  return {
    getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => { m.set(k, v); },
    removeItem: (k) => { m.delete(k); },
  };
}

describe('Onglet Regarder (iPhone / Web)', () => {
  it('génère et conserve un client_key stable pour ce téléphone', () => {
    const s = memStore();
    const k1 = getOrCreateClientKey(s);
    const k2 = getOrCreateClientKey(s);
    expect(k1).toMatch(/^web_[0-9A-Za-z]{24}$/);
    expect(k2).toBe(k1);
  });

  it('nettoie les noms de chaînes et reconnaît les séparateurs', () => {
    expect(cleanChannelTitle('CA| TVA SPORTS FHD')).toEqual({ title: 'TVA SPORTS', badge: 'FHD' });
    expect(cleanChannelTitle('FR: CANAL+ 4K UHD')).toEqual({ title: 'CANAL+', badge: '4K' });
    expect(isSeparatorStream('##### SPORTS #####')).toBe(true);
    expect(isSeparatorStream('TVA SPORTS')).toBe(false);
  });

  it('connecte au serveur maison via public_url et renouvelle le jeton sur 401', async () => {
    const s = memStore();
    let issued = 0;
    const db = {
      from: () => ({
        select: async () => ({
          data: [{ household_id: 'h1', url: 'https://192.168.2.81:8443', public_url: 'https://test-tunnel.trycloudflare.com/', version: '0.6.1' }],
          error: null,
        }),
      }),
      rpc: async (fn: string) => {
        if (fn === 'issue_web_server_token') {
          issued++;
          return { data: `tok_${issued}`, error: null };
        }
        return { data: null, error: null };
      },
    } as unknown as HomeServerDb;

    const conn = await connectHomeServer(db, 'h1', s);
    expect(conn.baseUrl).toBe('https://test-tunnel.trycloudflare.com');
    expect(conn.token).toBe('tok_1');

    const fakeFetch = vi.fn()
      .mockResolvedValueOnce({ status: 401, ok: false })
      .mockResolvedValueOnce({ status: 200, ok: true, json: async () => ({ rev: 'r1', categories: [], streams: [] }) });

    const res = await homeFetch<{ rev: string }>(db, conn, s, '/v1/live', undefined, fakeFetch as unknown as typeof fetch);
    expect(res.rev).toBe('r1');
    expect(conn.token).toBe('tok_2');
    expect(fakeFetch).toHaveBeenCalledTimes(2);
  });

  it('répare et passe en HTTPS les affiches TMDB, cmc.exchange-cdn et photo-tmdb', () => {
    expect(resolvePosterUrl('http://cmc.exchange-cdn.com/images/series/fbKE87mojpIETWepSbD5Qt741fp.jpg'))
      .toBe('https://image.tmdb.org/t/p/w342/fbKE87mojpIETWepSbD5Qt741fp.jpg');
    expect(resolvePosterUrl('http://cmc.exchange-cdn.com:80/images/movies/abc123.jpg'))
      .toBe('https://image.tmdb.org/t/p/w342/abc123.jpg');
    expect(resolvePosterUrl('https://image.tmdb.org/t/p/original/AwvoyAt3.jpg'))
      .toBe('https://image.tmdb.org/t/p/w342/AwvoyAt3.jpg');
    expect(resolvePosterUrl('http://photo-tmdb.com/stalker_portal/screenshots/422/42198.jpg'))
      .toBe('https://photo-tmdb.com/stalker_portal/screenshots/422/42198.jpg');
    expect(resolvePosterUrl('/4YLQj5XRrMJ7.jpg'))
      .toBe('https://image.tmdb.org/t/p/w342/4YLQj5XRrMJ7.jpg');
    expect(resolvePosterUrl('')).toBeNull();
    expect(resolvePosterUrl(null)).toBeNull();
  });

  it('nettoie les titres de films/séries et formate les notes > 0', () => {
    expect(cleanMediaTitle('NF - Mindhunter (US)')).toBe('Mindhunter');
    expect(cleanMediaTitle('4K-FR-HDR - Oppenheimer (2023)')).toBe('Oppenheimer');
    expect(cleanMediaTitle('12. 4K-AR-DO - حبتين')).toBe('حبتين');
    expect(formatRating('0')).toBeNull();
    expect(formatRating('0.0')).toBeNull();
    expect(formatRating(null)).toBeNull();
    expect(formatRating('7.89')).toBe('7.9');
    expect(formatRating('8.0')).toBe('8');
  });

  it('filtre et ordonne les catégories puis déduplique les copies dans les grilles', () => {
    const cats = [
      { id: '1', name: 'A', position: 0 },
      { id: '2', name: 'B', position: 1 },
      { id: '3', name: 'C', position: 2 },
    ];
    expect(filterAndOrderCategories(cats, new Set(['2']), ['3', '1']).map((c) => c.id)).toEqual(['3', '1']);

    const deduped = dedupeMediaItems([
      { id: '10', name: 'NF - Dune (2021)', year: '2021', cover: null },
      { id: '11', name: 'FR - Dune (2021)', year: '2021', cover: 'https://image.tmdb.org/t/p/w342/dune.jpg' },
      { id: '12', name: 'Dune (1984)', year: '1984', cover: 'https://image.tmdb.org/t/p/w342/dune84.jpg' },
    ]);
    expect(deduped.map((x) => x.id)).toEqual(['11', '12']);
  });

  it('regroupe les copies Direct et filtre masqués et séparateurs', () => {
    const grouped = groupLiveStreams(
      [
        { id: '0', name: '#### SPORTS ####', categoryId: '1' },
        { id: '1', name: 'CA| RDS FHD', categoryId: '1', autoKey: 'rds', icon: null },
        { id: '2', name: 'CA| RDS 4K', categoryId: '1', autoKey: 'rds', icon: 'http://photo-tmdb.com/rds.png' },
        { id: '3', name: 'CA| LCN HD', categoryId: '1', autoKey: 'lcn' },
      ],
      new Set(['3']),
      true,
    );
    expect(grouped).toHaveLength(1);
    expect(grouped[0]!.copyIds).toEqual(['1', '2']);
    expect(grouped[0]!.icon).toBe('https://photo-tmdb.com/rds.png');
  });

  it('construit la requête Sport exacte, résout channels et trie les matchs du profil', () => {
    const profile: ProfileHome = {
      id: 'p1',
      name: 'Hamza',
      home: 'sport',
      position: 0,
      color: '#4CD964',
      theme: 'sombre',
      settings: {
        look: { preset: 'foret', corners: 'arrondis' },
        wallpaper: true,
        wallpaper_v: 42,
        wallpaper_dim: 35,
        sport_teams: DEFAULT_TEAMS,
        sport_view: { theme: 'equipes', order: ['hockey', 'soccer', 'football', 'basketball'], hidden: ['basketball'], mine_first: true, layout: 'grille', hide_scores: false },
      },
    };
    const prefs: ProfilePrefs = {
      rev: 1,
      group_copies: true,
      hidden: [
        { item_type: 'live_category', item_id: '99' },
        { item_type: 'channel', item_id: '888' },
      ],
      favorites: ['412907'],
      playlists: [],
      order: null,
      movie_order: null,
      series_order: null,
      movie_list: [],
      series_list: [],
    };
    const req = buildSportBoardRequest(profile, prefs, -18000, false);
    expect(req.profileId).toBe('p1');
    expect(req.offsetSeconds).toBe(-18000);
    expect(req.hiddenCategories).toEqual(['99']);
    expect(req.hiddenChannels).toEqual(['888']);
    expect(req.favorites).toEqual(['412907']);
    expect(req.teams).toHaveLength(2);
    expect(req.view.layout).toBe('grille');

    const board: SportBoardReply = {
      rev: 'r1',
      fetchedAtMs: 1000,
      matches: [
        { id: 'm1', league: 'nba', startMs: 100, state: 'in', home: 'Lakers', away: 'Celtics', followed: false },
        { id: 'm2', league: 'nhl', startMs: 200, state: 'pre', home: 'Canadiens', away: 'Bruins', followed: true },
        { id: 'm3', league: 'esp.1', startMs: 150, state: 'in', home: 'Barcelone', away: 'Real', followed: true },
      ],
      candidates: {
        m3: { proposedIds: ['g1'], allIds: ['g1', 'g2'] },
      },
      channels: {
        g1: { groupId: 'g1', title: 'TVA SPORTS', categoryName: 'Sport', anchorSourceId: '412907' },
        g2: { groupId: 'g2', title: 'RDS', categoryName: 'Sport', anchorSourceId: '412908' },
      },
    };
    expect(resolveMatchChannels(board, 'm3').all.map((c) => c.anchorSourceId)).toEqual(['412907', '412908']);

    const sorted = filterAndSortSportMatches(board.matches, sportViewOf(profile.settings), ['hockey', 'soccer'], 'all');
    expect(sorted.map((m) => m.id)).toEqual(['m3', 'm2']); // nba masqué, m3 en direct avant m2 à venir
    const liveOnly = filterAndSortSportMatches(board.matches, sportViewOf(profile.settings), ['hockey', 'soccer'], 'live');
    expect(liveOnly.map((m) => m.id)).toEqual(['m3']); // uniquement les matchs en cours (state === 'in') non masqués

    const theme = computeProfileWatchTheme(profile, 'https://example.supabase.co');
    expect(theme.bg).toBe('#0E1F18');
    expect(theme.accent).toBe('#4CD964');
    expect(theme.light).toBe(false);
    expect(theme.wallpaperUrl).toBe('https://example.supabase.co/storage/v1/object/public/web/wallpapers/p1.webp?v=42');

    const mahaTheme = computeProfileWatchTheme({
      id: 'm1',
      name: 'Maha',
      home: 'series',
      position: 1,
      color: '#F4B6C8',
      theme: 'clair',
      settings: { wallpaper: true, wallpaper_v: 7, wallpaper_dim: 40 },
    }, 'https://example.supabase.co');
    expect(mahaTheme.light).toBe(true);
    expect(mahaTheme.metaBg).toMatch(/^#[0-9a-f]{6}$/i);
  });

  it('résout l’identifiant réel d’un résultat de recherche regroupé (Direct et VOD)', () => {
    expect(
      resolveSearchHitTargetId({
        id: 'auto:v1:ca|tva sports',
        title: 'CA FR: TVA SPORTS',
        categoryId: '1',
        anchorSourceId: '412907',
        versions: [],
      }),
    ).toBe('412907');

    expect(
      resolveSearchHitTargetId(
        {
          id: 'v:breaking bad|2008',
          title: 'Breaking Bad',
          categoryId: '156',
          anchorSourceId: null,
          versions: [
            { id: '1616', categoryId: '153', title: 'FR - Breaking Bad', tag: { lang: 'fr' } },
            { id: '2998', categoryId: '156', title: 'EN - Breaking Bad', tag: { lang: 'en' } },
          ],
        },
        ['en', 'qc', 'fr'],
      ),
    ).toBe('2998');
  });

  it('calcule le bouton principal d’une série (Reprendre, Épisode suivant, 1er épisode)', () => {
    const seasons = [
      {
        number: 1,
        name: 'Saison 1',
        episodes: [
          { id: 'e17', season: 1, number: 17, title: 'Ep 17', extension: 'mkv', durationS: 2600 },
          { id: 'e18', season: 1, number: 18, title: 'Ep 18', extension: 'mkv', durationS: 2600 },
          { id: 'e19', season: 1, number: 19, title: 'Ep 19', extension: 'mkv', durationS: 2600 },
        ],
      },
    ];

    // 1. Aucun historique -> 1er épisode
    expect(computeSeriesResumeAction(seasons, [])).toMatchObject({
      mode: 'start',
      positionS: 0,
      label: '▶ Regarder S1 · É17',
    });

    // 2. Dernier épisode vu (S1 É18) -> propose l'épisode suivant (S1 É19)
    expect(
      computeSeriesResumeAction(seasons, [
        { item_id: 'e17', season: 1, episode: 17, position_s: 2600, duration_s: 2600, watched: true },
        { item_id: 'e18', season: 1, episode: 18, position_s: 2600, duration_s: 2600, watched: true },
      ]),
    ).toMatchObject({
      mode: 'next',
      episode: { id: 'e19' },
      positionS: 0,
      label: '▶ Épisode suivant · S1 · É19',
    });

    // 3. Épisode en cours (S1 É19 à 1050s = 17 min) -> Reprendre à 17 min
    expect(
      computeSeriesResumeAction(seasons, [
        { item_id: 'e18', season: 1, episode: 18, position_s: 2600, duration_s: 2600, watched: true },
        { item_id: 'e19', season: 1, episode: 19, position_s: 1050, duration_s: 2606, watched: false, client_updated_at: '2026-11-21T20:00:00Z' },
      ]),
    ).toMatchObject({
      mode: 'resume',
      episode: { id: 'e19' },
      positionS: 1050,
      label: '▶ Reprendre S1 · É19 (17 min)',
    });
  });

  it('filtre séparateurs et doublons dans resolveMatchChannels et désambiguïse versions et catégories', () => {
    const board: SportBoardReply = {
      rev: 'r2',
      fetchedAtMs: 1000,
      matches: [],
      candidates: {
        m1: {
          proposedIds: ['g1'],
          likelyIds: ['g1', 'sep', 'g1b', 'g2'],
          allIds: ['g1', 'sep', 'g1b', 'g2', 'g3'],
        },
      },
      channels: {
        g1: { groupId: 'g1', title: 'TVA SPORTS', categoryName: 'Sport', anchorSourceId: '412907' },
        sep: { groupId: 'sep', title: '######## SPORTS FR ########', categoryName: 'Sport', anchorSourceId: '999' },
        g1b: { groupId: 'g1b', title: 'TVA SPORTS COPY', categoryName: 'Sport', anchorSourceId: '412907' },
        g2: { groupId: 'g2', title: 'RDS', categoryName: 'Sport', anchorSourceId: '412908' },
        g3: { groupId: 'g3', title: 'FOX NEWS', categoryName: 'News', anchorSourceId: '500' },
      },
    };
    const ch = resolveMatchChannels(board, 'm1');
    expect(ch.proposed.map((c) => c.anchorSourceId)).toEqual(['412907']);
    expect(ch.likely.map((c) => c.anchorSourceId)).toEqual(['412907', '412908']);
    expect(ch.all.map((c) => c.anchorSourceId)).toEqual(['412907', '412908', '500']);

    const catNames = new Map([
      ['10', 'FR - NETFLIX SERIES'],
      ['11', 'FR - PRIME'],
      ['12', 'EN - TOP'],
    ]);
    const labels = labelVersions(
      [
        { id: 'v1', categoryId: '10', title: 'Serie', tag: { lang: 'fr' } },
        { id: 'v2', categoryId: '11', title: 'Serie', tag: { lang: 'fr' } },
        { id: 'v3', categoryId: '12', title: 'Serie', tag: { lang: 'en', source: 'Blu-ray' } },
        { id: 'v4', categoryId: '12', title: 'Serie', tag: { lang: 'en' } },
        { id: 'v5', categoryId: '12', title: 'Serie', tag: { lang: 'en' } },
      ],
      catNames,
    );
    expect(labels.get('v1')).toBe('FR · NETFLIX SERIES');
    expect(labels.get('v2')).toBe('FR · PRIME');
    expect(labels.get('v3')).toBe('EN · Blu-ray');
    expect(new Set([labels.get('v4'), labels.get('v5')]).size).toBe(2);

    const liveLabels = formatLiveCategoryLabels([
      { id: 'c1', name: 'CA| SPORT HD/4K' },
      { id: 'c2', name: 'FR| SPORT HD/4K' },
      { id: 'c3', name: 'CA| GÉNÉRAL' },
    ]);
    expect(liveLabels.get('c1')).toBe('CA · SPORT HD/4K');
    expect(liveLabels.get('c2')).toBe('FR · SPORT HD/4K');
    expect(liveLabels.get('c3')).toBe('GÉNÉRAL');
  });
});
