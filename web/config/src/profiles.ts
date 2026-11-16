// Réglages des profils sur la page iPhone : onglet où la télé arrive quand on choisit le profil (profiles.home).
import type { DbResult } from './flow';

export interface ProfilesDb {
  from(table: string): {
    select(columns: string): PromiseLike<DbResult>;
    update(values: object): { eq(column: string, value: string): { select(columns: string): PromiseLike<DbResult> } };
  };
}

export type Home = 'direct' | 'series' | 'films' | 'sport';
export const HOMES: { id: Home; label: string }[] = [
  { id: 'direct', label: 'Direct' },
  { id: 'series', label: 'Séries' },
  { id: 'films', label: 'Films' },
  { id: 'sport', label: 'Sport' },
];

export interface ProfileHome { id: string; name: string; home: Home; position: number }

/** Valeur du compte vers un onglet connu ; l'ancienne valeur « mixte » ou une absence donnent Direct. */
export function toHome(v: unknown): Home {
  return HOMES.some((h) => h.id === v) ? (v as Home) : 'direct';
}

export async function loadProfileHomes(db: ProfilesDb): Promise<ProfileHome[]> {
  const r = await db.from('profiles').select('id, name, home, position');
  if (r.error) throw new Error('Impossible de lire les profils. Recharge la page.');
  return ((r.data ?? []) as { id: string; name: string; home: unknown; position: number }[])
    .map((p) => ({ id: p.id, name: p.name, position: p.position, home: toHome(p.home) }))
    .sort((a, b) => a.position - b.position);
}

export async function setProfileHome(db: ProfilesDb, profileId: string, home: Home): Promise<void> {
  if (!HOMES.some((h) => h.id === home)) throw new Error('Onglet inconnu.');
  const r = await db.from('profiles').update({ home }).eq('id', profileId).select('id');
  if (r.error) throw new Error("Impossible d'enregistrer. Réessaie dans un instant.");
  if (!Array.isArray(r.data) || r.data.length !== 1) throw new Error("Ce profil n'existe plus. Recharge la page.");
}
