// Éditeur de photo de profil (page iPhone) : choisir une photo, la placer au doigt dans le cercle, zoomer, voir le
// rendu sur l'écran « Qui regarde ? » de la télé (style Doux), puis enregistrer.
import { AVATAR_SIZE, ZOOM_MAX, avatarUrl, clampFraming, cropRect, removeAvatar, saveAvatar, type AvatarStorage, type Framing, type SettingsDb } from './avatar';
import type { ProfileHome } from './profiles';

const EDIT = 260;  // côté du cercle d'édition (px CSS)
const PREVIEW = 96; // photo dans l'aperçu télé
const MAX_SOURCE = 1600; // une photo d'iPhone (4000 px) est réduite avant tout : le glisser reste fluide

function esc(s: string): string {
  return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);
}

/** Photo actuelle (ou la lettre) dans un rond de la couleur du profil. */
export function avatarBadge(base: string, p: ProfileHome, size = 44): string {
  const v = typeof p.settings.avatar_v === 'number' ? p.settings.avatar_v : null;
  return `<span class="avatar" style="width:${size}px;height:${size}px;background:${esc(p.color)}">
    <span class="avatar-letter">${esc(p.name.slice(0, 1).toUpperCase())}</span>
    ${p.settings.avatar === false ? '' : `<img src="${esc(avatarUrl(base, p.id, v))}" alt="" onerror="this.remove()">`}</span>`;
}

async function loadImage(file: File): Promise<HTMLCanvasElement> {
  const url = URL.createObjectURL(file);
  try {
    const img = new Image();
    img.decoding = 'async';
    await new Promise<void>((ok, ko) => { img.onload = () => ok(); img.onerror = () => ko(new Error('Cette photo ne peut pas être lue.')); img.src = url; });
    const scale = Math.min(1, MAX_SOURCE / Math.max(img.naturalWidth, img.naturalHeight));
    const c = document.createElement('canvas');
    c.width = Math.max(1, Math.round(img.naturalWidth * scale));
    c.height = Math.max(1, Math.round(img.naturalHeight * scale));
    c.getContext('2d')!.drawImage(img, 0, 0, c.width, c.height);
    return c;
  } finally {
    URL.revokeObjectURL(url);
  }
}

function draw(target: HTMLCanvasElement, src: HTMLCanvasElement, f: Framing, px: number): void {
  const ratio = window.devicePixelRatio || 1;
  const w = Math.round(px * ratio);
  if (target.width !== w) { target.width = w; target.height = w; }
  const r = cropRect(src.width, src.height, f);
  const g = target.getContext('2d')!;
  g.fillStyle = '#ffffff';
  g.fillRect(0, 0, w, w);
  g.drawImage(src, r.sx, r.sy, r.size, r.size, 0, 0, w, w);
}

/** Image finale (512 px) : WebP si le navigateur sait le faire, sinon JPEG (Safari). */
async function exportImage(src: HTMLCanvasElement, f: Framing): Promise<Blob> {
  const c = document.createElement('canvas');
  c.width = AVATAR_SIZE; c.height = AVATAR_SIZE;
  const r = cropRect(src.width, src.height, f);
  const g = c.getContext('2d')!;
  g.fillStyle = '#ffffff';
  g.fillRect(0, 0, AVATAR_SIZE, AVATAR_SIZE);
  g.drawImage(src, r.sx, r.sy, r.size, r.size, 0, 0, AVATAR_SIZE, AVATAR_SIZE);
  const as = (type: string, q: number) => new Promise<Blob | null>((ok) => c.toBlob(ok, type, q));
  const webp = await as('image/webp', 0.85);
  if (webp && webp.type === 'image/webp') return webp;
  const jpeg = await as('image/jpeg', 0.88);
  if (!jpeg) throw new Error("La photo n'a pas pu être préparée.");
  return jpeg;
}

/**
 * Ouvre l'éditeur pour [p] dans [root]. [onSaved] reçoit les nouveaux réglages (avec avatar_v) une fois enregistré.
 */
