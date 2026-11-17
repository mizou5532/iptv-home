// Atelier de fond d'écran (page iPhone) : fond (couleur, modèle ou photo, flou, voile), éléments posés dessus
// (images détourées, autocollant du profil, emojis, texte) déplacés, agrandis et tournés au doigt, annuler/rétablir,
// aperçu de l'accueil de la télé, enregistrement (image 1920 × 1080 + création rouvrable) et téléchargement en PNG.
import type { AvatarStorage, SettingsDb } from './avatar';
import type { ProfileHome } from './profiles';
import { lookOf, themeColors } from './look';
import { fitSize, removeBackground, stickerUrl, visibleBounds } from './sticker';
import {
  BLURS, DIM_MAX, TEMPLATES, WALL_H, WALL_W, ZOOM_MAX, clampFrame, frameRect, removeWallpaper, saveWallpaper,
  wallpaperUrl, type Blur, type Template,
} from './wallpaper';
import { drawTemplate, exportAlpha, exportBlob, loadCanvas, loadUrl } from './wallpaper-ui';
import {
  EMOJIS, MAX_LAYERS, MIN_W, maxWidth, TEXT_COLORS, TEXT_MAX, History, addLayer, duplicateLayer, emptyProject, hitTest,
  moveLayer, newKey, parseProject, pinch, referencedKeys, removeLayer, studioPath, updateLayer, type Layer, type Project,
} from './studio';

const EMOJI_FONT = '"Apple Color Emoji","Segoe UI Emoji","Noto Color Emoji",sans-serif';
const TEXT_FONT = '800 100px -apple-system, "Inter", "Segoe UI", Roboto, sans-serif';

function esc(s: string): string {
  return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);
}

/** Rapport hauteur/largeur d'un texte (police de l'atelier). */
function textRatio(text: string): number {
  const g = document.createElement('canvas').getContext('2d')!;
  g.font = TEXT_FONT;
  return 125 / Math.max(40, g.measureText(text).width);
}

type Images = Map<string, HTMLCanvasElement>;

/** Fond seul (couleur, modèle ou photo cadrée), avec le flou demandé. */
function drawBackground(g: CanvasRenderingContext2D, W: number, H: number, p: Project, imgs: Images, templates: Map<Template, HTMLCanvasElement>, accent: string): void {
  const src = p.bg.kind === 'template'
    ? (templates.get(p.bg.id) ?? templates.set(p.bg.id, drawTemplate(p.bg.id, accent)).get(p.bg.id)!)
    : p.bg.kind === 'photo' ? imgs.get(p.bg.key) : null;
  if (p.bg.kind === 'color' || !src) { g.fillStyle = p.bg.kind === 'color' ? p.bg.color : '#0B0F17'; g.fillRect(0, 0, W, H); return; }
  const r = p.bg.kind === 'photo' ? frameRect(src.width, src.height, p.bg.frame) : { sx: 0, sy: 0, w: src.width, h: src.height };
  const factor = BLURS.find((b) => b.id === p.blur)!.factor;
  g.imageSmoothingEnabled = true; g.imageSmoothingQuality = 'high';
  if (factor === 1) { g.drawImage(src, r.sx, r.sy, r.w, r.h, 0, 0, W, H); return; }
  // Flou : réduction puis agrandissement, rapporté à 1920 px (même flou dans l'aperçu et l'image finale).
  const sw = Math.max(2, Math.round(WALL_W / factor)), sh = Math.max(2, Math.round(WALL_H / factor));
  const a = document.createElement('canvas'); a.width = sw * 4; a.height = sh * 4;
  a.getContext('2d')!.drawImage(src, r.sx, r.sy, r.w, r.h, 0, 0, a.width, a.height);
  const b = document.createElement('canvas'); b.width = sw; b.height = sh;
  const gb = b.getContext('2d')!; gb.imageSmoothingQuality = 'high'; gb.drawImage(a, 0, 0, sw, sh);
  g.drawImage(b, 0, 0, W, H);
}

