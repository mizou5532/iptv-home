// Vitrine Films et Séries (toute la maison) : catégories affichées en rangées sur les accueils Films et Séries
// (households.settings.showcase_movies et showcase_series). Les autres catégories restent trouvables par la recherche.
// Absent = choix automatique, même règle que la télé (Showcase.kt côté Kotlin).
import { loadHouseholdSettings, type HouseholdDb } from './look';
import { orgMovieCategories, orgSeriesCategories } from './org/api';
import type { Db } from './flow';

export type ShowcaseKind = 'movies' | 'series';

export interface ShowcaseCategory {
  id: string;
  name: string;
  position: number;
}

const norm = (s: string) =>
  s
    .toUpperCase()
    .replace(/É|È|Ê/g, 'E')
    .replace(/À/g, 'A')
    .replace(/Ç/g, 'C')
    .replace(/Ô/g, 'O')
    .replace(/\s+/g, ' ')
    .trim();

const DUPLICATE = /[⁴³ᴷᴰᴼᴸᴮʸᴴᴿⱽᵒˡᵇʸᶦˢ]/;
const DUPLICATE_WORDS = /\b(4K|3840P|2160P|UHD|HEVC|DOLBY|HDR)\b/;
const ALWAYS = /\b(KIDS|ENFANTS|NICKELODEON|ANIME|ANIMI|ANIMES|MANGA|CRUNCHYROLL|ADULT[ -]SWIM)\b|^JP - |JAPAN/;
const MOVIES = /^(FR|QC) - |\bNEW RELEASE\b|\bIMDB TOP\b/;
const SERIES = /^(FRANCE|QUEBEC)\b|^ENGLISH SERIES$|^NETFLIX SERIES$/;

/** Même règle automatique que Showcase.contains côté Kotlin. */
export function isDefaultShowcaseCategory(kind: ShowcaseKind, name: string): boolean {
  const n = norm(name);
  if (!n || DUPLICATE.test(name) || DUPLICATE_WORDS.test(n)) return false;
  if (ALWAYS.test(n)) return true;
  return kind === 'movies' ? MOVIES.test(n) : SERIES.test(n);
}

const settingKey = (kind: ShowcaseKind) => (kind === 'movies' ? 'showcase_movies' : 'showcase_series');

/** Catégories cochées : le choix enregistré (s'il en reste au moins une valide), sinon le choix automatique. */
export function chosenShowcaseCategories(
  settings: Record<string, unknown>,
  kind: ShowcaseKind,
  cats: ShowcaseCategory[],
): { ids: string[]; auto: boolean } {
  const valid = new Set(cats.map((c) => c.id));
  const raw = settings[settingKey(kind)];
  if (Array.isArray(raw)) {
    const picked = raw.filter((x): x is string => typeof x === 'string' && valid.has(x));
    if (picked.length > 0) return { ids: [...new Set(picked)], auto: false };
  }
  const autoIds = cats.filter((c) => isDefaultShowcaseCategory(kind, c.name)).map((c) => c.id);
  return { ids: autoIds.length > 0 ? autoIds : cats.map((c) => c.id), auto: true };
}

/** Recherche sans accents ni casse ; les cochées d'abord, puis l'ordre du fournisseur. */
export function filterShowcaseCategories(cats: ShowcaseCategory[], query: string, checked: Set<string>): ShowcaseCategory[] {
  const clean = (s: string) => s.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
  const words = clean(query).split(/\s+/).filter(Boolean);
  return cats
    .filter((c) => words.every((w) => clean(c.name).includes(w)))
    .sort((a, b) => Number(checked.has(b.id)) - Number(checked.has(a.id)) || a.position - b.position);
}

/** Charge les catégories visibles (non masquées pour tout le monde) des Films ou des Séries. */
export async function loadShowcaseCategories(db: Db, householdId: string, kind: ShowcaseKind): Promise<ShowcaseCategory[]> {
  const rows = kind === 'movies'
    ? await orgMovieCategories(db, householdId, null)
    : await orgSeriesCategories(db, householdId, null);
  return rows
    .filter((c) => !c.hiddenGlobal)
    .map((c) => ({ id: c.id, name: c.name, position: c.providerPosition }))
    .sort((a, b) => a.position - b.position);
}

/** Enregistre le choix (null = choix automatique). Propriétaire seulement. */
export async function saveShowcaseCategories(
  db: HouseholdDb,
  householdId: string,
  kind: ShowcaseKind,
  ids: string[] | null,
): Promise<Record<string, unknown>> {
  if (ids !== null && ids.length === 0) {
    throw new Error('Choisis au moins une catégorie, ou utilise « Choix automatique ».');
  }
  const settings = await loadHouseholdSettings(db, householdId);
  const next: Record<string, unknown> = { ...settings };
  const key = settingKey(kind);
  if (ids === null) delete next[key];
  else next[key] = [...new Set(ids)];
  const r = await db.from('households').update({ settings: next }).eq('id', householdId).select('id');
  if (r.error) throw new Error("Impossible d'enregistrer. Réessaie dans un instant.");
  if (!Array.isArray(r.data) || r.data.length !== 1) throw new Error('Seul le propriétaire du compte peut changer ce réglage.');
  return next;
}
