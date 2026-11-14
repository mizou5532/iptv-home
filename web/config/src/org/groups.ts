import { searchNorm } from './norm';

export interface GroupSource { id: string; categoryId: string; name: string; position: number }
export interface ManualGroup { id: string; title: string; sourceIds: string[]; blockedAutoKeys: string[] }
export interface GroupSnapshot { rev: number; rulesVersion: number; groups: ManualGroup[] }
export interface LiveGroup { id: string; title: string; sources: GroupSource[] }

// Seuls les suffixes techniques entiers, a la fin, sont retires.
const suffix = /(?:\s+|\s*[-|/]\s*)(?:hd|fhd|uhd|4k|sd)$|\s*\[(?:hd|fhd|uhd|4k|sd)\]$|\s*\((?:hd|fhd|uhd|4k|sd)\)$/iu;
function baseTitle(name: string): string {
  let title = name.trim();
  while (true) {
    const stripped = title.replace(suffix, '').trim();
    if (stripped === title) return title;
    title = stripped;
  }
}
export function autoKey(name: string): string | null {
  const key = searchNorm(baseTitle(name));
  return /[a-z]/.test(key) && !/^(?:hd|fhd|uhd|4k|sd)$/.test(key) ? key : null;
}
function compareId(a: string, b: string): number { return a < b ? -1 : a > b ? 1 : 0; }
function compareSources(a: GroupSource, b: GroupSource): number { return a.position - b.position || compareId(a.id, b.id); }

/** Pur : aucune entree modifiee. Indexation lineaire ; seul le tri final est O(n log n). */
export function buildGroups(sources: GroupSource[], snapshot: GroupSnapshot): LiveGroup[] {
  if (snapshot.rulesVersion !== 1) throw new Error('Version de regroupement inconnue');
  const byId = new Map(sources.map(source => [source.id, source]));
  const claimed = new Set<string>();
  const blocked = new Set(snapshot.groups.flatMap(group => group.blockedAutoKeys));
  const result: LiveGroup[] = [];
  for (const manual of [...snapshot.groups].sort((a, b) => compareId(a.id, b.id))) {
    const members: GroupSource[] = [];
    for (const id of manual.sourceIds) {
      const source = byId.get(id);
      if (source && !claimed.has(id)) { members.push(source); claimed.add(id); }
    }
    if (members.length) result.push({ id: `manual:${manual.id}`, title: manual.title, sources: members });
  }
  const automatic = new Map<string, LiveGroup>();
  for (const source of sources) {
    if (claimed.has(source.id)) continue;
    const key = autoKey(source.name);
    if (key === null || blocked.has(key)) {
      result.push({ id: `source:${source.id}`, title: source.name, sources: [source] });
    } else {
      let group = automatic.get(key);
      if (!group) {
        group = { id: `auto:v1:${key}`, title: '', sources: [] };
        automatic.set(key, group);
      }
      group.sources.push(source);
    }
  }
  for (const group of automatic.values()) {
    group.sources.sort(compareSources);
    group.title = baseTitle(group.sources[0].name);
    result.push(group);
  }
  for (const group of result) group.sources.sort(compareSources);
  return result.sort((a, b) => a.sources[0].position - b.sources[0].position || compareId(a.id, b.id));
}
