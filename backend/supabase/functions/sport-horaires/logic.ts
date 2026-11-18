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
