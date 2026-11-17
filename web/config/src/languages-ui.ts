// Éditeur « Langues » d'un profil : ordre des langues préférées, sous-titres, préférence 4K. Enregistré à chaque choix.
import type { SettingsDb } from './avatar';
import type { ProfileHome } from './profiles';
import { LANGS, SUBTITLES, languagesOf, moveLang, saveLanguages, type LanguagePrefs } from './languages';

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);

export function openLanguagesEditor(root: HTMLElement, p: ProfileHome, db: SettingsDb, onSaved: () => void): void {
  root.hidden = false;
  const paint = (msg = '', ok = true) => {
    const l = languagesOf(p.settings);
    const label = (c: string) => LANGS.find((x) => x.code === c)!.label;
    root.innerHTML = `<div class="ped langs">
      <h3>Langues de ${esc(p.name)}</h3>
      <p class="hint">Quand un film ou une série existe en plusieurs versions, la télé ouvre la première langue de la liste qui existe. Elle choisit aussi la piste audio et les sous-titres au lancement. Une autre version reste au choix sur la fiche.</p>
      <h4>Ordre de préférence</h4>
      <ol class="lang-order">${l.order.map((c, i) => `<li><span class="lang-rank">${i + 1}</span><strong>${esc(label(c))}</strong>
        <button type="button" class="small secondary" data-up="${c}" aria-label="Monter ${esc(label(c))}" ${i === 0 ? 'disabled' : ''}>↑</button>
        <button type="button" class="small secondary" data-down="${c}" aria-label="Descendre ${esc(label(c))}" ${i === l.order.length - 1 ? 'disabled' : ''}>↓</button>
        <button type="button" class="small secondary" data-remove="${c}" aria-label="Retirer ${esc(label(c))}" ${l.order.length === 1 ? 'disabled' : ''}>Retirer</button></li>`).join('')}</ol>
      ${LANGS.some((x) => !l.order.includes(x.code)) ? `<p class="hint">Ajouter :</p><div class="chips">${LANGS.filter((x) => !l.order.includes(x.code)).map((x) => `<button type="button" class="chip" data-add="${x.code}">+ ${esc(x.label)}</button>`).join('')}</div>` : ''}
      <h4>Sous-titres</h4>
      <div class="seg seg3" role="group" aria-label="Sous-titres">${SUBTITLES.map((s) => `<button type="button" data-sub="${s.code}" aria-pressed="${l.subtitles === s.code}">${esc(s.label)}</button>`).join('')}</div>
      <label class="check"><input type="checkbox" id="lang4k" ${l.prefer_4k ? 'checked' : ''}> Préférer la 4K quand elle existe</label>
      <label class="check"><input type="checkbox" id="langOnly" ${l.only_mine ? 'checked' : ''}> Seulement mes langues : cacher les versions dans les autres langues (recherche, ajouts récents, choix de version)</label>
      <div class="tools"><button type="button" class="small secondary" id="langclose">Fermer</button></div>
      <p class="msg ${ok ? 'ok' : 'error'}" role="status" aria-live="polite">${esc(msg)}</p>
    </div>`;
  };
  const save = async (next: LanguagePrefs, done: string) => {
    try { p.settings = await saveLanguages(db, p.id, p.settings, next); onSaved(); paint(`${done} Sur la télé : change de profil ou redémarre l’application.`); }
    catch (e) { paint((e as Error).message, false); }
  };
  root.onclick = (e) => {
    const b = (e.target as HTMLElement).closest<HTMLButtonElement>('button');
    if (!b) return;
    const l = languagesOf(p.settings);
    if (b.id === 'langclose') { root.hidden = true; root.innerHTML = ''; root.onclick = null; root.onchange = null; return; }
    if (b.dataset.up) void save({ ...l, order: moveLang(l.order, b.dataset.up, -1) }, 'Ordre enregistré.');
    if (b.dataset.down) void save({ ...l, order: moveLang(l.order, b.dataset.down, 1) }, 'Ordre enregistré.');
    if (b.dataset.remove) void save({ ...l, order: l.order.filter((c) => c !== b.dataset.remove) }, 'Langue retirée.');
    if (b.dataset.add) void save({ ...l, order: [...l.order, b.dataset.add] }, 'Langue ajoutée.');
    if (b.dataset.sub && b.dataset.sub !== l.subtitles) void save({ ...l, subtitles: b.dataset.sub }, 'Sous-titres enregistrés.');
  };
  root.onchange = (e) => {
    const t = e.target as HTMLInputElement;
    if (t.id === 'langOnly') void save({ ...languagesOf(p.settings), only_mine: t.checked }, t.checked ? 'Seules tes langues seront montrées.' : 'Toutes les langues seront montrées.');
    if (t.id === 'lang4k') void save({ ...languagesOf(p.settings), prefer_4k: t.checked }, t.checked ? '4K préférée.' : 'Version standard préférée.');
  };
  paint();
  root.scrollIntoView({ behavior: 'smooth', block: 'start' });
}
