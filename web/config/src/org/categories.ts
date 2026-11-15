// Logique pure de la liste des catégories (sans DOM).
import { searchNorm } from './norm';
import type { Category } from './api';

export function filterCategories<T extends { name: string }>(list: T[], query: string): T[] {
  const q = searchNorm(query);
  if (!q) return list;
  return list.filter((c) => searchNorm(c.name).includes(q));
}

// Déplace l'élément `from` à la position `to` (bornée à la liste).
export function moveCategory(ids: string[], from: number, to: number): string[] {
  const out = ids.slice();
  if (from < 0 || from >= out.length) return out;
  const target = Math.max(0, Math.min(out.length - 1, to));
  const [id] = out.splice(from, 1);
  out.splice(target, 0, id);
  return out;
}

export function hiddenInScope(c: Category, everyone: boolean): boolean {
  return everyone ? c.hiddenGlobal : c.hiddenProfile;
}
// En portée profil, une catégorie masquée pour tout le monde ne se modifie pas.
export function isLocked(c: Category, everyone: boolean): boolean {
  return !everyone && c.hiddenGlobal;
}

export function hideAllExceptPlan(list: Category[], keep: Set<string>, everyone = true): { toHide: number; toShow: number } {
  let toHide = 0, toShow = 0;
  for (const c of list) {
    if (isLocked(c, everyone)) continue;
    const hidden = hiddenInScope(c, everyone);
    if (keep.has(c.id)) { if (hidden) toShow++; } else if (!hidden) toHide++;
  }
  return { toHide, toShow };
}

// Les trois sections de l'onglet Catégories partagent le même écran ; seul ce tableau les distingue.
// `of` complète « catégorie(s) » dans la confirmation ; `channels` : compteur et ouverture des chaînes (direct seulement).
export type CategoryKind = 'live' | 'series' | 'movies';
export interface CategoryKindInfo { id: CategoryKind; label: string; of: string; channels: boolean; empty: string }
export const CATEGORY_KINDS: readonly CategoryKindInfo[] = [
  { id: 'live', label: 'Direct', of: '', channels: true, empty: "Ouvre l'app sur la télé une fois pour envoyer la liste des chaînes." },
  { id: 'series', label: 'Séries', of: ' de séries', channels: false, empty: 'Ouvre la section Séries sur la télé une fois pour envoyer la liste.' },
  { id: 'movies', label: 'Films', of: ' de films', channels: false, empty: 'Ouvre la section Films sur la télé une fois pour envoyer la liste.' },
];
export const kindInfo = (kind: CategoryKind): CategoryKindInfo => CATEGORY_KINDS.find((k) => k.id === kind)!;
export const EMPTY_TEXT = Object.fromEntries(CATEGORY_KINDS.map((k) => [k.id, k.empty])) as Record<CategoryKind, string>;

export function confirmText(scopeIsEveryone: boolean, toHide: number, kind: CategoryKind = 'live'): string | null {
  if (!scopeIsEveryone) return null;
  const { of } = kindInfo(kind);
  if (toHide === 1) return `Cette catégorie${of} ne sera plus téléchargée par la télé, pour tout le monde. Continuer ?`;
  return `Ces ${toHide} catégories${of} ne seront plus téléchargées par la télé, pour tout le monde. Continuer ?`;
}

// Jeton de génération : une réponse asynchrone périmée ne doit rien peindre.
export function createGate(): { next(): number; isCurrent(token: number): boolean } {
  let n = 0;
  return { next: () => ++n, isCurrent: (t) => t === n };
}
