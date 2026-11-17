// Éditeur d'autocollant (page iPhone) : choisir une image, retirer son fond uni, voir où elle se pose sur l'accueil de
// la télé (coin, taille), puis enregistrer. Jamais sur une vidéo : la télé ne l'affiche que sur les accueils.
import type { AvatarStorage, SettingsDb } from './avatar';
import type { ProfileHome } from './profiles';
import {
  STICKER_CORNERS, STICKER_GIF_MAX_BYTES, STICKER_SIZES, fitSize, removeBackground, removeSticker, saveSticker, saveStickerLook,
  stickerCorner, stickerSize, stickerUrl, visibleBounds, type StickerCorner, type StickerSize,
} from './sticker';

const TV_W = 300;        // largeur de l'aperçu télé (px CSS)
const TV_DP = 960;       // largeur de l'écran de la télé (dp)
const MAX_SOURCE = 1600; // une photo d'iPhone est réduite avant tout

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

/** Image prête à envoyer : fond retiré (si demandé), marges vides coupées, 512 px au plus. */
function prepare(src: HTMLCanvasElement, cutBackground: boolean): HTMLCanvasElement {
  const g = src.getContext('2d')!;
  const data = g.getImageData(0, 0, src.width, src.height);
  if (cutBackground) removeBackground(data.data, src.width, src.height);
  const b = visibleBounds(data.data, src.width, src.height) ?? { x: 0, y: 0, w: src.width, h: src.height };
  const tmp = document.createElement('canvas');
  tmp.width = src.width; tmp.height = src.height;
  tmp.getContext('2d')!.putImageData(data, 0, 0);
  const out = fitSize(b.w, b.h);
  const c = document.createElement('canvas');
  c.width = out.w; c.height = out.h;
  const o = c.getContext('2d')!;
  o.imageSmoothingQuality = 'high';
  o.drawImage(tmp, b.x, b.y, b.w, b.h, 0, 0, out.w, out.h);
  return c;
}

async function exportBlob(c: HTMLCanvasElement): Promise<Blob> {
  const as = (type: string) => new Promise<Blob | null>((ok) => c.toBlob(ok, type, 0.92));
  const webp = await as('image/webp');
  if (webp && webp.type === 'image/webp') return webp;
  const png = await as('image/png');
  if (!png) throw new Error("L'image n'a pas pu être préparée.");
  return png;
}

