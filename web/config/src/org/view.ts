// Onglet « Organiser » : rendu DOM sans framework, dans le style de main.ts.
// Pour ajouter un écran (chaînes, favoris, listes, recherche), ajouter une entrée à TABS.
import type { Db } from '../flow';
import { orgCategories, orgHideAllExcept, orgSetCategoryOrder, orgSetHidden, type Category } from './api';
import { confirmText, filterCategories, hiddenInScope, hideAllExceptPlan, isLocked, moveCategory } from './categories';

export const ROW_H = 52;
const OVERSCAN = 6;

export interface Profile { id: string; name: string; position: number }
// Contexte partagé par tous les écrans de l'onglet.
export interface OrgCtx {
  db: Db; householdId: string; profiles: Profile[];
  scope: string | null; // id du profil, ou null = « Tout le monde »
  root: HTMLElement;    // zone de contenu de l'écran courant
}
interface OrgTab { id: string; label: string; render(ctx: OrgCtx): void | Promise<void> }

const TABS: OrgTab[] = [
  { id: 'categories', label: 'Catégories', render: (ctx) => renderCategories(ctx) },
  // Tâche 7 : « Favoris », « Listes », « Recherche »…
];

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
  const state = { scope: (profiles[0]?.id ?? null) as string | null, tab: TABS[0].id };
  el.innerHTML = `<div class="chips" id="scope" role="group" aria-label="Pour qui ?"></div>
    ${TABS.length > 1 ? '<div class="subtabs" id="subtabs" role="tablist"></div>' : ''}
    <div id="org-content"></div>`;
  const scopeEl = el.querySelector<HTMLElement>('#scope')!;
  const content = el.querySelector<HTMLElement>('#org-content')!;

  const show = (): void => {
    scopeEl.innerHTML = [...profiles.map((p) => ({ id: p.id as string | null, name: p.name })), { id: null, name: 'Tout le monde' }]
      .map((s) => `<button type="button" class="chip" aria-pressed="${s.id === state.scope}" data-scope="${esc(s.id ?? '')}">${esc(s.name)}</button>`).join('');
    const tab = TABS.find((t) => t.id === state.tab)!;
    const ctx: OrgCtx = { db, householdId, profiles, scope: state.scope, root: content };
    void tab.render(ctx);
  };
  scopeEl.addEventListener('click', (e) => {
    const b = (e.target as HTMLElement).closest<HTMLButtonElement>('button[data-scope]');
    if (!b) return;
    state.scope = b.dataset.scope ? b.dataset.scope : null;
    show();
  });
  show();
}

// Point d'entrée de la tâche 7 : toucher une catégorie ouvre ses chaînes.
export function openCategory(ctx: OrgCtx, cat: Category, back: () => void): void {
  ctx.root.innerHTML = `<button type="button" class="link" id="back">‹ Catégories</button>
    <section class="card"><h2>${esc(cat.name)}</h2><p>Les chaînes de cette catégorie arrivent bientôt.</p></section>`;
  ctx.root.querySelector('#back')!.addEventListener('click', back);
}

type Mode = 'normal' | 'reorder' | 'pick';

let scrollHandler: (() => void) | null = null;

