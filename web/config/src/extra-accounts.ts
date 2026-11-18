// Comptes IPTV supplémentaires (table xtream_extra_accounts) : un compte par vidéo regardée en même temps dans la
// maison. La page ne lit jamais les identifiants, seulement l'état de la vérification faite par la télé.
import type { Db, DbResult } from './flow';

export interface ExtraAccountRow {
  id: string; position: number; check_status: 'a_verifier' | 'ok' | 'refuse';
  check_reason: string | null; expires_at: string | null; checked_at: string | null;
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
};

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

/** État d'un compte, en clair : « Vérifié · actif jusqu’au … », « En attente de la télé »… */
export function extraSummary(a: ExtraAccountRow): { text: string; tone: Tone; detail: string } {
  if (a.check_status === 'ok') {
    const detail = a.expires_at ? `Actif jusqu’au ${fmt.format(new Date(a.expires_at))}` : 'Sans date de fin';
    return { text: 'Vérifié : prêt à servir', tone: 'ok', detail };
  }
  if (a.check_status === 'refuse') return { text: 'Refusé', tone: 'bad', detail: REASONS[a.check_reason ?? ''] ?? 'Ce compte ne peut pas servir.' };
  return { text: 'En attente de la télé', tone: 'muted', detail: 'Une télé allumée sur l’app le vérifie en quelques secondes.' };
}

export async function loadExtraAccounts(db: ExtraDb): Promise<ExtraAccountRow[]> {
  // Jamais le nom d'utilisateur ni le mot de passe : seulement l'état.
  const r = await db.from('xtream_extra_accounts').select('id, position, check_status, check_reason, expires_at, checked_at');
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