export function openAvatarEditor(
  root: HTMLElement, base: string, p: ProfileHome, storage: AvatarStorage, db: SettingsDb,
  onSaved: (settings: Record<string, unknown>) => void,
): void {
  // Photo posée avant cette page (sans réglage) : on propose quand même de la retirer.
  const hasPhoto = p.settings.avatar !== false;
  root.hidden = false;
  root.innerHTML = `<div class="ped">
    <h3>Photo de ${esc(p.name)}</h3>
    <input type="file" accept="image/*" id="pedfile" hidden>
    <div id="pedpick" class="tools">
      <button type="button" class="small" id="pedchoose">Choisir une photo</button>
      ${hasPhoto ? '<button type="button" class="small secondary" id="pedremove">Retirer la photo</button>' : ''}
      <button type="button" class="small secondary" id="pedcancel">Fermer</button>
    </div>
    <div id="pedwork" hidden>
      <p class="hint">Fais glisser la photo avec le doigt pour la placer dans le cercle.</p>
      <canvas id="pedcanvas" class="ped-circle" style="width:${EDIT}px;height:${EDIT}px" aria-label="Photo à placer"></canvas>
      <label for="pedzoom">Zoom</label>
      <input type="range" id="pedzoom" min="1" max="${ZOOM_MAX}" step="0.01" value="1">
      <p class="hint">Aperçu sur la télé (« Qui regarde ? »)</p>
      <div class="tvprev">
        <div class="tvprev-title">Coucou ! Qui regarde ?</div>
        <div class="tvprev-bubble"><span class="tvprev-blob" style="background:${esc(p.color)}"></span>
          <canvas id="pedprev" class="tvprev-photo" style="width:${PREVIEW}px;height:${PREVIEW}px"></canvas></div>
        <div class="tvprev-name">${esc(p.name)}</div>
      </div>
      <div class="tools">
        <button type="button" class="small" id="pedsave">Enregistrer</button>
        <button type="button" class="small secondary" id="pedother">Autre photo</button>
        <button type="button" class="small secondary" id="pedcancel2">Annuler</button>
      </div>
    </div>
    <div id="pedmsg" class="msg" role="status"></div>
  </div>`;
  const $ = <T extends HTMLElement>(id: string) => root.querySelector<T>('#' + id)!;
  const msg = $('pedmsg');
  const say = (text: string, kind: 'ok' | 'error' = 'error') => { msg.textContent = text; msg.className = `msg ${kind}`; };
  const close = () => { root.hidden = true; root.innerHTML = ''; };
  const file = $<HTMLInputElement>('pedfile');
  const canvas = $<HTMLCanvasElement>('pedcanvas');
  const prev = $<HTMLCanvasElement>('pedprev');
  const zoom = $<HTMLInputElement>('pedzoom');
  let src: HTMLCanvasElement | null = null;
  let f: Framing = { zoom: 1, dx: 0, dy: 0 };
  const paint = () => { if (!src) return; draw(canvas, src, f, EDIT); draw(prev, src, f, PREVIEW); };

  $('pedchoose').addEventListener('click', () => file.click());
  $('pedother').addEventListener('click', () => file.click());
  $('pedcancel').addEventListener('click', close);
  $('pedcancel2').addEventListener('click', close);
  file.addEventListener('change', async () => {
    const chosen = file.files?.[0];
    file.value = '';
    if (!chosen) return;
    say('…', 'ok');
    try { src = await loadImage(chosen); } catch (e) { say((e as Error).message); return; }
    f = { zoom: 1, dx: 0, dy: 0 };
    zoom.value = '1';
    $('pedpick').hidden = true;
    $('pedwork').hidden = false;
    say('', 'ok');
    paint();
  });
  zoom.addEventListener('input', () => { if (!src) return; f = clampFraming(src.width, src.height, { ...f, zoom: Number(zoom.value) }); paint(); });
  // Glisser au doigt : le déplacement est converti en pixels de l'image (la photo suit le doigt).
  let last: { x: number; y: number } | null = null;
  canvas.addEventListener('pointerdown', (e) => { last = { x: e.clientX, y: e.clientY }; canvas.setPointerCapture(e.pointerId); });
  canvas.addEventListener('pointermove', (e) => {
    if (!last || !src) return;
    const perPx = cropRect(src.width, src.height, f).size / EDIT;
    f = clampFraming(src.width, src.height, { ...f, dx: f.dx - (e.clientX - last.x) * perPx, dy: f.dy - (e.clientY - last.y) * perPx });
    last = { x: e.clientX, y: e.clientY };
    paint();
  });
  const stop = () => { last = null; };
  canvas.addEventListener('pointerup', stop);
  canvas.addEventListener('pointercancel', stop);

  $('pedsave').addEventListener('click', async (e) => {
    if (!src) return;
    const b = e.currentTarget as HTMLButtonElement;
    b.disabled = true;
    say('Envoi de la photo…', 'ok');
    try {
      const next = await saveAvatar(storage, db, p.id, await exportImage(src, f), p.settings, Date.now());
      onSaved(next);
      close();
    } catch (err) { say((err as Error).message); b.disabled = false; }
  });
  root.querySelector('#pedremove')?.addEventListener('click', async () => {
    say('…', 'ok');
    try { onSaved(await removeAvatar(storage, db, p.id, p.settings, Date.now())); close(); }
    catch (err) { say((err as Error).message); }
  });
  root.scrollIntoView({ behavior: 'smooth', block: 'center' });
}
