// Onglet « Organiser » : rendu DOM sans framework, dans le style de main.ts.
// Pour ajouter un écran (chaînes, favoris, listes, recherche), ajouter une entrée à TABS.
import type { Db } from '../flow';
import { mountPlaybackAdmin } from './playback-admin';
import { mountGroups } from './groups-view';
import {
  orgCategories, orgChannels, orgFavorites, orgHideAllExcept, orgPlaylistCreate, orgPlaylistDelete, orgPlaylistRename,
  orgPlaylistSetItems, orgPlaylists, orgPlaylistsReorder, orgReorderFavorites, orgSearch, orgSetCategoryOrder,
  orgSetFavorite, orgSetHidden, orgSeriesCategories, orgSeriesHideAllExcept, orgSetSeriesCategoryOrder, type Category, type Channel, type FavoriteRow, type Playlist, type PlaylistItem, type SearchHit,
} from './api';
import { hideChannelConfirm, pageLoader, type PageLoader } from './channels';
import { reorder, stateLabel } from './favorites';
import { addTo, NAME_MAX, validName } from './playlists';
import { searchNorm } from './norm';
import { confirmText, createGate, EMPTY_TEXT, type CategoryKind, filterCategories, hiddenInScope, hideAllExceptPlan, isLocked, moveCategory } from './categories';

export const ROW_H = 52;
const OVERSCAN = 6;

export interface Profile { id: string; name: string; position: number }
// Contexte partagé par tous les écrans de l'onglet.
export interface OrgCtx {
  db: Db; householdId: string; profiles: Profile[];
  scope: string | null; // id du profil, ou null = « Tout le monde »
  root: HTMLElement;    // zone de contenu de l'écran courant
}
export interface OrgTab { id: string; label: string; render(ctx: OrgCtx): void | Promise<void> }

// L'onglet « Lecture » appelle les RPC de la migration 0010 (réservation de lecture), livrée à l'étape 8 :
// absent de la version publiée, activé seulement par VITE_PLAYBACK_ADMIN=1 à la construction.
const PLAYBACK_ADMIN = import.meta.env.VITE_PLAYBACK_ADMIN === '1';
export const TABS: OrgTab[] = [
  ...(PLAYBACK_ADMIN ? [{ id: 'playback', label: 'Lecture', render: (ctx: OrgCtx) => { startScreen(); return mountPlaybackAdmin(ctx); } }] : []),
  { id: 'copies', label: 'Copies', render: (ctx) => { startScreen(); return mountGroups(ctx); } },
  { id: 'categories', label: 'Catégories', render: (ctx) => renderCategories(ctx) },
  { id: 'favorites', label: 'Favoris', render: (ctx) => renderFavorites(ctx) },
  { id: 'playlists', label: 'Playlists', render: (ctx) => renderPlaylists(ctx) },
];
const SEARCH_MIN = 2;
const SEARCH_LIMIT = 200;
const SEARCH_DELAY = 250;

function esc(s: string): string {
  return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);
}

export async function mountOrganiser(el: HTMLElement, db: Db, householdId: string): Promise<void> {
  el.innerHTML = '<p>Chargement…</p>';
  let profiles: Profile[] = [];
  try {
    const r = await db.from('profiles').select('id, name, position');
    if (r.error) throw new Error(r.error.message);
    profiles = ((r.data ?? []) as Profile[]).slice().sort((a, b) => a.position - b.position);
  } catch {
    el.innerHTML = '<p class="msg error">Impossible de lire les profils. Recharge la page.</p>';
    return;
  }
  const state = { scope: (profiles[0]?.id ?? null) as string | null, tab: 'categories', query: '' };
  el.innerHTML = `<input id="gsearch" type="search" placeholder="Chercher une chaîne" aria-label="Chercher une chaîne" autocomplete="off" autocapitalize="off">
    <div class="chips" id="scope" role="group" aria-label="Pour qui ?"></div>
    <div class="tabs" id="subtabs" role="tablist"></div>
    <div id="org-content"></div>`;
  const scopeEl = el.querySelector<HTMLElement>('#scope')!;
  const subtabs = el.querySelector<HTMLElement>('#subtabs')!;
  const content = el.querySelector<HTMLElement>('#org-content')!;
  const gsearch = el.querySelector<HTMLInputElement>('#gsearch')!;

  const show = (): void => {
    scopeEl.innerHTML = [...profiles.map((p) => ({ id: p.id as string | null, name: p.name })), { id: null, name: 'Tout le monde' }]
      .map((s) => `<button type="button" class="chip" aria-pressed="${s.id === state.scope}" data-scope="${esc(s.id ?? '')}">${esc(s.name)}</button>`).join('');
    subtabs.innerHTML = TABS.map((t) => `<button type="button" class="tab" role="tab" aria-selected="${state.query.length < SEARCH_MIN && t.id === state.tab}" data-tab="${esc(t.id)}">${esc(t.label)}</button>`).join('');
    gsearch.hidden = state.tab === 'copies' || state.tab === 'playback';
    const ctx: OrgCtx = { db, householdId, profiles, scope: state.scope, root: content };
    if (state.query.length >= SEARCH_MIN) { void renderSearch(ctx, state.query); return; }
    void TABS.find((t) => t.id === state.tab)!.render(ctx);
  };
  subtabs.addEventListener('click', (e) => {
    const b = (e.target as HTMLElement).closest<HTMLButtonElement>('button[data-tab]');
    if (!b) return;
    state.tab = b.dataset.tab!;
    gsearch.value = ''; state.query = '';
    show();
  });
  bindSearch(gsearch, (q) => { if (q === state.query) return; state.query = q; show(); });
  scopeEl.addEventListener('click', (e) => {
    const b = (e.target as HTMLElement).closest<HTMLButtonElement>('button[data-scope]');
    if (!b) return;
    state.scope = b.dataset.scope ? b.dataset.scope : null;
    show();
  });
  show();
}