function drawLayer(g: CanvasRenderingContext2D, W: number, H: number, l: Layer, imgs: Images): void {
  const w = l.w * W, h = w * l.ar;
  g.save();
  g.translate(l.x * W, l.y * H); g.rotate(l.rot);
  if (l.kind === 'image') {
    const img = l.key ? imgs.get(l.key) : undefined;
    if (img) g.drawImage(img, -w / 2, -h / 2, w, h);
  } else if (l.kind === 'emoji') {
    g.font = `${h * 0.86}px ${EMOJI_FONT}`; g.textAlign = 'center'; g.textBaseline = 'middle';
    g.fillText(l.emoji ?? '', 0, h * 0.04);
  } else {
    const size = h / 1.25;
    g.font = TEXT_FONT.replace('100px', `${size}px`); g.textAlign = 'center'; g.textBaseline = 'middle';
    g.lineJoin = 'round'; g.lineWidth = size * 0.12;
    g.strokeStyle = (l.color ?? '#FFFFFF').toUpperCase() === '#0B0F17' ? 'rgba(255,255,255,.75)' : 'rgba(0,0,0,.45)';
    g.strokeText(l.text ?? '', 0, 0, w);
    g.fillStyle = l.color ?? '#FFFFFF'; g.fillText(l.text ?? '', 0, 0, w);
  }
  g.restore();
}

export function renderProject(target: HTMLCanvasElement, W: number, H: number, p: Project, imgs: Images, templates: Map<Template, HTMLCanvasElement>, accent: string, selected?: string | null): void {
  if (target.width !== W || target.height !== H) { target.width = W; target.height = H; }
  const g = target.getContext('2d')!;
  drawBackground(g, W, H, p, imgs, templates, accent);
  for (const l of p.layers) drawLayer(g, W, H, l, imgs);
  const s = selected ? p.layers.find((l) => l.id === selected) : undefined;
  if (s) {
    const w = s.w * W, h = w * s.ar;
    g.save(); g.translate(s.x * W, s.y * H); g.rotate(s.rot);
    g.setLineDash([8, 6]); g.lineWidth = 2; g.strokeStyle = '#FFFFFF'; g.strokeRect(-w / 2 - 4, -h / 2 - 4, w + 8, h + 8);
    g.setLineDash([]); g.strokeStyle = 'rgba(0,0,0,.6)'; g.lineWidth = 1; g.strokeRect(-w / 2 - 6, -h / 2 - 6, w + 12, h + 12);
    g.restore();
  }
}

/** Image ajoutée : fond uni retiré (si demandé), marges coupées, 1024 px au plus. */
function prepareImage(src: HTMLCanvasElement, cut: boolean): HTMLCanvasElement {
  const g = src.getContext('2d')!;
  const data = g.getImageData(0, 0, src.width, src.height);
  if (cut) removeBackground(data.data, src.width, src.height);
  const bnd = visibleBounds(data.data, src.width, src.height) ?? { x: 0, y: 0, w: src.width, h: src.height };
  const tmp = document.createElement('canvas'); tmp.width = src.width; tmp.height = src.height;
  tmp.getContext('2d')!.putImageData(data, 0, 0);
  const out = fitSize(bnd.w, bnd.h, 1024);
  const c = document.createElement('canvas'); c.width = out.w; c.height = out.h;
  const o = c.getContext('2d')!; o.imageSmoothingQuality = 'high';
  o.drawImage(tmp, bnd.x, bnd.y, bnd.w, bnd.h, 0, 0, out.w, out.h);
  return c;
}

