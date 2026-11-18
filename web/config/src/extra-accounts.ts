// Comptes IPTV supplémentaires (table xtream_extra_accounts) : un compte par vidéo regardée en même temps dans la
// maison. La page ne lit jamais les identifiants, seulement l'état de la vérification faite par la télé.
import type { Db, DbResult } from './flow';

export interface ExtraAccountRow {
  id: string; position: number; check_status: 'a_verifier' | 'ok' | 'refuse';
  check_reason: string | null; expires_at: string | null; checked_at: string | null;
  /** Relevé léger de la télé (toutes les 10 min quand elle est allumée) : vidéos en cours, message du fournisseur. */
  active_connections?: number | null; provider_message?: string | null; status_at?: string | null;
}
export interface ExtraDb { from(table: string): { select(columns: string): PromiseLike<DbResult> } }
export type Tone = 'ok' | 'warn' | 'bad' | 'muted';

export const MAX_EXTRA = 4;
const fmt = new Intl.DateTimeFormat('fr-CA', { day: 'numeric', month: 'long', year: 'numeric' });

const REASONS: Record<string, string> = {
  identifiants: 'Refusé par le fournisseur : nom d’utilisateur ou mot de passe incorrect.',
  expire: 'Abonnement expiré ou inactif.',
  catalogue: 'Pas le même catalogue que le compte principal (autre fournisseur ou autre forfait).',
  meme_compte: 'C’est le même compte que le compte principal.',
  films: 'Mêmes chaînes, mais pas les mêmes films que le compte principal.',
  series: 'Mêmes chaînes, mais pas les mêmes séries que le compte principal.',
  films_series: 'Mêmes chaînes, mais pas les mêmes films ni les mêmes séries que le compte principal.',
};

const DAY = 86_400_000;
/** Jours de calendrier entre aujourd'hui et la date (heure locale). */
function daysUntil(at: Date, now: Date): number {
  const a = Date.UTC(at.getFullYear(), at.getMonth(), at.getDate());
  const b = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate());
  return Math.round((a - b) / DAY);
}
const plural = (n: number) => (n === 1 ? '1 jour' : `${n} jours`);

const MESSAGES: Record<string, string> = {
  'identifiants-incomplets': 'Remplis le nom d’utilisateur et le mot de passe.',
  'compte-deja-ajoute': 'Ce compte est déjà enregistré.',
  'trop-de-comptes': `${MAX_EXTRA} comptes supplémentaires au plus.`,
  'compte-principal-absent': 'Enregistre d’abord tes identifiants IPTV principaux.',
  'acces-refuse': 'Seul le compte du propriétaire peut ajouter un compte.',
};

function friendly(message: string): string {
  for (const k of Object.keys(MESSAGES)) if (message.includes(k)) return MESSAGES[k]!;
  return 'Une erreur est survenue. Réessaie dans un instant.';
}

/** Date de la dernière vérification par une télé (« Vérifié par la télé le 18 novembre 2026 »). */
function checkedText(a: ExtraAccountRow): string {
  return a.checked_at ? ` · vérifié par la télé le ${fmt.format(new Date(a.checked_at))}` : '';
}

/**
 * État d'un compte, en clair, comme le compte principal : « Actif jusqu’au 7 décembre 2026 (dans 20 jours) »
 * (orange à 30 jours de la fin, rouge à 7), « Abonnement terminé depuis le … », « Refusé » avec la raison…
 */
export function extraSummary(a: ExtraAccountRow, now: Date = new Date()): { text: string; tone: Tone; detail: string } {
  if (a.check_status === 'ok') {
    if (!a.expires_at) return { text: 'Vérifié · abonnement sans date de fin', tone: 'ok', detail: `Prêt à servir${checkedText(a)}` };
    const end = new Date(a.expires_at);
    const date = fmt.format(end);
    const days = daysUntil(end, now);
    if (end.getTime() <= now.getTime()) return { text: `Abonnement terminé depuis le ${date}`, tone: 'bad', detail: `N’est plus utilisé par les télés${checkedText(a)}` };
    const detail = `Prêt à servir${checkedText(a)}`;
    if (days <= 0) return { text: 'L’abonnement se termine aujourd’hui', tone: 'bad', detail };
    if (days <= 7) return { text: `Se termine le ${date} (dans ${plural(days)})`, tone: 'bad', detail };
    if (days <= 30) return { text: `Actif jusqu’au ${date} (dans ${plural(days)})`, tone: 'warn', detail };
    return { text: `Actif jusqu’au ${date} (dans ${plural(days)})`, tone: 'ok', detail };
  }
  if (a.check_status === 'refuse') {
    const end = a.check_reason === 'expire' && a.expires_at ? ` Fin le ${fmt.format(new Date(a.expires_at))}.` : '';
    return { text: 'Refusé', tone: 'bad', detail: (REASONS[a.check_reason ?? ''] ?? 'Ce compte ne peut pas servir.') + end + (a.checked_at ? ` (vérifié par la télé le ${fmt.format(new Date(a.checked_at))})` : '') };
  }
  return { text: 'En attente de la télé', tone: 'muted', detail: 'Une télé allumée sur l’app le vérifie en quelques secondes.' };
}

export async function loadExtraAccounts(db: ExtraDb): Promise<ExtraAccountRow[]> {
  // Jamais le nom d'utilisateur ni le mot de passe : seulement l'état.
  const r = await db.from('xtream_extra_accounts').select('id, position, check_status, check_reason, expires_at, checked_at, active_connections, provider_message, status_at');
  if (r.error) throw new Error('Impossible de lire les comptes supplémentaires.');
  return ((r.data ?? []) as ExtraAccountRow[]).slice().sort((a, b) => a.position - b.position);
}

export async function addExtraAccount(db: Db, householdId: string, username: string, password: string): Promise<void> {
  if (!username.trim() || !password.trim()) throw new Error(MESSAGES['identifiants-incomplets']);
  const r = await db.rpc('add_extra_account', { h: householdId, username: username.trim(), password });
  if (r.error) throw new Error(friendly(r.error.message));
}

export async function removeExtraAccount(db: Db, householdId: string, id: string): Promise<void> {
  const r = await db.rpc('remove_extra_account', { h: householdId, account: id });
  if (r.error) throw new Error('Impossible de retirer ce compte. Réessaie.');
}