// Nettoyage des écouteurs et minuteurs de l'écran courant ; chaque écran repart de zéro.
const cleanups: (() => void)[] = [];
const renderGate = createGate();
function startScreen(): number {
  for (const fn of cleanups.splice(0)) fn();
  return renderGate.next();
}
const addCleanup = (fn: () => void): void => { cleanups.push(fn); };

// Une file : les écritures d'un même écran partent l'une après l'autre, dans l'ordre des touches.
function serial(): <T>(fn: () => Promise<T>) => Promise<T> {
  let tail: Promise<unknown> = Promise.resolve();
  return (fn) => { const r = tail.then(fn); tail = r.catch(() => undefined); return r; };
}

// Recherche : on attend la fin de la frappe, puis on normalise.
function bindSearch(input: HTMLInputElement, cb: (normalized: string) => void): () => void {
  let t: ReturnType<typeof setTimeout> | undefined;
  const on = (): void => { clearTimeout(t); t = setTimeout(() => cb(searchNorm(input.value)), SEARCH_DELAY); };
  input.addEventListener('input', on);
  return () => { clearTimeout(t); input.removeEventListener('input', on); };
}

type Mode = 'normal' | 'reorder' | 'pick';


// Onglet Catégories : sélecteur Direct / Séries ; le dernier choix est gardé le temps de la session.
let catKind: CategoryKind = 'live';
async function renderCategories(ctx: OrgCtx): Promise<void> {
  ctx.root.innerHTML = `<div class="chips" id="kind" role="group" aria-label="Direct ou Séries"></div><div id="kind-body"></div>`;
  const bar = ctx.root.querySelector<HTMLElement>('#kind')!;
  const body = ctx.root.querySelector<HTMLElement>('#kind-body')!;
  const paintBar = (): void => {
    bar.innerHTML = ([['live', 'Direct'], ['series', 'Séries']] as const)
      .map(([k, label]) => `<button type="button" class="chip" aria-pressed="${k === catKind}" data-kind="${k}">${label}</button>`).join('');
  };
  bar.addEventListener('click', (e) => {
    const k = (e.target as HTMLElement).closest<HTMLButtonElement>('button[data-kind]')?.dataset.kind as CategoryKind | undefined;
    if (!k || k === catKind) return;
    catKind = k; paintBar(); void renderCategoryList(ctx, k, body);
  });
  paintBar();
  await renderCategoryList(ctx, catKind, body);
}

