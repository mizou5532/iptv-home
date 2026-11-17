// Panneau « Séries et films » d'un profil (page iPhone) : Reprendre, Ma liste (ajout par recherche), bannière et
// rangées des accueils de la télé. Chaque choix est enregistré tout de suite ; la télé l'applique à la prochaine
// ouverture de l'onglet Séries ou Films.
import type { SettingsDb } from './avatar';
import type { Db } from './flow';
import type { ProfileHome } from './profiles';
import { escapeHtml as esc } from './icons';
import {
  MAXES, NEVER_MAX, PIN_MAX, ROWS, SECONDS, coverUrl, dismissContinue, kindViewOf, loadContinue, loadLists, markWatched, move,
  reorderList, saveKindView, searchTitles, setInList, sourceRows, titlesByIds,
  type ContinueItem, type Hit, type KindView, type ListItem, type MediaKind, type Title,
} from './mediaview';

const KINDS: { id: MediaKind; label: string; one: string }[] = [
  { id: 'series', label: 'Séries', one: 'série' },
  { id: 'movies', label: 'Films', one: 'film' },
];
const SAVED = 'Enregistré. La télé l’applique à la prochaine ouverture de l’onglet.';

interface State {
  kind: MediaKind;
  loading: boolean;
  error: string | null;
  cont: ContinueItem[];
  lists: Record<MediaKind, ListItem[]>;
  names: Map<string, Title>;
  query: string;
  hits: Hit[] | null;
  searching: boolean;
  confirmAll: boolean;
  /** Dernier message (gardé quand l'écran se redessine). */
  msg: { text: string; ok: boolean } | null;
}

function thumb(t: Title): string {
  const url = coverUrl(t.cover);
  return url ? `<img class="mthumb" src="${esc(url)}" alt="" loading="lazy" referrerpolicy="no-referrer">`
    : `<span class="mthumb mthumb-letter" aria-hidden="true">${esc((t.name.trim()[0] ?? '?').toUpperCase())}</span>`;
}

