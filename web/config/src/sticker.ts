// Autocollant d'un profil : image détourée (fond transparent) montrée par la télé dans un coin des accueils Séries et
// Films. Enregistrée dans le compartiment public « web » (stickers/<id>.webp, WebP ou PNG), puis réglages du profil
// (sticker_v pour que la télé l'affiche aussitôt, coin, taille).
import type { AvatarStorage, SettingsDb } from './avatar';

/** Plus grand côté de l'image envoyée (la télé l'affiche à 200 dp au plus). */
export const STICKER_MAX = 512;
export const STICKER_GIF_MAX_BYTES = 3 * 1024 * 1024;
export type StickerCorner = 'bas-droite' | 'bas-gauche';
export type StickerSize = 'petit' | 'moyen' | 'grand';
export const STICKER_CORNERS: { id: StickerCorner; label: string }[] = [{ id: 'bas-droite', label: 'En bas à droite' }, { id: 'bas-gauche', label: 'En bas à gauche' }];
export const STICKER_SIZES: { id: StickerSize; label: string; px: number }[] = [
  { id: 'petit', label: 'Petit', px: 96 }, { id: 'moyen', label: 'Moyen', px: 140 }, { id: 'grand', label: 'Grand', px: 200 },
];

export function stickerPath(profileId: string): string {
  return `stickers/${profileId}.webp`;
}
export function stickerUrl(base: string, profileId: string, version?: number | null): string {
  return `${base}/storage/v1/object/public/web/${stickerPath(profileId)}${version ? `?v=${version}` : ''}`;
}

export function stickerCorner(settings: Record<string, unknown>): StickerCorner {
  return settings.sticker_corner === 'bas-gauche' ? 'bas-gauche' : 'bas-droite';
}
export function stickerSize(settings: Record<string, unknown>): StickerSize {
  return settings.sticker_size === 'petit' || settings.sticker_size === 'grand' ? settings.sticker_size : 'moyen';
}

/**
 * Retire le fond uni d'une image (pixels RGBA) : remplissage depuis les bords, tant que la couleur reste à [tol] près
 * de celle des coins. Une petite tolérance s'arrête sur l'ombre douce d'un autocollant et garde sa bordure blanche.
 * Rien n'est retiré si les quatre coins n'ont pas la même couleur (photo, image déjà transparente). Rend le nombre de
 * pixels rendus transparents.
 */
export function removeBackground(px: Uint8ClampedArray, w: number, h: number, tol = 3): number {
  const at = (x: number, y: number) => (y * w + x) * 4;
  const corners = [at(0, 0), at(w - 1, 0), at(0, h - 1), at(w - 1, h - 1)];
  if (corners.some((i) => px[i + 3]! < 250)) return 0;
  const ref = [0, 1, 2].map((c) => Math.round(corners.reduce((s, i) => s + px[i + c]!, 0) / 4));
  const near = (i: number) => px[i + 3]! > 0 && Math.max(Math.abs(px[i]! - ref[0]!), Math.abs(px[i + 1]! - ref[1]!), Math.abs(px[i + 2]! - ref[2]!)) <= tol;
  if (!corners.every(near)) return 0;
  const seen = new Uint8Array(w * h);
  const stack: number[] = [];
  const push = (x: number, y: number) => { const k = y * w + x; if (!seen[k]) { seen[k] = 1; stack.push(k); } };
  for (let x = 0; x < w; x++) { push(x, 0); push(x, h - 1); }
  for (let y = 0; y < h; y++) { push(0, y); push(w - 1, y); }
  let removed = 0;
  while (stack.length) {
    const k = stack.pop()!;
    const i = k * 4;
    if (!near(i)) continue;
    px[i + 3] = 0; removed++;
    const x = k % w, y = (k - x) / w;
    if (x > 0) push(x - 1, y);
    if (x < w - 1) push(x + 1, y);
    if (y > 0) push(x, y - 1);
    if (y < h - 1) push(x, y + 1);
  }
  return removed + keepLargestPiece(px, w, h);
}

/**
 * Le grain d'un fond « uni » laisse des îlots presque invisibles (taches sur l'écran sombre de la télé) : seule la plus
 * grande pièce visible est gardée, l'autocollant lui-même. Rend le nombre de pixels effacés.
 */
