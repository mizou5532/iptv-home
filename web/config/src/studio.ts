// Atelier de fond d'écran : la création (fond + éléments posés dessus) est un petit document JSON gardé dans
// profiles.settings.wallpaper_project, pour la rouvrir et la modifier plus tard. Les images qu'il cite (photo du fond,
// images ajoutées) sont dans le compartiment « web » sous studio/<id du profil>/<clé>.webp. L'image finale (1920 × 1080)
// reste wallpapers/<id>.webp, seule lue par la télé.
import { BLURS, DIM_DEFAULT, DIM_MAX, TEMPLATES, ZOOM_MAX, type Blur, type Framing, type Template } from './wallpaper';

export const MAX_LAYERS = 30;
export const MIN_W = 0.03;
export const MAX_W = 1.5;
/** Les emojis sont de petites images : au-delà d'un quart de l'écran, ils deviennent flous. */
export const EMOJI_MAX_W = 0.25;
export function maxWidth(kind: Layer['kind']): number { return kind === 'emoji' ? EMOJI_MAX_W : MAX_W }
export const TEXT_MAX = 60;
/** Autocollants prêts (dessinés sur l'iPhone, puis cuits dans l'image finale). */
export const EMOJIS = ['❤️', '⭐', '✨', '🌙', '☀️', '🌈', '☁️', '🌸', '🦋', '🦄', '🐱', '🐶', '🎈', '🎀', '👑', '💎', '🎬', '🍿', '🎮', '⚽', '🏒', '🏀', '🎵', '🔥'];
export const TEXT_COLORS = ['#FFFFFF', '#0B0F17', '#FFC46B', '#F4B6C8', '#8FB4FF', '#4CD964'];

export type Background =
  | { kind: 'color'; color: string }
  | { kind: 'template'; id: Template }
  | { kind: 'photo'; key: string; frame: Framing };
export interface Layer {
  id: string;
  kind: 'image' | 'emoji' | 'text';
  /** image : fichier studio/<profil>/<key>.webp */
  key?: string;
  emoji?: string;
  text?: string;
  color?: string;
  /** Centre, en fraction de la largeur et de la hauteur de l'écran. */
  x: number; y: number;
  /** Largeur, en fraction de la largeur de l'écran ; hauteur = largeur × ar (en pixels). */
  w: number; ar: number;
  /** Rotation en radians. */
  rot: number;
}
export interface Project { v: 1; bg: Background; blur: Blur; dim: number; layers: Layer[] }

const COLOR = /^#[0-9A-Fa-f]{6}$/;
const KEY = /^[a-z0-9]{6,40}$/;
const num = (v: unknown, d: number, lo: number, hi: number) => (typeof v === 'number' && Number.isFinite(v) ? Math.min(Math.max(v, lo), hi) : d);

export function newKey(rnd: () => number = Math.random): string {
  let s = '';
  for (let i = 0; i < 14; i++) s += 'abcdefghijklmnopqrstuvwxyz0123456789'[Math.floor(rnd() * 36)];
  return s;
}

export function emptyProject(): Project {
  return { v: 1, bg: { kind: 'template', id: 'degrade' }, blur: 'aucun', dim: DIM_DEFAULT, layers: [] };
}

/** Création relue depuis les réglages : tout ce qui est invalide est corrigé ou écarté (jamais d'erreur). */
export function parseProject(raw: unknown): Project | null {
  if (!raw || typeof raw !== 'object') return null;
  const r = raw as Record<string, unknown>;
  const b = (r.bg ?? {}) as Record<string, unknown>;
  let bg: Background;
  if (b.kind === 'color' && typeof b.color === 'string' && COLOR.test(b.color)) bg = { kind: 'color', color: b.color.toUpperCase() };
  else if (b.kind === 'photo' && typeof b.key === 'string' && KEY.test(b.key)) {
    const f = (b.frame ?? {}) as Record<string, unknown>;
    bg = { kind: 'photo', key: b.key, frame: { zoom: num(f.zoom, 1, 1, ZOOM_MAX), dx: num(f.dx, 0, -1e5, 1e5), dy: num(f.dy, 0, -1e5, 1e5) } };
  } else if (b.kind === 'template' && TEMPLATES.some((t) => t.id === b.id)) bg = { kind: 'template', id: b.id as Template };
  else bg = { kind: 'template', id: 'degrade' };
  const layers: Layer[] = [];
  for (const x of Array.isArray(r.layers) ? r.layers : []) {
    if (layers.length >= MAX_LAYERS || !x || typeof x !== 'object') continue;
    const l = x as Record<string, unknown>;
    const base = {
      id: typeof l.id === 'string' && KEY.test(l.id) ? l.id : newKey(),
      x: num(l.x, 0.5, -0.5, 1.5), y: num(l.y, 0.5, -0.5, 1.5), w: num(l.w, 0.2, MIN_W, l.kind === 'emoji' ? EMOJI_MAX_W : MAX_W), ar: num(l.ar, 1, 0.05, 20), rot: num(l.rot, 0, -Math.PI * 4, Math.PI * 4),
    };
    if (l.kind === 'image' && typeof l.key === 'string' && KEY.test(l.key)) layers.push({ ...base, kind: 'image', key: l.key });
    else if (l.kind === 'emoji' && typeof l.emoji === 'string' && EMOJIS.includes(l.emoji)) layers.push({ ...base, kind: 'emoji', emoji: l.emoji });
    else if (l.kind === 'text' && typeof l.text === 'string' && l.text.trim()) {
      layers.push({ ...base, kind: 'text', text: l.text.slice(0, TEXT_MAX), color: typeof l.color === 'string' && COLOR.test(l.color) ? l.color.toUpperCase() : '#FFFFFF' });
    }
  }
  return {
    v: 1, bg, layers,
    blur: BLURS.some((x) => x.id === r.blur) ? (r.blur as Blur) : 'aucun',
    dim: Math.round(num(r.dim, DIM_DEFAULT, 0, DIM_MAX)),
  };
}

