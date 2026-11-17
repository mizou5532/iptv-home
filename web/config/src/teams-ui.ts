// Panneau « Équipes » d'un profil sur la page iPhone : ses équipes favorites (onglet Sport de la télé).
import type { SettingsDb } from './avatar';
import type { ProfileHome } from './profiles';
import { moveTeam } from './sportview';
import { fromCatalog, saveTeams, teamsOf, withTeam, withoutTeam, type LeagueTeams, type SportTeam } from './teams';

function esc(s: string): string {
  return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);
}

function crest(t: { name: string; logo?: string | null; abbr?: string }): string {
  return `<span class="crest">${t.logo ? `<img src="${esc(t.logo)}" alt="" loading="lazy">` : esc((t.abbr || t.name).slice(0, 3))}</span>`;
}

export async function openTeamsEditor(root: HTMLElement, p: ProfileHome, db: SettingsDb, onSaved: (settings: Record<string, unknown>) => void): Promise<void> {
  root.hidden = false;
  root.innerHTML = '<p>Chargement…</p>';
  // Catalogue chargé à la demande (≈ 30 Ko) : la page reste légère.
  const catalog = (await import('./teams-data.json')).default as LeagueTeams[];
  let league = catalog[0].key;
  let query = '';
  const paint = (): void => {
    const { teams, chosen } = teamsOf(p.settings);
    const lg = catalog.find((l) => l.key === league)!;
    const q = query.trim().toLowerCase();
    const shown = lg.teams.filter((t) => !q || t.name.toLowerCase().includes(q) || t.abbr.toLowerCase() === q);
    root.innerHTML = `<div class="ped">
      <h3>Équipes de ${esc(p.name)}</h3>
      <p class="hint">Leurs matchs en premier dans l'onglet Sport de la télé${chosen ? '' : ' (pour l’instant : Canadiens et Barça)'}.</p>
      <div class="teamlist">${teams.length ? teams.map((t, i) => `<div class="teamrow">${crest(t)}<span>${i + 1}. ${esc(t.name)}</span>
        <button type="button" class="small secondary" data-tup="${i}" ${i === 0 ? 'disabled' : ''} aria-label="Monter ${esc(t.name)}">↑</button>
        <button type="button" class="small secondary" data-tdown="${i}" ${i === teams.length - 1 ? 'disabled' : ''} aria-label="Descendre ${esc(t.name)}">↓</button>
        <button type="button" class="small secondary" data-del="${i}">Retirer</button></div>`).join('') : '<p class="hint">Aucune équipe.</p>'}</div>
      ${teams.length > 1 ? '<p class="hint">La 1re passe à l’affiche si deux de tes équipes jouent en même temps.</p>' : ''}
      <h3>Ajouter une équipe</h3>
      <div class="chips">${catalog.map((l) => `<button type="button" class="chip" data-league="${esc(l.key)}" aria-pressed="${l.key === league}">${esc(l.label)}</button>`).join('')}</div>
      <input id="tq" type="search" placeholder="Chercher dans ${esc(lg.label)}" value="${esc(query)}" autocomplete="off">
      <div class="teamgrid">${shown.map((t) => {
        const has = teams.some((x) => x.league === league && x.id === t.id);
        return `<button type="button" class="teampick" data-add="${esc(t.id)}" ${has ? 'aria-pressed="true" disabled' : ''}>${crest(t)}<span>${esc(t.name)}</span></button>`;
      }).join('')}</div>
      <div class="tools"><button type="button" class="small secondary" id="tclose">Fermer</button></div>
      <div id="tmsg" class="msg" role="status"></div>
    </div>`;
    const input = root.querySelector<HTMLInputElement>('#tq')!;
    input.addEventListener('input', () => { query = input.value; const pos = input.selectionStart; paint(); const again = root.querySelector<HTMLInputElement>('#tq')!; again.focus(); again.setSelectionRange(pos, pos); });
  };
  const save = async (next: SportTeam[]): Promise<void> => {
    try { p.settings = await saveTeams(db, p.id, p.settings, next); onSaved(p.settings); paint(); }
    catch (e) { const m = root.querySelector('#tmsg'); if (m) { m.textContent = (e as Error).message; m.className = 'msg error'; } }
  };
  root.onclick = (e) => {
    const el = e.target as HTMLElement;
    const b = el.closest<HTMLButtonElement>('button');
    if (!b) return;
    if (b.id === 'tclose') { root.hidden = true; root.innerHTML = ''; root.onclick = null; return; }
    if (b.dataset.league) { league = b.dataset.league; query = ''; paint(); return; }
    const { teams } = teamsOf(p.settings);
    if (b.dataset.del !== undefined) { void save(withoutTeam(teams, teams[Number(b.dataset.del)])); return; }
    if (b.dataset.tup !== undefined) { void save(moveTeam(teams, Number(b.dataset.tup), -1)); return; }
    if (b.dataset.tdown !== undefined) { void save(moveTeam(teams, Number(b.dataset.tdown), 1)); return; }
    if (b.dataset.add) {
      const t = catalog.find((l) => l.key === league)!.teams.find((x) => x.id === b.dataset.add);
      if (!t) return;
      try { void save(withTeam(teams, fromCatalog(league, t))); }
      catch (err) { const m = root.querySelector('#tmsg'); if (m) { m.textContent = (err as Error).message; m.className = 'msg error'; } }
    }
  };
  paint();
  root.scrollIntoView({ behavior: 'smooth', block: 'start' });
}
