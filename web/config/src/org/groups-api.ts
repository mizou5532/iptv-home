import type { Db } from '../flow';
import type { GroupSnapshot, GroupSource, ManualGroup } from './groups';
export interface CopySource extends GroupSource {
  categoryName: string;
}
export interface GroupReplacement {
  removeIds: string[];
  groups: ManualGroup[];
}
interface Cache {
  snapshot?: GroupSnapshot;
  sources?: CopySource[];
}
const caches = new WeakMap<Db, Map<string, Cache>>();
export function groupCache(db: Db, h: string): Cache {
  let households = caches.get(db);
  if (!households) {
    households = new Map();
    caches.set(db, households);
  }
  let cache = households.get(h);
  if (!cache) {
    cache = {};
    households.set(h, cache);
  }
  return cache;
}
async function call(db: Db, fn: string, args: object): Promise<any> {
  try {
    const r = await db.rpc(fn, args);
    if (r.error) {
      if (r.error.message.includes('conflit-revision'))
        throw new Error('Les corrections ont changé. Recharger avant de recommencer.');
      throw new Error('Impossible de terminer. Réessayer.');
    }
    return r.data;
  } catch (e) {
    if (e instanceof Error && e.message.startsWith('Les corrections')) throw e;
    throw new Error('Impossible de terminer. Réessayer.');
  }
}
export async function loadGroupSnapshot(db: Db, h: string): Promise<GroupSnapshot> {
  for (let attempt = 0; attempt < 3; attempt++) {
    let cursor: string | null = null;
    let rev: number | undefined;
    const groups: ManualGroup[] = [];
    const seen = new Set<string>();
    let changed = false;
    do {
      const page = await call(db, 'live_group_page', {
        h,
        after_id: cursor,
        lim: 500,
      });
      if (page.rules_version !== 1 || !Number.isSafeInteger(page.rev) || !Array.isArray(page.groups))
        throw new Error('Copie incomplète ou incompatible. Réessayer.');
      if (rev !== undefined && rev !== page.rev) {
        changed = true;
        break;
      }
      rev = page.rev;
      groups.push(
        ...page.groups.map((g: any) => ({
          id: g.id,
          title: g.title,
          sourceIds: g.source_ids,
          blockedAutoKeys: g.blocked_auto_keys,
        })),
      );
      cursor = page.next_id;
      if (cursor && seen.has(cursor)) throw new Error('Copie incomplète. Réessayer.');
      if (cursor) seen.add(cursor);
    } while (cursor);
    if (!changed) {
      const snapshot = { rev: rev!, rulesVersion: 1, groups };
      groupCache(db, h).snapshot = snapshot;
      return snapshot;
    }
  }
  throw new Error('La copie change encore. Réessayer.');
}
export async function loadGroupSources(db: Db, h: string): Promise<CopySource[]> {
  for (let attempt = 0; attempt < 3; attempt++) {
    let cursor: { position: number; id: string } | null = null;
    let rev: number | undefined;
    const sources: CopySource[] = [];
    const seen = new Set<string>();
    let changed = false;
    do {
      const page = await call(db, 'live_catalog_page', {
        h,
        after_position: cursor?.position ?? null,
        after_id: cursor?.id ?? null,
        lim: 1000,
      });
      if (!Number.isSafeInteger(page.catalog_rev) || !Array.isArray(page.channels))
        throw new Error('Copie incomplète. Réessayer.');
      if (rev !== undefined && rev !== page.catalog_rev) {
        changed = true;
        break;
      }
      rev = page.catalog_rev;
      sources.push(
        ...page.channels.map((c: any) => ({
          id: c.id,
          name: c.name,
          categoryId: c.category_id,
          categoryName: c.category_name ?? '',
          position: c.position,
        })),
      );
      cursor = page.next_cursor;
      const key = JSON.stringify(cursor);
      if (cursor && seen.has(key)) throw new Error('Copie incomplète. Réessayer.');
      if (cursor) seen.add(key);
    } while (cursor);
    if (!changed) {
      groupCache(db, h).sources = sources;
      return sources;
    }
  }
  throw new Error('La copie change encore. Réessayer.');
}
export async function saveGroups(
  db: Db,
  h: string,
  expectedRev: number,
  replacement: GroupReplacement,
): Promise<number> {
  const result = await call(db, 'live_group_edit', {
    h,
    expected_rev: expectedRev,
    replacement: {
      remove_ids: replacement.removeIds,
      groups: replacement.groups.map((g) => ({
        id: g.id,
        title: g.title,
        source_ids: g.sourceIds,
        blocked_auto_keys: g.blockedAutoKeys,
      })),
    },
  });
  return result.rev;
}
export async function setGroupCopies(db: Db, p: string, enabled: boolean): Promise<void> {
  await call(db, 'set_group_copies', { p, enabled });
}
