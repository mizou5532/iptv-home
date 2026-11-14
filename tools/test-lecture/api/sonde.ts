import type { VercelRequest, VercelResponse } from '@vercel/node';
import { streamUrl, type XtreamCreds } from '../src/xtream.js';
import { detectCodecs, type CodecInfo } from '../src/codecs.js';
import type { Sample } from '../src/summary.js';
import { assertPublicUrl, validateRequest } from './guard.js';
import { AnalyseError } from './errors.js';

export interface ProbeResult { sample: Sample; httpStatus: number | null; contentType: string; bytes: number; codecs: CodecInfo; error?: string }

export interface ProbeOptions { checkUrl?: (url: string) => Promise<void>; maxBytes?: number; pauseMs?: number; deadlineMs?: number }

const MAX_SAMPLES = 6;
const EXTS = new Set(['m3u8', 'ts', 'mkv', 'mp4', 'avi', 'm4v']);
const KINDS = new Set(['live', 'movie', 'series']);
const MAX_REDIRECTS = 3;
const CALL_TIMEOUT_MS = 15_000;

export function validateSamples(v: unknown): Sample[] | null {
  if (!Array.isArray(v) || v.length === 0 || v.length > MAX_SAMPLES) return null;
  const out: Sample[] = [];
  for (const x of v) {
    const o = (x ?? {}) as Record<string, unknown>;
    if (typeof o.kind !== 'string' || !KINDS.has(o.kind)) return null;
    if (typeof o.id !== 'string' || !/^\d{1,12}$/.test(o.id)) return null;
    if (typeof o.ext !== 'string' || !EXTS.has(o.ext)) return null;
    out.push({ kind: o.kind as Sample['kind'], id: o.id, ext: o.ext, label: typeof o.label === 'string' ? o.label.slice(0, 120) : '' });
  }
  return out;
}

const empty: CodecInfo = { container: 'inconnu', video: [], audio: [] };
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function probeOne(url: string, checkUrl: (u: string) => Promise<void>, maxBytes: number, deadline: number): Promise<Omit<ProbeResult, 'sample'>> {
  let current = url;
  for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
    await checkUrl(current);
    const remaining = deadline - Date.now();
    if (remaining <= 0) throw new AnalyseError('delai-analyse');
    const res = await fetch(current, { redirect: 'manual', signal: AbortSignal.timeout(Math.min(CALL_TIMEOUT_MS, remaining)), headers: { 'User-Agent': 'Mozilla/5.0' } });
    if (res.status >= 300 && res.status < 400) {
      const location = res.headers.get('location');
      await res.body?.cancel();
      if (!location) return { httpStatus: res.status, contentType: '', bytes: 0, codecs: empty };
      current = new URL(location, current).href;
      continue;
    }
    const contentType = res.headers.get('content-type') ?? '';
    if (!res.ok || !res.body) {
      await res.body?.cancel();
      return { httpStatus: res.status, contentType, bytes: 0, codecs: empty };
    }
    // Lire seulement le début, puis couper : une seule connexion, aucune vidéo relayée.
    const reader = res.body.getReader();
    const head = new Uint8Array(maxBytes);
    let bytes = 0;
    try {
      while (bytes < maxBytes) {
        const { value, done } = await reader.read();
        if (done || !value) break;
        const take = Math.min(value.length, maxBytes - bytes);
        head.set(value.subarray(0, take), bytes);
        bytes += take;
      }
    } catch { /* coupure ou délai : on garde ce qui est arrivé */ }
    await reader.cancel().catch(() => undefined);
    return { httpStatus: res.status, contentType, bytes, codecs: detectCodecs(head.subarray(0, bytes)) };
  }
  return { httpStatus: null, contentType: '', bytes: 0, codecs: empty, error: 'trop-de-redirections' };
}

/** Sonde les échantillons depuis le serveur, un à la fois, en ne lisant que les premiers octets de chacun. */
export async function probe(creds: XtreamCreds, samples: Sample[], opts: ProbeOptions = {}): Promise<ProbeResult[]> {
  const checkUrl = opts.checkUrl ?? ((u: string) => assertPublicUrl(u));
  const maxBytes = opts.maxBytes ?? 2 * 1024 * 1024;
  const pauseMs = opts.pauseMs ?? 3_000;
  const deadline = Date.now() + (opts.deadlineMs ?? 50_000);
  const results: ProbeResult[] = [];
  for (const [i, sample] of samples.entries()) {
    if (i > 0 && pauseMs > 0) await sleep(pauseMs);
    try {
      results.push({ sample, ...(await probeOne(streamUrl(creds, sample.kind, sample.id, sample.ext), checkUrl, maxBytes, deadline)) });
    } catch (e) {
      const error = e instanceof AnalyseError ? e.code : 'fournisseur-injoignable';
      results.push({ sample, httpStatus: null, contentType: '', bytes: 0, codecs: empty, error });
      if (error === 'delai-analyse' || error === 'adresse-refusee') break;
    }
  }
  return results;
}

export default async function handler(req: VercelRequest, res: VercelResponse) {
  // Ne jamais journaliser req.body ni les URL : elles contiennent les identifiants.
  if (req.method !== 'POST') return res.status(405).json({ error: 'methode-refusee' });
  const v = validateRequest(req.body);
  if (!v.ok) return res.status(v.status).json({ error: v.error });
  const samples = validateSamples((req.body as { samples?: unknown }).samples);
  if (!samples) return res.status(400).json({ error: 'echantillons-invalides' });
  return res.status(200).json(await probe(v.creds, samples));
}
