// Fonction « sport-horaires » : logique pure (adresses acceptées, durée de vie d'une réponse), testée avec vitest.

const ALLOWED = [
  /^https:\/\/api-web\.nhle\.com\/v1\/(schedule\/\d{4}-\d{2}-\d{2}|standings\/now)$/,
  /^https:\/\/site\.api\.espn\.com\/apis\/site\/v2\/sports\/[a-z]+\/[a-z0-9._]+\/scoreboard\?dates=\d{8}$/,
  /^https:\/\/site\.api\.espn\.com\/apis\/v2\/sports\/[a-z]+\/[a-z0-9._]+\/standings$/,
];
export const MAX_URLS = 80;

export function allowed(u: unknown): u is string {
  return typeof u === 'string' && u.length <= 300 && ALLOWED.some((r) => r.test(u));
}

const DAY = 86_400_000;
/** Jour (UTC) d'une adresse d'horaires ; null pour un classement. */
export function dayOf(u: string): number | null {
  const nhl = /schedule\/(\d{4})-(\d{2})-(\d{2})$/.exec(u);
  if (nhl) return Date.UTC(+nhl[1], +nhl[2] - 1, +nhl[3]);
  const espn = /dates=(\d{4})(\d{2})(\d{2})$/.exec(u);
  if (espn) return Date.UTC(+espn[1], +espn[2] - 1, +espn[3]);
  return null;
}

/**
 * Durée de vie (ms) d'une réponse gardée : 2 min pour une journée proche d'aujourd'hui (scores en direct ; l'horaire
 * LNH d'une date couvre aussi les 6 jours suivants, donc aujourd'hui s'il commence jusqu'à 7 jours avant), 6 h sinon
 * (jours suivants, classements).
 */
export function ttlMs(u: string, now: number): number {
  const d = dayOf(u);
  if (d === null) return 6 * 3600_000;
  const today = Math.floor(now / DAY) * DAY;
  const nhl = u.includes('nhle.com');
  const near = nhl ? d <= today + DAY && d >= today - 7 * DAY : Math.abs(d - today) <= DAY;
  return near ? 120_000 : 6 * 3600_000;
}

export function fresh(u: string, fetchedAt: string, now: number): boolean {
  const t = Date.parse(fetchedAt);
  return Number.isFinite(t) && now - t < ttlMs(u, now);
}

export interface RelayDeps {
  /** Mémoire de la maison (session de la télé) : `null` = refusé (pas membre) ; lève une exception si indisponible. */
  get(h: string, urls: string[]): Promise<{ url: string; body: string; fetched_at: string }[] | null>;
  put(h: string, items: { url: string; body: string }[]): Promise<void>;
  /** Adresse chez la LNH ou ESPN : texte, ou exception (panne, délai). */
  fetchUrl(u: string): Promise<{ ok: boolean; text: string }>;
  now(): number;
}
export const PARALLEL = 8;

export type RelayAnswer = { status: number; body: unknown };

/** Demande de la télé : { h, urls } → réponses gardées fraîches, puis le reste chez la source (PARALLEL à la fois). */
export async function relay(input: unknown, deps: RelayDeps): Promise<RelayAnswer> {
  const body = (input && typeof input === 'object' ? input : {}) as { h?: unknown; urls?: unknown };
  const h = typeof body.h === 'string' && /^[0-9a-f-]{36}$/.test(body.h) ? body.h : null;
  if (!h) return { status: 400, body: { error: 'maison-requise' } };
  const urls = Array.isArray(body.urls) ? [...new Set(body.urls.filter(allowed))].slice(0, MAX_URLS) : [];
  let kept: { url: string; body: string; fetched_at: string }[] | null;
  try { kept = await deps.get(h, urls); } catch { return { status: 502, body: { error: 'indisponible' } }; }
  // Pas membre de la maison : rien n'est demandé à la LNH ni à ESPN.
  if (kept === null) return { status: 403, body: { error: 'acces-refuse' } };
  const now = deps.now();
  const out: Record<string, string> = {};
  const stale: Record<string, string> = {};
  for (const k of kept) if (urls.includes(k.url)) (fresh(k.url, k.fetched_at, now) ? out : stale)[k.url] = k.body;
  const missing = urls.filter((u) => !(u in out));
  const fetched: { url: string; body: string }[] = [];
  let i = 0;
  async function worker() {
    while (i < missing.length) {
      const u = missing[i++]!;
      try {
        const r = await deps.fetchUrl(u);
        const t = r.ok ? r.text : '';
        if (t.trimStart().startsWith('{')) { out[u] = t; fetched.push({ url: u, body: t }); }
        else if (u in stale) out[u] = stale[u]!;
      } catch {
        // Site injoignable : l'ancienne réponse gardée vaut mieux que rien ; sinon la télé ira la chercher elle-même.
        if (u in stale) out[u] = stale[u]!;
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(PARALLEL, missing.length) }, worker));
  if (fetched.length) await deps.put(h, fetched).catch(() => undefined);
  return { status: 200, body: { bodies: out, fetched: fetched.length } };
}