async function renderCategoryList(ctx: OrgCtx, kind: CategoryKind, root: HTMLElement): Promise<void> {
  const token = startScreen();
  const { db, householdId: h, scope } = ctx;
  const series = kind === 'series';
  const loadCats = series ? orgSeriesCategories : orgCategories;
  const hideAllExcept = series ? orgSeriesHideAllExcept : orgHideAllExcept;
  const setOrder = series ? orgSetSeriesCategoryOrder : orgSetCategoryOrder;
  const hiddenType = series ? 'series_category' : 'live_category';
  const everyone = scope === null;
  const scopeName = everyone ? 'Tout le monde' : ctx.profiles.find((p) => p.id === scope)?.name ?? '';
  root.innerHTML = '<p>Chargement…</p>';
  let all: Category[];
  try { all = await loadCats(db, h, scope); }
  catch (e) { if (renderGate.isCurrent(token)) root.innerHTML = `<p class="msg error">${esc((e as Error).message)}</p>`; return; }
  if (!renderGate.isCurrent(token)) return;
  if (all.length === 0) {
    root.innerHTML = `<section class="card"><p>${esc(EMPTY_TEXT[kind])}</p></section>`;
    return;
  }

  let mode: Mode = 'normal';
  let query = '';
  let pick = new Set<string>();
  // Confirmation intégrée à la page (les confirm() natives sont rejetées d'office
  // par certains navigateurs et environnements automatisés) : texte + action à confirmer.
  let pending: { text: string; run: () => void } | null = null;
  let snapshot: Category[] = all;
  let dragging: { from: number } | null = null;
  let autoScroll = 0;
  let timer: ReturnType<typeof setInterval> | undefined;
  addCleanup(() => { if (timer) clearInterval(timer); });

  root.innerHTML = `<div class="orgbar">
      <input id="q" type="search" placeholder="Chercher une catégorie" aria-label="Chercher une catégorie" autocomplete="off" autocapitalize="off">
      <div id="tools" class="tools"></div>
      <div id="count" class="count"></div>
      <div id="omsg" class="msg" role="status"></div>
    </div>
    <div id="vlist" class="vlist" role="list"></div>`;
  const q = root.querySelector<HTMLInputElement>('#q')!;
  const tools = root.querySelector<HTMLElement>('#tools')!;
  const count = root.querySelector<HTMLElement>('#count')!;
  const omsg = root.querySelector<HTMLElement>('#omsg')!;
  const vlist = root.querySelector<HTMLElement>('#vlist')!;
  const say = (t: string, kind: 'error' | 'ok' = 'error'): void => { omsg.textContent = t; omsg.className = `msg ${kind}`; };

  const visible = (): Category[] => (mode === 'reorder' ? all : filterCategories(all, query));
  const shownCount = (): number => all.filter((c) => !hiddenInScope(c, everyone) && !isLocked(c, everyone)).length;

  function paintTools(): void {
    if (pending) {
      tools.innerHTML = `<span class="ctext">${esc(pending.text)}</span>
        <button type="button" class="small" data-act="yes">Confirmer</button>
        <button type="button" class="small secondary" data-act="no">Annuler</button>`;
      return;
    }
    if (mode === 'normal') {
      tools.innerHTML = `<button type="button" class="small secondary" data-act="reorder">Réordonner</button>
        <button type="button" class="small secondary" data-act="pick">Choisir</button>
        <button type="button" class="small secondary" data-act="showall">Tout réafficher</button>`;
    } else if (mode === 'reorder') {
      tools.innerHTML = `<button type="button" class="small" data-act="done">Terminer</button>
        <button type="button" class="small secondary" data-act="cancel">Annuler</button>`;
    } else {
      tools.innerHTML = `<button type="button" class="small" data-act="apply">Tout masquer sauf ce que j'ai coché</button>
        <button type="button" class="small secondary" data-act="cancel">Annuler</button>`;
    }
    q.disabled = mode === 'reorder';
  }
  function paintCount(): void {
    if (mode === 'pick') count.textContent = `${pick.size} coché${pick.size > 1 ? 'es' : 'e'} sur ${all.length}`;
    else if (mode === 'reorder') count.textContent = 'Monte ou descends les catégories, ou glisse la poignée ≡.';
    else count.textContent = `${shownCount()} affichées sur ${all.length} · ${scopeName}`;
  }

  function rowHtml(c: Category, i: number, list: Category[]): string {
    const locked = isLocked(c, everyone);
    const on = mode === 'pick' ? pick.has(c.id) : !hiddenInScope(c, everyone);
    const sub = locked ? 'masquée pour tout le monde' : series ? '' : `${c.channelCount} chaîne${c.channelCount > 1 ? 's' : ''}`;
    const check = mode === 'reorder' ? '' :
      `<label class="hit"><input type="checkbox" class="cb" aria-label="Afficher ${esc(c.name)}" ${on ? 'checked' : ''} ${locked ? 'disabled' : ''}></label>`;
    const move = mode === 'reorder'
      ? `<button type="button" class="mv" data-mv="-1" aria-label="Monter ${esc(c.name)}" ${i === 0 ? 'disabled' : ''}>▲</button>
         <button type="button" class="mv" data-mv="1" aria-label="Descendre ${esc(c.name)}" ${i === list.length - 1 ? 'disabled' : ''}>▼</button>
         <span class="grip" aria-label="Glisser pour déplacer">≡</span>`
      : '';
    return `<div class="orow${locked ? ' locked' : ''}${!on && !locked ? ' off' : ''}" role="listitem" style="top:${i * ROW_H}px" data-id="${esc(c.id)}" data-i="${i}">
      ${check}<div class="oname" ${mode === 'reorder' ? '' : 'data-open="1" role="button" tabindex="0"'}><span class="otitle">${esc(c.name)}</span><span class="osub">${sub}</span></div>${move}</div>`;
  }

  // Liste fenêtrée : seules les lignes visibles à l'écran sont dans le DOM.
  function paintRows(): void {
    const list = visible();
    vlist.style.height = `${list.length * ROW_H}px`;
    if (list.length === 0) { vlist.style.height = '80px'; vlist.innerHTML = '<p class="empty">Aucune catégorie ne correspond.</p>'; return; }
    const top = vlist.getBoundingClientRect().top;
    const first = Math.max(0, Math.floor(-top / ROW_H) - OVERSCAN);
    const last = Math.min(list.length, Math.ceil((window.innerHeight - top) / ROW_H) + OVERSCAN);
    let html = '';
    for (let i = first; i < last; i++) html += rowHtml(list[i], i, list);
    vlist.innerHTML = html;
  }
  const paintAll = (): void => { paintTools(); paintCount(); paintRows(); };

  const onScroll = (): void => { if (vlist.isConnected) paintRows(); };
  window.addEventListener('scroll', onScroll, { passive: true });
  window.addEventListener('resize', onScroll);
  addCleanup(() => { window.removeEventListener('scroll', onScroll); window.removeEventListener('resize', onScroll); });

  function applyOrder(ids: string[]): void {
    const byId = new Map(all.map((c) => [c.id, c] as const));
    all = ids.map((i) => byId.get(i)!);
  }

  async function reload(): Promise<void> {
    const fresh = await loadCats(db, h, scope);
    if (!renderGate.isCurrent(token)) return;
    all = fresh;
    paintAll();
  }
  async function attempt(fn: () => Promise<void>): Promise<void> {
    say('', 'ok');
    try { await fn(); } catch (e) { say((e as Error).message); try { await reload(); } catch { /* garde l'affichage */ } }
  }

  q.addEventListener('input', () => { query = q.value; paintRows(); });

  tools.addEventListener('click', (e) => {
    const act = (e.target as HTMLElement).closest<HTMLButtonElement>('button[data-act]')?.dataset.act;
    if (!act) return;
    if (act === 'yes') { const p = pending; pending = null; paintTools(); p?.run(); return; }
    if (act === 'no') { pending = null; paintAll(); return; }
    if (act === 'reorder') { mode = 'reorder'; query = ''; q.value = ''; snapshot = all.slice(); paintAll(); }
    else if (act === 'pick') { mode = 'pick'; pick = new Set(); paintAll(); }
    else if (act === 'cancel') {
      if (mode === 'reorder') all = snapshot;
      mode = 'normal'; paintAll();
    }
    else if (act === 'done') {
      const ids = all.map((c) => c.id);
      void attempt(async () => { await setOrder(db, h, scope, ids); mode = 'normal'; paintAll(); say('Ordre enregistré.', 'ok'); });
    }
    else if (act === 'apply') {
      const plan = hideAllExceptPlan(all, pick, everyone);
      if (plan.toHide === 0 && plan.toShow === 0) { say('Rien à changer.', 'ok'); mode = 'normal'; paintAll(); return; }
      const empty = pick.size === 0 ? "Rien n'est coché : toutes les catégories seront masquées. " : '';
      const warn = confirmText(everyone, plan.toHide, kind) ?? (empty ? 'Continuer ?' : null);
      const doApply = (): void => { void attempt(async () => { await hideAllExcept(db, h, scope, [...pick]); mode = 'normal'; await reload(); say('Fait.', 'ok'); }); };
      if (warn) { pending = { text: empty + warn, run: doApply }; paintTools(); }
      else doApply();
    }
    else if (act === 'showall') {
      const ids = all.filter((c) => hiddenInScope(c, everyone) && !isLocked(c, everyone)).map((c) => c.id);
      if (ids.length === 0) return say('Tout est déjà affiché.', 'ok');
      void attempt(async () => { await orgSetHidden(db, h, scope, hiddenType, ids, false); await reload(); say('Tout est réaffiché.', 'ok'); });
    }
  });

  vlist.addEventListener('click', (e) => {
    const t = e.target as HTMLElement;
    const row = t.closest<HTMLElement>('.orow');
    if (!row) return;
    const id = row.dataset.id!;
    const c = all.find((x) => x.id === id);
    if (!c) return;
    const mv = t.closest<HTMLButtonElement>('button[data-mv]');
    if (mv) {
      const from = all.findIndex((x) => x.id === id);
      applyOrder(moveCategory(all.map((x) => x.id), from, from + Number(mv.dataset.mv)));
      paintRows();
      return;
    }
    if (t.classList.contains('cb')) {
      const box = t as HTMLInputElement;
      if (mode === 'pick') { if (box.checked) pick.add(id); else pick.delete(id); paintCount(); row.classList.toggle('off', !box.checked); return; }
      const hidden = !box.checked;
      const applyToggle = (): void => {
        if (everyone) c.hiddenGlobal = hidden; else c.hiddenProfile = hidden;
        paintRows(); paintCount();
        void attempt(async () => { await orgSetHidden(db, h, scope, hiddenType, [id], hidden); });
      };
      if (hidden && everyone) { box.checked = true; pending = { text: confirmText(true, 1, kind)!, run: applyToggle }; paintTools(); return; }
      applyToggle();
      return;
    }
    if (t.closest('[data-open]')) {
      if (mode === 'pick') {
        if (isLocked(c, everyone)) return;
        if (pick.has(id)) pick.delete(id); else pick.add(id);
        paintRows(); paintCount();
      } else if (!series) {
        openCategory(ctx, c, () => { void renderCategories(ctx); });
      }
    }
  });

  vlist.addEventListener('keydown', (e) => {
    const t = e.target as HTMLElement;
    if ((e.key === 'Enter' || e.key === ' ') && t.dataset.open) { e.preventDefault(); t.click(); }
  });

  // Glisser par la poignée (événements pointer).
  vlist.addEventListener('pointerdown', (e) => {
    const grip = (e.target as HTMLElement).closest<HTMLElement>('.grip');
    const row = grip?.closest<HTMLElement>('.orow');
    if (!grip || !row) return;
    e.preventDefault();
    vlist.setPointerCapture(e.pointerId);
    dragging = { from: Number(row.dataset.i) };
    row.classList.add('drag');
    timer = setInterval(() => { if (autoScroll) { window.scrollBy(0, autoScroll); } }, 16);
  });
  vlist.addEventListener('pointermove', (e) => {
    if (!dragging) return;
    const y = e.clientY;
    autoScroll = y < 90 ? -10 : y > window.innerHeight - 90 ? 10 : 0;
    const to = Math.floor((y - vlist.getBoundingClientRect().top) / ROW_H);
    const target = Math.max(0, Math.min(all.length - 1, to));
    if (target !== dragging.from) {
      applyOrder(moveCategory(all.map((c) => c.id), dragging.from, target));
      dragging.from = target;
    }
    paintRows();
    vlist.querySelector(`.orow[data-i="${dragging.from}"]`)?.classList.add('drag');
  });
  const endDrag = (): void => { if (!dragging) return; dragging = null; autoScroll = 0; if (timer) clearInterval(timer); paintRows(); };
  vlist.addEventListener('pointerup', endDrag);
  vlist.addEventListener('pointercancel', endDrag);

  paintAll();
}

