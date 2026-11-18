// Sauvegardes de la maison (table household_backups, propriétaire seulement) : liste, sauvegarde immédiate,
// téléchargement d'un fichier, restauration par parties (depuis le serveur ou depuis un fichier).
import type { Db, DbResult } from './flow';

export interface BackupRow { id: string; created_at: string; reason: string; summary: Record<string, number> }
export interface BackupDb { from(table: string): { select(columns: string): { eq(c: string, v: string): PromiseLike<DbResult> } & PromiseLike<DbResult> } }
export type Part = 'profils' | 'progression' | 'organisation';

export const PARTS: { id: Part; label: string; detail: string }[] = [
  { id: 'progression', label: 'Progression', detail: 'Où chacun est rendu, « vu » ; une position plus récente n’est jamais remplacée.' },
  { id: 'organisation', label: 'Organisation', detail: 'Ma liste, favoris, playlists, masquages, ordre des catégories, regroupements.' },
  { id: 'profils', label: 'Profils et réglages', detail: 'Profils (même supprimés), photos et thèmes, réglages de la maison.' },
];

const REASONS: Record<string, string> = {
  nuit: 'Sauvegarde de la nuit',
  manuelle: 'Sauvegarde manuelle',
  avant_identifiants: 'Avant changement d’identifiants IPTV',
  avant_suppression_profil: 'Avant suppression d’un profil',
  avant_restauration: 'Avant une restauration',
  avant_ia: 'Avant des changements par une IA',
};

const when = new Intl.DateTimeFormat('fr-CA', { weekday: 'short', day: 'numeric', month: 'long', hour: 'numeric', minute: '2-digit' });

export function backupTitle(b: BackupRow): string { return REASONS[b.reason] ?? 'Sauvegarde'; }
export function backupWhen(b: BackupRow): string { return when.format(new Date(b.created_at)); }

/** « 412 positions · 3 playlists · 1 404 masquages · 3 profils ». */
export function backupContent(b: BackupRow): string {
  const s = b.summary ?? {};
  const n = (v: number | undefined, one: string, many: string) => (v ? `${v.toLocaleString('fr-CA')} ${v > 1 ? many : one}` : null);
  return [n(s.progression, 'position', 'positions'), n(s.favoris, 'favori', 'favoris'), n(s.playlists, 'playlist', 'playlists'),
    n(s.masquages, 'masquage', 'masquages'), n(s.profils, 'profil', 'profils')].filter(Boolean).join(' · ') || 'Vide';
}

/** Nom du fichier téléchargé : « iptv-home-sauvegarde-2026-11-18-2117.json ». */
export function backupFileName(b: BackupRow): string {
  const d = new Date(b.created_at);
  const p = (x: number) => String(x).padStart(2, '0');
  return `iptv-home-sauvegarde-${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}.json`;
}

const MESSAGES: Record<string, string> = {
  'acces-refuse': 'Seul le compte du propriétaire peut gérer les sauvegardes.',
  'sauvegarde-invalide': 'Ce fichier n’est pas une sauvegarde de cette maison.',
  'sauvegarde-introuvable': 'Cette sauvegarde n’existe plus.',
  'trop-de-profils': 'Restauration impossible : elle dépasserait 10 profils.',
  'parties-invalides': 'Choisis au moins une partie à restaurer.',
  'profils-absents': 'Cette sauvegarde contient des profils supprimés depuis : coche aussi « Profils et réglages ».',
};
function friendly(m: string): string {
  for (const k of Object.keys(MESSAGES)) if (m.includes(k)) return MESSAGES[k]!;
  return 'Une erreur est survenue. Réessaie dans un instant.';
}

export async function loadBackups(db: BackupDb, householdId: string): Promise<BackupRow[]> {
  const r = await db.from('household_backups').select('id, created_at, reason, summary').eq('household_id', householdId);
  if (r.error) throw new Error('Impossible de lire les sauvegardes.');
  return ((r.data ?? []) as BackupRow[]).slice().sort((a, b) => b.created_at.localeCompare(a.created_at));
}

export async function loadBackupData(db: BackupDb, householdId: string, id: string): Promise<unknown> {
  const r = await db.from('household_backups').select('data').eq('id', id);
  const row = ((r.data ?? []) as { data: unknown }[])[0];
  if (r.error || !row) throw new Error('Impossible de télécharger cette sauvegarde.');
  void householdId;
  return row.data;
}

export async function backupNow(db: Db, householdId: string): Promise<void> {
  const r = await db.rpc('backup_now', { h: householdId });
  if (r.error) throw new Error(friendly(r.error.message));
}

export async function restoreBackup(db: Db, householdId: string, id: string, parts: Part[]): Promise<void> {
  if (!parts.length) throw new Error(MESSAGES['parties-invalides']);
  const r = await db.rpc('backup_restore', { h: householdId, backup: id, parts });
  if (r.error) throw new Error(friendly(r.error.message));
}

/** Fichier choisi sur le téléphone : vérifié ici (format), puis par le serveur (même maison). */
export async function restoreFile(db: Db, householdId: string, text: string, parts: Part[]): Promise<void> {
  if (!parts.length) throw new Error(MESSAGES['parties-invalides']);
  let data: unknown;
  try { data = JSON.parse(text); } catch { throw new Error(MESSAGES['sauvegarde-invalide']); }
  if (!data || typeof data !== 'object' || (data as { format?: number }).format !== 1) throw new Error(MESSAGES['sauvegarde-invalide']);
  const r = await db.rpc('backup_restore_file', { h: householdId, d: data, parts });
  if (r.error) throw new Error(friendly(r.error.message));
}
