import { describe, expect, it, vi } from 'vitest';
import { cleanChannelTitle, connectHomeServer, getOrCreateClientKey, homeFetch, isSeparatorStream, type HomeServerDb } from '../src/watch';
import type { KV } from '../src/flow';

function memStore(): KV {
  const m = new Map<string, string>();
  return {
    getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => { m.set(k, v); },
    removeItem: (k) => { m.delete(k); },
  };
}

describe('Onglet Regarder (iPhone / Web)', () => {
  it('génère et conserve un client_key stable pour ce téléphone', () => {
    const s = memStore();
    const k1 = getOrCreateClientKey(s);
    const k2 = getOrCreateClientKey(s);
    expect(k1).toMatch(/^web_[0-9A-Za-z]{24}$/);
    expect(k2).toBe(k1);
  });

  it('nettoie les noms de chaînes et reconnaît les séparateurs', () => {
    expect(cleanChannelTitle('CA| TVA SPORTS FHD')).toEqual({ title: 'TVA SPORTS', badge: 'FHD' });
    expect(cleanChannelTitle('FR: CANAL+ 4K UHD')).toEqual({ title: 'CANAL+', badge: '4K' });
    expect(isSeparatorStream('##### SPORTS #####')).toBe(true);
    expect(isSeparatorStream('TVA SPORTS')).toBe(false);
  });

  it('connecte au serveur maison via public_url et renouvelle le jeton sur 401', async () => {
    const s = memStore();
    let issued = 0;
    const db = {
      from: () => ({
        select: async () => ({
          data: [{ household_id: 'h1', url: 'https://192.168.2.81:8443', public_url: 'https://test-tunnel.trycloudflare.com/', version: '0.6.0' }],
          error: null,
        }),
      }),
      rpc: async (fn: string) => {
        if (fn === 'issue_web_server_token') {
          issued++;
          return { data: `tok_${issued}`, error: null };
        }
        return { data: null, error: null };
      },
    } as unknown as HomeServerDb;

    const conn = await connectHomeServer(db, 'h1', s);
    expect(conn.baseUrl).toBe('https://test-tunnel.trycloudflare.com');
    expect(conn.token).toBe('tok_1');

    const fakeFetch = vi.fn()
      .mockResolvedValueOnce({ status: 401, ok: false })
      .mockResolvedValueOnce({ status: 200, ok: true, json: async () => ({ rev: 'r1', categories: [], streams: [] }) });

    const res = await homeFetch<{ rev: string }>(db, conn, s, '/v1/live', undefined, fakeFetch as unknown as typeof fetch);
    expect(res.rev).toBe('r1');
    expect(conn.token).toBe('tok_2');
    expect(fakeFetch).toHaveBeenCalledTimes(2);
  });
});
