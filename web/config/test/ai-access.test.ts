import { describe, it, expect } from 'vitest';
import { accessState, connectorUrl, createAccess, logLabel } from '../src/ai-access';

describe('accès pour les IA (page)', () => {
  it('adresse du connecteur, états et journal', () => {
    expect(connectorUrl('https://x.supabase.co/', 'ab12')).toBe('https://x.supabase.co/functions/v1/assistant?cle=ab12');
    expect(accessState({ id: 'a', label: 'Claude', created_at: '2026-11-18T10:00:00Z', last_used_at: null, revoked_at: null })).toBe('Jamais utilisé pour l’instant');
    expect(accessState({ id: 'a', label: 'Claude', created_at: '2026-11-18T10:00:00Z', last_used_at: null, revoked_at: '2026-11-18T12:00:00Z' })).toMatch(/^Coupé le /);
    expect(logLabel({ at: '2026-11-18T12:00:00Z', tool: 'playlist_creer', summary: '' })).toMatch(/^Playlist créée · /);
  });
  it('création : clé rendue ; limite atteinte : message clair', async () => {
    let error: { message: string } | null = null;
    const db = { rpc: async () => ({ data: 'k'.repeat(64), error }), from: () => ({ select: async () => ({ data: [], error: null }) }) };
    expect(await createAccess(db, 'h', 'Claude')).toHaveLength(64);
    error = { message: 'trop-d-acces' };
    await expect(createAccess(db, 'h', 'Claude')).rejects.toThrow('Cinq accès actifs');
  });
});
