// Langues préférées d'un profil (profiles.settings.languages, lu par la télé : shared/versions/Versions.kt) :
// ordre des langues pour choisir la version d'un film ou d'une série et la piste audio, sous-titres, préférence 4K.
import type { SettingsDb } from './avatar';

export const LANGS: { code: string; label: string }[] = [
  { code: 'qc', label: 'Français (Québec)' }, { code: 'fr', label: 'Français (France)' }, { code: 'en', label: 'Anglais' },
  { code: 'es', label: 'Espagnol' }, { code: 'ar', label: 'Arabe' }, { code: 'it', label: 'Italien' }, { code: 'de', label: 'Allemand' },
  { code: 'pt', label: 'Portugais' },
];
export const SUBTITLES: { code: string; label: string }[] = [
  { code: 'en', label: 'Anglais' }, { code: 'fr', label: 'Français' }, { code: 'es', label: 'Espagnol' }, { code: 'ar', label: 'Arabe' }, { code: 'off', label: 'Aucun' },
];
export interface LanguagePrefs { order: string[]; subtitles: string; prefer_4k: boolean; only_mine: boolean }
export const DEFAULT_LANGUAGES: LanguagePrefs = { order: ['qc', 'fr', 'en'], subtitles: 'en', prefer_4k: false, only_mine: false };

export function languagesOf(settings: Record<string, unknown>): LanguagePrefs {
  const l = (settings.languages && typeof settings.languages === 'object' ? settings.languages : {}) as Record<string, unknown>;
  const codes = LANGS.map((x) => x.code);
  const order = Array.isArray(l.order) ? [...new Set(l.order.filter((c): c is string => typeof c === 'string' && codes.includes(c)))] : [];
  return {
    order: order.length ? order : DEFAULT_LANGUAGES.order,
    subtitles: typeof l.subtitles === 'string' && SUBTITLES.some((s) => s.code === l.subtitles) ? l.subtitles : DEFAULT_LANGUAGES.subtitles,
    prefer_4k: l.prefer_4k === true,
    only_mine: l.only_mine === true,
  };
}

/** Résumé de la ligne du profil : « QC, FR, EN · sous-titres anglais ». */
export function languagesSummary(p: LanguagePrefs): string {
  const sub = SUBTITLES.find((s) => s.code === p.subtitles)!;
  return `${p.order.map((c) => c.toUpperCase()).join(', ')} · ${p.subtitles === 'off' ? 'sans sous-titres' : `sous-titres ${sub.label.toLowerCase()}`}${p.prefer_4k ? ' · 4K' : ''}${p.only_mine ? ' · seulement ces langues' : ''}`;
}

export function moveLang(order: string[], code: string, dir: -1 | 1): string[] {
  const i = order.indexOf(code), j = i + dir;
  if (i < 0 || j < 0 || j >= order.length) return order;
  const next = [...order];
  [next[i], next[j]] = [next[j]!, next[i]!];
  return next;
}

export async function saveLanguages(db: SettingsDb, profileId: string, settings: Record<string, unknown>, prefs: LanguagePrefs): Promise<Record<string, unknown>> {
  if (!prefs.order.length) throw new Error('Garde au moins une langue.');
  const next = { ...settings, languages: { order: prefs.order, subtitles: prefs.subtitles, prefer_4k: prefs.prefer_4k, only_mine: prefs.only_mine } };
  const r = await db.from('profiles').update({ settings: next }).eq('id', profileId).select('id');
  if (r.error) throw new Error("Impossible d'enregistrer. Réessaie dans un instant.");
  if (!Array.isArray(r.data) || r.data.length !== 1) throw new Error("Ce profil n'existe plus. Recharge la page.");
  return next;
}
