import { lookup as dnsLookup } from 'node:dns/promises';
import { normalizeServer, type XtreamCreds } from '../src/xtream.js';
import { AnalyseError } from './errors.js';

export type GuardResult = { ok: true; creds: XtreamCreds } | { ok: false; status: 400; error: string };
export type Lookup = (host: string) => Promise<{ address: string; family: number }[]>;

const fail = (error: string): GuardResult => ({ ok: false, status: 400, error });
const localAllowed = () => process.env.AUTORISER_LOCAL === '1';

/** Vrai pour une adresse IP privée, locale, réservée ou de partage (CGNAT). */
export function isPrivateAddress(ip: string): boolean {
  const h = ip.toLowerCase().replace(/^\[|\]$/g, '').replace(/\.$/, '');
  const v4 = h.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (v4) {
    const [a, b] = [Number(v4[1]), Number(v4[2])];
    return a === 0 || a === 10 || a === 127 || a >= 224 || (a === 100 && b >= 64 && b <= 127)
      || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
  }
  if (h.includes(':')) {
    // "::…" couvre ::1, ::, ::ffff:a.b.c.d et les adresses compatibles IPv4 (::7f00:1).
    return h.startsWith('::') || /^f[cd][0-9a-f]{2}:/.test(h) || /^fe[89ab][0-9a-f]:/.test(h);
  }
  return false;
}

function isPrivateHost(hostname: string): boolean {
  const h = hostname.toLowerCase().replace(/^\[|\]$/g, '').replace(/\.$/, '');
  return h === 'localhost' || h.endsWith('.localhost') || isPrivateAddress(h);
}

const isIpLiteral = (h: string) => /^\[.*\]$/.test(h) || /^\d{1,3}(\.\d{1,3}){3}\.?$/.test(h);

/** Valide le corps envoyé à /api/analyse : la fonction ne doit jamais servir de relais vers un réseau privé. */
export function validateRequest(body: unknown): GuardResult {
  if (!body || typeof body !== 'object') return fail('corps-invalide');
  const { server, username, password } = body as Record<string, unknown>;
  if (typeof server !== 'string' || typeof username !== 'string' || typeof password !== 'string') return fail('champs-manquants');
  if (!server.trim() || !username || !password) return fail('champs-manquants');

  const raw = /^[a-z][a-z0-9+.-]*:\/\//i.test(server.trim()) ? server.trim() : `http://${server.trim()}`;
  let url: URL;
  try { url = new URL(raw); } catch { return fail('adresse-invalide'); }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return fail('protocole-refuse');
  if (url.username || url.password) return fail('adresse-invalide');
  if (!localAllowed() && isPrivateHost(url.hostname)) return fail('adresse-privee-refusee');

  return { ok: true, creds: { server: normalizeServer(server), username, password } };
}

/** Vérifie, au moment de l'appel (y compris après une redirection), que la cible résout vers des adresses publiques. */
export async function assertPublicUrl(target: string, lookup: Lookup = (h) => dnsLookup(h, { all: true })): Promise<void> {
  if (localAllowed()) return;
  let url: URL;
  try { url = new URL(target); } catch { throw new AnalyseError('adresse-refusee'); }
  if ((url.protocol !== 'http:' && url.protocol !== 'https:') || url.username || url.password) throw new AnalyseError('adresse-refusee');
  if (isPrivateHost(url.hostname)) throw new AnalyseError('adresse-refusee');
  if (isIpLiteral(url.hostname)) return;
  let addresses: { address: string }[];
  try { addresses = await lookup(url.hostname.replace(/\.$/, '')); } catch { throw new AnalyseError('fournisseur-injoignable'); }
  if (addresses.length === 0 || addresses.some((a) => isPrivateAddress(a.address))) throw new AnalyseError('adresse-refusee');
}
