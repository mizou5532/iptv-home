import { describe, expect, it } from 'vitest';
import { allowed, dayOf, fresh, MAX_URLS, PARALLEL, relay, ttlMs, type RelayDeps } from '../supabase/functions/sport-horaires/logic';

const H = '11111111-2222-3333-4444-555555555555';
const NOW = Date.UTC(2026, 10, 18, 15);
const espn = (d: string, l = 'soccer/esp.1') => `https://site.api.espn.com/apis/site/v2/sports/${l}/scoreboard?dates=${d}`;
const nhl = (d: string) => `https://api-web.nhle.com/v1/schedule/${d}`;
const STAND = 'https://site.api.espn.com/apis/v2/sports/football/nfl/standings';
const NHL_STAND = 'https://api-web.nhle.com/v1/standings/now';
const iso = (ms: number) => new Date(ms).toISOString();

function deps(over: Partial<RelayDeps> & { kept?: { url: string; body: string; fetched_at: string }[] | null } = {}) {
  const log = { fetched: [] as string[], put: [] as { url: string; body: string }[][], got: [] as string[][], max: 0 };
  let running = 0;
  const d: RelayDeps = {
    get: over.get ?? (async (_h, urls) => { log.got.push(urls); return over.kept === undefined ? [] : over.kept; }),
    put: over.put ?? (async (_h, items) => { log.put.push(items); }),
    fetchUrl: over.fetchUrl ?? (async (u) => {
      running++; log.max = Math.max(log.max, running); log.fetched.push(u);
      await new Promise((r) => setTimeout(r, 1));
      running--;
      return { ok: true, text: `{"from":"${u}"}` };
    }),
    now: over.now ?? (() => NOW),
  };
  return { d, log };
}

describe('sport-horaires : adresses', () => {
  it('seules les adresses exactes de la LNH et d’ESPN passent', () => {
    for (const u of [nhl('2026-11-18'), NHL_STAND, espn('20261118'), espn('20261118', 'soccer/uefa.champions'), espn('20261118', 'soccer/esp.copa_del_rey'), STAND,
      'https://site.api.espn.com/apis/v2/sports/basketball/nba/standings']) expect(allowed(u), u).toBe(true);
    for (const u of [
      'https://evil.test/x', 'http://api-web.nhle.com/v1/schedule/2026-11-18', `${espn('20261118')}&x=1`, espn('2026111'),
      'https://site.api.espn.com/apis/site/v2/sports/../x', 'https://site.api.espn.com/apis/site/v2/sports/soccer/ESP.1/scoreboard?dates=20261118',
      ' ' + NHL_STAND, NHL_STAND + ' ', 'https://api-web.nhle.com/v1/schedule/2026-11-18/../../x', 'https://api-web.nhle.com.evil.test/v1/standings/now',
      'https://site.api.espn.com@evil.test/apis/v2/sports/football/nfl/standings', 'x'.repeat(400), 42, null, undefined, {},
    ]) expect(allowed(u), String(u)).toBe(false);
  });

  it('jour d’une adresse', () => {
    expect(dayOf(nhl('2026-11-18'))).toBe(Date.UTC(2026, 10, 18));
    expect(dayOf(espn('20261231'))).toBe(Date.UTC(2026, 11, 31));
    expect(dayOf(STAND)).toBeNull();
    expect(dayOf(NHL_STAND)).toBeNull();
  });

  it('durée de vie : hier, aujourd’hui, demain 2 min ; plus loin 6 h ; semaine LNH qui contient aujourd’hui 2 min', () => {
    expect(ttlMs(espn('20261117'), NOW)).toBe(120_000);
    expect(ttlMs(espn('20261119'), NOW)).toBe(120_000);
    expect(ttlMs(espn('20261120'), NOW)).toBe(6 * 3600_000);
    expect(ttlMs(espn('20261116'), NOW)).toBe(6 * 3600_000);
    expect(ttlMs(nhl('2026-11-12'), NOW)).toBe(120_000);
    expect(ttlMs(nhl('2026-11-10'), NOW)).toBe(6 * 3600_000);
    expect(ttlMs(nhl('2026-11-20'), NOW)).toBe(6 * 3600_000);
    expect(ttlMs(NHL_STAND, NOW)).toBe(6 * 3600_000);
  });

  it('fraîcheur : date illisible ou future lointaine = pas fraîche ; juste avant la limite = fraîche', () => {
    expect(fresh(espn('20261118'), 'n’importe quoi', NOW)).toBe(false);
    expect(fresh(espn('20261118'), iso(NOW - 119_000), NOW)).toBe(true);
    expect(fresh(espn('20261118'), iso(NOW - 120_000), NOW)).toBe(false);
    expect(fresh(STAND, iso(NOW - 5 * 3600_000), NOW)).toBe(true);
  });
});