export function openMediaEditor(root: HTMLElement, p: ProfileHome, db: Db & SettingsDb): void {
  root.hidden = false;
  const s: State = { kind: 'series', loading: true, error: null, cont: [], lists: { series: [], movies: [] }, names: new Map(), query: '', hits: null, searching: false, confirmAll: false, msg: null };
  const view = (): KindView => kindViewOf(p.settings, s.kind);
  const one = () => KINDS.find((k) => k.id === s.kind)!.one;
  const say = (text: string, ok = true) => {
    s.msg = { text, ok };
    const m = root.querySelector<HTMLElement>('#mvmsg'); if (m) { m.textContent = text; m.className = `msg ${ok ? 'ok' : 'error'}`; }
  };
  const nameOf = (id: string): string =>
    s.names.get(id)?.name || s.cont.find((c) => c.id === id)?.name || s.lists[s.kind].find((l) => l.id === id)?.name || 'Titre inconnu';
  const titleOf = (id: string): Title | undefined =>
    s.cont.find((c) => c.id === id) ?? s.lists[s.kind].find((l) => l.id === id) ?? s.hits?.find((h) => h.id === id) ?? s.names.get(id);

  const hitsHtml = (): string => {
    if (s.searching) return '<p class="hint" role="status">Recherche…</p>';
    if (s.hits === null) return s.query.trim().length >= 2 ? '' : '<p class="hint">Tape au moins 2 lettres du titre.</p>';
    if (!s.hits.length) return `<p class="hint">Aucun titre trouvé. La télé envoie la liste des titres une fois par jour, quand son catalogue est à jour.</p>`;
    const v = view();
    return `<ul class="mlist">${s.hits.map((h) => {
      const pinned = v.banner_pinned.some((x) => x.id === h.id);
      const never = v.never.includes(h.id);
      return `<li class="mrow">${thumb(h)}<span class="mname">${esc(h.name)}${h.year ? `<small>${esc(h.year)}</small>` : ''}</span>
        <span class="mact"><button type="button" class="small ${h.inList ? 'secondary' : ''}" data-add="${esc(h.id)}" ${h.inList ? 'disabled' : ''}>${h.inList ? 'Dans ta liste' : '+ Ma liste'}</button>
        <button type="button" class="small secondary" data-pin="${esc(h.id)}" aria-pressed="${pinned}">${pinned ? '★ Épinglé' : '☆ Bannière'}</button>
        <button type="button" class="small secondary" data-never="${esc(h.id)}" aria-pressed="${never}">${never ? 'Écarté' : 'Ne plus proposer'}</button></span></li>`;
    }).join('')}</ul>`;
  };

  const paint = (): void => {
    const v = view();
    const pinnedIds = new Set(v.banner_pinned.map((x) => x.id));
    const pinBtn = (id: string) => `<button type="button" class="small secondary" data-pin="${esc(id)}" aria-pressed="${pinnedIds.has(id)}" aria-label="${pinnedIds.has(id) ? 'Retirer de la bannière' : 'Épingler dans la bannière'}">${pinnedIds.has(id) ? '★' : '☆'}</button>`;
    const list = s.lists[s.kind];
    const body = s.loading ? '<p class="loading" role="status">Chargement…</p>' : s.error ? `<p class="msg error">${esc(s.error)}</p><button type="button" class="small" data-retry>Réessayer</button>` : `
      <h4>Reprendre <small>${s.cont.length ? `${s.cont.length} en cours` : ''}</small></h4>
      ${s.cont.length ? `<ul class="mlist">${s.cont.map((c) => `<li class="mrow">${thumb(c)}<span class="mname">${esc(c.name)}<small>${esc(c.detail)}</small>
          ${c.progress != null ? `<span class="mbar"><i style="width:${Math.round(c.progress * 100)}%"></i></span>` : ''}</span>
          <span class="mact">${pinBtn(c.id)}<button type="button" class="small secondary" data-watched="${esc(c.id)}">${s.kind === 'series' ? 'Épisode vu' : 'Vu'}</button>
          <button type="button" class="small" data-dismiss="${esc(c.id)}">Retirer</button></span></li>`).join('')}</ul>
        ${s.confirmAll ? `<div class="tools confirm" role="group" aria-label="Confirmer"><span>Retirer les ${s.cont.length} titres de Reprendre ?</span><button type="button" class="small" data-allyes>Oui, tout retirer</button><button type="button" class="small secondary" data-allno>Annuler</button></div>`
          : '<div class="tools"><button type="button" class="small secondary" data-all>Tout retirer</button></div>'}
        <p class="hint">Retirer : le titre quitte Reprendre et la bannière. Ta position est gardée : si tu le relances, il revient. Sur la télé, maintiens OK sur une carte de Reprendre pour faire pareil.</p>`
        : `<p class="hint">Rien en cours.</p>`}

      <h4>Ma liste <small>${list.length ? `${list.length} titre${list.length > 1 ? 's' : ''}` : ''}</small></h4>
      ${list.length ? `<ul class="mlist">${list.map((l, i) => `<li class="mrow">${thumb(l)}<span class="mname">${esc(l.name)}${l.known ? '' : '<small>Plus dans le catalogue de la télé</small>'}</span>
          <span class="mact">${pinBtn(l.id)}<button type="button" class="small secondary" data-lup="${i}" ${i === 0 ? 'disabled' : ''} aria-label="Monter">↑</button>
          <button type="button" class="small secondary" data-ldown="${i}" ${i === list.length - 1 ? 'disabled' : ''} aria-label="Descendre">↓</button>
          <button type="button" class="small" data-remove="${esc(l.id)}">Retirer</button></span></li>`).join('')}</ul>` : '<p class="hint">Ta liste est vide.</p>'}
      <label for="mvq">Chercher un${s.kind === 'series' ? 'e série' : ' film'} à ajouter</label>
      <input id="mvq" type="search" autocomplete="off" autocorrect="off" spellcheck="false" placeholder="Titre" value="${esc(s.query)}">
      <div id="mvhits">${hitsHtml()}</div>

      <h4>Bannière</h4>
      <div class="seg" role="group" aria-label="Mouvement de la bannière"><button type="button" data-mode="auto" aria-pressed="${v.banner_mode === 'auto'}">Défile</button><button type="button" data-mode="fixe" aria-pressed="${v.banner_mode === 'fixe'}">Reste fixe</button></div>
      <p class="hint">${v.banner_mode === 'fixe' ? 'La bannière reste sur le premier titre.' : `Un nouveau titre toutes les ${v.banner_seconds} secondes.`}</p>
      ${v.banner_mode === 'auto' ? `<div class="seg seg4" role="group" aria-label="Vitesse du défilement">${SECONDS.map((n) => `<button type="button" data-secs="${n}" aria-pressed="${v.banner_seconds === n}">${n} s</button>`).join('')}</div>` : ''}
      <h5>Titres choisis</h5>
      ${v.banner_pinned.length ? `<ul class="mlist">${v.banner_pinned.map((x, i) => `<li class="mrow"><span class="mthumb mthumb-letter" aria-hidden="true">${i + 1}</span><span class="mname">${esc(nameOf(x.id))}</span>
          <span class="mact"><button type="button" class="small secondary" data-pup="${i}" ${i === 0 ? 'disabled' : ''} aria-label="Monter">↑</button>
          <button type="button" class="small secondary" data-pdown="${i}" ${i === v.banner_pinned.length - 1 ? 'disabled' : ''} aria-label="Descendre">↓</button>
          <button type="button" class="small" data-pin="${esc(x.id)}">Retirer</button></span></li>`).join('')}</ul>
        <p class="hint">La bannière ne montre que ces titres${v.banner_pinned.length >= PIN_MAX ? ` (${PIN_MAX} au plus)` : ''}.</p>`
        : `<p class="hint">Aucun : la bannière choisit toute seule (réglages ci-dessous). Touche ☆ à côté d’un titre pour l’épingler (${PIN_MAX} au plus).</p>`}
      ${v.banner_pinned.length ? '' : `
      <h5>Ce que la bannière peut montrer</h5>
      <div class="sportorder">${sourceRows(v, s.kind).map((r, i, all) => `<div class="sportrow${r.on ? '' : ' off'}"><span class="sporticon" aria-hidden="true">${r.on ? i + 1 : '–'}</span><span>${esc(r.label)}</span>
          <button type="button" class="small secondary" data-sup="${r.id}" ${!r.on || i === 0 ? 'disabled' : ''} aria-label="Monter ${esc(r.label)}">↑</button>
          <button type="button" class="small secondary" data-sdown="${r.id}" ${!r.on || !all[i + 1]?.on ? 'disabled' : ''} aria-label="Descendre ${esc(r.label)}">↓</button>
          <button type="button" class="small ${r.on ? 'secondary' : ''}" data-son="${r.id}" aria-pressed="${r.on}">${r.on ? 'Affiché' : 'Masqué'}</button></div>`).join('')}</div>
      <h5>Nombre de titres</h5>
      <div class="seg seg3" role="group" aria-label="Nombre de titres">${MAXES.map((n) => `<button type="button" data-max="${n}" aria-pressed="${v.banner_max === n}">${n}</button>`).join('')}</div>`}
      <h5>Ne plus proposer <small>${v.never.length ? `${v.never.length}` : ''}</small></h5>
      ${v.never.length ? `<ul class="mlist">${v.never.map((id) => `<li class="mrow"><span class="mname">${esc(nameOf(id))}</span><span class="mact"><button type="button" class="small secondary" data-never="${esc(id)}">Remettre</button></span></li>`).join('')}</ul>`
        : '<p class="hint">Aucun titre écarté. Cherche un titre ci-dessus et touche « Ne plus proposer » : il n’apparaîtra plus dans la bannière.</p>'}

      <h4>Rangées de l’accueil ${esc(KINDS.find((k) => k.id === s.kind)!.label)}</h4>
      <div class="sportorder">${v.rows.map((r, i) => { const label = ROWS[s.kind].find((x) => x.id === r.key)!.label; return `<div class="sportrow${r.on ? '' : ' off'}"><span class="sporticon" aria-hidden="true">${i + 1}</span><span>${esc(label)}</span>
          <button type="button" class="small secondary" data-rup="${i}" ${i === 0 ? 'disabled' : ''} aria-label="Monter ${esc(label)}">↑</button>
          <button type="button" class="small secondary" data-rdown="${i}" ${i === v.rows.length - 1 ? 'disabled' : ''} aria-label="Descendre ${esc(label)}">↓</button>
          <button type="button" class="small ${r.on ? 'secondary' : ''}" data-ron="${i}" aria-pressed="${r.on}">${r.on ? 'Affichée' : 'Masquée'}</button></div>`; }).join('')}</div>
      <p class="hint">La bannière reste toujours en haut. « Catégories » : toutes les rangées de catégories (leur ordre se règle dans « Organiser »).</p>`;
    root.innerHTML = `<div class="ped look mediaview">
      <h3>Séries et films de ${esc(p.name)}</h3>
      <div class="seg" role="tablist" aria-label="Type">${KINDS.map((k) => `<button type="button" role="tab" data-kind="${k.id}" aria-selected="${s.kind === k.id}" aria-pressed="${s.kind === k.id}">${k.label}</button>`).join('')}</div>
      ${body}
      <div class="tools"><button type="button" class="small secondary" id="mvclose">Fermer</button></div>
      <p id="mvmsg" class="msg${s.msg ? (s.msg.ok ? ' ok' : ' error') : ''}" role="status" aria-live="polite">${esc(s.msg?.text ?? '')}</p>
    </div>`;
    const input = root.querySelector<HTMLInputElement>('#mvq');
    if (input) input.oninput = () => { s.query = input.value; scheduleSearch(); };
  };

  const paintHits = () => { const box = root.querySelector<HTMLElement>('#mvhits'); if (box) box.innerHTML = hitsHtml(); };
  let timer: ReturnType<typeof setTimeout> | undefined;
  let searchSeq = 0;
  const scheduleSearch = () => {
    clearTimeout(timer);
    const q = s.query;
    if (q.trim().length < 2) { s.hits = null; s.searching = false; paintHits(); return; }
    timer = setTimeout(async () => {
      const seq = ++searchSeq; const kind = s.kind;
      s.searching = true; paintHits();
      try { const hits = await searchTitles(db, p.id, kind, q); if (seq === searchSeq && kind === s.kind) { s.hits = hits; } }
      catch (e) { if (seq === searchSeq) { s.hits = []; say((e as Error).message, false); } }
      finally { if (seq === searchSeq) { s.searching = false; paintHits(); } }
    }, 300);
  };

  let loadSeq = 0;
  const load = async () => {
    const seq = ++loadSeq; const kind = s.kind;
    s.loading = true; s.error = null; paint();
    try {
      const [cont, lists] = await Promise.all([loadContinue(db, p.id, kind), loadLists(db, p.id)]);
      const v = kindViewOf(p.settings, kind);
      const unknown = [...v.banner_pinned.map((x) => x.id), ...v.never].filter((id) => !cont.some((c) => c.id === id) && !lists[kind].some((l) => l.id === id));
      const names = await titlesByIds(db, p.id, kind, [...new Set(unknown)]).catch(() => new Map<string, Title>());
      if (seq !== loadSeq) return;
      s.cont = cont; s.lists = lists; s.names = names; s.loading = false;
    } catch (e) {
      if (seq !== loadSeq) return;
      s.loading = false; s.error = (e as Error).message;
    }
    paint();
  };

  const saveView = async (change: (v: KindView) => KindView, done = SAVED) => {
    say('Enregistrement…');
    try { p.settings = await saveKindView(db, p.id, p.settings, s.kind, change(view())); paint(); say(done); }
    catch (e) { paint(); say((e as Error).message, false); }
  };
  const act = async (work: () => Promise<void>, done: string) => {
    say('Enregistrement…');
    root.querySelectorAll<HTMLButtonElement>('.mediaview button').forEach((b) => { b.disabled = true; });
    try { await work(); say(done); } catch (e) { say((e as Error).message, false); }
    paint();
  };

  root.onclick = (e) => {
    const b = (e.target as HTMLElement).closest<HTMLButtonElement>('button');
    if (!b || b.disabled) return;
    const d = b.dataset;
    if (b.id === 'mvclose') { clearTimeout(timer); root.hidden = true; root.innerHTML = ''; root.onclick = null; return; }
    if (d.kind && d.kind !== s.kind) { s.kind = d.kind as MediaKind; s.query = ''; s.hits = null; s.confirmAll = false; void load(); return; }
    if ('retry' in d) { void load(); return; }
    const list = s.lists[s.kind];
    if (d.dismiss) {
      const id = d.dismiss;
      void act(async () => { await dismissContinue(db, p.id, s.kind, [id]); s.cont = s.cont.filter((c) => c.id !== id); }, `Retiré de Reprendre. ${SAVED}`);
    } else if ('all' in d) { s.confirmAll = true; paint(); }
    else if ('allno' in d) { s.confirmAll = false; paint(); }
    else if ('allyes' in d) {
      s.confirmAll = false;
      void act(async () => { await dismissContinue(db, p.id, s.kind, s.cont.map((c) => c.id)); s.cont = []; }, `Reprendre est vide. ${SAVED}`);
    } else if (d.watched) {
      const item = s.cont.find((c) => c.id === d.watched);
      if (item) void act(async () => { await markWatched(db, p.id, s.kind, item); s.cont = await loadContinue(db, p.id, s.kind); },
        s.kind === 'series' ? 'Épisode marqué vu : la télé proposera le suivant.' : 'Film marqué vu.');
    } else if (d.remove) {
      const t = list.find((l) => l.id === d.remove);
      if (t) void act(async () => { await setInList(db, p.id, s.kind, t, false); s.lists[s.kind] = list.filter((l) => l.id !== t.id); if (s.hits) s.hits = s.hits.map((h) => h.id === t.id ? { ...h, inList: false } : h); }, `Retiré de Ma liste. ${SAVED}`);
    } else if (d.lup || d.ldown) {
      const next = move(list, Number(d.lup ?? d.ldown), d.lup ? -1 : 1);
      void act(async () => { await reorderList(db, p.id, s.kind, next.map((l) => l.id)); s.lists[s.kind] = next; }, `Ordre enregistré. ${SAVED}`);
    } else if (d.add) {
      const h = s.hits?.find((x) => x.id === d.add);
      if (h) void act(async () => {
        await setInList(db, p.id, s.kind, h, true);
        s.lists[s.kind] = [...list, { ...h, known: true }];
        s.hits = s.hits!.map((x) => x.id === h.id ? { ...x, inList: true } : x);
      }, `« ${h.name} » ajouté à Ma liste. ${SAVED}`);
    } else if (d.pin) {
      const id = d.pin;
      const v = view();
      if (v.banner_pinned.some((x) => x.id === id)) void saveView((w) => ({ ...w, banner_pinned: w.banner_pinned.filter((x) => x.id !== id) }), `Retiré de la bannière. ${SAVED}`);
      else if (v.banner_pinned.length >= PIN_MAX) say(`${PIN_MAX} titres au plus dans la bannière : retires-en un d’abord.`, false);
      else {
        const t = titleOf(id);
        if (t) s.names.set(id, t);
        void saveView((w) => ({ ...w, banner_pinned: [...w.banner_pinned, { id, category: t?.category ?? null }], never: w.never.filter((x) => x !== id) }), `Épinglé dans la bannière. ${SAVED}`);
      }
    } else if (d.pup || d.pdown) {
      void saveView((w) => ({ ...w, banner_pinned: move(w.banner_pinned, Number(d.pup ?? d.pdown), d.pup ? -1 : 1) }));
    } else if (d.never) {
      const id = d.never;
      const t = titleOf(id); if (t) s.names.set(id, t);
      if (view().never.includes(id)) void saveView((w) => ({ ...w, never: w.never.filter((x) => x !== id) }), `Titre remis. ${SAVED}`);
      else if (view().never.length >= NEVER_MAX) say(`${NEVER_MAX} titres écartés au plus.`, false);
      else void saveView((w) => ({ ...w, never: [...w.never, id], banner_pinned: w.banner_pinned.filter((x) => x.id !== id) }), `Ce titre ne sera plus proposé. ${SAVED}`);
    } else if (d.mode) void saveView((w) => ({ ...w, banner_mode: d.mode === 'fixe' ? 'fixe' : 'auto' }));
    else if (d.secs) void saveView((w) => ({ ...w, banner_seconds: Number(d.secs) }));
    else if (d.max) void saveView((w) => ({ ...w, banner_max: Number(d.max) }));
    else if (d.son) {
      const id = d.son;
      void saveView((w) => ({ ...w, banner_sources: w.banner_sources.includes(id) ? w.banner_sources.filter((x) => x !== id) : [...w.banner_sources, id] }));
    } else if (d.sup || d.sdown) {
      const id = (d.sup ?? d.sdown)!;
      void saveView((w) => ({ ...w, banner_sources: move(w.banner_sources, w.banner_sources.indexOf(id), d.sup ? -1 : 1) }));
    } else if (d.rup || d.rdown) {
      void saveView((w) => ({ ...w, rows: move(w.rows, Number(d.rup ?? d.rdown), d.rup ? -1 : 1) }));
    } else if (d.ron) {
      const i = Number(d.ron);
      void saveView((w) => ({ ...w, rows: w.rows.map((r, j) => j === i ? { ...r, on: !r.on } : r) }));
    }
  };
  void load();
  root.scrollIntoView({ behavior: 'smooth', block: 'start' });
}
