// Abonnement IPTV : ce que la télé a lu chez le fournisseur (table iptv_account), résumé pour la page Réglages.
import type { DbResult } from './flow';

export interface IptvAccountRow {
  expires_at: string | null; status: string | null; max_connections: number | null; checked_at: string;
  /** Vidéos ouvertes en ce moment sur le compte (relevé de la télé) ; message du fournisseur. */
  active_connections?: number | null; provider_message?: string | null;
}

const hour = new Intl.DateTimeFormat('fr-CA', { hour: 'numeric', minute: '2-digit' });

/** « 1 vidéo en cours sur ce compte (relevé à 21 h 04) » ; `null` si la télé ne l'a pas relevé. */
export function usageText(active: number | null | undefined, at: string | null | undefined, max?: number | null): string | null {
  if (active == null || !at) return null;
  const when = `relevé à ${hour.format(new Date(at))}`;
  if (active === 0) return `Aucune vidéo en cours sur ce compte (${when})`;
  const of = max && max > 0 ? ` sur ${max} permise${max > 1 ? 's' : ''}` : '';
  return `${active === 1 ? '1 vidéo en cours' : `${active} vidéos en cours`}${of} (${when})`;
}
export interface AccountDb { from(table: string): { select(columns: string): PromiseLike<DbResult> } }
export type Tone = 'ok' | 'warn' | 'bad' | 'muted';

const DAY = 86_400_000;
const fmt = new Intl.DateTimeFormat('fr-CA', { day: 'numeric', month: 'long', year: 'numeric' });

/** Jours de calendrier entre aujourd'hui et la date (heure locale). */
function daysUntil(at: Date, now: Date): number {
  const a = Date.UTC(at.getFullYear(), at.getMonth(), at.getDate());
  const b = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate());
  return Math.round((a - b) / DAY);
}

/** Phrase principale, ton (couleur) et détail (« Vérifié par la télé le … · 2 connexions »). */
export function accountSummary(row: IptvAccountRow | null, now: Date): { text: string; tone: Tone; detail: string } {
  if (!row) return { text: 'Date de fin pas encore connue', tone: 'muted', detail: 'La télé la lira chez le fournisseur à sa prochaine ouverture.' };
  const checked = new Date(row.checked_at);
  const parts = [`Vérifié par la télé le ${fmt.format(checked)}`];
  if (row.max_connections) parts.push(row.max_connections === 1 ? '1 connexion à la fois' : `${row.max_connections} connexions à la fois`);
  const detail = parts.join(' · ');
  if (!row.expires_at) return { text: 'Abonnement sans date de fin', tone: 'ok', detail };
  const end = new Date(row.expires_at);
  const days = daysUntil(end, now);
  const date = fmt.format(end);
  if (end.getTime() <= now.getTime()) return { text: `Abonnement terminé depuis le ${date}`, tone: 'bad', detail };
  if (days <= 0) return { text: 'L’abonnement se termine aujourd’hui', tone: 'bad', detail };
  if (days <= 7) return { text: `Se termine le ${date} (dans ${days === 1 ? '1 jour' : `${days} jours`})`, tone: 'warn', detail };
  if (days <= 30) return { text: `Actif jusqu’au ${date} (dans ${days} jours)`, tone: 'warn', detail };
  return { text: `Actif jusqu’au ${date}`, tone: 'ok', detail };
}

export async function loadIptvAccount(db: AccountDb): Promise<IptvAccountRow | null> {
  const r = await db.from('iptv_account').select('expires_at, status, max_connections, checked_at, active_connections, provider_message');
  if (r.error) throw new Error("Impossible de lire l'abonnement.");
  return ((r.data ?? []) as IptvAccountRow[])[0] ?? null;
}
