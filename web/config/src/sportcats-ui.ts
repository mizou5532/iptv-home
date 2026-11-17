// Section « Chaînes du Sport » (toute la maison) : catégories du fournisseur où la télé cherche les chaînes des matchs.
import { escapeHtml as esc } from './icons';
import type { HouseholdDb } from './look';
import { loadHouseholdSettings } from './look';
import { chosenSportCategories, filterCategories, loadCategories, saveSportCategories, type CategoryDb, type SportCategory } from './sportcats';

const SHOWN_MAX = 150;

export async function mountSportCategories(section: HTMLElement, db: HouseholdDb & CategoryDb, householdId: string): Promise<void> {
  let cats: SportCategory[] = [];
  let settings: Record<string, unknown> = {};
  try { [cats, settings] = await Promise.all([loadCategories(db), loadHouseholdSettings(db, householdId)]); }
  catch (e) { section.querySelector('.sc-body')!.innerHTML = `<p class="msg error">${esc((e as Error).message)}</p>`; return; }
  if (!section.isConnected) return;
  let chosen = chosenSportCategories(settings, cats);
  let checked = new Set(chosen.ids);
  let query = '';
  let editing = false;
  const body = section.querySelector<HTMLElement>('.sc-body')!;
  const say = (text: string, ok = true) => { const m = section.querySelector<HTMLElement>('#scmsg'); if (m) { m.textContent = text; m.className = `msg ${ok ? 'ok' : 'error'}`; } };
  const names = () => cats.filter((c) => chosen.ids.includes(c.id)).map((c) => c.name);
  const list = () => {
    const shown = filterCategories(cats, query, checked);
    return `${shown.slice(0, SHOWN_MAX).map((c) => `<label class="catrow"><input type="checkbox" data-cat="${esc(c.id)}" ${checked.has(c.id) ? 'checked' : ''}><span>${esc(c.name)}</span></label>`).join('')}
      ${shown.length > SHOWN_MAX ? `<p class="hint">${shown.length - SHOWN_MAX} autres : précise la recherche.</p>` : ''}${shown.length ? '' : '<p class="hint">Aucune catégorie.</p>'}`;
  };
  const paint = () => {
    if (!cats.length) { body.innerHTML = '<p class="hint">Aucune catégorie encore : ouvre le Direct sur la télé une fois.</p>'; return; }
    if (!editing) {
      const n = names();
      body.innerHTML = `<p><strong>${chosen.auto ? 'Choix automatique' : 'Mon choix'}</strong> : ${n.length} catégorie${n.length > 1 ? 's' : ''}.</p>
        <p class="hint">${esc(n.slice(0, 8).join(' · '))}${n.length > 8 ? ' …' : ''}</p>
        <div class="tools"><button type="button" class="small" data-edit>Modifier</button></div><p id="scmsg" class="msg" role="status" aria-live="polite"></p>`;
      return;
    }
    body.innerHTML = `<input type="search" id="scq" placeholder="Chercher (ex. NHL, ESPN, RDS)" value="${esc(query)}" aria-label="Chercher une catégorie">
      <p class="hint">${checked.size} cochée${checked.size > 1 ? 's' : ''} · les cochées d’abord</p>
      <div class="catpick">${list()}</div>
      <div class="tools"><button type="button" class="small" data-save>Enregistrer</button><button type="button" class="small secondary" data-auto>Choix automatique</button><button type="button" class="small secondary" data-close>Fermer</button></div>
      <p id="scmsg" class="msg" role="status" aria-live="polite"></p>`;
    const q = body.querySelector<HTMLInputElement>('#scq')!;
    q.oninput = () => { query = q.value; body.querySelector('.catpick')!.innerHTML = list(); };
  };
  const save = async (ids: string[] | null) => {
    say('Enregistrement…');
    try {
      settings = await saveSportCategories(db, householdId, ids);
      chosen = chosenSportCategories(settings, cats); checked = new Set(chosen.ids); editing = false; paint();
      say('Enregistré. La télé relit ces catégories à la prochaine ouverture du Sport.');
    } catch (e) { say((e as Error).message, false); }
  };
  body.onchange = (e) => {
    const box = e.target as HTMLInputElement;
    if (!box.dataset.cat) return;
    if (box.checked) checked.add(box.dataset.cat); else checked.delete(box.dataset.cat);
    const h = body.querySelector('.hint'); if (h) h.textContent = `${checked.size} cochée${checked.size > 1 ? 's' : ''} · les cochées d’abord`;
  };
  body.onclick = (e) => {
    const b = (e.target as HTMLElement).closest<HTMLButtonElement>('button');
    if (!b) return;
    if (b.dataset.edit !== undefined) { editing = true; query = ''; paint(); }
    else if (b.dataset.close !== undefined) { editing = false; checked = new Set(chosen.ids); paint(); }
    else if (b.dataset.save !== undefined) void save(cats.filter((c) => checked.has(c.id)).map((c) => c.id));
    else if (b.dataset.auto !== undefined) void save(null);
  };
  paint();
}