// ───────────────────────── Chaînes d'une catégorie et résultats de recherche ─────────────────────────

const CH_H = 104;
type Row = Channel & { categoryName?: string };

function needProfile(root: HTMLElement): void {
  root.innerHTML = '<section class="card"><p>Choisis un profil</p></section>';
}

// Liste fenêtrée de chaînes : pages chargées à la demande, seules les lignes visibles sont dans le DOM.
function channelList(ctx: OrgCtx, host: HTMLElement, token: number, loader: PageLoader, hint: number, showCategory: boolean, note = ''): void {
  const { db, householdId: h, scope } = ctx;
  const everyone = scope === null;
  host.innerHTML = `<div class="msg" role="status"></div><div class="vlist" role="list"></div>${note ? `<p class="count">${esc(note)}</p>` : ''}`;
  const msg = host.querySelector<HTMLElement>('.msg')!;
  const vlist = host.querySelector<HTMLElement>('.vlist')!;
  const say = (t: string, kind: 'error' | 'ok' = 'error'): void => { msg.textContent = t; msg.className = `msg ${kind}`; };
  const live = (): boolean => renderGate.isCurrent(token) && vlist.isConnected;
  const queue = serial();

  function rowHtml(c: Row | undefined, i: number): string {
    if (!c) return `<div class="crow" role="listitem" style="top:${i * CH_H}px"><div class="cinfo"><span class="otitle">…</span></div></div>`;
    const locked = !everyone && c.hiddenGlobal;
    const hidden = everyone ? c.hiddenGlobal : c.hiddenProfile;
    const sub = [showCategory ? c.categoryName ?? '' : '', locked ? 'Masquée pour tout le monde' : hidden ? 'Masquée' : ''].filter(Boolean).join(' · ');
    const title = `${c.number !== null ? `${c.number}. ` : ''}${c.name}`;
    const hideLabel = locked ? 'Masquée (tous)' : hidden ? 'Réafficher' : everyone ? 'Masquer pour tout le monde' : 'Masquer';
    const profileActs = everyone ? '' : `
      <button type="button" class="act${c.favorite ? ' on' : ''}" data-a="fav" aria-pressed="${c.favorite}" aria-label="Favori : ${esc(c.name)}">⭐</button>
      <button type="button" class="act" data-a="pl" aria-label="Ajouter ${esc(c.name)} à une playlist">+ Playlist</button>`;
    return `<div class="crow${hidden || locked ? ' off' : ''}" role="listitem" style="top:${i * CH_H}px" data-i="${i}">
      <div class="cinfo"><span class="otitle">${esc(title)}</span><span class="osub">${esc(sub)}</span></div>
      <div class="cact">${profileActs}
        <button type="button" class="act" data-a="hide" ${locked ? 'disabled' : ''} aria-label="${esc(hideLabel)} : ${esc(c.name)}">${esc(hideLabel)}</button></div></div>`;
  }

  function paint(): void {
    if (!live()) return;
    const total = loader.total() ?? hint;
    if (total === 0) { vlist.style.height = '80px'; vlist.innerHTML = '<p class="empty">Aucune chaîne.</p>'; return; }
    vlist.style.height = `${total * CH_H}px`;
    const top = vlist.getBoundingClientRect().top;
    const first = Math.max(0, Math.floor(-top / CH_H) - OVERSCAN);
    const last = Math.min(total, Math.ceil((window.innerHeight - top) / CH_H) + OVERSCAN);
    let html = '';
    let missing = false;
    for (let i = first; i < last; i++) {
      const c = loader.get(i);
      if (!c) missing = true;
      html += rowHtml(c, i);
    }
    vlist.innerHTML = html;
    if (missing) {
      loader.ensure(first, last).then(() => { if (live()) paint(); }, (e: Error) => { if (live()) say(e.message); });
    }
  }
  const onScroll = (): void => paint();
  window.addEventListener('scroll', onScroll, { passive: true });
  window.addEventListener('resize', onScroll);
  addCleanup(() => { window.removeEventListener('scroll', onScroll); window.removeEventListener('resize', onScroll); });

  vlist.addEventListener('click', (e) => {
    const b = (e.target as HTMLElement).closest<HTMLButtonElement>('button[data-a]');
    const row = b?.closest<HTMLElement>('.crow');
    if (!b || !row) return;
    const c = loader.get(Number(row.dataset.i));
    if (!c) return;
    say('', 'ok');
    const a = b.dataset.a;
    if (a === 'fav' && scope) {
      const on = !c.favorite;
      c.favorite = on; paint();
      queue(() => orgSetFavorite(db, scope, c.id, on)).catch((err: Error) => { c.favorite = !on; paint(); say(err.message); });
    } else if (a === 'hide') {
      const hide = !(everyone ? c.hiddenGlobal : c.hiddenProfile);
      const warn = hide ? hideChannelConfirm(everyone) : null;
      const doHide = (): void => {
        const set = (v: boolean): void => { if (everyone) c.hiddenGlobal = v; else c.hiddenProfile = v; };
        set(hide); paint();
        queue(() => orgSetHidden(db, h, scope, 'channel', [c.id], hide)).catch((err: Error) => { set(!hide); paint(); say(err.message); });
      };
      if (warn) {
        // Confirmation intégrée à la page, comme pour les catégories (pas de confirm() native).
        msg.innerHTML = `<span>${esc(warn)}</span> <button type="button" class="small" data-cf="1">Confirmer</button> <button type="button" class="small secondary" data-cf="0">Annuler</button>`;
        msg.className = 'msg';
        msg.querySelector('[data-cf="1"]')!.addEventListener('click', () => { say('', 'ok'); doHide(); });
        msg.querySelector('[data-cf="0"]')!.addEventListener('click', () => { say('', 'ok'); });
        return;
      }
      doHide();
    } else if (a === 'pl' && scope) {
      openPlaylistSheet(ctx, c, say);
    }
  });
  paint();
}

