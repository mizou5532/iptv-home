// Versions séparées à la main sur la télé (« Ce n'est pas le même titre ») : households.settings.version_splits,
// liste « movie:123 » / « series:9 ». La page les montre dans Réglages et peut les réunir (set_version_split).
import type { Db } from './flow';
import { titlesByIds } from './mediaview';

export interface Split { kind: 'movie' | 'series'; id: string }

export function splitsOf(settings: Record<string, unknown>): Split[] {
  const list = Array.isArray(settings.version_splits) ? settings.version_splits : [];
  const out: Split[] = [];
  for (const v of list) {
    const m = typeof v === 'string' ? /^(movie|series):([0-9A-Za-z_-]{1,40})$/.exec(v) : null;
    if (m && !out.some((s) => s.kind === m[1] && s.id === m[2])) out.push({ kind: m[1] as Split['kind'], id: m[2]! });
  }
  return out;
}

export async function reunite(db: Db, householdId: string, s: Split): Promise<void> {
  const r = await db.rpc('set_version_split', { h: householdId, kind: s.kind, item_id: s.id, split: false });
  if (r.error) throw new Error('Impossible de réunir. Réessaie dans un instant.');
}

/** Noms des titres séparés (catalogue publié par la télé) ; repli « Film n° 123 ». */
export async function splitNames(db: Db, profileId: string | null, splits: Split[]): Promise<Map<string, string>> {
  const names = new Map<string, string>();
  if (profileId) for (const kind of ['movie', 'series'] as const) {
    const ids = splits.filter((s) => s.kind === kind).map((s) => s.id);
    try { (await titlesByIds(db, profileId, kind === 'movie' ? 'movies' : 'series', ids)).forEach((t, id) => { if (t.name) names.set(`${kind}:${id}`, t.name); }); } catch { /* repli */ }
  }
  for (const s of splits) if (!names.has(`${s.kind}:${s.id}`)) names.set(`${s.kind}:${s.id}`, `${s.kind === 'movie' ? 'Film' : 'Série'} n° ${s.id}`);
  return names;
}