export function openStickerEditor(
  root: HTMLElement, base: string, p: ProfileHome, storage: AvatarStorage, db: SettingsDb,
  onSaved: (settings: Record<string, unknown>) => void,
): void {
  const has = p.settings.sticker === true;
  const v = typeof p.settings.sticker_v === 'number' ? p.settings.sticker_v : null;
  let corner: StickerCorner = stickerCorner(p.settings);
  let size: StickerSize = stickerSize(p.settings);
  root.hidden = false;
  root.innerHTML = `<div class="ped">
    <h3>Autocollant de ${esc(p.name)}</h3>
    <p class="hint">Il s'affiche dans un coin des accueils Séries et Films de la télé, jamais sur une vidéo.</p>
    <input type="file" accept="image/*" id="stkfile" hidden>
    <div class="tvstk" aria-label="Aperçu sur la télé"><div class="tvstk-bar"></div><div class="tvstk-hero"></div>
      <div class="tvstk-row"></div><div class="tvstk-row"></div>
      <img id="stkimg" alt="" ${has ? `src="${esc(stickerUrl(base, p.id, v))}"` : 'hidden'}></div>
    <label class="check" id="stkcutbox" hidden><input type="checkbox" id="stkcut" checked> Retirer le fond uni</label>
    <p class="hint">Coin</p>
    <div class="seg" role="group" aria-label="Coin">${STICKER_CORNERS.map((c) => `<button type="button" data-corner="${c.id}" aria-pressed="${c.id === corner}">${c.label}</button>`).join('')}</div>
    <p class="hint">Taille</p>
    <div class="seg seg3" role="group" aria-label="Taille">${STICKER_SIZES.map((s) => `<button type="button" data-size="${s.id}" aria-pressed="${s.id === size}">${s.label}</button>`).join('')}</div>
    <div class="tools">
      <button type="button" class="small" id="stkchoose">${has ? "Changer d'image" : 'Choisir une image'}</button>
      <button type="button" class="small" id="stksave" ${has ? '' : 'hidden'}>Enregistrer</button>
      ${has ? '<button type="button" class="small secondary" id="stkremove">Retirer l’autocollant</button>' : ''}
      <button type="button" class="small secondary" id="stkclose">Fermer</button>
    </div>
    <div id="stkmsg" class="msg" role="status"></div>
  </div>`;
  const $ = <T extends HTMLElement>(id: string) => root.querySelector<T>('#' + id)!;
  const img = $<HTMLImageElement>('stkimg');
  const msg = $('stkmsg');
  const say = (text: string, kind: 'ok' | 'error' = 'error') => { msg.textContent = text; msg.className = `msg ${kind}`; };
  const close = () => { root.hidden = true; root.innerHTML = ''; };
  let src: HTMLCanvasElement | null = null;
  let ready: HTMLCanvasElement | null = null;
  let gif: File | null = null;
  let objectUrl: string | null = null;

  const place = () => {
    const px = (STICKER_SIZES.find((s) => s.id === size)!.px * TV_W) / TV_DP;
    img.style.width = `${px}px`; img.style.height = `${px}px`;
    img.style.left = corner === 'bas-gauche' ? '6px' : ''; img.style.right = corner === 'bas-gauche' ? '' : '6px';
  };
  const showImage = (url: string) => { if (objectUrl) URL.revokeObjectURL(objectUrl); objectUrl = url.startsWith('blob:') ? url : null; img.src = url; img.hidden = false; };
  const render = async () => {
    if (!src) return;
    ready = prepare(src, $<HTMLInputElement>('stkcut').checked);
    showImage(URL.createObjectURL(await exportBlob(ready)));
  };
  place();

  root.querySelectorAll<HTMLButtonElement>('[data-corner]').forEach((b) => b.addEventListener('click', () => {
    corner = b.dataset.corner as StickerCorner;
    root.querySelectorAll('[data-corner]').forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
    place(); $('stksave').hidden = false;
  }));
  root.querySelectorAll<HTMLButtonElement>('[data-size]').forEach((b) => b.addEventListener('click', () => {
    size = b.dataset.size as StickerSize;
    root.querySelectorAll('[data-size]').forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
    place(); $('stksave').hidden = false;
  }));
  $('stkchoose').addEventListener('click', () => $<HTMLInputElement>('stkfile').click());
  $('stkclose').addEventListener('click', close);
  $<HTMLInputElement>('stkcut').addEventListener('change', () => { void render(); });
  $<HTMLInputElement>('stkfile').addEventListener('change', async (e) => {
    const input = e.currentTarget as HTMLInputElement;
    const chosen = input.files?.[0];
    input.value = '';
    if (!chosen) return;
    say('…', 'ok');
    src = null; ready = null; gif = null;
    if (chosen.type === 'image/gif') {
      if (chosen.size > STICKER_GIF_MAX_BYTES) { say('Ce GIF est trop lourd (3 Mo au plus).'); return; }
      gif = chosen; $('stkcutbox').hidden = true;
      showImage(URL.createObjectURL(chosen));
    } else {
      try { src = await loadCanvas(chosen); } catch (err) { say((err as Error).message); return; }
      $('stkcutbox').hidden = false;
      await render();
    }
    $('stksave').hidden = false;
    say('', 'ok');
  });
  $('stksave').addEventListener('click', async (e) => {
    const b = e.currentTarget as HTMLButtonElement;
    b.disabled = true;
    say('Enregistrement…', 'ok');
    try {
      const next = gif || ready
        ? await saveSticker(storage, db, p.id, gif ?? await exportBlob(ready!), p.settings, corner, size, Date.now())
        : await saveStickerLook(db, p.id, p.settings, corner, size);
      onSaved(next);
      close();
    } catch (err) { say((err as Error).message); b.disabled = false; }
  });
  root.querySelector('#stkremove')?.addEventListener('click', async () => {
    say('…', 'ok');
    try { onSaved(await removeSticker(storage, db, p.id, p.settings, Date.now())); close(); }
    catch (err) { say((err as Error).message); }
  });
  root.scrollIntoView({ behavior: 'smooth', block: 'center' });
}