function keepLargestPiece(px: Uint8ClampedArray, w: number, h: number): number {
  const label = new Int32Array(w * h);
  const sizes: number[] = [0];
  const stack: number[] = [];
  for (let k0 = 0; k0 < w * h; k0++) {
    if (label[k0] || px[k0 * 4 + 3] === 0) continue;
    const id = sizes.length;
    let n = 0;
    label[k0] = id; stack.push(k0);
    while (stack.length) {
      const k = stack.pop()!; n++;
      const x = k % w, y = (k - x) / w;
      const next = [x > 0 ? k - 1 : -1, x < w - 1 ? k + 1 : -1, y > 0 ? k - w : -1, y < h - 1 ? k + w : -1];
      for (const j of next) if (j >= 0 && !label[j] && px[j * 4 + 3] !== 0) { label[j] = id; stack.push(j); }
    }
    sizes.push(n);
  }
  if (sizes.length <= 2) return 0;
  let best = 1;
  for (let i = 2; i < sizes.length; i++) if (sizes[i]! > sizes[best]!) best = i;
  let cleared = 0;
  for (let k = 0; k < w * h; k++) if (label[k] && label[k] !== best) { px[k * 4 + 3] = 0; cleared++; }
  return cleared;
}

/** Rectangle des pixels visibles (marges transparentes retirées) ; null si l'image est entièrement transparente. */
export function visibleBounds(px: Uint8ClampedArray, w: number, h: number): { x: number; y: number; w: number; h: number } | null {
  let x0 = w, y0 = h, x1 = -1, y1 = -1;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    if (px[(y * w + x) * 4 + 3]! > 8) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
  }
  return x1 < 0 ? null : { x: x0, y: y0, w: x1 - x0 + 1, h: y1 - y0 + 1 };
}

/** Taille de sortie : le plus grand côté ramené à [max] (jamais agrandi). */
export function fitSize(w: number, h: number, max = STICKER_MAX): { w: number; h: number } {
  const s = Math.min(1, max / Math.max(w, h));
  return { w: Math.max(1, Math.round(w * s)), h: Math.max(1, Math.round(h * s)) };
}

async function write(db: SettingsDb, profileId: string, next: Record<string, unknown>): Promise<Record<string, unknown>> {
  const r = await db.from('profiles').update({ settings: next }).eq('id', profileId).select('id');
  if (r.error) throw new Error("L'autocollant est enregistré mais la télé ne le sait pas encore. Réessaie.");
  if (!Array.isArray(r.data) || r.data.length !== 1) throw new Error("Ce profil n'existe plus. Recharge la page.");
  return next;
}

/** Envoie l'image puis les réglages ; [settings] = réglages actuels du profil (les autres clés sont gardées). */
export async function saveSticker(
  storage: AvatarStorage, db: SettingsDb, profileId: string, image: Blob, settings: Record<string, unknown>,
  corner: StickerCorner, size: StickerSize, now: number,
): Promise<Record<string, unknown>> {
  if (!['image/webp', 'image/png', 'image/gif'].includes(image.type)) throw new Error("Format d'image non pris en charge.");
  if (image.type === 'image/gif' && image.size > STICKER_GIF_MAX_BYTES) throw new Error('Ce GIF est trop lourd (3 Mo au plus).');
  const up = await storage.upload(stickerPath(profileId), image, { upsert: true, contentType: image.type, cacheControl: '60' });
  if (up.error) throw new Error("L'autocollant n'a pas pu être envoyé. Réessaie dans un instant.");
  return write(db, profileId, { ...settings, sticker_v: now, sticker: true, sticker_corner: corner, sticker_size: size });
}

/** Change seulement le coin ou la taille (l'image reste). */
export function saveStickerLook(db: SettingsDb, profileId: string, settings: Record<string, unknown>, corner: StickerCorner, size: StickerSize): Promise<Record<string, unknown>> {
  return write(db, profileId, { ...settings, sticker_corner: corner, sticker_size: size });
}

/** Retire l'autocollant : la télé n'affiche plus rien et ne télécharge plus rien. */
export async function removeSticker(storage: AvatarStorage, db: SettingsDb, profileId: string, settings: Record<string, unknown>, now: number): Promise<Record<string, unknown>> {
  const r = await storage.remove([stickerPath(profileId)]);
  if (r.error) throw new Error("L'autocollant n'a pas pu être retiré. Réessaie dans un instant.");
  return write(db, profileId, { ...settings, sticker_v: now, sticker: false });
}
