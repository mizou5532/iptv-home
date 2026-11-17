// Photo de profil : recadrage carré, enregistrement dans le compartiment public « web » (avatars/<id>.webp, l'adresse
// que lit la télé), puis version dans profiles.settings.avatar_v pour que la télé affiche aussitôt la nouvelle photo.
import type { DbResult } from './flow';

export const AVATAR_SIZE = 1024;
export const ZOOM_MAX = 4;
/** GIF animé gardé tel quel (pas de recadrage possible image par image) : au plus 5 Mo pour ménager la mémoire de la télé. */
export const GIF_MAX_BYTES = 5 * 1024 * 1024;

export function avatarPath(profileId: string): string {
  return `avatars/${profileId}.webp`;
}

/** Adresse publique de la photo ; [version] change l'adresse à chaque enregistrement (pas d'ancienne photo en cache). */
export function avatarUrl(base: string, profileId: string, version?: number | null): string {
  return `${base}/storage/v1/object/public/web/${avatarPath(profileId)}${version ? `?v=${version}` : ''}`;
}

/** Position de la photo dans le cercle : zoom (1 = le petit côté remplit le cercle) et décalage du centre en pixels de l'image. */
export interface Framing { zoom: number; dx: number; dy: number }

/**
 * Partie de l'image source à dessiner dans le carré de sortie. Le carré ne sort jamais de l'image : le décalage est
 * borné, quel que soit le doigt de l'utilisateur.
 */
export function cropRect(imgW: number, imgH: number, f: Framing): { sx: number; sy: number; size: number } {
  const zoom = Math.min(Math.max(f.zoom, 1), ZOOM_MAX);
  const size = Math.min(imgW, imgH) / zoom;
  const cx = imgW / 2 + f.dx;
  const cy = imgH / 2 + f.dy;
  const sx = Math.min(Math.max(cx - size / 2, 0), imgW - size);
  const sy = Math.min(Math.max(cy - size / 2, 0), imgH - size);
  return { sx, sy, size };
}

/** Décalage ramené dans les bornes (pour que le glisser ne « colle » pas au bord). */
export function clampFraming(imgW: number, imgH: number, f: Framing): Framing {
  const zoom = Math.min(Math.max(f.zoom, 1), ZOOM_MAX);
  const r = cropRect(imgW, imgH, { ...f, zoom });
  return { zoom, dx: r.sx + r.size / 2 - imgW / 2, dy: r.sy + r.size / 2 - imgH / 2 };
}

export interface AvatarStorage {
  upload(path: string, file: Blob, options: { upsert: boolean; contentType: string; cacheControl: string }): Promise<{ error: { message: string } | null }>;
  remove(paths: string[]): Promise<{ error: { message: string } | null }>;
}
export interface SettingsDb {
  from(table: string): {
    update(values: object): { eq(column: string, value: string): { select(columns: string): PromiseLike<DbResult> } };
  };
}

/** Enregistre la photo puis sa version ; [settings] = réglages actuels du profil (les autres clés sont gardées). */
export async function saveAvatar(
  storage: AvatarStorage, db: SettingsDb, profileId: string, image: Blob, settings: Record<string, unknown>, now: number,
): Promise<Record<string, unknown>> {
  if (!['image/webp', 'image/jpeg', 'image/gif'].includes(image.type)) throw new Error('Format de photo non pris en charge.');
  if (image.type === 'image/gif' && image.size > GIF_MAX_BYTES) throw new Error('Ce GIF est trop lourd (5 Mo au plus).');
  const up = await storage.upload(avatarPath(profileId), image, { upsert: true, contentType: image.type, cacheControl: '60' });
  if (up.error) throw new Error("La photo n'a pas pu être envoyée. Réessaie dans un instant.");
  return writeVersion(db, profileId, { ...settings, avatar_v: now, avatar: true });
}

/** Retire la photo : la télé revient à la lettre sur la couleur du profil. */
export async function removeAvatar(storage: AvatarStorage, db: SettingsDb, profileId: string, settings: Record<string, unknown>, now: number): Promise<Record<string, unknown>> {
  const r = await storage.remove([avatarPath(profileId)]);
  if (r.error) throw new Error("La photo n'a pas pu être retirée. Réessaie dans un instant.");
  return writeVersion(db, profileId, { ...settings, avatar_v: now, avatar: false });
}

async function writeVersion(db: SettingsDb, profileId: string, next: Record<string, unknown>): Promise<Record<string, unknown>> {
  const r = await db.from('profiles').update({ settings: next }).eq('id', profileId).select('id');
  if (r.error) throw new Error("La photo est enregistrée mais la télé ne le sait pas encore. Réessaie.");
  if (!Array.isArray(r.data) || r.data.length !== 1) throw new Error("Ce profil n'existe plus. Recharge la page.");
  return next;
}
