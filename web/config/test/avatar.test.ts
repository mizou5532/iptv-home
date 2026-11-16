import { describe, it, expect } from 'vitest';
import { avatarUrl, clampFraming, cropRect, removeAvatar, saveAvatar, type AvatarStorage, type SettingsDb } from '../src/avatar';
import type { DbResult } from '../src/flow';

function fakes(upload: { error: { message: string } | null } = { error: null }, update: DbResult = { data: [{ id: 'p1' }], error: null }) {
  const calls: unknown[] = [];
  const storage: AvatarStorage = {
    upload: async (path, file, options) => { calls.push(['upload', path, file.type, options]); return upload; },
    remove: async (paths) => { calls.push(['remove', paths]); return upload; },
  };
  const db: SettingsDb = { from: () => ({ update: (values) => ({ eq: (c, v) => ({ select: async () => { calls.push(['update', values, c, v]); return update; } }) }) }) };
  return { storage, db, calls };
}

describe('photo de profil', () => {
  it('adresse lue par la télé, avec version', () => {
    expect(avatarUrl('https://x.supabase.co', 'p1')).toBe('https://x.supabase.co/storage/v1/object/public/web/avatars/p1.webp');
    expect(avatarUrl('https://x.supabase.co', 'p1', 42)).toBe('https://x.supabase.co/storage/v1/object/public/web/avatars/p1.webp?v=42');
  });
  it('recadrage carré centré, zoomé et borné à l’image', () => {
    expect(cropRect(1000, 500, { zoom: 1, dx: 0, dy: 0 })).toEqual({ sx: 250, sy: 0, size: 500 });
    expect(cropRect(1000, 500, { zoom: 2, dx: 0, dy: 0 })).toEqual({ sx: 375, sy: 125, size: 250 });
    expect(cropRect(1000, 500, { zoom: 1, dx: 9999, dy: -9999 })).toEqual({ sx: 500, sy: 0, size: 500 });
    expect(cropRect(1000, 500, { zoom: 0.2, dx: 0, dy: 0 }).size).toBe(500);
    expect(clampFraming(1000, 500, { zoom: 1, dx: 9999, dy: 30 })).toEqual({ zoom: 1, dx: 250, dy: 0 });
  });
  it('enregistre la photo puis la version, en gardant les autres réglages', async () => {
    const { storage, db, calls } = fakes();
    const next = await saveAvatar(storage, db, 'p1', new Blob(['x'], { type: 'image/jpeg' }), { pinned_series: '45692' }, 1234);
    expect(next).toEqual({ pinned_series: '45692', avatar_v: 1234, avatar: true });
    expect(calls).toEqual([
      ['upload', 'avatars/p1.webp', 'image/jpeg', { upsert: true, contentType: 'image/jpeg', cacheControl: '60' }],
      ['update', { settings: { pinned_series: '45692', avatar_v: 1234, avatar: true } }, 'id', 'p1'],
    ]);
  });
  it('refuse un format inconnu et signale un envoi raté', async () => {
    const { storage, db, calls } = fakes();
    await expect(saveAvatar(storage, db, 'p1', new Blob(['x'], { type: 'image/png' }), {}, 1)).rejects.toThrow(/Format/);
    expect(calls).toEqual([]);
    const bad = fakes({ error: { message: 'x' } });
    await expect(saveAvatar(bad.storage, bad.db, 'p1', new Blob(['x'], { type: 'image/webp' }), {}, 1)).rejects.toThrow(/envoyée/);
  });
  it('retirer la photo efface le fichier et change la version', async () => {
    const { storage, db, calls } = fakes();
    expect(await removeAvatar(storage, db, 'p1', { avatar_v: 1 }, 2)).toEqual({ avatar_v: 2, avatar: false });
    expect(calls[0]).toEqual(['remove', ['avatars/p1.webp']]);
  });
});