// Feuille « Ajouter à une playlist » : choisir une playlist ou en créer une.
function openPlaylistSheet(ctx: OrgCtx, c: Channel, say: (t: string, kind?: 'error' | 'ok') => void): void {
  const { db, scope, root } = ctx;
  if (!scope) return;
  root.querySelector('.sheet-wrap')?.remove();
  const wrap = document.createElement('div');
  wrap.className = 'sheet-wrap';
  wrap.innerHTML = `<div class="sheet-back" data-close="1"></div>
    <div class="sheet" role="dialog" aria-label="Ajouter à une playlist">
      <h2>Ajouter « ${esc(c.name)} »</h2>
      <div class="sheet-list"><p>Chargement…</p></div>
      <label for="np">Nouvelle playlist</label>
      <div class="row"><input id="np" maxlength="${NAME_MAX}" placeholder="Nom de la playlist" autocomplete="off"><button type="button" class="small" data-new="1">Créer</button></div>
      <div class="msg" role="status"></div>
      <button type="button" class="secondary" data-close="1">Annuler</button></div>`;
  root.appendChild(wrap);
  const list = wrap.querySelector<HTMLElement>('.sheet-list')!;
  const smsg = wrap.querySelector<HTMLElement>('.msg')!;
  const np = wrap.querySelector<HTMLInputElement>('#np')!;
  let pls: Playlist[] = [];
  const fail = (e: Error): void => { smsg.textContent = e.message; smsg.className = 'msg error'; };
  const done = (t: string): void => { wrap.remove(); say(t, 'ok'); };

  orgPlaylists(db, scope).then((r) => {
    if (!wrap.isConnected) return;
    pls = r;
    list.innerHTML = pls.length === 0 ? '<p>Aucune playlist pour l\'instant.</p>'
      : pls.map((p) => `<button type="button" class="secondary plopt" data-pl="${esc(p.id)}">${esc(p.name)} <span class="osub">${p.channelIds.length}</span></button>`).join('');
  }, (e: Error) => { list.innerHTML = ''; fail(e); });

  wrap.addEventListener('click', (e) => {
    const t = e.target as HTMLElement;
    if (t.closest('[data-close]')) { wrap.remove(); return; }
    const opt = t.closest<HTMLButtonElement>('button[data-pl]');
    if (opt) {
      const pl = pls.find((p) => p.id === opt.dataset.pl);
      if (!pl) return;
      const next = addTo(pl.channelIds, c.id);
      if (next.length === pl.channelIds.length) { done(`Déjà dans « ${pl.name} ».`); return; }
      orgPlaylistSetItems(db, pl.id, next).then(() => done(`Ajoutée à « ${pl.name} ».`), fail);
      return;
    }
    if (t.closest('[data-new]')) {
      const name = validName(np.value);
      if (!name) { fail(new Error(`Le nom doit faire de 1 à ${NAME_MAX} caractères.`)); return; }
      orgPlaylistCreate(db, scope, name)
        .then((id) => orgPlaylistSetItems(db, id, [c.id]))
        .then(() => done(`Ajoutée à « ${name} ».`), fail);
    }
  });
}

