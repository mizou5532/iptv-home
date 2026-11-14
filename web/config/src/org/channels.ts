// Logique pure des listes de chaînes (sans DOM) : chargement par pages et noms déjà vus.
import type { Channel } from './api';

export interface PageLoader {
  get(index: number): Channel | undefined;
  // Charge les pages qui couvrent [from, to) ; une page déjà chargée ou en cours n'est jamais redemandée.
  ensure(from: number, to: number): Promise<void>;
  // Connu dès qu'une page courte (ou vide) est reçue ; sinon null.
  total(): number | null;
}

export function pageLoader(fetchPage: (off: number, lim: number) => Promise<Channel[]>, pageSize = 500): PageLoader {
  const pages = new Map<number, Channel[]>();
  const inflight = new Map<number, Promise<void>>();
  let total: number | null = null;

  const load = (pg: number): Promise<void> => {
    if (pages.has(pg)) return Promise.resolve();
    const running = inflight.get(pg);
    if (running) return running;
    const p = fetchPage(pg * pageSize, pageSize)
      .then((rows) => {
        pages.set(pg, rows);
        if (rows.length < pageSize) total = pg * pageSize + rows.length;
      })
      .finally(() => { inflight.delete(pg); });
    inflight.set(pg, p);
    return p;
  };

  return {
    get: (i) => (i < 0 ? undefined : pages.get(Math.floor(i / pageSize))?.[i % pageSize]),
    async ensure(from, to) {
      const first = Math.floor(Math.max(0, from) / pageSize);
      const last = Math.floor(Math.max(from, to - 1) / pageSize);
      const jobs: Promise<void>[] = [];
      for (let pg = first; pg <= last; pg++) {
        if (total !== null && pg * pageSize >= total) break;
        jobs.push(load(pg));
      }
      await Promise.all(jobs);
    },
    total: () => total,
  };
}

// Les listes de playlists ne contiennent que des identifiants : on retient les noms déjà vus.
const seen = new Map<string, { name: string; number: number | null }>();
export function rememberChannels(list: { id: string; name: string; number: number | null }[]): void {
  for (const c of list) if (c.name) seen.set(c.id, { name: c.name, number: c.number });
}
export function channelLabel(id: string): string {
  const c = seen.get(id);
  if (!c) return 'Chaîne inconnue';
  return c.number !== null ? `${c.number}. ${c.name}` : c.name;
}

export const HIDE_EVERYONE_CONFIRM = 'Cette chaîne sera masquée pour tout le monde. Continuer ?';
// Masquer une chaîne pour tout le monde demande confirmation ; pour un profil, non.
export function hideChannelConfirm(everyone: boolean): string | null {
  return everyone ? HIDE_EVERYONE_CONFIRM : null;
}
