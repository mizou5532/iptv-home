// Mode démo (?demo) : faux client en mémoire avec des données inventées. Aucun réseau.
import type { Db } from '../flow';

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
        const c = cats.find((x) => x.id === id); if (!c) continue;
        if (p === null) c.hg = hide; else c.hp[p] = hide;
      }
      return (a.ids as string[]).length;
    },
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