export function openCategory(ctx: OrgCtx, cat: Category, back: () => void): void {
  const token = startScreen();
  const { db, householdId: h, scope, root } = ctx;
  root.innerHTML = `<button type="button" class="link" id="back">‹ Catégories</button>
    <h2>${esc(cat.name)}</h2><div id="chs"></div>`;
  root.querySelector('#back')!.addEventListener('click', back);
  const loader = pageLoader(async (off, lim) => {
    const rows = await orgChannels(db, h, cat.id, scope, off, lim);
    return rows;
  });
  const host = root.querySelector<HTMLElement>('#chs')!;
  host.innerHTML = '<p>Chargement…</p>';
  loader.ensure(0, 1).then(() => {
    if (renderGate.isCurrent(token)) channelList(ctx, host, token, loader, cat.channelCount, false);
  }, (e: Error) => { if (renderGate.isCurrent(token)) host.innerHTML = `<p class="msg error">${esc(e.message)}</p>`; });
}

async function renderSearch(ctx: OrgCtx, query: string): Promise<void> {
  const token = startScreen();
  const { db, householdId: h, scope, root } = ctx;
  root.innerHTML = '<p>Recherche…</p>';
  let hits: SearchHit[];
  try { hits = await orgSearch(db, h, query, scope, SEARCH_LIMIT); }
  catch (e) { if (renderGate.isCurrent(token)) root.innerHTML = `<p class="msg error">${esc((e as Error).message)}</p>`; return; }
  if (!renderGate.isCurrent(token)) return;
  if (hits.length === 0) { root.innerHTML = '<p class="empty">Aucune chaîne trouvée.</p>'; return; }
  const loader = pageLoader(async (off, lim) => hits.slice(off, off + lim));
  await loader.ensure(0, 1);
  if (!renderGate.isCurrent(token)) return;
  channelList(ctx, root, token, loader, hits.length, true, hits.length >= SEARCH_LIMIT ? 'Affine ta recherche' : '');
}

// ───────────────────────── Favoris ─────────────────────────

async function renderFavorites(ctx: OrgCtx): Promise<void> {
  const token = startScreen();
  const { db, scope, root } = ctx;
  if (!scope) { needProfile(root); return; }
  const profile = scope;
  root.innerHTML = '<p>Chargement…</p>';
  let rows: FavoriteRow[];
  try { rows = await orgFavorites(db, profile); }
  catch (e) { if (renderGate.isCurrent(token)) root.innerHTML = `<p class="msg error">${esc((e as Error).message)}</p>`; return; }
  if (!renderGate.isCurrent(token)) return;
  root.innerHTML = '<div class="msg" role="status"></div><div id="flist"></div>';
  const msg = root.querySelector<HTMLElement>('.msg')!;
  const flist = root.querySelector<HTMLElement>('#flist')!;
  const queue = serial();
  const say = (t: string, kind: 'error' | 'ok' = 'error'): void => { msg.textContent = t; msg.className = `msg ${kind}`; };

  function paint(): void {
    if (rows.length === 0) { flist.innerHTML = '<p class="empty">Aucun favori. Touche ⭐ sur une chaîne pour l\'ajouter.</p>'; return; }
    flist.innerHTML = rows.map((r, i) => {
      const label = r.state === 'n-existe-plus' ? 'Chaîne disparue' : `${r.number !== null ? `${r.number}. ` : ''}${r.name}`;
      const why = stateLabel(r.state);
      return `<div class="frow${why ? ' off' : ''}" data-id="${esc(r.id)}">
        <div class="oname"><span class="otitle">${esc(label)}</span>${why ? `<span class="osub">${esc(why)}</span>` : ''}</div>
        <button type="button" class="mv" data-mv="-1" aria-label="Monter ${esc(label)}" ${i === 0 ? 'disabled' : ''}>▲</button>
        <button type="button" class="mv" data-mv="1" aria-label="Descendre ${esc(label)}" ${i === rows.length - 1 ? 'disabled' : ''}>▼</button>
        <button type="button" class="small secondary" data-rm="1" aria-label="Retirer ${esc(label)}">Retirer</button></div>`;
    }).join('');
  }
  async function reload(): Promise<void> {
    const fresh = await orgFavorites(db, profile);
    if (renderGate.isCurrent(token)) { rows = fresh; paint(); }
  }
  function write(fn: () => Promise<void>): void {
    say('', 'ok');
    queue(fn).catch(async (e: Error) => { say(e.message); try { await reload(); } catch { /* garde l'affichage */ } });
  }

  flist.addEventListener('click', (e) => {
    const t = e.target as HTMLElement;
    const row = t.closest<HTMLElement>('.frow');
    if (!row) return;
    const i = rows.findIndex((r) => r.id === row.dataset.id);
    if (i < 0) return;
    const mv = t.closest<HTMLButtonElement>('button[data-mv]');
    if (mv) {
      const ids = reorder(rows.map((r) => r.id), i, i + Number(mv.dataset.mv));
      rows = ids.map((id) => rows.find((r) => r.id === id)!);
      paint();
      write(() => orgReorderFavorites(db, profile, ids));
    } else if (t.closest('[data-rm]')) {
      const id = rows[i].id;
      rows = rows.filter((r) => r.id !== id);
      paint();
      write(() => orgSetFavorite(db, profile, id, false));
    }
  });
  paint();
}

