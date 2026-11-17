import { describe, it, expect } from 'vitest';
import { clampFrame, dimOf, frameRect, removeWallpaper, saveWallpaper, saveWallpaperDim, seeded, wallpaperUrl } from '../src/wallpaper';
import type { AvatarStorage, SettingsDb } from '../src/avatar';

function fakes() {
  const calls: unknown[] = [];
  const storage: AvatarStorage = {
    upload: async (path, file, options) => { calls.push(['upload', path, file.type, options]); return { error: null }; },
    remove: async (paths) => { calls.push(['remove', paths]); return { error: null }; },
  };
  const db: SettingsDb = { from: () => ({ update: (values) => ({ eq: () => ({ select: async () => { calls.push(['update', values]); return { data: [{ id: 'p1' }], error: null }; } }) }) }) };
  return { storage, db, calls };
}

describe("fond d'écran", () => {
  it('adresse lue par la télé et voile par défaut, borné', () => {
    expect(wallpaperUrl('https://x.co', 'p1', 5)).toBe('https://x.co/storage/v1/object/public/web/wallpapers/p1.webp?v=5');
    expect(dimOf({})).toBe(40); expect(dimOf({ wallpaper_dim: 120 })).toBe(80); expect(dimOf({ wallpaper_dim: -3 })).toBe(0);
  });
  it('cadre 16:9 dans une photo en hauteur ou en largeur, borné', () => {
    expect(frameRect(1080, 1920, { zoom: 1, dx: 0, dy: 0 })).toEqual({ sx: 0, sy: 656.25, w: 1080, h: 607.5 });
    expect(frameRect(4000, 1000, { zoom: 1, dx: 0, dy: 0 })).toEqual({ sx: 1111.111111111111, sy: 0, w: 1777.7777777777778, h: 1000 });
    expect(frameRect(1920, 1080, { zoom: 2, dx: 99999, dy: 0 }).sx).toBe(960);
    expect(clampFrame(1920, 1080, { zoom: 1, dx: 300, dy: 300 })).toEqual({ zoom: 1, dx: 0, dy: 0 });
  });
  it('modèles identiques d’une fois à l’autre', () => {
    const a = seeded(7), b = seeded(7);
    expect([a(), a(), a()]).toEqual([b(), b(), b()]);
  });
  it('enregistre l’image puis le voile ; change le voile seul ; retire', async () => {
    const { storage, db, calls } = fakes();
    expect(await saveWallpaper(storage, db, 'p1', new Blob(['x'], { type: 'image/jpeg' }), { sticker: true }, 55.4, 9))
      .toEqual({ sticker: true, wallpaper: true, wallpaper_v: 9, wallpaper_dim: 55 });
    expect(calls[0]).toEqual(['upload', 'wallpapers/p1.webp', 'image/jpeg', { upsert: true, contentType: 'image/jpeg', cacheControl: '60' }]);
    await expect(saveWallpaper(storage, db, 'p1', new Blob(['x'], { type: 'image/png' }), {}, 40, 1)).rejects.toThrow(/Format/);
    expect(await saveWallpaperDim(db, 'p1', { wallpaper: true }, 10)).toEqual({ wallpaper: true, wallpaper_dim: 10 });
    expect(await removeWallpaper(storage, db, 'p1', { wallpaper: true }, 12)).toEqual({ wallpaper: false, wallpaper_v: 12 });
    expect(calls).toContainEqual(['remove', ['wallpapers/p1.webp']]);
  });
});
