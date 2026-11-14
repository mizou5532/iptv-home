import { normalizeServer, type XtreamCreds } from '../src/xtream.js';

export type GuardResult = { ok: true; creds: XtreamCreds } | { ok: false; status: 400; error: string };

const fail = (error: string): GuardResult => ({ ok: false, status: 400, error });

function isPrivateHost(hostname: string): boolean {
  const h = hostname.toLowerCase().replace(/^\[|\]$/g, '');
  if (h === 'localhost' || h.endsWith('.localhost')) return true;
  const v4 = h.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (v4) {
    const [a, b] = [Number(v4[1]), Number(v4[2])];
    return a === 127 || a === 10 || a === 0 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 169 && b === 254);
  }
  if (h.includes(':')) {
    return h === '::1' || h === '::' || /^f[cd][0-9a-f]{2}:/.test(h) || /^fe80:/.test(h) || h.startsWith('::ffff:');
  }
  return false;
}

/** Valide le corps envoyé à /api/analyse : la fonction ne doit jamais servir de relais vers un réseau privé. */
export function validateRequest(body: unknown): GuardResult {
  if (!body || typeof body !== 'object') return fail('corps-invalide');
  const { server, username, password } = body as Record<string, unknown>;
  if (typeof server !== 'string' || typeof username !== 'string' || typeof password !== 'string') return fail('champs-manquants');
  if (!server.trim() || !username || !password) return fail('champs-manquants');

  const normalized = normalizeServer(server);
  let url: URL;
  try { url = new URL(normalized); } catch { return fail('adresse-invalide'); }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return fail('protocole-refuse');
  if (process.env.AUTORISER_LOCAL !== '1' && isPrivateHost(url.hostname)) return fail('adresse-privee-refusee');

  return { ok: true, creds: { server: normalized, username, password } };
}
