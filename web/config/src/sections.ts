// Sections montrées sur la télé pour un profil (profiles.settings.sections, lu par la télé : data/LocalStore.kt
// visibleTabs) : Direct, Séries, Films, Sport. Absent = toutes. Au moins une ; la page d'ouverture en fait toujours partie.
import type { SettingsDb } from './avatar';
import { HOMES, type Home } from './profiles';

export function sectionsOf(settings: Record<string, unknown>): Home[] {
  const raw = Array.isArray(settings.sections) ? settings.sections : null;
  const kept = HOMES.map((h) => h.id).filter((id) => raw?.includes(id));
  return kept.length ? kept : HOMES.map((h) => h.id);
}

/** Bascule [id] ; rend les sections et la page d'ouverture qui en résultent (ouverture déplacée si on la cache). */
export function toggleSection(current: Home[], home: Home, id: Home): { sections: Home[]; home: Home } | { error: string } {
  const on = current.includes(id);
  const sections = HOMES.map((h) => h.id).filter((x) => (x === id ? !on : current.includes(x)));
  if (!sections.length) return { error: 'Garde au moins une section.' };
  return { sections, home: sections.includes(home) ? home : sections[0]! };
}

export function sectionsSummary(sections: Home[]): string {
  return sections.length === HOMES.length ? 'Toutes' : sections.map((s) => HOMES.find((h) => h.id === s)!.label).join(', ');
}

/** Enregistre les sections (et la page d'ouverture si elle change) en une seule écriture. */
export async function saveSections(db: SettingsDb, profileId: string, settings: Record<string, unknown>, sections: Home[], home: Home, homeBefore: Home): Promise<Record<string, unknown>> {
  const next = { ...settings, sections };
  const values: Record<string, unknown> = { settings: next };
  if (home !== homeBefore) values.home = home;
  const r = await db.from('profiles').update(values).eq('id', profileId).select('id');
  if (r.error) throw new Error("Impossible d'enregistrer. Réessaie dans un instant.");
  if (!Array.isArray(r.data) || r.data.length !== 1) throw new Error("Ce profil n'existe plus. Recharge la page.");
  return next;
}
