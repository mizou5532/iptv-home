// Panneau « Réglages du sport » d'un profil (page iPhone) : disposition (affiche ou grille), fond, match à l'affiche, ordre et sports
// affichés, mes équipes d'abord, ligne d'aide. Chaque choix est enregistré tout de suite.
import type { SettingsDb } from './avatar';
import type { ProfileHome } from './profiles';
import { escapeHtml as esc } from './icons';
import { AFFICHE_THEMES, FEATURED, LANGS, LAYOUTS, SPORTS, moveSport, saveSportView, sportViewOf, sportsOfTeams, type SportView } from './sportview';
import { teamsOf } from './teams';

/** Aperçu miniature de l'onglet Sport de la télé avec ce fond et cette disposition. */
export function affichePreview(view: SportView, profileColor: string): string {
  const bg = view.theme === 'sombre' ? 'linear-gradient(105deg,#26324A,#1A2130)'
    : view.theme === 'profil' ? `linear-gradient(105deg,${esc(profileColor)},color-mix(in srgb, ${esc(profileColor)} 65%, black))`
    : 'linear-gradient(105deg,#7a5d10 0%,#3b2e05 46%,#0b1e4d 54%,#7a0d20 100%)';
  if (view.layout === 'grille') return gridPreview(bg, view.hints);
  return `<div class="affmini" style="background:${bg}" aria-label="Aperçu de l'affiche Sport">
    <span class="affmini-live">● DIRECT</span>
    <div class="affmini-match"><span class="affmini-crest">BOS</span><strong>1 : 2</strong><span class="affmini-crest">MTL</span></div>
    <span class="affmini-btn">▶ Regarder sur RDS</span>
    ${view.hints ? '<small>OK : regarder · maintenir OK : choisir la chaîne</small>' : ''}
    <div class="affmini-strip"><span></span><span></span><span></span></div>
  </div>`;
}

/** Aperçu de la grille : le match choisi en haut (sur le fond choisi), les filtres, puis la grille des autres matchs. */
function gridPreview(heroBg: string, hints: boolean): string {
  return `<div class="gridmini" aria-label="Aperçu de la grille Sport">
    <div class="gridmini-hero" style="background:${heroBg}">
      <span class="affmini-live">● DIRECT</span>
      <div class="affmini-match"><span class="affmini-crest">BOS</span><strong>1 : 2</strong><span class="affmini-crest">MTL</span></div>
      <span class="affmini-btn">▶ Regarder sur RDS</span>
      ${hints ? '<small>OK : regarder · maintenir OK : choisir la chaîne</small>' : ''}
    </div>
    <div class="gridmini-chips"><span class="on"></span><span></span><span></span><span></span></div>
    <div class="gridmini-tiles">${'<span></span>'.repeat(8)}</div>
  </div>`;
}

