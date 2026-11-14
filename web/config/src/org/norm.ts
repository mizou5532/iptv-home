// Normalisation de recherche : identique à la version Kotlin de la télé.
// Ordre : ligatures, NFD, retrait des marques, minuscules, espaces compactés, trim.
const LIGATURES: Record<string, string> = { 'œ': 'oe', 'Œ': 'OE', 'æ': 'ae', 'Æ': 'AE' };

export function searchNorm(s: string): string {
  return s
    .replace(/[œŒæÆ]/g, (c) => LIGATURES[c])
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .toLowerCase()
    .replace(/\s+/g, ' ')
    .trim();
}