export function openWallpaperEditor(
  root: HTMLElement, base: string, p: ProfileHome, storage: AvatarStorage, db: SettingsDb,
  onSaved: (settings: Record<string, unknown>) => void,
): void {
  const had = p.settings.wallpaper === true;
  const version = typeof p.settings.wallpaper_v === 'number' ? p.settings.wallpaper_v : null;
  const saved = parseProject(p.settings.wallpaper_project);
  const colors = themeColors(lookOf(p.settings, p.theme), p.color);
  const history = new History(saved ?? emptyProject());
  const imgs: Images = new Map();
  const pending = new Set<string>(); // images à envoyer (ajoutées ici)
  const templates = new Map<Template, HTMLCanvasElement>();
  let selected: string | null = null;
  let tab: 'fond' | 'ajouter' | 'element' = 'fond';
  let tvOverlay = false;
  let dirty = false;
  const project = () => history.current;

  root.hidden = false;
  root.innerHTML = `<div class="ped studio">
    <h3>Fond d'écran de ${esc(p.name)}</h3>
    <p class="hint">Derrière les accueils Séries et Films. Touche un élément pour le choisir ; un doigt le déplace, deux doigts l'agrandissent et le tournent.</p>
    <div class="tvwall" id="stbox" style="background:${esc(colors.bg)}">
      <canvas id="stcanvas"></canvas>
      <div class="tvwall-veil" id="stveil" style="background:${esc(colors.bg)}" hidden></div>
      <div class="tvwall-ui" id="stui" hidden><div class="tvwall-bar" style="background:${colors.surface}"></div><div class="tvwall-hero" style="background:${colors.surface}"></div>
        <div class="tvwall-row">${('<span style="background:' + colors.surface + '"></span>').repeat(5)}</div></div>
    </div>
    <div class="tools sttop">
      <button type="button" class="small secondary" id="stundo">↶ Annuler</button>
      <button type="button" class="small secondary" id="stredo">↷ Rétablir</button>
      <button type="button" class="small secondary" id="sttv" aria-pressed="false">Aperçu télé</button>
    </div>
    <div class="seg seg3 sttabs" role="tablist">
      <button type="button" data-tab="fond">Fond</button><button type="button" data-tab="ajouter">Ajouter</button><button type="button" data-tab="element">Élément</button>
    </div>
    <div id="stpanel"></div>
    <input type="file" accept="image/*" id="stbgfile" hidden><input type="file" accept="image/*" id="stimgfile" hidden>
    <div class="tools">
      <button type="button" class="small" id="stsave">Enregistrer</button>
      <button type="button" class="small secondary" id="stdl">Télécharger</button>
      ${had ? '<button type="button" class="small secondary" id="stremove">Retirer le fond</button>' : ''}
      <button type="button" class="small secondary" id="stclose">Fermer</button>
    </div>
    <div id="stmsg" class="msg" role="status"></div>
  </div>`;
  const $ = <T extends HTMLElement>(id: string) => root.querySelector<T>('#' + id)!;
  const box = $('stbox');
  const canvas = $<HTMLCanvasElement>('stcanvas');
  const panel = $('stpanel');
  const msg = $('stmsg');
  const say = (text: string, kind: 'ok' | 'error' = 'error') => { msg.textContent = text; msg.className = `msg ${kind}`; };
  const close = () => { root.hidden = true; root.innerHTML = ''; };

  const paint = () => {
    const ratio = window.devicePixelRatio || 1;
    const W = Math.max(1, Math.round(box.clientWidth * ratio));
    renderProject(canvas, W, Math.round((W * WALL_H) / WALL_W), project(), imgs, templates, p.color, tvOverlay ? null : selected);
    $('stveil').hidden = !tvOverlay; $('stui').hidden = !tvOverlay;
    $('stveil').style.opacity = String(project().dim / 100);
    ($('stundo') as HTMLButtonElement).disabled = !history.canUndo;
    ($('stredo') as HTMLButtonElement).disabled = !history.canRedo;
  };
  const commit = (next: Project) => { history.push(next); dirty = true; paint(); renderPanel(); };
  const sel = () => project().layers.find((l) => l.id === selected) ?? null;

  function renderPanel(): void {
    root.querySelectorAll<HTMLButtonElement>('[data-tab]').forEach((b) => {
      b.setAttribute('aria-pressed', String(b.dataset.tab === tab));
      b.disabled = b.dataset.tab === 'element' && !sel();
    });
    const pr = project();
    if (tab === 'fond') {
      const photo = pr.bg.kind === 'photo';
      panel.innerHTML = `
        <div class="wptpl">${TEMPLATES.map((t) => `<button type="button" data-tpl="${t.id}" aria-pressed="${pr.bg.kind === 'template' && pr.bg.id === t.id}">${t.label}</button>`).join('')}
          <label class="stcolor" aria-pressed="${pr.bg.kind === 'color'}">Couleur unie<input type="color" id="stbgcolor" value="${pr.bg.kind === 'color' ? pr.bg.color : '#1E2A44'}"></label>
          <button type="button" id="stbgphoto" aria-pressed="${photo}">${photo ? 'Changer de photo' : 'Photo…'}</button></div>
        ${photo ? `<label class="hint" for="stzoom">Zoom de la photo (ou pincer le fond)</label><input type="range" id="stzoom" min="1" max="${ZOOM_MAX}" step="0.01" value="${pr.bg.kind === 'photo' ? pr.bg.frame.zoom : 1}">` : ''}
        <p class="hint">Flou du fond</p>
        <div class="seg seg3" role="group" aria-label="Flou">${BLURS.map((b) => `<button type="button" data-blur="${b.id}" aria-pressed="${pr.blur === b.id}">${b.label}</button>`).join('')}</div>
        <label for="stdim" class="hint">Voile pour la lisibilité (télé) : <b>${pr.dim}</b> %</label>
        <input type="range" id="stdim" min="0" max="${DIM_MAX}" step="5" value="${pr.dim}">`;
    } else if (tab === 'ajouter') {
      const full = pr.layers.length >= MAX_LAYERS;
      panel.innerHTML = full ? `<p class="hint">${MAX_LAYERS} éléments au plus : retires-en un pour en ajouter.</p>` : `
        <div class="tools"><button type="button" class="small" id="stimg">Mon image…</button>
          ${p.settings.sticker === true ? '<button type="button" class="small secondary" id="ststicker">Autocollant du profil</button>' : ''}</div>
        <label class="check"><input type="checkbox" id="stcut" checked> Retirer le fond uni de mon image</label>
        <p class="hint">Autocollants</p>
        <div class="stemojis">${EMOJIS.map((e) => `<button type="button" data-emoji="${e}" aria-label="Ajouter ${e}">${e}</button>`).join('')}</div>
        <p class="hint">Texte</p>
        <div class="sttext"><input id="sttextin" maxlength="${TEXT_MAX}" placeholder="Ex. : Bonne soirée Maha"><button type="button" class="small" id="sttextadd">Ajouter</button></div>`;
    } else {
      const l = sel();
      if (!l) { tab = 'ajouter'; renderPanel(); return; }
      panel.innerHTML = `
        <label class="hint" for="stsize">Taille</label><input type="range" id="stsize" min="${MIN_W}" max="${maxWidth(l.kind)}" step="0.005" value="${l.w}">
        <label class="hint" for="strot">Rotation : <b>${Math.round((l.rot * 180) / Math.PI)}°</b></label><input type="range" id="strot" min="-180" max="180" step="1" value="${Math.round((((l.rot * 180) / Math.PI + 540) % 360) - 180)}">
        ${l.kind === 'text' ? `<div class="sttext"><input id="stedit" maxlength="${TEXT_MAX}" value="${esc(l.text ?? '')}"></div>
          <div class="stswatches">${TEXT_COLORS.map((c) => `<button type="button" data-tcolor="${c}" aria-pressed="${(l.color ?? '').toUpperCase() === c}" style="background:${c}" aria-label="Couleur ${c}"></button>`).join('')}</div>` : ''}
        <div class="seg stactions"><button type="button" id="stfront">Devant</button><button type="button" id="stback">Derrière</button>
          <button type="button" id="stdup">Dupliquer</button><button type="button" id="stdel">Supprimer</button></div>`;
    }
  }

  // ---- Panneaux ----
  root.querySelectorAll<HTMLButtonElement>('[data-tab]').forEach((b) => b.addEventListener('click', () => { tab = b.dataset.tab as typeof tab; renderPanel(); }));
  panel.addEventListener('click', async (e) => {
    const b = (e.target as HTMLElement).closest<HTMLElement>('button');
    if (!b) return;
    const pr = project();
    if (b.dataset.tpl) commit({ ...pr, bg: { kind: 'template', id: b.dataset.tpl as Template } });
    else if (b.dataset.blur) commit({ ...pr, blur: b.dataset.blur as Blur });
    else if (b.id === 'stbgphoto') $<HTMLInputElement>('stbgfile').click();
    else if (b.id === 'stimg') $<HTMLInputElement>('stimgfile').click();
    else if (b.dataset.emoji) { const key = newKey(); commit(addLayer(pr, { kind: 'emoji', emoji: b.dataset.emoji, ar: 1 }, key)); selected = key; tab = 'element'; renderPanel(); paint(); }
    else if (b.id === 'sttextadd') {
      const t = $<HTMLInputElement>('sttextin').value.trim();
      if (!t) return;
      const key = newKey();
      commit(addLayer(pr, { kind: 'text', text: t.slice(0, TEXT_MAX), color: colors.light ? '#0B0F17' : '#FFFFFF', ar: textRatio(t) }, key));
      selected = key; tab = 'element'; renderPanel(); paint();
    } else if (b.id === 'ststicker') {
      say('…', 'ok');
      try {
        const c = await loadUrl(stickerUrl(base, p.id, typeof p.settings.sticker_v === 'number' ? p.settings.sticker_v : null));
        const key = newKey(); imgs.set(key, c); pending.add(key);
        commit(addLayer(project(), { kind: 'image', key, ar: c.height / c.width, w: 0.2 }, key));
        selected = key; tab = 'element'; renderPanel(); paint(); say('', 'ok');
      } catch (err) { say((err as Error).message); }
    } else if (selected) {
      if (b.id === 'stfront') commit(moveLayer(pr, selected, 1));
      else if (b.id === 'stback') commit(moveLayer(pr, selected, -1));
      else if (b.id === 'stdup') { const key = newKey(); commit(duplicateLayer(pr, selected, key)); selected = key; paint(); }
      else if (b.id === 'stdel') { const id = selected; selected = null; tab = 'ajouter'; commit(removeLayer(pr, id)); }
      else if (b.dataset.tcolor) commit(updateLayer(pr, selected, { color: b.dataset.tcolor }));
    }
  });
  // Curseurs : une seule étape d'annulation par geste.
  let sliding = false;
  panel.addEventListener('input', (e) => {
    const t = e.target as HTMLInputElement;
    const pr = project();
    const step = (next: Project) => { if (!sliding) { history.push(next); sliding = true; } else history.replace(next); dirty = true; paint(); };
    if (t.id === 'stdim') { step({ ...pr, dim: Number(t.value) }); t.previousElementSibling!.querySelector('b')!.textContent = t.value; }
    else if (t.id === 'stzoom' && pr.bg.kind === 'photo') { const src = imgs.get(pr.bg.key)!; step({ ...pr, bg: { ...pr.bg, frame: clampFrame(src.width, src.height, { ...pr.bg.frame, zoom: Number(t.value) }) } }); }
    else if (t.id === 'stsize' && selected) step(updateLayer(pr, selected, { w: Number(t.value) }));
    else if (t.id === 'strot' && selected) { step(updateLayer(pr, selected, { rot: (Number(t.value) * Math.PI) / 180 })); t.previousElementSibling!.querySelector('b')!.textContent = `${t.value}°`; }
    else if (t.id === 'stedit' && selected && t.value.trim()) step(updateLayer(pr, selected, { text: t.value.slice(0, TEXT_MAX), ar: textRatio(t.value) }));
  });
  panel.addEventListener('change', (e) => {
    const t = e.target as HTMLInputElement;
    sliding = false;
    if (t.id === 'stbgcolor') commit({ ...project(), bg: { kind: 'color', color: t.value.toUpperCase() } });
    // Seuls les curseurs redessinent le panneau (une zone de texte quittée ne doit pas avaler le clic qui suit).
    else if (t.type === 'range') renderPanel();
  });
  $<HTMLInputElement>('stbgfile').addEventListener('change', async (e) => {
    const input = e.currentTarget as HTMLInputElement; const f = input.files?.[0]; input.value = '';
    if (!f) return;
    say('…', 'ok');
    try { const c = await loadCanvas(f); const key = newKey(); imgs.set(key, c); pending.add(key); commit({ ...project(), bg: { kind: 'photo', key, frame: { zoom: 1, dx: 0, dy: 0 } } }); say('', 'ok'); }
    catch (err) { say((err as Error).message); }
  });
  $<HTMLInputElement>('stimgfile').addEventListener('change', async (e) => {
    const input = e.currentTarget as HTMLInputElement; const f = input.files?.[0]; input.value = '';
    if (!f) return;
    say('…', 'ok');
    try {
      const c = prepareImage(await loadCanvas(f), root.querySelector<HTMLInputElement>('#stcut')?.checked ?? true);
      const key = newKey(); imgs.set(key, c); pending.add(key);
      commit(addLayer(project(), { kind: 'image', key, ar: c.height / c.width }, key));
      selected = key; tab = 'element'; renderPanel(); paint(); say('', 'ok');
    } catch (err) { say((err as Error).message); }
  });

  // ---- Gestes sur l'aperçu ----
  const pts = new Map<number, [number, number]>();
  type Gesture = { kind: 'layer' | 'bg'; start: Project; startPts: Map<number, [number, number]>; pushed: boolean } | null;
  let gesture: Gesture = null;
  const local = (e: PointerEvent): [number, number] => { const r = box.getBoundingClientRect(); return [e.clientX - r.left, e.clientY - r.top]; };
  const begin = () => {
    const pr = project();
    const kind = selected ? 'layer' : pr.bg.kind === 'photo' ? 'bg' : null;
    gesture = kind ? { kind, start: pr, startPts: new Map(pts), pushed: gesture?.pushed ?? false } : null;
  };
  box.style.touchAction = 'none';
  box.addEventListener('pointerdown', (e) => {
    try { box.setPointerCapture(e.pointerId); } catch { /* pointeur déjà relâché */ }
    pts.set(e.pointerId, local(e));
    if (tvOverlay) return;
    if (pts.size === 1) {
      const [x, y] = local(e);
      const hit = hitTest(project(), x, y, box.clientWidth, box.clientHeight);
      selected = hit;
      if (hit) tab = 'element'; else if (tab === 'element') tab = 'ajouter';
      renderPanel(); paint();
    }
    begin();
  });
  box.addEventListener('pointermove', (e) => {
    if (!pts.has(e.pointerId) || !gesture) return;
    pts.set(e.pointerId, local(e));
    // Une seule étape d'annulation par geste, créée au premier mouvement (un simple toucher n'en crée pas).
    if (!gesture.pushed) { history.push(gesture.start); gesture.pushed = true; }
    const W = box.clientWidth, H = box.clientHeight;
    const ids = [...gesture.startPts.keys()].filter((id) => pts.has(id));
    const s = gesture.start;
    if (gesture.kind === 'layer' && selected) {
      const l0 = s.layers.find((l) => l.id === selected);
      if (!l0) return;
      if (ids.length >= 2) {
        const [a, b] = ids as [number, number];
        const r = pinch(l0, gesture.startPts.get(a)!, gesture.startPts.get(b)!, pts.get(a)!, pts.get(b)!);
        const mid0 = [(gesture.startPts.get(a)![0] + gesture.startPts.get(b)![0]) / 2, (gesture.startPts.get(a)![1] + gesture.startPts.get(b)![1]) / 2];
        const mid1 = [(pts.get(a)![0] + pts.get(b)![0]) / 2, (pts.get(a)![1] + pts.get(b)![1]) / 2];
        history.replace(updateLayer(s, selected, { ...r, x: l0.x + (mid1[0]! - mid0[0]!) / W, y: l0.y + (mid1[1]! - mid0[1]!) / H }));
      } else if (ids.length === 1) {
        const id = ids[0]!;
        const [x0, y0] = gesture.startPts.get(id)!, [x1, y1] = pts.get(id)!;
        history.replace(updateLayer(s, selected, { x: l0.x + (x1 - x0) / W, y: l0.y + (y1 - y0) / H }));
      }
    } else if (gesture.kind === 'bg' && s.bg.kind === 'photo') {
      const src = imgs.get(s.bg.key);
      if (!src) return;
      const f0 = s.bg.frame;
      if (ids.length >= 2) {
        const [a, b] = ids as [number, number];
        const d0 = Math.hypot(gesture.startPts.get(b)![0] - gesture.startPts.get(a)![0], gesture.startPts.get(b)![1] - gesture.startPts.get(a)![1]) || 1;
        const d1 = Math.hypot(pts.get(b)![0] - pts.get(a)![0], pts.get(b)![1] - pts.get(a)![1]);
        history.replace({ ...s, bg: { ...s.bg, frame: clampFrame(src.width, src.height, { ...f0, zoom: f0.zoom * (d1 / d0) }) } });
      } else if (ids.length === 1) {
        const id = ids[0]!;
        const per = frameRect(src.width, src.height, f0).w / W;
        const [x0, y0] = gesture.startPts.get(id)!, [x1, y1] = pts.get(id)!;
        history.replace({ ...s, bg: { ...s.bg, frame: clampFrame(src.width, src.height, { ...f0, dx: f0.dx - (x1 - x0) * per, dy: f0.dy - (y1 - y0) * per }) } });
      }
    }
    dirty = true; paint();
  });
  const end = (e: PointerEvent) => {
    pts.delete(e.pointerId);
    // Un doigt levé : le geste repart des positions actuelles (pas de saut).
    if (gesture) { const g = gesture; gesture = pts.size ? { kind: g.kind, start: project(), startPts: new Map(pts), pushed: g.pushed } : null; }
    if (!pts.size) renderPanel();
  };
  box.addEventListener('pointerup', end); box.addEventListener('pointercancel', end);

  // ---- Barre ----
  $('stundo').addEventListener('click', () => { history.undo(); if (!sel()) selected = null; dirty = true; paint(); renderPanel(); });
  $('stredo').addEventListener('click', () => { history.redo(); if (!sel()) selected = null; dirty = true; paint(); renderPanel(); });
  $('sttv').addEventListener('click', (e) => { tvOverlay = !tvOverlay; (e.currentTarget as HTMLElement).setAttribute('aria-pressed', String(tvOverlay)); paint(); });
  $('stclose').addEventListener('click', () => { if (!dirty || confirmClose()) close(); });
  const confirmClose = () => {
    if (root.querySelector('#stconfirm')) return false;
    const box2 = document.createElement('div'); box2.id = 'stconfirm'; box2.className = 'tools';
    box2.innerHTML = '<span class="hint">Fermer sans enregistrer ?</span><button type="button" class="small secondary" id="stquit">Fermer quand même</button>';
    msg.before(box2); box2.querySelector('button')!.addEventListener('click', close);
    return false;
  };
  const full = () => { const c = document.createElement('canvas'); renderProject(c, WALL_W, WALL_H, project(), imgs, templates, p.color, null); return c; };
  $('stdl').addEventListener('click', () => {
    full().toBlob((b) => {
      if (!b) return;
      const a = document.createElement('a');
      a.href = URL.createObjectURL(b); a.download = `fond-${p.name.toLowerCase().replace(/[^a-z0-9]+/gi, '-')}.png`;
      a.click(); setTimeout(() => URL.revokeObjectURL(a.href), 4000);
    }, 'image/png');
  });
  $('stsave').addEventListener('click', async (e) => {
    const b = e.currentTarget as HTMLButtonElement;
    b.disabled = true; say('Enregistrement…', 'ok');
    try {
      const pr = project();
      const keys = referencedKeys(pr);
      // 1. Images nouvelles de la création (fond photo opaque ; images ajoutées avec transparence).
      for (const key of keys) {
        if (!pending.has(key)) continue;
        const c = imgs.get(key)!;
        const blob = pr.bg.kind === 'photo' && pr.bg.key === key ? await exportBlob(c) : await exportAlpha(c);
        const up = await storage.upload(studioPath(p.id, key), blob, { upsert: true, contentType: blob.type, cacheControl: '31536000' });
        if (up.error) throw new Error("Une image de la création n'a pas pu être envoyée. Réessaie.");
        pending.delete(key);
      }
      // 2. Image finale (lue par la télé) et création rouvrable.
      const next = await saveWallpaper(storage, db, p.id, await exportBlob(full()), { ...p.settings, wallpaper_project: pr }, pr.dim, Date.now());
      // 3. Images qui ne servent plus (anciennes versions de la création) : effacées sans bloquer.
      const old = saved ? [...referencedKeys(saved)].filter((k) => !keys.has(k)) : [];
      if (old.length) void storage.remove(old.map((k) => studioPath(p.id, k)));
      onSaved(next); close();
    } catch (err) { say((err as Error).message); b.disabled = false; }
  });
  root.querySelector('#stremove')?.addEventListener('click', async () => {
    say('…', 'ok');
    try {
      const keys = saved ? [...referencedKeys(saved)] : [];
      const next = await removeWallpaper(storage, db, p.id, p.settings, Date.now());
      if (keys.length) void storage.remove(keys.map((k) => studioPath(p.id, k)));
      const { wallpaper_project: _drop, ...rest } = next; void _drop;
      onSaved(rest); close();
    } catch (err) { say((err as Error).message); }
  });

  // ---- Ouverture : relit les images de la création (ou le fond d'avant l'atelier, repris comme photo). ----
  renderPanel(); paint();
  window.addEventListener('resize', paint, { passive: true });
  void (async () => {
    try {
      if (saved) {
        say('Ouverture de la création…', 'ok');
        await Promise.all([...referencedKeys(saved)].map(async (k) => imgs.set(k, await loadUrl(`${base}/storage/v1/object/public/web/${studioPath(p.id, k)}`))));
        say('', 'ok');
      } else if (had) {
        const c = await loadUrl(wallpaperUrl(base, p.id, version));
        const key = newKey(); imgs.set(key, c); pending.add(key);
        history.replace({ ...emptyProject(), dim: typeof p.settings.wallpaper_dim === 'number' ? p.settings.wallpaper_dim : emptyProject().dim, bg: { kind: 'photo', key, frame: { zoom: 1, dx: 0, dy: 0 } } });
      }
    } catch (err) { say((err as Error).message); }
    paint(); renderPanel();
  })();
  root.scrollIntoView({ behavior: 'smooth', block: 'start' });
}