export function openSportViewEditor(root: HTMLElement, p: ProfileHome, db: SettingsDb, onSaved: (settings: Record<string, unknown>) => void): void {
  root.hidden = false;
  const say = (text: string, ok = true) => { const m = root.querySelector<HTMLElement>('#svmsg'); if (m) { m.textContent = text; m.className = `msg ${ok ? 'ok' : 'error'}`; } };
  const yesNo = (key: string, on: boolean) =>
    `<div class="seg" role="group"><button type="button" data-${key}="1" aria-pressed="${on}">Oui</button><button type="button" data-${key}="0" aria-pressed="${!on}">Non</button></div>`;
  const paint = (): void => {
    const v = sportViewOf(p.settings);
    const locked = sportsOfTeams(p.settings);
    const teams = teamsOf(p.settings).teams;
    root.innerHTML = `<div class="ped look sportview">
      <h3>Réglages du sport de ${esc(p.name)}</h3>
      ${affichePreview(v, p.color)}
      <h4>Disposition de l'écran</h4>
      <div class="seg" role="group" aria-label="Disposition de l'écran">${LAYOUTS.map((l) => `<button type="button" data-layout="${l.id}" aria-pressed="${v.layout === l.id}">${esc(l.label)}</button>`).join('')}</div>
      <p class="hint">${esc(LAYOUTS.find((l) => l.id === v.layout)!.hint)}</p>
      <h4>Fond ${v.layout === 'grille' ? 'du match du haut' : "de l'affiche"}</h4>
      <div class="seg seg3" role="group" aria-label="Fond du match">${AFFICHE_THEMES.map((t) => `<button type="button" data-theme="${t.id}" aria-pressed="${v.theme === t.id}">${esc(t.label)}</button>`).join('')}</div>
      <p class="hint">${esc(AFFICHE_THEMES.find((t) => t.id === v.theme)!.hint)}</p>
      <h4>À l'affiche en arrivant</h4>
      <div class="seg seg3" role="group" aria-label="Match à l'affiche">${FEATURED.map((f) => `<button type="button" data-featured="${f.id}" aria-pressed="${v.featured === f.id}">${esc(f.label)}</button>`).join('')}</div>
      <p class="hint">${esc(FEATURED.find((f) => f.id === v.featured)!.hint)}</p>
      <h4>Cacher les scores <small>pour regarder en différé</small></h4>
      ${yesNo('hidescores', v.hide_scores)}
      <p class="hint">${v.hide_scores ? 'Aucun score ni classement sur la télé. Pour voir le score d’un match : touche Info, ou « Voir le score » en maintenant OK.' : 'Scores et classements visibles.'}</p>
      <h4>Ordre des sports <small>${v.layout === 'grille' ? 'grille' : 'bande du bas'}</small></h4>
      <div class="sportorder">${v.order.map((id, i) => {
        const s = SPORTS.find((x) => x.id === id)!;
        const hidden = v.hidden.includes(id);
        const lock = locked.includes(id);
        return `<div class="sportrow${hidden ? ' off' : ''}"><span class="sporticon" aria-hidden="true">${s.icon}</span><span>${esc(s.label)}${lock ? '<small>Une de tes équipes y joue</small>' : ''}</span>
          <button type="button" class="small secondary" data-up="${id}" ${i === 0 ? 'disabled' : ''} aria-label="Monter ${esc(s.label)}">↑</button>
          <button type="button" class="small secondary" data-down="${id}" ${i === v.order.length - 1 ? 'disabled' : ''} aria-label="Descendre ${esc(s.label)}">↓</button>
          <button type="button" class="small ${hidden ? '' : 'secondary'}" data-hide="${id}" aria-pressed="${!hidden}" ${lock && !hidden ? 'disabled title="Les matchs de tes équipes restent toujours"' : ''}>${hidden ? 'Masqué' : 'Affiché'}</button></div>`;
      }).join('')}</div>
      <p class="hint">Un sport masqué n’est plus téléchargé ni montré (sauf les matchs de tes équipes).</p>
      <h4>Langue des chaînes <small>proposées pour un match</small></h4>
      <div class="sportorder">${v.lang_order.map((id, i) => {
        const l = LANGS.find((x) => x.id === id)!;
        return `<div class="sportrow"><span class="sporticon" aria-hidden="true">${i + 1}</span><span>${esc(l.label)}<small>${esc(l.hint)}</small></span>
          <button type="button" class="small secondary" data-lup="${id}" ${i === 0 ? 'disabled' : ''} aria-label="Monter ${esc(l.label)}">↑</button>
          <button type="button" class="small secondary" data-ldown="${id}" ${i === v.lang_order.length - 1 ? 'disabled' : ''} aria-label="Descendre ${esc(l.label)}">↓</button></div>`;
      }).join('')}</div>
      <p class="hint">Les chaînes qui diffusent le match sont proposées dans cet ordre (ta chaîne préférée ★ reste toujours la première).</p>
      <h4>Mes équipes en premier ${v.layout === 'grille' ? 'dans la grille' : 'dans la bande'}</h4>
      ${yesNo('mine', v.mine_first)}
      <p class="hint">${v.mine_first ? 'Les matchs de tes équipes d’abord, puis les autres par sport.' : 'Tous les matchs triés par sport, tes équipes comprises.'}</p>
      <h4>Ordre de mes équipes <small>${teams.length ? 'départage leurs matchs' : ''}</small></h4>
      <p class="hint">${teams.length ? esc(teams.map((t, i) => `${i + 1}. ${t.name}`).join('   ')) : 'Aucune équipe favorite.'} — à changer dans « Équipes favorites ».</p>
      <h4>Ligne d'aide sous les boutons</h4>
      ${yesNo('hints', v.hints)}
      <div class="tools"><button type="button" class="small secondary" id="svclose">Fermer</button></div>
      <p id="svmsg" class="msg" role="status" aria-live="polite"></p>
    </div>`;
  };
  const save = async (change: (v: SportView) => SportView, done: string) => {
    say('Enregistrement…');
    try {
      p.settings = await saveSportView(db, p.id, p.settings, change(sportViewOf(p.settings)));
      onSaved(p.settings); paint(); say(`${done} Sur la télé : redémarre l’application.`);
    } catch (e) { paint(); say((e as Error).message, false); }
  };
  root.onclick = (e) => {
    const b = (e.target as HTMLElement).closest<HTMLButtonElement>('button');
    if (!b || b.disabled) return;
    const d = b.dataset;
    if (b.id === 'svclose') { root.hidden = true; root.innerHTML = ''; root.onclick = null; return; }
    if (d.layout) void save((v) => ({ ...v, layout: d.layout as SportView['layout'] }), 'Disposition enregistrée.');
    else if (d.theme) void save((v) => ({ ...v, theme: d.theme as SportView['theme'] }), 'Fond enregistré.');
    else if (d.featured) void save((v) => ({ ...v, featured: d.featured as SportView['featured'] }), 'Affiche enregistrée.');
    else if (d.up) void save((v) => ({ ...v, order: moveSport(v.order, d.up as SportView['order'][number], -1) }), 'Ordre enregistré.');
    else if (d.down) void save((v) => ({ ...v, order: moveSport(v.order, d.down as SportView['order'][number], 1) }), 'Ordre enregistré.');
    else if (d.hide) {
      const id = d.hide as SportView['order'][number];
      void save((v) => ({ ...v, hidden: v.hidden.includes(id) ? v.hidden.filter((x) => x !== id) : [...v.hidden, id] }), 'Sports affichés enregistrés.');
    }
    else if (d.lup) void save((v) => ({ ...v, lang_order: moveSport(v.lang_order, d.lup as SportView['lang_order'][number], -1) }), 'Ordre des langues enregistré.');
    else if (d.ldown) void save((v) => ({ ...v, lang_order: moveSport(v.lang_order, d.ldown as SportView['lang_order'][number], 1) }), 'Ordre des langues enregistré.');
    else if (d.mine) void save((v) => ({ ...v, mine_first: d.mine === '1' }), 'Ordre de la bande enregistré.');
    else if (d.hidescores) void save((v) => ({ ...v, hide_scores: d.hidescores === '1' }), d.hidescores === '1' ? 'Scores cachés.' : 'Scores visibles.');
    else if (d.hints) void save((v) => ({ ...v, hints: d.hints === '1' }), 'Ligne d’aide enregistrée.');
  };
  paint();
  root.scrollIntoView({ behavior: 'smooth', block: 'start' });
}
