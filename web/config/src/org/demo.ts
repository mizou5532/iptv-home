// Mode démo (?demo) : faux client en mémoire avec des données inventées. Aucun réseau.
import type { Db } from '../flow';
import { searchNorm } from './norm';

const THEMES = ['Sports', 'Cinéma', 'Jeunesse', 'Actualités', 'Musique', 'Découverte', 'Séries', 'Québec', 'France', 'Documentaires', 'Œuvres classiques', 'Été'];
const SUFFIX = ['HD', 'Premium', 'Plus', 'Direct', 'Extra', 'Famille', 'Archives', 'Nuit', 'Mondial', 'Régions'];

interface Cat { id: string; name: string; position: number; n: number; hg: boolean; hp: Record<string, boolean> }

export function demoDb(): { db: Db; householdId: string } {
  const profiles = [
    { id: 'p1', name: 'Toi', position: 0 }, { id: 'p2', name: 'Sœur', position: 1 }, { id: 'p3', name: 'Frère', position: 2 },
  ];
  const cats: Cat[] = Array.from({ length: 300 }, (_, i) => ({
    id: `c${i + 1}`,
    name: `${THEMES[i % THEMES.length]} ${SUFFIX[Math.floor(i / THEMES.length) % SUFFIX.length]} ${Math.floor(i / 120) + 1}`,
    position: i, n: 5 + ((i * 37) % 180), hg: i % 29 === 7, hp: {},
  }));
  let order: string[] | null = null;

  // Chaînes inventées, créées à la demande : c<N>_<k>.
  const BASES = ['Écran Nord', 'Télé Soleil', 'Canal Rive', 'Sport Arena', 'Ciné Lune', 'Mini Pop', 'Info 24', 'Zen Musique', 'Cœur Direct'];
  interface Ch { id: string; name: string; number: number; categoryId: string; categoryName: string }
  const channelsOf = (c: Cat): Ch[] => Array.from({ length: c.n }, (_, k) => ({
    id: `${c.id}_${k}`, name: `${BASES[(k + Number(c.id.slice(1))) % BASES.length]} ${k + 1}`, number: k + 1, categoryId: c.id, categoryName: c.name,
  }));
  const chById = (id: string): Ch | undefined => {
    const m = /^(c\d+)_(\d+)$/.exec(id);
    const c = m && cats.find((x) => x.id === m[1]);
    return c ? channelsOf(c)[Number(m![2])] : undefined;
  };
  const chHidG = new Set<string>(['c1_2']);
  const chHidP: Record<string, Set<string>> = { p1: new Set(['c1_4']), p2: new Set(), p3: new Set() };
  const favs: Record<string, string[]> = { p1: ['c1_0', 'c2_1', 'c8_0', 'c3_5', 'zz_9'], p2: [], p3: [] };
  interface Pl { id: string; profile: string; name: string; position: number; items: string[] }
  let plSeq = 2;
  let pls: Pl[] = [
    { id: 'pl1', profile: 'p1', name: 'Matchs', position: 0, items: ['c4_0', 'c4_1', 'c1_0'] },
    { id: 'pl2', profile: 'p1', name: 'Soirée cinéma', position: 1, items: ['c2_3'] },
  ];
  const flags = (c: Ch, p: string | null): Record<string, unknown> => ({
    id: c.id, name: c.name, number: c.number,
    favorite: p !== null && (favs[p] ?? []).includes(c.id),
    hidden_global: chHidG.has(c.id), hidden_profile: p !== null && chHidP[p]?.has(c.id) === true,
  });
  const sortPls = (p: string): Pl[] => pls.filter((x) => x.profile === p).sort((a, b) => a.position - b.position);

  const handlers: Record<string, (a: Record<string, unknown>) => unknown> = {
    org_categories: (a) => {
      const p = a.p as string | null;
      const list = order ? order.map((id) => cats.find((c) => c.id === id)!) : cats;
      return list.map((c) => ({
        id: c.id, name: c.name, provider_position: c.position,
        hidden_global: c.hg, hidden_profile: p !== null && !!c.hp[p], channel_count: c.n,
      }));
    },
    org_set_hidden: (a) => {
      const p = a.p as string | null; const hide = Boolean(a.hidden);
      for (const id of a.ids as string[]) {
        if (a.item_type === 'channel') {
          const set = p === null ? chHidG : (chHidP[p] ??= new Set());
          if (hide) set.add(id); else set.delete(id);
          continue;
        }
        const c = cats.find((x) => x.id === id); if (!c) continue;
        if (p === null) c.hg = hide; else c.hp[p] = hide;
      }
      return (a.ids as string[]).length;
    },
    org_channels: (a) => {
      const c = cats.find((x) => x.id === a.category);
      return (c ? channelsOf(c) : []).slice(Number(a.off), Number(a.off) + Number(a.lim)).map((ch) => flags(ch, a.p as string | null));
    },
    org_search: (a) => {
      const q = String(a.q);
      const out: unknown[] = [];
      for (const c of cats) {
        for (const ch of channelsOf(c)) {
          if (!searchNorm(ch.name).includes(q)) continue;
          out.push({ ...flags(ch, a.p as string | null), category_id: c.id, category_name: c.name });
          if (out.length >= Number(a.lim)) return out;
        }
      }
      return out;
    },
    org_favorites: (a) => (favs[a.p as string] ?? []).map((id) => {
      const ch = chById(id);
      if (!ch) return { id, name: null, number: null, category_id: null, state: 'n-existe-plus' };
      const p = a.p as string;
      const cat = cats.find((x) => x.id === ch.categoryId)!;
      const state = chHidG.has(id) || chHidP[p]?.has(id) ? 'masquee' : cat.hg || cat.hp[p] ? 'categorie-masquee' : 'ok';
      return { id, name: ch.name, number: ch.number, category_id: ch.categoryId, state };
    }),
    org_set_favorite: (a) => {
      const p = a.p as string; const list = (favs[p] ??= []);
      const id = String(a.channel);
      if (a.on_) { if (!list.includes(id)) list.push(id); } else favs[p] = list.filter((x) => x !== id);
      return null;
    },
    org_reorder_favorites: (a) => {
      const p = a.p as string; const given = a.ids as string[];
      favs[p] = [...given.filter((x) => (favs[p] ?? []).includes(x)), ...(favs[p] ?? []).filter((x) => !given.includes(x))];
      return null;
    },
    org_playlists: (a) => sortPls(a.p as string).map((x) => ({ id: x.id, name: x.name, position: x.position, channel_ids: x.items })),
    org_playlist_create: (a) => {
      const id = `pl${++plSeq}`;
      pls.push({ id, profile: a.p as string, name: String(a.name).trim(), position: sortPls(a.p as string).length, items: [] });
      return id;
    },
    org_playlist_rename: (a) => { const x = pls.find((y) => y.id === a.id); if (x) x.name = String(a.name).trim(); return null; },
    org_playlist_delete: (a) => { pls = pls.filter((y) => y.id !== a.id); return null; },
    org_playlists_reorder: (a) => {
      const given = a.ids as string[];
      sortPls(a.p as string).sort((x, y) => (given.indexOf(x.id) + 1 || 999) - (given.indexOf(y.id) + 1 || 999)).forEach((x, i) => { x.position = i; });
      return null;
    },
    org_playlist_set_items: (a) => { const x = pls.find((y) => y.id === a.id); if (x) x.items = [...new Set(a.channel_ids as string[])]; return null; },
    org_hide_all_except: (a) => {
      const p = a.p as string | null; const keep = new Set(a.keep as string[]);
      for (const c of cats) { const hide = !keep.has(c.id); if (p === null) c.hg = hide; else c.hp[p] = hide; }
      return cats.filter((c) => !keep.has(c.id)).length;
    },
    org_set_category_order: (a) => { order = a.ids as string[]; return null; },
  };

  const db: Db = {
    async rpc(fn, args) {
      const h = handlers[fn];
      if (!h) return { data: null, error: { message: `demo: fonction inconnue ${fn}` } };
      return { data: h((args ?? {}) as Record<string, unknown>), error: null };
    },
    from(table) { return { select: async () => ({ data: table === 'profiles' ? profiles : [], error: null }) }; },
  };
  return { db, householdId: 'demo' };
}