// ───────────────────────── Playlists ─────────────────────────

async function renderPlaylists(ctx: OrgCtx): Promise<void> {
  const token = startScreen();
  const { db, scope, root } = ctx;
  if (!scope) { needProfile(root); return; }
  const profile = scope;
  root.innerHTML = '<p>Chargement…</p>';
  let pls: Playlist[];
  try { pls = await orgPlaylists(db, profile); }
  catch (e) { if (renderGate.isCurrent(token)) root.innerHTML = `<p class="msg error">${esc((e as Error).message)}</p>`; return; }
  if (!renderGate.isCurrent(token)) return;

  root.innerHTML = `<section class="card"><h2>Nouvelle playlist</h2>
      <div class="row"><input id="newname" maxlength="${NAME_MAX}" placeholder="Nom de la playlist" aria-label="Nom de la nouvelle playlist" autocomplete="off">
      <button type="button" class="small" id="create">Créer</button></div></section>
    <div class="msg" role="status"></div><div id="pllist"></div>`;
  const msg = root.querySelector<HTMLElement>('.msg')!;
  const plist = root.querySelector<HTMLElement>('#pllist')!;
  const newname = root.querySelector<HTMLInputElement>('#newname')!;
  const queue = serial();
  const say = (t: string, kind: 'error' | 'ok' = 'error'): void => { msg.textContent = t; msg.className = `msg ${kind}`; };

  function paint(): void {
    if (pls.length === 0) { plist.innerHTML = '<p class="empty">Aucune playlist.</p>'; return; }
    plist.innerHTML = pls.map((p, i) => `<div class="frow" data-id="${esc(p.id)}">
        <div class="oname" data-open="1" role="button" tabindex="0"><span class="otitle">${esc(p.name)}</span><span class="osub">${p.channelIds.length} chaîne${p.channelIds.length > 1 ? 's' : ''}</span></div>
        <button type="button" class="mv" data-mv="-1" aria-label="Monter ${esc(p.name)}" ${i === 0 ? 'disabled' : ''}>▲</button>
        <button type="button" class="mv" data-mv="1" aria-label="Descendre ${esc(p.name)}" ${i === pls.length - 1 ? 'disabled' : ''}>▼</button></div>`).join('');
  }
  async function reload(): Promise<void> {
    const fresh = await orgPlaylists(db, profile);
    if (renderGate.isCurrent(token)) { pls = fresh; paint(); }
  }

  root.querySelector('#create')!.addEventListener('click', () => {
    const name = validName(newname.value);
    if (!name) return say(`Le nom doit faire de 1 à ${NAME_MAX} caractères.`);
    say('', 'ok');
    queue(() => orgPlaylistCreate(db, profile, name)).then(async () => { newname.value = ''; await reload(); say('Playlist créée.', 'ok'); }, (e: Error) => say(e.message));
  });
  plist.addEventListener('click', (e) => {
    const t = e.target as HTMLElement;
    const row = t.closest<HTMLElement>('.frow');
    const i = pls.findIndex((p) => p.id === row?.dataset.id);
    if (i < 0) return;
    const mv = t.closest<HTMLButtonElement>('button[data-mv]');
    if (mv) {
      const ids = reorder(pls.map((p) => p.id), i, i + Number(mv.dataset.mv));
      pls = ids.map((id) => pls.find((p) => p.id === id)!);
      paint(); say('', 'ok');
      queue(() => orgPlaylistsReorder(db, profile, ids)).catch(async (err: Error) => { say(err.message); try { await reload(); } catch { /* garde */ } });
    } else if (t.closest('[data-open]')) {
      openPlaylist(ctx, pls[i], () => { void renderPlaylists(ctx); });
    }
  });
  plist.addEventListener('keydown', (e) => {
    const t = e.target as HTMLElement;
    if ((e.key === 'Enter' || e.key === ' ') && t.dataset.open) { e.preventDefault(); t.click(); }
  });
  paint();
}

