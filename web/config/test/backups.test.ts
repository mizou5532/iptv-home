import { describe, it, expect } from 'vitest';
import { backupContent, backupFileName, backupTitle, restoreBackup, restoreFile, backupNow } from '../src/backups';

const row = { id: 'b', created_at: new Date(2026, 10, 18, 21, 17).toISOString(), reason: 'avant_identifiants', summary: { progression: 1412, playlists: 3, masquages: 1, profils: 3 } };

describe('sauvegardes (page)', () => {
  it('textes : raison, contenu, nom du fichier', () => {
    expect(backupTitle(row)).toBe('Avant changement d’identifiants IPTV');
    expect(backupContent(row)).toBe('1 412 positions · 3 playlists · 1 masquage · 3 profils');
    expect(backupFileName(row)).toBe('iptv-home-sauvegarde-2026-11-18-2117.json');
  });

  it('appels au serveur et messages', async () => {
    const calls: unknown[] = [];
    let error: { message: string } | null = null;
    const db = { rpc: async (fn: string, args: object) => { calls.push([fn, args]); return { data: null, error }; }, from: () => ({ select: async () => ({ data: [], error: null }) }) };
    await backupNow(db, 'h');
    await restoreBackup(db, 'h', 'b', ['progression']);
    await restoreFile(db, 'h', JSON.stringify({ format: 1, household_id: 'h' }), ['organisation']);
    expect(calls.map((c) => (c as [string])[0])).toEqual(['backup_now', 'backup_restore', 'backup_restore_file']);
    await expect(restoreBackup(db, 'h', 'b', [])).rejects.toThrow('Choisis au moins une partie');
    await expect(restoreFile(db, 'h', 'pas du json', ['organisation'])).rejects.toThrow('pas une sauvegarde');
    error = { message: 'sauvegarde-invalide' };
    await expect(restoreFile(db, 'h', JSON.stringify({ format: 1 }), ['organisation'])).rejects.toThrow('pas une sauvegarde de cette maison');
  });
});
