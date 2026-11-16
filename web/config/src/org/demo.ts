// Mode démo (?demo) : faux client en mémoire avec des données inventées. Aucun réseau.
import type { Db } from '../flow';
import { searchNorm } from './norm';

const THEMES = ['Sports', 'Cinéma', 'Jeunesse', 'Actualités', 'Musique', 'Découverte', 'Séries', 'Québec', 'France', 'Documentaires', 'Œuvres classiques', 'Été'];
const SUFFIX = ['HD', 'Premium', 'Plus', 'Direct', 'Extra', 'Famille', 'Archives', 'Nuit', 'Mondial', 'Régions'];

interface Cat { id: string; name: string; position: number; n: number; hg: boolean; hp: Record<string, boolean> }

export function demoDb(): { db: Db; householdId: string } {
  const profiles = [
    { id: 'p1', name: 'Toi', position: 0, home: 'direct', color: '#214837', settings: { avatar: false } }, { id: 'p2', name: 'Sœur', position: 1, home: 'direct', color: '#C8D4BF', settings: { avatar: false } }, { id: 'p3', name: 'Frère', position: 2, home: 'films', color: '#CCB88E', settings: { avatar: false } },
  ];
  const cats: Cat[] = Array.from({ length: 300 }, (_, i) => ({
    id: `c${i + 1}`,
    name: `${THEMES[i % THEMES.length]} ${SUFFIX[Math.floor(i / THEMES.length) % SUFFIX.length]} ${Math.floor(i / 120) + 1}`,
    position: i, n: 5 + ((i * 37) % 180), hg: i % 29 === 7, hp: {},
  }));
  let order: string[] | null = null;
  // Catégories de séries et de films inventées (même forme que org_series_categories / org_movie_categories).
  interface Shelf { cats: { id: string; name: string; position: number; hg: boolean; hp: Record<string, boolean> }[]; orders: Map<string | null, string[]> }
  const shelf = (prefix: string, names: string[], hiddenAt: number): Shelf => ({
    cats: names.map((name, i) => ({ id: `${prefix}${i + 1}`, name, position: i, hg: i === hiddenAt, hp: {} })), orders: new Map(),
  });
  const scats = shelf('s', ['Drames', 'Comédies', 'Policier', 'Jeunesse', 'Documentaires', 'Science-fiction', 'Séries québécoises', 'Animation', 'Thriller', 'Histoire'], 8);
  const mcats = shelf('m', ['Nouveautés', 'Action', 'Comédies', 'Films québécois', 'Famille', 'Animation', 'Drames', 'Science-fiction', 'Horreur', 'Documentaires', 'Classiques', 'Romance'], 8);
  const shelfHandlers = (name: string, s: Shelf): Record<string, (a: Record<string, unknown>) => unknown> => ({
    [`org_${name}_categories`]: (a) => {
      const p = a.p as string | null;
      const order = s.orders.get(p) ?? s.orders.get(null);
      const list = order ? [...s.cats].sort((a, b) => {
        const ai = order.indexOf(a.id); const bi = order.indexOf(b.id);
        return (ai < 0 ? Infinity : ai) - (bi < 0 ? Infinity : bi) || a.position - b.position;
      }) : s.cats;
      return list.map((c) => ({ id: c.id, name: c.name, provider_position: c.position, hidden_global: c.hg, hidden_profile: p !== null && !!c.hp[p] }));
    },
    [`org_${name}_hide_all_except`]: (a) => {
      const p = a.p as string | null; const keep = new Set(a.keep as string[]);
      for (const c of s.cats) { const hide = !keep.has(c.id); if (p === null) c.hg = hide; else c.hp[p] = hide; }
      return s.cats.filter((c) => !keep.has(c.id)).length;
    },
    [`org_set_${name}_category_order`]: (a) => { s.orders.set(a.p as string | null, [...new Set(a.ids as string[])]); return null; },
  });

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
    { id: 'pl1', profile: 'p1', name: 'Matchs', position: 0, items: ['c4_0', 'c4_1', 'c1_0', 'c8_1', 'zz_9'] },
    { id: 'pl2', profile: 'p1', name: 'Soirée cinéma', position: 1, items: ['c2_3'] },
  ];
  const flags = (c: Ch, p: string | null): Record<string, unknown> => ({
    id: c.id, name: c.name, number: c.number,
    favorite: p !== null && (favs[p] ?? []).includes(c.id),
    hidden_global: chHidG.has(c.id), hidden_profile: p !== null && chHidP[p]?.has(c.id) === true,
  });
  const sortPls = (p: string): Pl[] => pls.filter((x) => x.profile === p).sort((a, b) => a.position - b.position);

  let groupRev = 0;
  let corrections: {id:string;title:string;source_ids:string[];blocked_auto_keys:string[]}[] = [];
  const groupCopies: Record<string, boolean> = {};
  const catalog = cats.flatMap(c => channelsOf(c)).map((c,position)=>({id:c.id,name:c.name,category_id:c.categoryId,category_name:c.categoryName,number:c.number,position}));
  let playbackEnabled = false;
  let recoveryWaiting = true;
  const handlers: Record<string, (a: Record<string, unknown>) => unknown> = {
    set_device_settings: () => null,
    playback_admin_state: () => ({enabled:playbackEnabled,slot:playbackEnabled?{generation:'demo-generation',state:recoveryWaiting?'stopping':'empty',device_name:'Télé démo',label:'Chaîne fictive',stale:true}:null,requests:playbackEnabled&&recoveryWaiting?[{id:'demo-request',generation:'demo-generation',device_name:'Ordinateur démo',label:'Chaîne fictive',state:'waiting',expires_at:new Date(Date.now()+30000).toISOString()}]:[]}),
    playback_enable: () => {playbackEnabled=true;return null;},
    playback_confirm_physical_stop: (a) => {if(a.request_id!=='demo-request'||!playbackEnabled||!recoveryWaiting)throw new Error('demande-invalide');recoveryWaiting=false;return null;},
    live_group_page: (a) => {
      const rest = corrections.filter(g => a.after_id == null || g.id > String(a.after_id)).sort((a,b)=>a.id.localeCompare(b.id));
      const page = rest.slice(0,Number(a.lim));
      return {rev:groupRev,rules_version:1,groups:structuredClone(page),next_id:rest.length>page.length?page.at(-1)!.id:null};
    },
    live_catalog_page: (a) => {
      const start = a.after_id == null ? 0 : catalog.findIndex(c=>c.id===a.after_id)+1;
      const page = catalog.slice(start,start+Number(a.lim)); const last=page.at(-1);
      return {catalog_rev:1,channels:page,next_cursor:start+page.length<catalog.length&&last?{category_position:0,position:last.position,id:last.id}:null};
    },
    live_group_edit: (a) => {
      if(a.expected_rev!==groupRev) throw new Error('conflit-revision');
      const r=a.replacement as {remove_ids:string[];groups:typeof corrections};
      const ids=new Set([...r.remove_ids,...r.groups.map(g=>g.id)]);
      const next=[...corrections.filter(g=>!ids.has(g.id)),...structuredClone(r.groups)];
      const sources=new Set<string>();
      for(const g of next){if(!g.title.trim()||!g.source_ids.length)throw new Error('correction-invalide');for(const id of g.source_ids){if(sources.has(id))throw new Error('correction-invalide');sources.add(id);}}
      corrections=next; return {rev:++groupRev};
    },
    profile_prefs: a => ({rev:groupRev,group_copies:groupCopies[String(a.p)]??true,hidden:[...cats.filter(c=>c.hg||c.hp[String(a.p)]).map(c=>({item_type:'live_category',item_id:c.id})),...[...chHidG,...chHidP[String(a.p)]??[]].map(id=>({item_type:'channel',item_id:id}))],favorites:favs[String(a.p)]??[],playlists:[],order}),
    set_group_copies: a => {groupCopies[String(a.p)]=Boolean(a.enabled);return null;},
    org_categories: (a) => {
      const p = a.p as string | null;
      const list = order ? order.map((id) => cats.find((c) => c.id === id)!) : cats;
      return list.map((c) => ({
        id: c.id, name: c.name, provider_position: c.position,
        hidden_global: c.hg, hidden_profile: p !== null && !!c.hp[p], channel_count: c.n,
      }));
    },
    ...shelfHandlers('series', scats),
    ...shelfHandlers('movie', mcats),
    org_set_hidden: (a) => {
      const p = a.p as string | null; const hide = Boolean(a.hidden);
      for (const id of a.ids as string[]) {
        if (a.item_type === 'channel') {
          const set = p === null ? chHidG : (chHidP[p] ??= new Set());
          if (hide) set.add(id); else set.delete(id);
          continue;
        }
        const c = (a.item_type === 'vod_category' ? mcats.cats : a.item_type === 'series_category' ? scats.cats : cats).find((x) => x.id === id); if (!c) continue;
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
    org_playlists: (a) => sortPls(a.p as string).map((x) => ({
      id: x.id, name: x.name, position: x.position, channel_ids: x.items,
      items: x.items.map((id) => {
        const ch = chById(id);
        if (!ch) return { id, name: null, number: null, category_id: null, state: 'n-existe-plus' };
        const p = a.p as string; const cat = cats.find((y) => y.id === ch.categoryId)!;
        const state = chHidG.has(id) || chHidP[p]?.has(id) ? 'masquee' : cat.hg || cat.hp[p] ? 'categorie-masquee' : 'ok';
        return { id, name: ch.name, number: ch.number, category_id: ch.categoryId, state };
      }),
    })),
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
      try { return { data: h((args ?? {}) as Record<string, unknown>), error: null }; } catch(e) { return {data:null,error:{message:(e as Error).message}}; }
    },
    from(table) { return {
      select: async () => ({ data: table === 'profiles' ? structuredClone(profiles) : [], error: null }),
      update: (values: object) => ({ eq: (_column: string, id: string) => ({ select: async () => {
        const p = table === 'profiles' ? profiles.find(p => p.id === id) : undefined;
        if (p) Object.assign(p, values);
        return {data: p ? [{id:p.id}] : [], error:null};
      } }) }),
    }; },
  };
  return { db, householdId: 'demo' };
}
