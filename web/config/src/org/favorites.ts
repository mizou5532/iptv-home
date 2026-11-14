// Logique pure des favoris (sans DOM).
import { moveCategory } from './categories';

const LABELS: Record<string, string> = {
  'categorie-masquee': 'Catégorie masquée',
  masquee: 'Masquée',
  'n-existe-plus': "N'existe plus",
};
export function stateLabel(state: string): string {
  return LABELS[state] ?? '';
}

// Déplace l'élément `from` à la position `to` (bornée) sans modifier la liste d'origine.
export function reorder(ids: string[], from: number, to: number): string[] {
  return moveCategory(ids, from, to);
}
