// Catégories du fournisseur lues par l'onglet Sport de la télé, pour toute la maison (households.settings.sport_categories),
// même masquées dans le Direct : chaînes d'événements (« Raptors vs Knicks @ Nov 15 ») et chaînes d'équipes.
// Absent = choix automatique, même règle que la télé (isDefaultSportCategory côté Kotlin).
import { loadHouseholdSettings, type HouseholdDb } from './look';

export interface SportCategory { id: string; name: string; position: number }

const countryOf = (s: string) => /^\s*\|?\s*([A-Za-z]{2,3})\s*[|:]/.exec(s)?.[1]?.toUpperCase() ?? null;

/** Catégories proposées par défaut : événements et équipes du Canada, des É.-U. et de France (pas VIP, pas reprises). */
export function isDefaultSportCategory(name: string): boolean {
  const n = name.toUpperCase();
  const code = countryOf(name);
  if (!code || !['CA', 'US', 'FR'].includes(code)) return false;
  if (n.includes('VIP') || name.includes('ⱽᴵᴾ') || name.includes('⁽ᴮᴷ⁾') || n.includes('REPLAY')) return false;
  return /\b(NHL|NBA|NFL|MLS)\b.*PPV|NBA PASS|TSN\+|SPORTSNET\+? PPV|ESPN\+ PPV|DAZN( LA LIGA)? PPV|SOCCER PPV|PEACOCK PPV|PARAMOUNT\+ PPV|CBC PPV/.test(n);
}

/** Catégories cochées : le choix enregistré, sinon le choix automatique. */
export function chosenSportCategories(settings: Record<string, unknown>, cats: SportCategory[]): { ids: string[]; auto: boolean } {
  const raw = settings.sport_categories;
  if (Array.isArray(raw)) return { ids: raw.filter((x): x is string => typeof x === 'string'), auto: false };
  return { ids: cats.filter((c) => isDefaultSportCategory(c.name)).map((c) => c.id), auto: true };
}

/** Recherche sans accents ni casse ; les cochées d'abord, puis l'ordre du fournisseur. */
export function filterCategories(cats: SportCategory[], query: string, checked: Set<string>): SportCategory[] {
  const norm = (s: string) => s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
  const words = norm(query).split(/\s+/).filter(Boolean);
  return cats.filter((c) => words.every((w) => norm(c.name).includes(w)))
    .sort((a, b) => Number(checked.has(b.id)) - Number(checked.has(a.id)) || a.position - b.position);
}

export interface CategoryDb { from(table: string): { select(columns: string): PromiseLike<{ data: unknown; error: unknown }> } }

export async function loadCategories(db: CategoryDb): Promise<SportCategory[]> {
  const r = await db.from('live_catalog_category').select('id, name, position');
  if (r.error) throw new Error('Impossible de lire les catégories. Ouvre d’abord le Direct sur la télé.');
  return ((r.data ?? []) as SportCategory[]).sort((a, b) => a.position - b.position);
}

/** Enregistre le choix (null = choix automatique), en relisant les réglages de la maison juste avant. Propriétaire seulement. */
export async function saveSportCategories(db: HouseholdDb, householdId: string, ids: string[] | null): Promise<Record<string, unknown>> {
  const settings = await loadHouseholdSettings(db, householdId);
  const next: Record<string, unknown> = { ...settings };
  if (ids === null) delete next.sport_categories; else next.sport_categories = [...new Set(ids)];
  const r = await db.from('households').update({ settings: next }).eq('id', householdId).select('id');
  if (r.error) throw new Error("Impossible d'enregistrer. Réessaie dans un instant.");
  if (!Array.isArray(r.data) || r.data.length !== 1) throw new Error('Seul le propriétaire du compte peut changer ce réglage.');
  return next;
}