function openPlaylist(ctx: OrgCtx, pl: Playlist, back: () => void): void {
  const token = startScreen();
  const { db, householdId: h, scope, root } = ctx;
  if (!scope) { needProfile(root); return; }
  const profile = scope;
  let items = pl.items.slice();
  const idsOf = (): string[] => items.map((x) => x.id);
  let name = pl.name;
  root.innerHTML = `<button type="button" class="link" id="back">‹ Playlists</button>
    <section class="card"><h2 id="ptitle"></h2>
      <div class="row"><input id="rn" maxlength="${NAME_MAX}" aria-label="Nom de la playlist" autocomplete="off">
      <button type="button" class="small" id="ren">Renommer</button></div>
      <button type="button" class="secondary danger" id="del">Supprimer la playlist</button></section>
    <div class="msg" role="status"></div><div id="items"></div>
    <section class="card"><h2>Ajouter des chaînes</h2>
      <input id="sq" type="search" placeholder="Chercher une chaîne" aria-label="Chercher une chaîne à ajouter" autocomplete="off" autocapitalize="off">
      <div id="hits"></div></section>`;
  const msg = root.querySelector<HTMLElement>('.msg')!;
  const itemsEl = root.querySelector<HTMLElement>('#items')!;
  const hitsEl = root.querySelector<HTMLElement>('#hits')!;
  const rn = root.querySelector<HTMLInputElement>('#rn')!;
  const sq = root.querySelector<HTMLInputElement>('#sq')!;
  const queue = serial();
  const say = (t: string, kind: 'error' | 'ok' = 'error'): void => { msg.textContent = t; msg.className = `msg ${kind}`; };
  const live = (): boolean => renderGate.isCurrent(token);
  const paintTitle = (): void => { root.querySelector('#ptitle')!.textContent = name; rn.value = name; };
  root.querySelector('#back')!.addEventListener('click', back);

  function paintItems(): void {
    if (items.length === 0) { itemsEl.innerHTML = '<p class="empty">Cette playlist est vide.</p>'; return; }
    itemsEl.innerHTML = items.map((it, i) => {
      const label = it.state === 'n-existe-plus' ? 'Chaîne disparue' : `${it.number !== null ? `${it.number}. ` : ''}${it.name}`;
      const why = stateLabel(it.state);
      return `<div class="frow${why ? ' off' : ''}" data-id="${esc(it.id)}"><div class="oname"><span class="otitle">${esc(label)}</span>${why ? `<span class="osub">${esc(why)}</span>` : ''}</div>
        <button type="button" class="mv" data-mv="-1" aria-label="Monter ${esc(label)}" ${i === 0 ? 'disabled' : ''}>▲</button>
        <button type="button" class="mv" data-mv="1" aria-label="Descendre ${esc(label)}" ${i === items.length - 1 ? 'disabled' : ''}>▼</button>
        <button type="button" class="small secondary" data-rm="1" aria-label="Retirer ${esc(label)}">Retirer</button></div>`;
    }).join('');
  }
  // Écrit la liste complète ; en cas d'échec, relit le serveur.
  function saveItems(): void {
    say('', 'ok');
    const snapshot = idsOf();
    queue(() => orgPlaylistSetItems(db, pl.id, snapshot)).catch(async (e: Error) => {
      say(e.message);
      try {
        const fresh = (await orgPlaylists(db, profile)).find((p) => p.id === pl.id);
        if (live() && fresh) { items = fresh.items; paintItems(); }
      } catch { /* garde l'affichage */ }
    });
  }

  root.querySelector('#ren')!.addEventListener('click', () => {
    const v = validName(rn.value);
    if (!v) return say(`Le nom doit faire de 1 à ${NAME_MAX} caractères.`);
    say('', 'ok');
    queue(() => orgPlaylistRename(db, pl.id, v)).then(() => { name = v; if (live()) { paintTitle(); say('Nom enregistré.', 'ok'); } }, (e: Error) => say(e.message));
  });
  root.querySelector('#del')!.addEventListener('click', () => {
    if (!confirm(`Supprimer la playlist « ${name} » ? Les chaînes ne sont pas supprimées.`)) return;
    queue(() => orgPlaylistDelete(db, pl.id)).then(() => { if (live()) back(); }, (e: Error) => say(e.message));
  });
  itemsEl.addEventListener('click', (e) => {
    const t = e.target as HTMLElement;
    const row = t.closest<HTMLElement>('.frow');
    const i = items.findIndex((x) => x.id === row?.dataset.id);
    if (i < 0) return;
    const mv = t.closest<HTMLButtonElement>('button[data-mv]');
    if (mv) { const order = reorder(idsOf(), i, i + Number(mv.dataset.mv)); items = order.map((id) => items.find((x) => x.id === id)!); }
    else if (t.closest('[data-rm]')) items = items.filter((_, k) => k !== i);
    else return;
    paintItems(); saveItems();
  });

  // Ajouter par la recherche : mêmes règles que la recherche globale.
  let hitToken = 0;
  let lastHits: SearchHit[] = [];
  const paintHits = (list: SearchHit[]): void => {
    hitsEl.innerHTML = list.length === 0 ? '<p class="empty">Aucune chaîne trouvée.</p>' : list.map((c) => {
      const label = `${c.number !== null ? `${c.number}. ` : ''}${c.name}`;
      const inside = items.some((x) => x.id === c.id);
      return `<div class="frow" data-id="${esc(c.id)}"><div class="oname"><span class="otitle">${esc(label)}</span><span class="osub">${esc(c.categoryName)}</span></div>
        <button type="button" class="small ${inside ? 'secondary' : ''}" data-add="1" ${inside ? 'disabled' : ''} aria-label="Ajouter ${esc(label)}">${inside ? 'Ajoutée' : 'Ajouter'}</button></div>`;
    }).join('');
  };
  addCleanup(bindSearch(sq, (q) => {
    const mine = ++hitToken;
    if (q.length < SEARCH_MIN) { hitsEl.innerHTML = ''; return; }
    orgSearch(db, h, q, profile, 30).then((r) => {
      if (!live() || mine !== hitToken) return;
      lastHits = r; paintHits(r);
    }, (e: Error) => { if (live() && mine === hitToken) say(e.message); });
  }));
  hitsEl.addEventListener('click', (e) => {
    const b = (e.target as HTMLElement).closest<HTMLButtonElement>('button[data-add]');
    const id = b?.closest<HTMLElement>('.frow')?.dataset.id;
    if (!id) return;
    const hit = lastHits.find((x) => x.id === id);
    if (!hit || addTo(idsOf(), id).length === items.length) return;
    const gone = hit.hiddenGlobal || hit.hiddenProfile;
    items = [...items, { id, name: hit.name, number: hit.number, categoryId: hit.categoryId, state: gone ? 'masquee' : 'ok' } satisfies PlaylistItem];
    paintItems(); paintHits(lastHits); saveItems();
  });

  paintTitle(); paintItems();
}
