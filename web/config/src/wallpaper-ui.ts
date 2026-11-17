// Éditeur de fond d'écran (page iPhone) : photo ou modèle, cadrage 16:9 au doigt, zoom, flou, voile, aperçu de
// l'accueil de la télé, puis enregistrer pour le profil ou télécharger l'image (PNG 1920 × 1080).
import type { AvatarStorage, SettingsDb } from './avatar';
import type { ProfileHome } from './profiles';
import { lookOf, themeColors } from './look';
import {
  BLURS, DIM_MAX, TEMPLATES, WALL_H, WALL_MAX_BYTES, WALL_W, ZOOM_MAX, clampFrame, dimOf, frameRect, removeWallpaper,
  saveWallpaper, saveWallpaperDim, seeded, wallpaperUrl, type Blur, type Framing, type Template,
} from './wallpaper';

const MAX_SOURCE = 2400;

function esc(s: string): string {
  return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);
}

async function loadCanvas(file: File): Promise<HTMLCanvasElement> {
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
function render(target: HTMLCanvasElement, src: HTMLCanvasElement, f: Framing, blur: Blur, w: number, h: number): void {
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

async function exportBlob(c: HTMLCanvasElement): Promise<Blob> {
  for (const q of [0.9, 0.82, 0.72, 0.6]) {
    const as = (type: string) => new Promise<Blob | null>((ok) => c.toBlob(ok, type, q));
    let blob = await as('image/webp');
    if (!blob || blob.type !== 'image/webp') blob = await as('image/jpeg');
    if (!blob) throw new Error("L'image n'a pas pu être préparée.");
    if (blob.size <= WALL_MAX_BYTES) return blob;
  }
  throw new Error('Image trop détaillée : essaie un peu de flou.');
}

export function openWallpaperEditor(
  root: HTMLElement, base: string, p: ProfileHome, storage: AvatarStorage, db: SettingsDb,
  onSaved: (settings: Record<string, unknown>) => void,
): void {
  const has = p.settings.wallpaper === true;
  const v = typeof p.settings.wallpaper_v === 'number' ? p.settings.wallpaper_v : null;
  const colors = themeColors(lookOf(p.settings, p.theme), p.color);
  let dim = dimOf(p.settings);
  let blur: Blur = 'aucun';
  root.hidden = false;
  root.innerHTML = `<div class="ped">
    <h3>Fond d'écran de ${esc(p.name)}</h3>
    <p class="hint">Derrière les accueils Séries et Films de la télé. Fais glisser l'image pour la placer.</p>
    <input type="file" accept="image/*" id="wpfile" hidden>
    <div class="tvwall" id="wpbox" style="background:${esc(colors.bg)}">
      ${has ? `<img id="wpcur" src="${esc(wallpaperUrl(base, p.id, v))}" alt="">` : ''}
      <canvas id="wpcanvas" hidden></canvas>
      <div class="tvwall-veil" id="wpveil" style="background:${esc(colors.bg)}"></div>
      <div class="tvwall-ui"><div class="tvwall-bar" style="background:${colors.surface}"></div><div class="tvwall-hero" style="background:${colors.surface}"></div>
        <div class="tvwall-row">${('<span style="background:' + colors.surface + '"></span>').repeat(5)}</div></div>
    </div>
    <p class="hint">Modèles</p>
    <div class="wptpl">${TEMPLATES.map((t) => `<button type="button" data-tpl="${t.id}">${t.label}</button>`).join('')}</div>
    <div id="wpzoombox" hidden><label for="wpzoom">Zoom</label><input type="range" id="wpzoom" min="1" max="${ZOOM_MAX}" step="0.01" value="1"></div>
    <div id="wpblurbox" hidden><p class="hint">Flou</p>
    <div class="seg seg3" role="group" aria-label="Flou">${BLURS.map((b) => `<button type="button" data-blur="${b.id}" aria-pressed="${b.id === blur}">${b.label}</button>`).join('')}</div></div>
    <label for="wpdim" class="hint">Voile pour la lisibilité : <b id="wpdimv">${dim}</b> %</label>
    <input type="range" id="wpdim" min="0" max="${DIM_MAX}" step="5" value="${dim}">
    <div class="tools">
      <button type="button" class="small" id="wpchoose">${has ? 'Changer de photo' : 'Choisir une photo'}</button>
      <button type="button" class="small" id="wpsave" ${has ? '' : 'hidden'}>Enregistrer</button>
      <button type="button" class="small secondary" id="wpdl" hidden>Télécharger</button>
      ${has ? '<button type="button" class="small secondary" id="wpremove">Retirer le fond</button>' : ''}
      <button type="button" class="small secondary" id="wpclose">Fermer</button>
    </div>
    <div id="wpmsg" class="msg" role="status"></div>
  </div>`;
  const $ = <T extends HTMLElement>(id: string) => root.querySelector<T>('#' + id)!;
  const box = $('wpbox');
  const canvas = $<HTMLCanvasElement>('wpcanvas');
  const msg = $('wpmsg');
  const say = (text: string, kind: 'ok' | 'error' = 'error') => { msg.textContent = text; msg.className = `msg ${kind}`; };
  const close = () => { root.hidden = true; root.innerHTML = ''; };
  let src: HTMLCanvasElement | null = null;
  let f: Framing = { zoom: 1, dx: 0, dy: 0 };
  const veil = () => { $('wpveil').style.opacity = String(dim / 100); $('wpdimv').textContent = String(dim); };
  const paint = () => {
    if (!src) return;
    const ratio = window.devicePixelRatio || 1;
    const w = Math.round(box.clientWidth * ratio);
    render(canvas, src, f, blur, w, Math.round((w * WALL_H) / WALL_W));
  };
  const use = (c: HTMLCanvasElement) => {
    src = c; f = { zoom: 1, dx: 0, dy: 0 }; $<HTMLInputElement>('wpzoom').value = '1';
    root.querySelector('#wpcur')?.remove();
    canvas.hidden = false; $('wpzoombox').hidden = false; $('wpblurbox').hidden = false;
    $('wpsave').hidden = false; $('wpdl').hidden = false;
    paint();
  };
  veil();

  $('wpchoose').addEventListener('click', () => $<HTMLInputElement>('wpfile').click());
  $('wpclose').addEventListener('click', close);
  $<HTMLInputElement>('wpfile').addEventListener('change', async (e) => {
    const input = e.currentTarget as HTMLInputElement;
    const chosen = input.files?.[0];
    input.value = '';
    if (!chosen) return;
    say('…', 'ok');
    try { use(await loadCanvas(chosen)); say('', 'ok'); } catch (err) { say((err as Error).message); }
  });
  root.querySelectorAll<HTMLButtonElement>('[data-tpl]').forEach((b) => b.addEventListener('click', () => use(drawTemplate(b.dataset.tpl as Template, p.color))));
  root.querySelectorAll<HTMLButtonElement>('[data-blur]').forEach((b) => b.addEventListener('click', () => {
    blur = b.dataset.blur as Blur;
    root.querySelectorAll('[data-blur]').forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
    paint();
  }));
  $<HTMLInputElement>('wpzoom').addEventListener('input', (e) => {
    if (!src) return;
    f = clampFrame(src.width, src.height, { ...f, zoom: Number((e.target as HTMLInputElement).value) }); paint();
  });
  $<HTMLInputElement>('wpdim').addEventListener('input', (e) => { dim = Number((e.target as HTMLInputElement).value); veil(); $('wpsave').hidden = false; });
  // Glisser au doigt : l'image suit le doigt.
  let last: { x: number; y: number } | null = null;
  box.style.touchAction = 'none';
  box.addEventListener('pointerdown', (e) => { if (!src) return; last = { x: e.clientX, y: e.clientY }; box.setPointerCapture(e.pointerId); });
  box.addEventListener('pointermove', (e) => {
    if (!last || !src) return;
    const perPx = frameRect(src.width, src.height, f).w / box.clientWidth;
    f = clampFrame(src.width, src.height, { ...f, dx: f.dx - (e.clientX - last.x) * perPx, dy: f.dy - (e.clientY - last.y) * perPx });
    last = { x: e.clientX, y: e.clientY }; paint();
  });
  const stop = () => { last = null; };
  box.addEventListener('pointerup', stop); box.addEventListener('pointercancel', stop);

  const full = () => { const c = document.createElement('canvas'); render(c, src!, f, blur, WALL_W, WALL_H); return c; };
  $('wpdl').addEventListener('click', () => {
    if (!src) return;
    full().toBlob((b) => {
      if (!b) return;
      const a = document.createElement('a');
      a.href = URL.createObjectURL(b); a.download = `fond-${p.name.toLowerCase().replace(/[^a-z0-9]+/gi, '-')}.png`;
      a.click(); setTimeout(() => URL.revokeObjectURL(a.href), 4000);
    }, 'image/png');
  });
  $('wpsave').addEventListener('click', async (e) => {
    const b = e.currentTarget as HTMLButtonElement;
    b.disabled = true; say('Enregistrement…', 'ok');
    try {
      const next = src
        ? await saveWallpaper(storage, db, p.id, await exportBlob(full()), p.settings, dim, Date.now())
        : await saveWallpaperDim(db, p.id, p.settings, dim);
      onSaved(next); close();
    } catch (err) { say((err as Error).message); b.disabled = false; }
  });
  root.querySelector('#wpremove')?.addEventListener('click', async () => {
    say('…', 'ok');
    try { onSaved(await removeWallpaper(storage, db, p.id, p.settings, Date.now())); close(); }
    catch (err) { say((err as Error).message); }
  });
  root.scrollIntoView({ behavior: 'smooth', block: 'center' });
}
