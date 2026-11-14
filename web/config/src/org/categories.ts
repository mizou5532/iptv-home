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

export function confirmText(scopeIsEveryone: boolean, toHide: number): string | null {
  if (!scopeIsEveryone) return null;
  if (toHide === 1) return 'Cette catégorie ne sera plus téléchargée par la télé, pour tout le monde. Continuer ?';
  return `Ces ${toHide} catégories ne seront plus téléchargées par la télé, pour tout le monde. Continuer ?`;
}

// Jeton de génération : une réponse asynchrone périmée ne doit rien peindre.
export function createGate(): { next(): number; isCurrent(token: number): boolean } {
  let n = 0;
  return { next: () => ++n, isCurrent: (t) => t === n };
}
