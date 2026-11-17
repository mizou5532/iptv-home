// Dessin des fonds d'écran (page iPhone) : lecture d'une photo, modèles aux couleurs du profil, cadrage 16:9 et flou,
// export sous la limite du compartiment. Utilisé par l'atelier (studio-ui.ts).
import {
  BLURS, WALL_H, WALL_MAX_BYTES, WALL_W, frameRect, seeded, type Blur, type Framing, type Template,
} from './wallpaper';

const MAX_SOURCE = 2400;

export async function loadCanvas(file: File): Promise<HTMLCanvasElement> {
  const url = URL.createObjectURL(file);
  try {
    const img = new Image();
    await new Promise<void>((ok, ko) => { img.onload = () => ok(); img.onerror = () => ko(new Error('Cette image ne peut pas être lue.')); img.src = url; });
    const scale = Math.min(1, MAX_SOURCE / Math.max(img.naturalWidth, img.naturalHeight));
    const c = document.createElement('canvas');
    c.width = Math.max(1, Math.round(img.naturalWidth * scale));
    c.height = Math.max(1, Math.round(img.naturalHeight * scale));
    c.getContext('2d')!.drawImage(img, 0, 0, c.width, c.height);
    return c;
  } finally { URL.revokeObjectURL(url); }
}

/** Modèle dessiné à la taille de la télé, aux couleurs du profil. */
export function drawTemplate(t: Template, accent: string): HTMLCanvasElement {
  const c = document.createElement('canvas');
  c.width = WALL_W; c.height = WALL_H;
  const g = c.getContext('2d')!;
  const rnd = seeded(t.length * 97 + accent.length);
  const mix = (a: string, pct: number, b: string) => {
    const p = (h: string) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
    const [x, y] = [p(a), p(b)];
    return `rgb(${x.map((v, i) => Math.round(v * pct + y[i]! * (1 - pct))).join(',')})`;
  };
  const radial = (x: number, y: number, r: number, color: string, alpha: number) => {
    const grad = g.createRadialGradient(x, y, 0, x, y, r);
    grad.addColorStop(0, color.replace('rgb(', 'rgba(').replace(')', `,${alpha})`));
    grad.addColorStop(1, color.replace('rgb(', 'rgba(').replace(')', ',0)'));
    g.fillStyle = grad; g.fillRect(0, 0, WALL_W, WALL_H);
  };
  if (t === 'degrade') {
    const grad = g.createLinearGradient(0, 0, WALL_W, WALL_H);
    grad.addColorStop(0, mix(accent, 0.55, '#0B0F17')); grad.addColorStop(1, mix(accent, 0.12, '#0B0F17'));
    g.fillStyle = grad; g.fillRect(0, 0, WALL_W, WALL_H);
  } else if (t === 'aurore') {
    g.fillStyle = '#070A12'; g.fillRect(0, 0, WALL_W, WALL_H);
    radial(WALL_W * 0.2, WALL_H * 0.3, 900, mix(accent, 1, '#000000'), 0.55);
    radial(WALL_W * 0.8, WALL_H * 0.25, 800, 'rgb(54,194,180)', 0.4);
    radial(WALL_W * 0.6, WALL_H * 0.95, 900, 'rgb(192,132,252)', 0.35);
  } else if (t === 'etoiles') {
    const grad = g.createLinearGradient(0, 0, 0, WALL_H);
    grad.addColorStop(0, '#05070F'); grad.addColorStop(1, mix(accent, 0.25, '#0B1A33'));
    g.fillStyle = grad; g.fillRect(0, 0, WALL_W, WALL_H);
    for (let i = 0; i < 420; i++) {
      const r = rnd() < 0.92 ? 1 + rnd() * 1.6 : 2.5 + rnd() * 2;
      g.fillStyle = `rgba(255,255,255,${0.35 + rnd() * 0.65})`;
      g.beginPath(); g.arc(rnd() * WALL_W, rnd() * WALL_H, r, 0, Math.PI * 2); g.fill();
    }
  } else {
    g.fillStyle = mix(accent, 0.12, '#FFFFFF'); g.fillRect(0, 0, WALL_W, WALL_H);
    for (let i = 0; i < 26; i++) {
      g.fillStyle = mix(accent, 0.25 + rnd() * 0.5, '#FFFFFF').replace('rgb(', 'rgba(').replace(')', `,${0.25 + rnd() * 0.35})`);
      g.beginPath(); g.arc(rnd() * WALL_W, rnd() * WALL_H, 40 + rnd() * 220, 0, Math.PI * 2); g.fill();
    }
  }
  return c;
}

