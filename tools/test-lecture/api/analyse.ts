import type { VercelRequest, VercelResponse } from '@vercel/node';
import { apiUrl, type XtreamCreds } from '../src/xtream.js';
import { summarize, type CatalogSummary } from '../src/summary.js';
import { assertPublicUrl, validateRequest } from './guard.js';
import { AnalyseError } from './errors.js';

export { AnalyseError } from './errors.js';
export type { AnalyseErrorCode } from './errors.js';

const CALL_TIMEOUT_MS = 20_000;
/** Sous la limite de 60 s de la fonction Vercel (vercel.json). */
const DEADLINE_MS = 55_000;
const MAX_REDIRECTS = 3;

export interface AnalyseOptions {
  checkUrl?: (url: string) => Promise<void>;
  deadlineMs?: number;
}

async function getJson(url: string, checkUrl: (u: string) => Promise<void>, deadline: number): Promise<unknown> {
  let current = url;
  for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
    await checkUrl(current);
    const remaining = deadline - Date.now();
    if (remaining <= 0) throw new AnalyseError('delai-analyse');
    let res: Response;
    try {
      res = await fetch(current, {
        redirect: 'manual',
        signal: AbortSignal.timeout(Math.min(CALL_TIMEOUT_MS, remaining)),
        headers: { 'User-Agent': 'Mozilla/5.0' },
      });
    } catch {
      throw new AnalyseError(Date.now() >= deadline - 5 ? 'delai-analyse' : 'fournisseur-injoignable');
    }
    if (res.status >= 300 && res.status < 400) {
      const location = res.headers.get('location');
      if (!location) throw new AnalyseError('fournisseur-injoignable');
      current = new URL(location, current).href;
      continue;
    }
    if (!res.ok) throw new AnalyseError(res.status === 401 || res.status === 403 ? 'fournisseur-refuse' : 'fournisseur-injoignable');
    try { return await res.json(); } catch { throw new AnalyseError('fournisseur-injoignable'); }
  }
  throw new AnalyseError('fournisseur-injoignable');
}

const asArray = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);

/** Interroge l'API Xtream côté serveur, séquentiellement, et ne renvoie qu'un résumé. */
export async function analyse(creds: XtreamCreds, opts: AnalyseOptions = {}): Promise<CatalogSummary> {
  const checkUrl = opts.checkUrl ?? ((u: string) => assertPublicUrl(u));
  const deadline = Date.now() + (opts.deadlineMs ?? DEADLINE_MS);
  const get = (action?: string, params?: Record<string, string>) => getJson(apiUrl(creds, action, params), checkUrl, deadline);

  const userInfo = await get();
  const auth = (userInfo as { user_info?: { auth?: unknown } } | null)?.user_info?.auth;
  if (Number(auth) !== 1) throw new AnalyseError('fournisseur-refuse');

  const live = asArray(await get('get_live_streams'));
  const movies = asArray(await get('get_vod_streams'));
  const series = asArray(await get('get_series'));
  const seriesInfos: unknown[] = [];
  for (const s of series.slice(0, 2)) {
    const id = String((s as { series_id?: unknown })?.series_id ?? '');
    if (id) seriesInfos.push(await get('get_series_info', { series_id: id }));
  }
  return summarize({ serverUrl: creds.server, userInfo, live, movies, series, seriesInfos });
}

export default async function handler(req: VercelRequest, res: VercelResponse) {
  // Ne jamais journaliser req.body ni les URL : elles contiennent les identifiants.
  if (req.method !== 'POST') return res.status(405).json({ error: 'methode-refusee' });
  const v = validateRequest(req.body);
  if (!v.ok) return res.status(v.status).json({ error: v.error });
  try {
    return res.status(200).json(await analyse(v.creds));
  } catch (e) {
    const code = e instanceof AnalyseError ? e.code : 'fournisseur-injoignable';
    return res.status(code === 'adresse-refusee' ? 400 : 502).json({ error: code });
  }
}