async function renderCategories(ctx: OrgCtx): Promise<void> {
  if (scrollHandler) { window.removeEventListener('scroll', scrollHandler); window.removeEventListener('resize', scrollHandler); scrollHandler = null; }
  const { db, householdId: h, scope, root } = ctx;
  const everyone = scope === null;
  const scopeName = everyone ? 'Tout le monde' : ctx.profiles.find((p) => p.id === scope)?.name ?? '';
  root.innerHTML = '<p>Chargement…</p>';
  let all: Category[];
  try { all = await orgCategories(db, h, scope); }
  catch (e) { root.innerHTML = `<p class="msg error">${esc((e as Error).message)}</p>`; return; }
  if (all.length === 0) {
    root.innerHTML = '<section class="card"><p>Ouvre l\'app sur la télé une fois pour envoyer la liste des chaînes.</p></section>';
    return;
  }

  let mode: Mode = 'normal';
  let query = '';
  let pick = new Set<string>();
  let snapshot: Category[] = all;
  let dragging: { from: number } | null = null;
  let autoScroll = 0;
  let timer: ReturnType<typeof setInterval> | undefined;

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
    const sub = locked ? 'masquée pour tout le monde' : `${c.channelCount} chaîne${c.channelCount > 1 ? 's' : ''}`;
    const check = mode === 'reorder' ? '' :
      `<input type="checkbox" class="cb" aria-label="Afficher ${esc(c.name)}" ${on ? 'checked' : ''} ${locked ? 'disabled' : ''}>`;
    const move = mode === 'reorder'
      ? `<button type="button" class="mv" data-mv="-1" aria-label="Monter" ${i === 0 ? 'disabled' : ''}>▲</button>
         <button type="button" class="mv" data-mv="1" aria-label="Descendre" ${i === list.length - 1 ? 'disabled' : ''}>▼</button>
         <span class="grip" aria-label="Glisser pour déplacer">≡</span>`
      : '';
    return `<div class="orow${locked ? ' locked' : ''}${!on && !locked ? ' off' : ''}" role="listitem" style="top:${i * ROW_H}px" data-id="${esc(c.id)}" data-i="${i}">
      ${check}<div class="oname" ${mode === 'reorder' ? '' : 'data-open="1"'}><span class="otitle">${esc(c.name)}</span><span class="osub">${sub}</span></div>${move}</div>`;
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

  scrollHandler = () => paintRows();
  window.addEventListener('scroll', scrollHandler, { passive: true });
  window.addEventListener('resize', scrollHandler);

  async function reload(): Promise<void> {
    all = await orgCategories(db, h, scope);
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
    if (act === 'reorder') { mode = 'reorder'; query = ''; q.value = ''; snapshot = all.slice(); paintAll(); }
    else if (act === 'pick') { mode = 'pick'; pick = new Set(); paintAll(); }
    else if (act === 'cancel') {
      if (mode === 'reorder') all = snapshot;
      mode = 'normal'; paintAll();
    }
    else if (act === 'done') {
      const ids = all.map((c) => c.id);
      void attempt(async () => { await orgSetCategoryOrder(db, h, scope, ids); mode = 'normal'; paintAll(); say('Ordre enregistré.', 'ok'); });
    }
    else if (act === 'apply') {
      const plan = hideAllExceptPlan(all, pick, everyone);
      if (plan.toHide === 0 && plan.toShow === 0) { say('Rien à changer.', 'ok'); mode = 'normal'; paintAll(); return; }
      const warn = pick.size === 0 ? "Rien n'est coché : toutes les catégories seront masquées. Continuer ?" : confirmText(everyone, plan.toHide);
      if (warn && !confirm(warn)) return;
      void attempt(async () => { await orgHideAllExcept(db, h, scope, [...pick]); mode = 'normal'; await reload(); say('Fait.', 'ok'); });
    }
    else if (act === 'showall') {
      const ids = all.filter((c) => hiddenInScope(c, everyone) && !isLocked(c, everyone)).map((c) => c.id);
      if (ids.length === 0) return say('Tout est déjà affiché.', 'ok');
      void attempt(async () => { await orgSetHidden(db, h, scope, 'live_category', ids, false); await reload(); say('Tout est réaffiché.', 'ok'); });
    }
  });

  vlist.addEventListener('click', (e) => {
    const t = e.target as HTMLElement;
    const row = t.closest<HTMLElement>('.orow');
    if (!row) return;
    const id = row.dataset.id!;
    const c = all.find((x) => x.id === id)!;
    const mv = t.closest<HTMLButtonElement>('button[data-mv]');
    if (mv) {
      const from = all.findIndex((x) => x.id === id);
      const ids = moveCategory(all.map((x) => x.id), from, from + Number(mv.dataset.mv));
      all = ids.map((i) => all.find((x) => x.id === i)!);
      paintRows();
      return;
    }
    if (t.classList.contains('cb')) {
      const box = t as HTMLInputElement;
      if (mode === 'pick') { if (box.checked) pick.add(id); else pick.delete(id); paintCount(); row.classList.toggle('off', !box.checked); return; }
      const hidden = !box.checked;
      if (everyone) c.hiddenGlobal = hidden; else c.hiddenProfile = hidden;
      paintRows(); paintCount();
      void attempt(async () => { await orgSetHidden(db, h, scope, 'live_category', [id], hidden); });
      return;
    }
    if (t.closest('[data-open]')) {
      if (mode === 'pick') {
        if (isLocked(c, everyone)) return;
        if (pick.has(id)) pick.delete(id); else pick.add(id);
        paintRows(); paintCount();
      } else {
        openCategory(ctx, c, () => { void renderCategories(ctx); });
      }
    }
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
      const ids = moveCategory(all.map((c) => c.id), dragging.from, target);
      all = ids.map((i) => all.find((c) => c.id === i)!);
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
