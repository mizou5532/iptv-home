// Fond d'écran d'un profil : image 1920 × 1080 montrée par la télé derrière les accueils Séries et Films, sous un voile
// de la couleur du thème (wallpaper_dim, en %). Enregistrée dans le compartiment public « web »
// (wallpapers/<id>.webp, WebP ou JPEG), puis réglages du profil (wallpaper_v pour que la télé l'affiche aussitôt).
import type { AvatarStorage, SettingsDb } from './avatar';

export const WALL_W = 1920;
export const WALL_H = 1080;
/** Le compartiment refuse plus de 2 Mo : la qualité baisse jusqu'à passer sous ce seuil. */
export const WALL_MAX_BYTES = 1_900_000;
export const DIM_MAX = 80;
export const DIM_DEFAULT = 40;
export const ZOOM_MAX = 4;

export type Blur = 'aucun' | 'leger' | 'fort';
export const BLURS: { id: Blur; label: string; factor: number }[] = [
  { id: 'aucun', label: 'Aucun', factor: 1 }, { id: 'leger', label: 'Léger', factor: 24 }, { id: 'fort', label: 'Fort', factor: 64 },
];
export type Template = 'degrade' | 'aurore' | 'etoiles' | 'bulles';
export const TEMPLATES: { id: Template; label: string }[] = [
  { id: 'degrade', label: 'Dégradé doux' }, { id: 'aurore', label: 'Aurore' }, { id: 'etoiles', label: 'Nuit étoilée' }, { id: 'bulles', label: 'Bulles' },
];

export function wallpaperPath(profileId: string): string { return `wallpapers/${profileId}.webp` }
export function wallpaperUrl(base: string, profileId: string, version?: number | null): string {
  return `${base}/storage/v1/object/public/web/${wallpaperPath(profileId)}${version ? `?v=${version}` : ''}`;
}
export function dimOf(settings: Record<string, unknown>): number {
  const d = settings.wallpaper_dim;
  return typeof d === 'number' && Number.isFinite(d) ? Math.round(Math.min(Math.max(d, 0), DIM_MAX)) : DIM_DEFAULT;
}

/** Position de l'image dans le cadre 16:9 : zoom (1 = le cadre le plus grand possible) et décalage du centre (px de l'image). */
export interface Framing { zoom: number; dx: number; dy: number }

/** Partie de l'image dessinée dans le cadre 16:9, jamais hors de l'image. */
export function frameRect(imgW: number, imgH: number, f: Framing): { sx: number; sy: number; w: number; h: number } {
  const zoom = Math.min(Math.max(f.zoom, 1), ZOOM_MAX);
  const w = Math.min(imgW, (imgH * WALL_W) / WALL_H) / zoom;
  const h = (w * WALL_H) / WALL_W;
  const sx = Math.min(Math.max(imgW / 2 + f.dx - w / 2, 0), imgW - w);
  const sy = Math.min(Math.max(imgH / 2 + f.dy - h / 2, 0), imgH - h);
  return { sx, sy, w, h };
}
export function clampFrame(imgW: number, imgH: number, f: Framing): Framing {
  const zoom = Math.min(Math.max(f.zoom, 1), ZOOM_MAX);
  const r = frameRect(imgW, imgH, { ...f, zoom });
  return { zoom, dx: r.sx + r.w / 2 - imgW / 2, dy: r.sy + r.h / 2 - imgH / 2 };
}

/** Générateur pseudo-aléatoire stable (modèles identiques d'une fois à l'autre). */
export function seeded(seed: number): () => number {
  let s = seed >>> 0;
  return () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; };
}

async function write(db: SettingsDb, profileId: string, next: Record<string, unknown>): Promise<Record<string, unknown>> {
  const r = await db.from('profiles').update({ settings: next }).eq('id', profileId).select('id');
  if (r.error) throw new Error("Le fond est enregistré mais la télé ne le sait pas encore. Réessaie.");
  if (!Array.isArray(r.data) || r.data.length !== 1) throw new Error("Ce profil n'existe plus. Recharge la page.");
  return next;
}

export async function saveWallpaper(
  storage: AvatarStorage, db: SettingsDb, profileId: string, image: Blob, settings: Record<string, unknown>, dim: number, now: number,
): Promise<Record<string, unknown>> {
  if (!['image/webp', 'image/jpeg'].includes(image.type)) throw new Error("Format d'image non pris en charge.");
  if (image.size > WALL_MAX_BYTES) throw new Error('Image trop lourde pour être envoyée.');
  const up = await storage.upload(wallpaperPath(profileId), image, { upsert: true, contentType: image.type, cacheControl: '60' });
  if (up.error) throw new Error("Le fond d'écran n'a pas pu être envoyé. Réessaie dans un instant.");
  return write(db, profileId, { ...settings, wallpaper: true, wallpaper_v: now, wallpaper_dim: Math.round(Math.min(Math.max(dim, 0), DIM_MAX)) });
}

/** Change seulement le voile (l'image reste). */
export function saveWallpaperDim(db: SettingsDb, profileId: string, settings: Record<string, unknown>, dim: number): Promise<Record<string, unknown>> {
  return write(db, profileId, { ...settings, wallpaper_dim: Math.round(Math.min(Math.max(dim, 0), DIM_MAX)) });
}

export async function removeWallpaper(storage: AvatarStorage, db: SettingsDb, profileId: string, settings: Record<string, unknown>, now: number): Promise<Record<string, unknown>> {
  const r = await storage.remove([wallpaperPath(profileId)]);
  if (r.error) throw new Error("Le fond d'écran n'a pas pu être retiré. Réessaie dans un instant.");
  return write(db, profileId, { ...settings, wallpaper: false, wallpaper_v: now });
}
