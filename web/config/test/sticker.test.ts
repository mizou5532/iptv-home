import { describe, it, expect } from 'vitest';
import { fitSize, removeBackground, removeSticker, saveSticker, saveStickerLook, stickerCorner, stickerSize, stickerUrl, visibleBounds } from '../src/sticker';
import type { AvatarStorage, SettingsDb } from '../src/avatar';
import type { DbResult } from '../src/flow';

function fakes(update: DbResult = { data: [{ id: 'p1' }], error: null }) {
  const calls: unknown[] = [];
  const storage: AvatarStorage = {
    upload: async (path, file, options) => { calls.push(['upload', path, file.type, options]); return { error: null }; },
    remove: async (paths) => { calls.push(['remove', paths]); return { error: null }; },
  };
  const db: SettingsDb = { from: () => ({ update: (values) => ({ eq: (c, v) => ({ select: async () => { calls.push(['update', values, c, v]); return update; } }) }) }) };
  return { storage, db, calls };
}

/** Image w×h remplie de [bg], avec un carré [fg] de côté s au centre et un anneau d'ombre [shadow] autour. */
function image(w: number, h: number, bg: number[], fg: number[], s: number, shadow?: number[]): Uint8ClampedArray {
  const px = new Uint8ClampedArray(w * h * 4);
  const c0 = Math.floor((w - s) / 2);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const inside = x >= c0 && x < c0 + s && y >= c0 && y < c0 + s;
    const ring = shadow && x >= c0 - 1 && x <= c0 + s && y >= c0 - 1 && y <= c0 + s;
    const col = inside ? fg : ring ? shadow! : bg;
    px.set([col[0]!, col[1]!, col[2]!, 255], (y * w + x) * 4);
  }
  return px;
}

describe('autocollant', () => {
  it('adresse lue par la télé, coin et taille par défaut', () => {
    expect(stickerUrl('https://x.supabase.co', 'p1', 9)).toBe('https://x.supabase.co/storage/v1/object/public/web/stickers/p1.webp?v=9');
    expect(stickerCorner({})).toBe('bas-droite');
    expect(stickerCorner({ sticker_corner: 'bas-gauche' })).toBe('bas-gauche');
    expect(stickerSize({ sticker_size: 'énorme' })).toBe('moyen');
  });

  it('retire le fond blanc mais garde la bordure blanche derrière son ombre', () => {
    // Fond blanc, ombre grise d'un pixel, puis l'autocollant (blanc lui aussi au bord) : seul le fond part.
    const px = image(10, 10, [254, 254, 254], [255, 255, 255], 4, [235, 235, 235]);
    const removed = removeBackground(px, 10, 10);
    expect(removed).toBe(100 - 36);
    expect(px[(5 * 10 + 5) * 4 + 3]).toBe(255);
    expect(visibleBounds(px, 10, 10)).toEqual({ x: 2, y: 2, w: 6, h: 6 });
  });

  it('efface les îlots du grain du fond, garde la pièce principale', () => {
    const px = image(12, 12, [254, 254, 254], [200, 50, 50], 4);
    px.set([240, 240, 240, 255], (1 * 12 + 1) * 4); // grain isolé dans le fond
    removeBackground(px, 12, 12);
    expect(px[(1 * 12 + 1) * 4 + 3]).toBe(0);
    expect(visibleBounds(px, 12, 12)).toEqual({ x: 4, y: 4, w: 4, h: 4 });
  });

  it('ne touche ni une photo ni une image déjà transparente', () => {
    const photo = image(6, 6, [10, 120, 200], [250, 0, 0], 2);
    photo.set([200, 30, 30, 255], 0); // coins différents
    expect(removeBackground(photo, 6, 6)).toBe(0);
    const clear = image(6, 6, [255, 255, 255], [0, 0, 0], 2);
    clear[3] = 0;
    expect(removeBackground(clear, 6, 6)).toBe(0);
  });

  it('taille de sortie ramenée à 512 sans jamais agrandir', () => {
    expect(fitSize(1024, 512)).toEqual({ w: 512, h: 256 });
    expect(fitSize(200, 100)).toEqual({ w: 200, h: 100 });
  });

  it('enregistre l’image puis les réglages, en gardant les autres', async () => {
    const { storage, db, calls } = fakes();
    const next = await saveSticker(storage, db, 'p1', new Blob(['x'], { type: 'image/png' }), { avatar_v: 3 }, 'bas-gauche', 'grand', 50);
    expect(next).toEqual({ avatar_v: 3, sticker_v: 50, sticker: true, sticker_corner: 'bas-gauche', sticker_size: 'grand' });
    expect(calls[0]).toEqual(['upload', 'stickers/p1.webp', 'image/png', { upsert: true, contentType: 'image/png', cacheControl: '60' }]);
    await expect(saveSticker(storage, db, 'p1', new Blob(['x'], { type: 'image/jpeg' }), {}, 'bas-droite', 'moyen', 1)).rejects.toThrow(/Format/);
  });

  it('change le coin sans renvoyer l’image ; retirer efface le fichier', async () => {
    const { storage, db, calls } = fakes();
    expect(await saveStickerLook(db, 'p1', { sticker: true, sticker_v: 4 }, 'bas-droite', 'petit'))
      .toEqual({ sticker: true, sticker_v: 4, sticker_corner: 'bas-droite', sticker_size: 'petit' });
    expect(calls.some((c) => (c as unknown[])[0] === 'upload')).toBe(false);
    expect(await removeSticker(storage, db, 'p1', { sticker: true }, 8)).toEqual({ sticker: false, sticker_v: 8 });
    expect(calls).toContainEqual(['remove', ['stickers/p1.webp']]);
  });
});