/** Dessine le cadre choisi dans [target] (w × h), flou compris (réduction puis agrandissement : rapide partout). */
export function render(target: HTMLCanvasElement, src: HTMLCanvasElement, f: Framing, blur: Blur, w: number, h: number): void {
  if (target.width !== w) { target.width = w; target.height = h; }
  const g = target.getContext('2d')!;
  g.imageSmoothingEnabled = true; g.imageSmoothingQuality = 'high';
  const r = frameRect(src.width, src.height, f);
  const factor = BLURS.find((b) => b.id === blur)!.factor;
  if (factor === 1) { g.drawImage(src, r.sx, r.sy, r.w, r.h, 0, 0, w, h); return; }
  // Taille réduite rapportée à l'image finale : même flou dans l'aperçu et sur la télé.
  const sw = Math.max(2, Math.round(WALL_W / factor)), sh = Math.max(2, Math.round(WALL_H / factor));
  const a = document.createElement('canvas'); a.width = sw * 4; a.height = sh * 4;
  const b = document.createElement('canvas'); b.width = sw; b.height = sh;
  const ga = a.getContext('2d')!; ga.imageSmoothingQuality = 'high';
  ga.drawImage(src, r.sx, r.sy, r.w, r.h, 0, 0, a.width, a.height);
  const gb = b.getContext('2d')!; gb.imageSmoothingQuality = 'high';
  gb.drawImage(a, 0, 0, sw, sh);
  g.drawImage(b, 0, 0, w, h);
}

/** Image opaque (fond) : WebP, sinon JPEG ; qualité baissée jusqu'à passer sous la limite du compartiment. */
export async function exportBlob(c: HTMLCanvasElement): Promise<Blob> {
  for (const q of [0.9, 0.82, 0.72, 0.6, 0.5]) {
    const as = (type: string) => new Promise<Blob | null>((ok) => c.toBlob(ok, type, q));
    let blob = await as('image/webp');
    if (!blob || blob.type !== 'image/webp') blob = await as('image/jpeg');
    if (!blob) throw new Error("L'image n'a pas pu être préparée.");
    if (blob.size <= WALL_MAX_BYTES) return blob;
  }
  throw new Error('Image trop détaillée : essaie un peu de flou.');
}


/** Image avec transparence (élément ajouté) : WebP, sinon PNG (Safari). */
export async function exportAlpha(c: HTMLCanvasElement): Promise<Blob> {
  const as = (type: string) => new Promise<Blob | null>((ok) => c.toBlob(ok, type, 0.92));
  const webp = await as('image/webp');
  if (webp && webp.type === 'image/webp' && webp.size <= WALL_MAX_BYTES) return webp;
  const png = await as('image/png');
  if (!png || png.size > WALL_MAX_BYTES) throw new Error('Image trop lourde : choisis-en une plus petite.');
  return png;
}

/** Image en ligne lue dans un canevas (sans « salir » le canevas : le compartiment autorise toutes les origines). */
export async function loadUrl(url: string): Promise<HTMLCanvasElement> {
  const img = new Image();
  img.crossOrigin = 'anonymous';
  await new Promise<void>((ok, ko) => { img.onload = () => ok(); img.onerror = () => ko(new Error("Une image de la création n'a pas pu être relue.")); img.src = url; });
  const c = document.createElement('canvas');
  c.width = img.naturalWidth; c.height = img.naturalHeight;
  c.getContext('2d')!.drawImage(img, 0, 0);
  return c;
}
