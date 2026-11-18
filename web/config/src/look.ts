// Personnalisation : couleur principale et fond de chaque profil (colonnes profiles.color et profiles.theme, déjà lues
// par la télé), et thème de l'écran « Qui regarde ? » pour toute la maison (households.settings.welcome_theme).
import type { DbResult } from './flow';
import type { ProfilesDb } from './profiles';

export type Background = 'sombre' | 'clair';
export const BACKGROUNDS: { id: Background; label: string }[] = [{ id: 'sombre', label: 'Sombre' }, { id: 'clair', label: 'Clair' }];

/** Couleurs proposées (la télé accepte n'importe quelle couleur « #RRGGBB »). */
export const PALETTE: { color: string; name: string }[] = [
  { color: '#8FB4FF', name: 'Bleu ciel' }, { color: '#2F6BFF', name: 'Bleu roi' }, { color: '#36C2B4', name: 'Turquoise' },
  { color: '#4CD964', name: 'Vert' }, { color: '#C7F464', name: 'Vert lime' }, { color: '#FFC46B', name: 'Or' },
  { color: '#FF8A3D', name: 'Orange' }, { color: '#FF4D5E', name: 'Rouge' }, { color: '#F4B6C8', name: 'Rose' },
  { color: '#C084FC', name: 'Violet' }, { color: '#E5E7EB', name: 'Argent' }, { color: '#AF1E2D', name: 'Rouge Canadien' },
];