/** Fichiers du compartiment cités par la création (fond photo, images ajoutées). */
export function referencedKeys(p: Project): Set<string> {
  const keys = new Set<string>();
  if (p.bg.kind === 'photo') keys.add(p.bg.key);
  for (const l of p.layers) if (l.kind === 'image' && l.key) keys.add(l.key);
  return keys;
}
export function studioPath(profileId: string, key: string): string { return `studio/${profileId}/${key}.webp` }

/** Nouvel élément au centre, largeur par défaut selon sa nature. */
export function addLayer(p: Project, l: Omit<Layer, 'id' | 'x' | 'y' | 'rot' | 'w'> & Partial<Pick<Layer, 'w'>>, key = newKey()): Project {
  if (p.layers.length >= MAX_LAYERS) return p;
  const w = l.w ?? (l.kind === 'emoji' ? 0.12 : l.kind === 'text' ? 0.4 : 0.25);
  return { ...p, layers: [...p.layers, { ...l, id: key, x: 0.5, y: 0.5, rot: 0, w } as Layer] };
}
export function updateLayer(p: Project, id: string, change: Partial<Layer>): Project {
  return { ...p, layers: p.layers.map((l) => (l.id === id ? { ...l, ...change, w: change.w === undefined ? l.w : Math.min(Math.max(change.w, MIN_W), maxWidth(l.kind)) } : l)) };
}
export function removeLayer(p: Project, id: string): Project { return { ...p, layers: p.layers.filter((l) => l.id !== id) } }
/** Devant (+1) ou derrière (-1) d'un cran. */
export function moveLayer(p: Project, id: string, dir: 1 | -1): Project {
  const i = p.layers.findIndex((l) => l.id === id);
  const j = i + dir;
  if (i < 0 || j < 0 || j >= p.layers.length) return p;
  const layers = [...p.layers];
  [layers[i], layers[j]] = [layers[j]!, layers[i]!];
  return { ...p, layers };
}
export function duplicateLayer(p: Project, id: string, key = newKey()): Project {
  const l = p.layers.find((x) => x.id === id);
  if (!l || p.layers.length >= MAX_LAYERS) return p;
  return { ...p, layers: [...p.layers, { ...l, id: key, x: l.x + 0.04, y: l.y + 0.04 }] };
}

/** Élément le plus en avant sous le point (x, y en pixels d'un écran W × H), rotation comprise ; [pad] : marge pour le doigt. */
export function hitTest(p: Project, x: number, y: number, W: number, H: number, pad = 12): string | null {
  for (let i = p.layers.length - 1; i >= 0; i--) {
    const l = p.layers[i]!;
    const cx = l.x * W, cy = l.y * H, w = l.w * W, h = w * l.ar;
    const dx = x - cx, dy = y - cy;
    const c = Math.cos(-l.rot), s = Math.sin(-l.rot);
    const lx = dx * c - dy * s, ly = dx * s + dy * c;
    if (Math.abs(lx) <= w / 2 + pad && Math.abs(ly) <= h / 2 + pad) return l.id;
  }
  return null;
}

/** Pincer à deux doigts : nouvelle largeur et rotation à partir des positions de départ et actuelles. */
export function pinch(start: Layer, a0: [number, number], b0: [number, number], a1: [number, number], b1: [number, number]): Pick<Layer, 'w' | 'rot'> {
  const d0 = Math.hypot(b0[0] - a0[0], b0[1] - a0[1]) || 1;
  const d1 = Math.hypot(b1[0] - a1[0], b1[1] - a1[1]);
  const t0 = Math.atan2(b0[1] - a0[1], b0[0] - a0[0]);
  const t1 = Math.atan2(b1[1] - a1[1], b1[0] - a1[0]);
  return { w: Math.min(Math.max(start.w * (d1 / d0), MIN_W), maxWidth(start.kind)), rot: start.rot + (t1 - t0) };
}

/** Historique pour « Annuler » / « Rétablir » (40 états au plus). */
export class History {
  private past: Project[] = [];
  private future: Project[] = [];
  constructor(public current: Project) {}
  push(next: Project): Project { this.past.push(this.current); if (this.past.length > 40) this.past.shift(); this.future = []; this.current = next; return next; }
  /** Remplace sans créer d'étape (glisser en cours). */
  replace(next: Project): Project { this.current = next; return next; }
  undo(): Project { const p = this.past.pop(); if (p) { this.future.push(this.current); this.current = p; } return this.current; }
  redo(): Project { const n = this.future.pop(); if (n) { this.past.push(this.current); this.current = n; } return this.current; }
  get canUndo(): boolean { return this.past.length > 0 }
  get canRedo(): boolean { return this.future.length > 0 }
}