describe('sport-horaires : relais', () => {
  it('maison absente ou mal écrite : 400, rien demandé', async () => {
    const { d, log } = deps();
    expect((await relay(null, d)).status).toBe(400);
    expect((await relay({ h: 'pas-un-id', urls: [STAND] }, d)).status).toBe(400);
    expect((await relay('texte', d)).status).toBe(400);
    expect(log.got).toEqual([]);
    expect(log.fetched).toEqual([]);
  });

  it('pas membre : 403 et jamais de demande à la LNH ni à ESPN', async () => {
    const { d, log } = deps({ kept: null });
    const r = await relay({ h: H, urls: [STAND] }, d);
    expect(r.status).toBe(403);
    expect(log.fetched).toEqual([]);
  });

  it('mémoire indisponible : 502, rien demandé', async () => {
    const { d, log } = deps({ get: async () => { throw new Error('panne'); } });
    expect((await relay({ h: H, urls: [STAND] }, d)).status).toBe(502);
    expect(log.fetched).toEqual([]);
  });

  it('réponses fraîches rendues sans demande ; les autres demandées puis gardées', async () => {
    const today = espn('20261118');
    const later = espn('20261125');
    const { d, log } = deps({ kept: [{ url: today, body: '{"gardé":1}', fetched_at: iso(NOW - 30_000) }] });
    const r = await relay({ h: H, urls: [today, later] }, d);
    expect(r.status).toBe(200);
    const b = r.body as { bodies: Record<string, string>; fetched: number };
    expect(b.bodies[today]).toBe('{"gardé":1}');
    expect(b.bodies[later]).toBe(`{"from":"${later}"}`);
    expect(b.fetched).toBe(1);
    expect(log.fetched).toEqual([later]);
    expect(log.put).toEqual([[{ url: later, body: `{"from":"${later}"}` }]]);
  });

  it('réponse périmée : redemandée ; source en panne ou illisible → l’ancienne réponse, jamais gardée de nouveau', async () => {
    const a = espn('20261118'), b = espn('20261118', 'football/nfl'), c = espn('20261118', 'basketball/nba');
    const kept = [a, b].map((url) => ({ url, body: `{"vieux":"${url}"}`, fetched_at: iso(NOW - 3600_000) }));
    const { d, log } = deps({
      kept,
      fetchUrl: async (u) => { if (u === a) throw new Error('délai'); if (u === b) return { ok: true, text: '<html>panne</html>' }; return { ok: false, text: '' }; },
    });
    const r = await relay({ h: H, urls: [a, b, c] }, d);
    const bodies = (r.body as { bodies: Record<string, string> }).bodies;
    expect(bodies[a]).toBe(`{"vieux":"${a}"}`);
    expect(bodies[b]).toBe(`{"vieux":"${b}"}`);
    expect(c in bodies).toBe(false);
    expect(log.put).toEqual([]);
  });

  it('adresses refusées retirées, doublons une seule fois, au plus MAX_URLS, au plus PARALLEL à la fois', async () => {
    const many = Array.from({ length: MAX_URLS + 20 }, (_, i) => espn(`202612${String((i % 28) + 1).padStart(2, '0')}`, `soccer/l${i}`));
    const { d, log } = deps();
    await relay({ h: H, urls: ['https://evil.test/x', STAND, STAND, ...many] }, d);
    expect(log.fetched.filter((u) => u === STAND)).toHaveLength(1);
    expect(log.fetched).not.toContain('https://evil.test/x');
    expect(log.fetched.length).toBe(MAX_URLS);
    expect(log.max).toBeLessThanOrEqual(PARALLEL);
    expect(log.got[0]!.length).toBe(MAX_URLS);
  });

  it('une ligne gardée pour une adresse non demandée n’est jamais rendue', async () => {
    const { d } = deps({ kept: [{ url: 'https://evil.test/x', body: '{"x":1}', fetched_at: iso(NOW) }] });
    const r = await relay({ h: H, urls: [STAND] }, d);
    expect(Object.keys((r.body as { bodies: Record<string, string> }).bodies)).toEqual([STAND]);
  });

  it('mise en mémoire en échec : la réponse part quand même', async () => {
    const { d } = deps({ put: async () => { throw new Error('écriture'); } });
    const r = await relay({ h: H, urls: [STAND] }, d);
    expect(r.status).toBe(200);
    expect((r.body as { fetched: number }).fetched).toBe(1);
  });

  it('liste vide ou absente : rien demandé, réponse vide', async () => {
    const { d, log } = deps();
    const r = await relay({ h: H }, d);
    expect(r.body).toEqual({ bodies: {}, fetched: 0 });
    expect(log.fetched).toEqual([]);
  });
});
