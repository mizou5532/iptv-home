// Thèmes de saison de « Qui regarde ? » (réglage de la maison `seasons`, lu par la télé) : même calendrier que la télé
// (apps/shared/.../account/Seasons.kt), vérifié sur les mêmes exemples (fixtures/seasons.json).
import type { HouseholdDb } from './look';

export type SeasonId = 'noel' | 'nouvel_an' | 'halloween' | 'valentin' | 'ramadan' | 'aid_fitr' | 'aid_adha' | 'fete_nationale';
export const SEASONS: { id: SeasonId; label: string; detail: string }[] = [
  { id: 'noel', label: 'Noël', detail: 'Neige qui tombe et guirlande lumineuse' },
  { id: 'nouvel_an', label: 'Nouvel An', detail: 'Confettis et étincelles dorées' },
  { id: 'halloween', label: 'Halloween', detail: 'Citrouilles et chauves-souris' },
  { id: 'valentin', label: 'Saint-Valentin', detail: 'Cœurs qui montent' },
  { id: 'ramadan', label: 'Ramadan', detail: 'Croissant de lune, étoiles et lanternes' },
  { id: 'aid_fitr', label: 'Aïd el-Fitr', detail: 'Lanternes et étincelles dorées' },
  { id: 'aid_adha', label: 'Aïd el-Adha', detail: 'Lanternes et étincelles dorées' },
  { id: 'fete_nationale', label: 'Fête nationale du Québec', detail: 'Confettis bleus et blancs, fleurs de lys' },
];
export interface SeasonSettings { on: boolean; off: SeasonId[]; force: SeasonId | null }

/** Fêtes musulmanes : dates estimées, à un jour près selon l'observation de la lune (même table que la télé). */
const ISLAMIC: { ramadan: string; fitr: string; adha: string }[] = [
  { ramadan: '2026-02-18', fitr: '2026-03-20', adha: '2026-05-27' },
  { ramadan: '2027-02-08', fitr: '2027-03-10', adha: '2027-05-16' },
  { ramadan: '2028-01-28', fitr: '2028-02-26', adha: '2028-05-05' },
  { ramadan: '2029-01-16', fitr: '2029-02-14', adha: '2029-04-24' },
  { ramadan: '2030-01-06', fitr: '2030-02-05', adha: '2030-04-13' },
];

const iso = (d: Date) => `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}-${String(d.getUTCDate()).padStart(2, '0')}`;
const addDays = (s: string, n: number) => { const d = new Date(`${s}T00:00:00Z`); d.setUTCDate(d.getUTCDate() + n); return iso(d); };

/** Fête du jour « AAAA-MM-JJ » (priorité : Aïd, Ramadan, puis fêtes à date fixe) ; null sinon. */
export function seasonAt(day: string): SeasonId | null {
  const y = Number(day.slice(0, 4)), m = Number(day.slice(5, 7)), d = Number(day.slice(8, 10));
  const i = ISLAMIC.find((x) => [x.ramadan, x.fitr, x.adha].some((s) => Number(s.slice(0, 4)) === y));
  if (i) {
    if (day >= i.fitr && day <= addDays(i.fitr, 2)) return 'aid_fitr';
    if (day >= i.adha && day <= addDays(i.adha, 3)) return 'aid_adha';
    if (day >= i.ramadan && day < i.fitr) return 'ramadan';
  }
  if ((m === 12 && d === 31) || (m === 1 && d <= 3)) return 'nouvel_an';
  if (m === 12 && d <= 30) return 'noel';
  if (m === 10 && d >= 24) return 'halloween';
  if (m === 2 && d >= 12 && d <= 14) return 'valentin';
  if (m === 6 && d >= 22 && d <= 24) return 'fete_nationale';
  return null;
}

/** Prochaine période (ou celle en cours) de chaque fête, dans l'année qui vient. */
export function nextWindows(today: string): Partial<Record<SeasonId, { from: string; to: string }>> {
  const out: Partial<Record<SeasonId, { from: string; to: string }>> = {};
  let open: SeasonId | null = null;
  for (let n = 0; n < 400; n++) {
    const day = addDays(today, n);
    const s = seasonAt(day);
    if (open && s !== open) open = null;
    if (s && !out[s]) { out[s] = { from: day, to: day }; open = s; }
    else if (s && open === s) out[s]!.to = day;
  }
  return out;
}

const MONTHS = ['janv.', 'févr.', 'mars', 'avr.', 'mai', 'juin', 'juil.', 'août', 'sept.', 'oct.', 'nov.', 'déc.'];
/** « du 1 au 30 déc. », « le 24 juin », « du 31 déc. au 3 janv. ». */
export function windowLabel(w: { from: string; to: string }): string {
  const f = (s: string) => `${Number(s.slice(8, 10))} ${MONTHS[Number(s.slice(5, 7)) - 1]}`;
  if (w.from === w.to) return `le ${f(w.from)}`;
  return w.from.slice(5, 7) === w.to.slice(5, 7) ? `du ${Number(w.from.slice(8, 10))} au ${f(w.to)}` : `du ${f(w.from)} au ${f(w.to)}`;
}

export function seasonsOf(settings: Record<string, unknown>): SeasonSettings {
  const s = (settings.seasons && typeof settings.seasons === 'object' ? settings.seasons : {}) as Record<string, unknown>;
  const ids = SEASONS.map((x) => x.id) as string[];
  return {
    on: s.on !== false,
    off: Array.isArray(s.off) ? (s.off.filter((x) => typeof x === 'string' && ids.includes(x)) as SeasonId[]) : [],
    force: typeof s.force === 'string' && ids.includes(s.force) ? (s.force as SeasonId) : null,
  };
}

/** Seul le propriétaire du compte peut changer ce réglage de la maison. */
export async function saveSeasons(db: HouseholdDb, householdId: string, settings: Record<string, unknown>, next: SeasonSettings): Promise<Record<string, unknown>> {
  const merged = { ...settings, seasons: { on: next.on, off: next.off, force: next.force } };
  const r = await db.from('households').update({ settings: merged }).eq('id', householdId).select('id');
  if (r.error) throw new Error("Impossible d'enregistrer. Réessaie dans un instant.");
  if (!Array.isArray(r.data) || r.data.length !== 1) throw new Error('Seul le propriétaire du compte peut changer ce réglage.');
  return merged;
}