export function isColor(v: string): boolean { return /^#[0-9A-Fa-f]{6}$/.test(v) }
export function backgroundOf(theme: string | undefined): Background { return theme === 'clair' ? 'clair' : 'sombre' }

/** Texte lisible sur une couleur (noir sur clair, blanc sur foncé), comme la télé. */
export function inkOn(color: string): string {
  const n = parseInt(color.slice(1), 16);
  const [r, g, b] = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((c) => { const s = c / 255; return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4; });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.45 ? '#0B0F17' : '#FFFFFF';
}

/** Clarté perçue d'une couleur « #RRGGBB » (0 à 1), même calcul que la télé. */
export function luminance(color: string): number {
  const n = parseInt(color.slice(1), 16);
  const [r, g, b] = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((c) => { const s = c / 255; return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4; });
  return 0.2126 * r! + 0.7152 * g! + 0.0722 * b!;
}

// ---- Thèmes complets (profiles.settings.look, lu par la télé : theme/IptvTheme.kt, même liste) ----
export type ThemePreset = 'nuit' | 'doux' | 'minuit' | 'foret' | 'prune' | 'charbon' | 'sable' | 'ciel' | 'perso';
/** Fond de chaque thème ; null = teinte de la couleur du profil (« Doux »). */
export const THEMES: { id: Exclude<ThemePreset, 'perso'>; label: string; bg: string | null; light: boolean }[] = [
  { id: 'nuit', label: 'Nuit', bg: '#0B0F17', light: false },
  { id: 'doux', label: 'Doux', bg: null, light: true },
  { id: 'minuit', label: 'Minuit', bg: '#0B1A33', light: false },
  { id: 'foret', label: 'Forêt', bg: '#0E1F18', light: false },
  { id: 'prune', label: 'Prune', bg: '#1F1226', light: false },
  { id: 'charbon', label: 'Charbon', bg: '#141414', light: false },
  { id: 'sable', label: 'Sable', bg: '#F3ECDF', light: true },
  { id: 'ciel', label: 'Ciel', bg: '#EAF2FF', light: true },
];
export type Corners = 'carres' | 'arrondis' | 'ronds';
export const CORNERS: { id: Corners; label: string; px: number }[] = [
  { id: 'carres', label: 'Carrés', px: 3 }, { id: 'arrondis', label: 'Arrondis', px: 7 }, { id: 'ronds', label: 'Très ronds', px: 12 },
];
export interface ThemeLook { preset?: ThemePreset; background?: string; corners?: Corners }

/** Thème lu dans les réglages ; sans réglage, celui qui correspond à l'ancien fond (colonne theme). */
export function lookOf(settings: Record<string, unknown>, theme: string | undefined): Required<Pick<ThemeLook, 'preset' | 'corners'>> & { background?: string } {
  const l = (settings.look && typeof settings.look === 'object' ? settings.look : {}) as ThemeLook;
  const valid = l.preset === 'perso' ? !!l.background && isColor(l.background) : THEMES.some((t) => t.id === l.preset);
  const preset: ThemePreset = valid ? l.preset! : backgroundOf(theme) === 'clair' ? 'doux' : 'nuit';
  const corners: Corners = CORNERS.some((c) => c.id === l.corners) ? l.corners! : 'arrondis';
  return preset === 'perso' ? { preset, corners, background: l.background!.toUpperCase() } : { preset, corners };
}

/** Couleurs d'aperçu d'un thème (fond, panneaux, texte), comme la télé les calcule. */
export function themeColors(look: ThemeLook, accent: string): { bg: string; surface: string; text: string; light: boolean } {
  const base = look.preset === 'perso' && look.background && isColor(look.background)
    ? { bg: look.background, light: luminance(look.background) > 0.4 }
    : THEMES.find((t) => t.id === look.preset) ?? THEMES[0]!;
  const bg = base.bg ?? `color-mix(in srgb, ${accent} 12%, white)`;
  if (!base.light) return { bg, surface: 'rgba(255,255,255,.08)', text: '#F2F4F8', light: false };
  const nearWhite = base.bg !== null && luminance(base.bg) > 0.92;
  return { bg, surface: nearWhite ? 'rgba(0,0,0,.05)' : '#FFFFFF', text: '#2B1D26', light: true };
}

/** Enregistre le thème complet ; la colonne theme suit (clair ou sombre) pour les écrans qui ne lisent qu'elle. */
export async function setThemeLook(db: ProfilesDb, profileId: string, settings: Record<string, unknown>, look: ThemeLook, accent: string): Promise<Record<string, unknown>> {
  if (look.preset === 'perso' && !(look.background && isColor(look.background))) throw new Error('Couleur de fond invalide.');
  const next = { ...settings, look: { preset: look.preset, corners: look.corners ?? 'arrondis', ...(look.preset === 'perso' ? { background: look.background!.toUpperCase() } : {}) } };
  const theme: Background = themeColors(look, accent).light ? 'clair' : 'sombre';
  const r = await db.from('profiles').update({ settings: next, theme }).eq('id', profileId).select('id');
  if (r.error) throw new Error("Impossible d'enregistrer. Réessaie dans un instant.");
  if (!Array.isArray(r.data) || r.data.length !== 1) throw new Error("Ce profil n'existe plus. Recharge la page.");
  return next;
}

/** Enregistre la couleur et/ou le fond d'un profil. */
export async function setProfileLook(db: ProfilesDb, profileId: string, look: { color?: string; theme?: Background }): Promise<void> {
  const values: Record<string, string> = {};
  if (look.color !== undefined) { if (!isColor(look.color)) throw new Error('Couleur invalide.'); values.color = look.color.toUpperCase(); }
  if (look.theme !== undefined) { if (!BACKGROUNDS.some((b) => b.id === look.theme)) throw new Error('Fond inconnu.'); values.theme = look.theme; }
  const r = await db.from('profiles').update(values).eq('id', profileId).select('id');
  if (r.error) throw new Error("Impossible d'enregistrer. Réessaie dans un instant.");
  if (!Array.isArray(r.data) || r.data.length !== 1) throw new Error("Ce profil n'existe plus. Recharge la page.");
}

// ---- Écran « Qui regarde ? » (toute la maison) ----
export type WelcomeTheme = 'doux' | 'doux_nuit' | 'aurore';
export const WELCOME_THEMES: { id: WelcomeTheme; label: string; detail: string }[] = [
  { id: 'doux', label: 'Doux', detail: 'Clair et pastel, formes qui flottent' },
  { id: 'doux_nuit', label: 'Doux nuit', detail: 'Le même que Doux, en sombre' },
  { id: 'aurore', label: 'Aurore', detail: 'Nuit, halos aux couleurs des profils' },
];
/** Thème enregistré ; une valeur inconnue (ancienne ou abîmée) donne « Doux », comme sur la télé. */
export function welcomeOf(settings: Record<string, unknown>): WelcomeTheme {
  const t = settings.welcome_theme;
  return WELCOME_THEMES.some((w) => w.id === t) ? (t as WelcomeTheme) : 'doux';
}

export interface HouseholdDb {
  from(table: string): {
    select(columns: string): PromiseLike<DbResult>;
    update(values: object): { eq(column: string, value: string): { select(columns: string): PromiseLike<DbResult> } };
  };
}
export async function loadHouseholdSettings(db: HouseholdDb, householdId: string): Promise<Record<string, unknown>> {
  const r = await db.from('households').select('id, settings');
  if (r.error) throw new Error('Impossible de lire les réglages de la maison.');
  const row = ((r.data ?? []) as { id: string; settings?: unknown }[]).find((h) => h.id === householdId);
  const s = row?.settings;
  return s && typeof s === 'object' && !Array.isArray(s) ? (s as Record<string, unknown>) : {};
}
/** Seul le propriétaire du compte peut changer le thème de l'écran d'accueil. */
export async function setWelcomeTheme(db: HouseholdDb, householdId: string, settings: Record<string, unknown>, theme: WelcomeTheme): Promise<Record<string, unknown>> {
  if (!WELCOME_THEMES.some((w) => w.id === theme)) throw new Error('Thème inconnu.');
  const next = { ...settings, welcome_theme: theme };
  const r = await db.from('households').update({ settings: next }).eq('id', householdId).select('id');
  if (r.error) throw new Error("Impossible d'enregistrer. Réessaie dans un instant.");
  if (!Array.isArray(r.data) || r.data.length !== 1) throw new Error('Seul le propriétaire du compte peut changer ce thème.');
  return next;
}
