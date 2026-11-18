// Accès pour les IA (connecteur MCP) : création d'une clé (montrée une seule fois), adresse à donner à l'IA,
// accès coupés, journal des changements faits par une IA.
import type { Db, DbResult } from './flow';

export interface AiAccess { id: string; label: string; created_at: string; last_used_at: string | null; revoked_at: string | null }
export interface AiLog { at: string; tool: string; summary: string }
export interface AiDb { from(table: string): { select(columns: string): PromiseLike<DbResult> } }

export function connectorUrl(supabaseUrl: string, key: string): string {
  return `${supabaseUrl.replace(/\/$/, '')}/functions/v1/assistant?cle=${encodeURIComponent(key)}`;
}

const when = new Intl.DateTimeFormat('fr-CA', { day: 'numeric', month: 'long', hour: 'numeric', minute: '2-digit' });
export function accessState(a: AiAccess): string {
  if (a.revoked_at) return `Coupé le ${when.format(new Date(a.revoked_at))}`;
  return a.last_used_at ? `Utilisé le ${when.format(new Date(a.last_used_at))}` : 'Jamais utilisé pour l’instant';
}

const TOOL_LABELS: Record<string, string> = {
  masquer: 'Masquage', afficher: 'Réaffichage', favoris_ajouter: 'Favoris ajoutés', favoris_retirer: 'Favoris retirés',
  playlist_creer: 'Playlist créée', playlist_ajouter: 'Chaînes ajoutées à une playlist', playlist_retirer: 'Chaînes retirées d’une playlist',
  playlist_supprimer: 'Playlist supprimée', annuler: 'Annulation',
};
export function logLabel(l: AiLog): string { return `${TOOL_LABELS[l.tool] ?? l.tool} · ${when.format(new Date(l.at))}`; }

export async function loadAccesses(db: AiDb): Promise<AiAccess[]> {
  const r = await db.from('ai_access').select('id, label, created_at, last_used_at, revoked_at');
  if (r.error) throw new Error('Impossible de lire les accès.');
  return ((r.data ?? []) as AiAccess[]).slice().sort((a, b) => b.created_at.localeCompare(a.created_at));
}

export async function loadAiLog(db: AiDb): Promise<AiLog[]> {
  const r = await db.from('ai_log').select('at, tool, summary');
  if (r.error) return [];
  return ((r.data ?? []) as AiLog[]).slice().sort((a, b) => b.at.localeCompare(a.at)).slice(0, 20);
}

export async function createAccess(db: Db, householdId: string, label: string): Promise<string> {
  const r = await db.rpc('ai_create_access', { h: householdId, label: label.trim() || 'Assistant IA' });
  if (r.error) throw new Error(r.error.message.includes('trop-d-acces') ? 'Cinq accès actifs au plus : coupe d’abord un accès.' : 'Impossible de créer l’accès. Réessaie.');
  return String(r.data);
}

export async function revokeAccess(db: Db, householdId: string, id: string): Promise<void> {
  const r = await db.rpc('ai_revoke_access', { h: householdId, access: id });
  if (r.error) throw new Error('Impossible de couper cet accès. Réessaie.');
}
