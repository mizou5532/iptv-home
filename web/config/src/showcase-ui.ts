// Section « Vitrine Films et Séries » (toute la maison) : choisir quelles catégories apparaissent en rangées à l'accueil.
import { escapeHtml as esc } from './icons';
import type { Db } from './flow';
import type { HouseholdDb } from './look';
import { loadHouseholdSettings } from './look';
import {
  chosenShowcaseCategories,
  filterShowcaseCategories,
  loadShowcaseCategories,
  saveShowcaseCategories,
  type ShowcaseCategory,
  type ShowcaseKind,
} from './showcase';

const SHOWN_MAX = 150;

export async function mountShowcase(section: HTMLElement, db: HouseholdDb & Db, householdId: string): Promise<void> {
  const body = section.querySelector<HTMLElement>('.sh-body')!;
  let movieCats: ShowcaseCategory[] = [];
  let seriesCats: ShowcaseCategory[] = [];
  let settings: Record<string, unknown> = {};
  try {
    [movieCats, seriesCats, settings] = await Promise.all([
      loadShowcaseCategories(db, householdId, 'movies'),
      loadShowcaseCategories(db, householdId, 'series'),
      loadHouseholdSettings(db, householdId),
    ]);
  } catch (e) {
    body.innerHTML = `<p class="msg error">${esc((e as Error).message)}</p>`;
    return;
  }
  if (!section.isConnected) return;

  let activeKind: ShowcaseKind = 'movies';
  let editing: ShowcaseKind | null = null;
  let query = '';
  let checked = new Set<string>();

  const catsOf = (k: ShowcaseKind) => (k === 'movies' ? movieCats : seriesCats);
  const labelOf = (k: ShowcaseKind) => (k === 'movies' ? 'Films' : 'Séries');

  const say = (text: string, ok = true) => {
    const m = section.querySelector<HTMLElement>('#shmsg');
    if (m) {
      m.textContent = text;
      m.className = `msg ${ok ? 'ok' : 'error'}`;
    }
  };

  const renderList = (cats: ShowcaseCategory[]) => {
    const shown = filterShowcaseCategories(cats, query, checked);
    return `${shown
      .slice(0, SHOWN_MAX)
      .map(
        (c) =>
          `<label class="catrow"><input type="checkbox" data-shcat="${esc(c.id)}" ${checked.has(c.id) ? 'checked' : ''}><span>${esc(c.name)}</span></label>`,
      )
      .join('')}
      ${shown.length > SHOWN_MAX ? `<p class="hint">${shown.length - SHOWN_MAX} autres : précise la recherche.</p>` : ''}${
      shown.length ? '' : '<p class="hint">Aucune catégorie.</p>'
    }`;
  };

  const paint = () => {
    const tabs = `<div class="tools" role="group" aria-label="Type de vitrine">
      <button type="button" class="small${activeKind === 'movies' ? '' : ' secondary'}" data-kind="movies" aria-pressed="${activeKind === 'movies'}">Films</button>
      <button type="button" class="small${activeKind === 'series' ? '' : ' secondary'}" data-kind="series" aria-pressed="${activeKind === 'series'}">Séries</button>
    </div>`;
    const cats = catsOf(activeKind);
    if (!cats.length) {
      body.innerHTML = `${tabs}<p class="hint">Aucune catégorie ${labelOf(activeKind).toLowerCase()} encore : ouvre ${labelOf(activeKind)} une fois sur la télé.</p><p id="shmsg" class="msg" role="status" aria-live="polite"></p>`;
      return;
    }
    const chosen = chosenShowcaseCategories(settings, activeKind, cats);
    if (editing !== activeKind) {
      const names = cats.filter((c) => chosen.ids.includes(c.id)).map((c) => c.name);
      body.innerHTML = `${tabs}
        <p><strong>${chosen.auto ? 'Choix automatique' : 'Mon choix'} (${labelOf(activeKind)})</strong> : ${names.length} catégorie${names.length > 1 ? 's' : ''}.</p>
        <p class="hint">${esc(names.slice(0, 8).join(' · '))}${names.length > 8 ? ' …' : ''}</p>
        <div class="tools"><button type="button" class="small" data-edit>Modifier ${labelOf(activeKind)}</button></div>
        <p id="shmsg" class="msg" role="status" aria-live="polite"></p>`;
      return;
    }
    body.innerHTML = `${tabs}
      <input type="search" id="shq" placeholder="Chercher (ex. FR, Netflix, Anime, Kids)" value="${esc(query)}" aria-label="Chercher une catégorie">
      <p class="hint" id="shcount">${checked.size} cochée${checked.size > 1 ? 's' : ''} · les cochées d’abord</p>
      <div class="catpick">${renderList(cats)}</div>
      <div class="tools">
        <button type="button" class="small" data-save>Enregistrer</button>
        <button type="button" class="small secondary" data-auto>Choix automatique</button>
        <button type="button" class="small secondary" data-close>Fermer</button>
      </div>
      <p id="shmsg" class="msg" role="status" aria-live="polite"></p>`;
    const q = body.querySelector<HTMLInputElement>('#shq')!;
    q.oninput = () => {
      query = q.value;
      body.querySelector('.catpick')!.innerHTML = renderList(cats);
    };
  };

  const save = async (kind: ShowcaseKind, ids: string[] | null) => {
    const btns = [...body.querySelectorAll<HTMLButtonElement>('[data-save], [data-auto]')];
    btns.forEach((b) => { b.disabled = true; });
    say('Enregistrement…');
    try {
      settings = await saveShowcaseCategories(db, householdId, kind, ids);
      editing = null;
      paint();
      say('Enregistré. La télé mettra à jour ses rangées au prochain démarrage ou changement d’onglet.');
    } catch (e) {
      btns.forEach((b) => { b.disabled = false; });
      say((e as Error).message, false);
    }
  };

  body.onchange = (e) => {
    const box = e.target as HTMLInputElement;
    if (!box.dataset.shcat) return;
    if (box.checked) checked.add(box.dataset.shcat);
    else checked.delete(box.dataset.shcat);
    const h = body.querySelector('#shcount');
    if (h) h.textContent = `${checked.size} cochée${checked.size > 1 ? 's' : ''} · les cochées d’abord`;
  };

  body.onclick = (e) => {
    const b = (e.target as HTMLElement).closest<HTMLButtonElement>('button');
    if (!b) return;
    if (b.dataset.kind === 'movies' || b.dataset.kind === 'series') {
      activeKind = b.dataset.kind;
      editing = null;
      query = '';
      paint();
    } else if (b.dataset.edit !== undefined) {
      const cats = catsOf(activeKind);
      checked = new Set(chosenShowcaseCategories(settings, activeKind, cats).ids);
      editing = activeKind;
      query = '';
      paint();
    } else if (b.dataset.close !== undefined) {
      editing = null;
      paint();
    } else if (b.dataset.save !== undefined) {
      const cats = catsOf(activeKind);
      void save(activeKind, cats.filter((c) => checked.has(c.id)).map((c) => c.id));
    } else if (b.dataset.auto !== undefined) {
      void save(activeKind, null);
    }
  };

  paint();
}
