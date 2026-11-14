import type { VercelRequest, VercelResponse } from '@vercel/node';
import { apiUrl, type XtreamCreds } from '../src/xtream.js';
import { summarize, type CatalogSummary } from '../src/summary.js';
import { validateRequest } from './guard.js';

export type AnalyseErrorCode = 'fournisseur-injoignable' | 'fournisseur-refuse';

export class AnalyseError extends Error {
  constructor(public readonly code: AnalyseErrorCode) { super(code); }
}

const TIMEOUT_MS = 20_000;

async function getJson(url: string): Promise<unknown> {
  let res: Response;
  try {
    res = await fetch(url, { signal: AbortSignal.timeout(TIMEOUT_MS), headers: { 'User-Agent': 'Mozilla/5.0' } });
  } catch {
    throw new AnalyseError('fournisseur-injoignable');
  }
  if (!res.ok) throw new AnalyseError(res.status === 401 || res.status === 403 ? 'fournisseur-refuse' : 'fournisseur-injoignable');
  try { return await res.json(); } catch { throw new AnalyseError('fournisseur-injoignable'); }
}

const asArray = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);

/** Interroge l'API Xtream côté serveur, séquentiellement, et ne renvoie qu'un résumé. */
export async function analyse(creds: XtreamCreds): Promise<CatalogSummary> {
  const userInfo = await getJson(apiUrl(creds));
  const auth = (userInfo as { user_info?: { auth?: unknown } } | null)?.user_info?.auth;
  if (Number(auth) !== 1) throw new AnalyseError('fournisseur-refuse');

  const live = asArray(await getJson(apiUrl(creds, 'get_live_streams')));
  const movies = asArray(await getJson(apiUrl(creds, 'get_vod_streams')));
  const series = asArray(await getJson(apiUrl(creds, 'get_series')));
  const seriesInfos: unknown[] = [];
  for (const s of series.slice(0, 2)) {
    const id = String((s as { series_id?: unknown })?.series_id ?? '');
    if (id) seriesInfos.push(await getJson(apiUrl(creds, 'get_series_info', { series_id: id })));
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
    return res.status(502).json({ error: code });
  }
}
