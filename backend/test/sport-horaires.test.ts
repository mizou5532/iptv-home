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

describe('sport-horaires : règles de la mémoire', () => {
  async function maison(email: string) {
    const owner = await db.newUser({ email });
    const h = (await db.as(owner, (c) => c.query(`select create_household('Maison', $1::text[]) id`, [['Toi']]))).rows[0].id as string;
    return { owner, h };
  }
  const put = (u: string | null, h: string, items: unknown) => db.as(u, (c) => c.query('select sport_cache_put($1, $2::jsonb) n', [h, JSON.stringify(items)])).then((r) => r.rows[0].n);
  const get = (u: string | null, h: string, urls: string[]) => db.as(u, (c) => c.query('select url, body from sport_cache_get($1, $2::text[]) order by url', [h, urls])).then((r) => r.rows);

  it('adresses acceptées par le serveur (même liste que la fonction)', async () => {
    const ok = (u: string) => db.admin.query('select sport_url_ok($1) r', [u]).then((r) => r.rows[0].r);
    expect(await ok(ESPN)).toBe(true);
    expect(await ok(NHL)).toBe(true);
    expect(await ok(STAND)).toBe(true);
    expect(await ok('https://api-web.nhle.com/v1/standings/now')).toBe(true);
    for (const u of ['https://evil.test/x', `${ESPN}&x=1`, 'http://api-web.nhle.com/v1/standings/now', 'https://api-web.nhle.com/v1/standings/now/x', 'x'.repeat(301)])
      expect(await ok(u), u).toBe(false);
  });

  it('deux maisons : même adresse, réponses séparées ; une télé de la maison peut lire et écrire', async () => {
    const a = await maison('proprio-sh2@exemple.test');
    const bOwner = await db.newUser({ email: 'proprio-sh3@exemple.test' });
    const b = { owner: bOwner, h: await db.seedHousehold(bOwner) };
    await put(a.owner, a.h, [{ url: ESPN, body: '{"maison":"a"}' }]);
    await put(b.owner, b.h, [{ url: ESPN, body: '{"maison":"b"}' }]);
    expect(await get(a.owner, a.h, [ESPN])).toEqual([{ url: ESPN, body: '{"maison":"a"}' }]);
    expect(await get(b.owner, b.h, [ESPN])).toEqual([{ url: ESPN, body: '{"maison":"b"}' }]);
    await expect(get(a.owner, b.h, [ESPN])).rejects.toThrow('acces-refuse');
    const tv = await db.newUser({ anonymous: true });
    await db.admin.query(`insert into household_members (household_id, user_id, role, device_name) values ($1, $2, 'device', 'Télé')`, [a.h, tv]);
    expect(await put(tv, a.h, [{ url: ESPN, body: '{"maison":"a2"}' }])).toBe(1);
    expect(await get(tv, a.h, [ESPN])).toEqual([{ url: ESPN, body: '{"maison":"a2"}' }]);
  });

  it('anonyme refusé ; seulement les adresses demandées ; mise à jour ; trop d’éléments refusés ; trop gros ignoré', async () => {
    const { owner, h } = await maison('proprio-sh4@exemple.test');
    await expect(get(null, h, [ESPN])).rejects.toThrow();
    await expect(put(null, h, [{ url: ESPN, body: '{}' }])).rejects.toThrow();
    await put(owner, h, [{ url: ESPN, body: '{"v":1}' }, { url: STAND, body: '{"s":1}' }]);
    expect(await get(owner, h, [STAND])).toEqual([{ url: STAND, body: '{"s":1}' }]);
    await put(owner, h, [{ url: STAND, body: '{"s":2}' }]);
    expect(await get(owner, h, [STAND])).toEqual([{ url: STAND, body: '{"s":2}' }]);
    await expect(put(owner, h, Array.from({ length: 101 }, () => ({ url: ESPN, body: '{}' })))).rejects.toThrow('donnees-invalides');
    await expect(put(owner, h, { url: ESPN, body: '{}' })).rejects.toThrow('donnees-invalides');
    expect(await put(owner, h, [{ url: NHL, body: '{"x":"' + 'a'.repeat(2_000_001) + '"}' }])).toBe(0);
    expect(await put(owner, h, [{ url: NHL, body: null }, { url: NHL }])).toBe(0);
    expect(await put(owner, h, [{ url: NHL, body: '   {"espaces":1}' }])).toBe(1);
  });

  it('réponses de plus de 3 jours effacées à la prochaine écriture ; maison supprimée = mémoire supprimée', async () => {
    const { owner, h } = await maison('proprio-sh5@exemple.test');
    await put(owner, h, [{ url: ESPN, body: '{"v":1}' }]);
    await db.admin.query(`update sport_cache set fetched_at = now() - interval '4 days' where household_id = $1`, [h]);
    await put(owner, h, [{ url: STAND, body: '{"s":1}' }]);
    expect(await get(owner, h, [ESPN, STAND])).toEqual([{ url: STAND, body: '{"s":1}' }]);
    await db.admin.query('delete from households where id = $1', [h]);
    expect((await db.admin.query('select count(*)::int n from sport_cache where household_id = $1', [h])).rows[0].n).toBe(0);
  });
});

