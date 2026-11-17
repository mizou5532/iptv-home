// Réglages de l'onglet Sport d'un profil (page iPhone, « Réglages du sport ») : profiles.settings.sport_view.
// La télé les lit au lancement (SportView côté Kotlin : mêmes noms de champs).
import type { SettingsDb } from './avatar';
import { teamsOf, type SportTeam } from './teams';

export type SportKey = 'hockey' | 'football' | 'basketball' | 'soccer';
export type AfficheTheme = 'equipes' | 'sombre' | 'profil';
export type Featured = 'mes_equipes' | 'direct';
export type LangKey = 'qc' | 'fr' | 'us' | 'ca';

export interface SportView {
  theme: AfficheTheme;
  order: SportKey[];
  hidden: SportKey[];
  mine_first: boolean;
  featured: Featured;
  hints: boolean;
  /** Ordre des langues des chaînes proposées pour un match (télé : regionOrder). */
  lang_order: LangKey[];
}

export const LANGS: { id: LangKey; label: string; hint: string }[] = [
  { id: 'qc', label: 'Français (Québec)', hint: 'RDS, TVA Sports…' },
  { id: 'fr', label: 'Français (France)', hint: 'Canal+, beIN Sports FR…' },
  { id: 'us', label: 'Anglais (États-Unis)', hint: 'ESPN, TNT, chaînes d’équipes…' },
  { id: 'ca', label: 'Anglais (Canada)', hint: 'TSN, Sportsnet…' },
];
const LANG_IDS = LANGS.map((l) => l.id);
const isLang = (v: unknown): v is LangKey => typeof v === 'string' && (LANG_IDS as string[]).includes(v);

export const SPORTS: { id: SportKey; label: string; icon: string }[] = [
  { id: 'hockey', label: 'Hockey (LNH)', icon: '🏒' },
  { id: 'football', label: 'Football (NFL)', icon: '🏈' },
  { id: 'basketball', label: 'Basketball (NBA)', icon: '🏀' },
  { id: 'soccer', label: 'Soccer', icon: '⚽' },
];
export const AFFICHE_THEMES: { id: AfficheTheme; label: string; hint: string }[] = [
  { id: 'equipes', label: 'Couleurs des équipes', hint: 'Le fond prend les couleurs des deux équipes du match.' },
  { id: 'sombre', label: 'Sombre', hint: 'Fond sombre et sobre, les logos ressortent.' },
  { id: 'profil', label: 'Ma couleur', hint: 'Le fond prend la couleur principale du profil.' },
];
export const FEATURED: { id: Featured; label: string }[] = [
  { id: 'mes_equipes', label: 'Le match de mes équipes' },
  { id: 'direct', label: 'Un match en direct' },
];

const IDS = SPORTS.map((s) => s.id);
const isSport = (v: unknown): v is SportKey => typeof v === 'string' && (IDS as string[]).includes(v);

/** Réglages du profil, complétés : ordre toujours complet (les sports oubliés à la fin), valeurs inconnues ignorées. */
export function sportViewOf(settings: Record<string, unknown>): SportView {
  const raw = (settings.sport_view ?? {}) as Partial<Record<keyof SportView, unknown>>;
  const order = Array.isArray(raw.order) ? raw.order.filter(isSport) : [];
  return {
    theme: AFFICHE_THEMES.some((t) => t.id === raw.theme) ? raw.theme as AfficheTheme : 'equipes',
    order: [...new Set([...order, ...IDS])],
    hidden: Array.isArray(raw.hidden) ? [...new Set(raw.hidden.filter(isSport))] : [],
    mine_first: raw.mine_first !== false,
    featured: raw.featured === 'direct' ? 'direct' : 'mes_equipes',
    hints: raw.hints !== false,
    lang_order: [...new Set([...(Array.isArray(raw.lang_order) ? raw.lang_order.filter(isLang) : []), ...LANG_IDS])],
  };
}

/** Monte (delta -1) ou descend (+1) un sport (ou une langue) dans l'ordre. */
export function moveSport<T extends string>(order: T[], id: T, delta: -1 | 1): T[] {
  const i = order.indexOf(id), j = i + delta;
  if (i < 0 || j < 0 || j >= order.length) return order;
  const next = [...order];
  [next[i], next[j]] = [next[j]!, next[i]!];
  return next;
}

/** Monte ou descend une équipe favorite (son rang départage ses matchs à l'affiche). */
export function moveTeam(teams: SportTeam[], index: number, delta: -1 | 1): SportTeam[] {
  const j = index + delta;
  if (index < 0 || index >= teams.length || j < 0 || j >= teams.length) return teams;
  const next = [...teams];
  [next[index], next[j]] = [next[j]!, next[index]!];
  return next;
}

/** Sports où joue une équipe favorite : jamais masqués (leurs matchs restent toujours). */
export function sportsOfTeams(settings: Record<string, unknown>): SportKey[] {
  const map: Record<string, SportKey> = { nhl: 'hockey', nfl: 'football', nba: 'basketball' };
  return [...new Set(teamsOf(settings).teams.map((t) => map[t.league] ?? 'soccer'))];
}

/** Enregistre les réglages (les autres réglages du profil sont gardés) ; renvoie les nouveaux réglages. */
export async function saveSportView(db: SettingsDb, profileId: string, settings: Record<string, unknown>, view: SportView): Promise<Record<string, unknown>> {
  const next = { ...settings, sport_view: view };
  const r = await db.from('profiles').update({ settings: next }).eq('id', profileId).select('id');
  if (r.error) throw new Error("Impossible d'enregistrer. Réessaie dans un instant.");
  if (!Array.isArray(r.data) || r.data.length !== 1) throw new Error("Ce profil n'existe plus. Recharge la page.");
  return next;
}
