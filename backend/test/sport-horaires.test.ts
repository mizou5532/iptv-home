import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { startDb, type Db } from './db';
import { allowed, dayOf, fresh, ttlMs } from '../supabase/functions/sport-horaires/logic';

const NHL = 'https://api-web.nhle.com/v1/schedule/2026-11-18';
const ESPN = 'https://site.api.espn.com/apis/site/v2/sports/soccer/esp.1/scoreboard?dates=20261118';
const STAND = 'https://site.api.espn.com/apis/v2/sports/football/nfl/standings';

describe('sport-horaires : logique', () => {
  it('adresses acceptées seulement', () => {
    for (const u of [NHL, ESPN, STAND, 'https://api-web.nhle.com/v1/standings/now']) expect(allowed(u)).toBe(true);
    for (const u of ['https://evil.test/x', 'http://api-web.nhle.com/v1/schedule/2026-11-18', `${ESPN}&x=1`, 'https://site.api.espn.com/apis/site/v2/sports/../x', 42])
      expect(allowed(u)).toBe(false);
  });
  it('2 min pour aujourd’hui, 6 h pour les jours suivants et les classements', () => {
    const now = Date.UTC(2026, 10, 18, 15);
    expect(dayOf(ESPN)).toBe(Date.UTC(2026, 10, 18));
    expect(ttlMs(ESPN, now)).toBe(120_000);
    expect(ttlMs(ESPN.replace('20261118', '20261121'), now)).toBe(6 * 3600_000);
    expect(ttlMs(NHL, now)).toBe(120_000);
    expect(ttlMs('https://api-web.nhle.com/v1/schedule/2026-11-25', now)).toBe(6 * 3600_000);
    expect(ttlMs(STAND, now)).toBe(6 * 3600_000);
    expect(fresh(ESPN, new Date(now - 60_000).toISOString(), now)).toBe(true);
    expect(fresh(ESPN, new Date(now - 180_000).toISOString(), now)).toBe(false);
  });
});

let db: Db;
beforeAll(async () => { db = await startDb(); });
afterAll(async () => { await db?.stop(); });
beforeEach(async () => { await db.resetHouseholds(); });

describe('sport-horaires : mémoire de la maison', () => {
  it('membres seulement ; une maison ne voit ni ne change celle d’une autre ; adresses et contenus vérifiés', async () => {
    const owner = await db.newUser({ email: 'proprio-sh1@exemple.test' });
    const h = (await db.as(owner, (c) => c.query(`select create_household('Maison', $1::text[]) id`, [['Toi']]))).rows[0].id;
    const put = (u: string, items: unknown) => db.as(u, (c) => c.query('select sport_cache_put($1, $2::jsonb) n', [h, JSON.stringify(items)])).then((r) => r.rows[0].n);
    const get = (u: string, urls: string[]) => db.as(u, (c) => c.query('select url, body from sport_cache_get($1, $2::text[])', [h, urls])).then((r) => r.rows);
    expect(await put(owner, [{ url: ESPN, body: '{"events":[]}' }, { url: 'https://evil.test/x', body: '{}' }, { url: STAND, body: '<html>' }])).toBe(1);
    expect(await get(owner, [ESPN, STAND])).toEqual([{ url: ESPN, body: '{"events":[]}' }]);
    const autre = await db.newUser();
    await expect(get(autre, [ESPN])).rejects.toThrow('acces-refuse');
    await expect(put(autre, [{ url: ESPN, body: '{"faux":1}' }])).rejects.toThrow('acces-refuse');
    await expect(db.as(owner, (c) => c.query('select * from sport_cache'))).rejects.toThrow();
  });
});
