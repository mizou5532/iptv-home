// Logique pure des playlists (sans DOM).
export const NAME_MAX = 40;

export function validName(s: string): string | null {
  const t = s.trim();
  return t.length >= 1 && t.length <= NAME_MAX ? t : null;
}

// Ajoute une chaîne en fin de liste, sans doublon.
export function addTo(items: string[], id: string): string[] {
  return items.includes(id) ? items.slice() : [...items, id];
}
