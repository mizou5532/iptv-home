import { avatarBadge } from './avatar-ui';
import { HOMES, PLAYER_STYLES, loadProfileHomes, playerStyleOf, setPlayerStyle, setProfileHome, type Home, type PlayerStyle, type ProfileHome, type ProfilesDb } from './profiles';
import { escapeHtml as esc, icon } from './icons';
export interface ProfileViewOptions {
  db: ProfilesDb; base: string; selectedId: string | null;
  onSelect(id: string): void; onOrganise(id: string): void;
  onPhoto(p: ProfileHome, editor: HTMLElement, refresh: () => void): void;
  onTeams(p: ProfileHome, editor: HTMLElement): void;
}
// Une navigation aller-retour attend les écritures déjà parties avant de relire le serveur.
const writes = new WeakMap<ProfilesDb, Set<Promise<void>>>();
/** Vues isolées : une réponse tardive ne peut remplacer un nouvel écran de navigation. */
export async function mountProfileView(root: HTMLElement, opts: ProfileViewOptions): Promise<void> {
  root.innerHTML = '<p class="loading" role="status">Chargement des profils…</p>';
  let pending = writes.get(opts.db);
  if (!pending) { pending = new Set(); writes.set(opts.db, pending); }
  if (pending.size) { root.innerHTML = '<p class="loading" role="status">Enregistrement des changements…</p>'; await Promise.allSettled([...pending]); }
  if (!root.isConnected) return;
  let list: ProfileHome[];
  try { list = await loadProfileHomes(opts.db); }
  catch {
    if (!root.isConnected) return;
    root.innerHTML = '<p class="msg error" role="status">Impossible de lire les profils.</p><button data-retry>Réessayer</button>';
    root.querySelector('[data-retry]')!.addEventListener('click', () => { void mountProfileView(root, opts); });
    return;
  }
  if (!root.isConnected) return;
  if (!list.length) { root.innerHTML = '<h1>Mes profils</h1><p>Aucun profil disponible. Recharge la page ou vérifie ton compte.</p>'; return; }
  let active = list.find(p => p.id === opts.selectedId) ?? null;
  const paint = (): void => {
    if (!active) {
      root.innerHTML = `<div class="page-intro"><p class="eyebrow">À LA MAISON</p><h1>Chacun son espace.</h1><p>Choisis un profil pour personnaliser sa télé.</p></div><div class="profile-list">${list.map(p => `<button type="button" class="profile-choice" data-profile="${esc(p.id)}">${avatarBadge(opts.base, p, 56)}<span><strong>${esc(p.name)}</strong><small>S’ouvre sur ${HOMES.find(h => h.id === p.home)!.label}</small></span>${icon('chevron')}</button>`).join('')}</div><p class="footnote">La même télé, les goûts de chacun.</p>`;
      root.querySelectorAll<HTMLButtonElement>('[data-profile]').forEach(b => b.addEventListener('click', () => {
        active = list.find(p => p.id === b.dataset.profile)!; opts.onSelect(active.id); paint();
      }));
      return;
    }
    const p = active;
    root.innerHTML = `<section class="profile-hero"><button class="avatar-edit" data-photo aria-label="Modifier la photo de ${esc(p.name)}">${avatarBadge(opts.base, p, 64)}<span class="edit-badge">${icon('edit')}</span></button><div><h1>${esc(p.name)}</h1><p>Mon espace télé</p><button type="button" class="text-action" data-switch>Changer de profil ${icon('chevron')}</button></div></section>
      <section class="opening-section"><h2>À l’ouverture de la télé</h2><p>Mon écran de départ</p><div class="home-picker" role="group" aria-label="Écran de départ de ${esc(p.name)}">${HOMES.map(h => `<button type="button" data-home="${h.id}" aria-pressed="${p.home === h.id}">${icon(h.id)}<span>${h.label}</span></button>`).join('')}</div></section>
      <section class="opening-section"><h2>Lecteur vidéo</h2><p>Films et séries</p><div class="home-picker player-picker" role="group" aria-label="Lecteur de ${esc(p.name)}">${PLAYER_STYLES.map(s => `<button type="button" data-player="${s.id}" aria-pressed="${playerStyleOf(p.settings) === s.id}">${icon(s.id === 'minimal' ? 'films' : 'series')}<span>${s.label}</span><small>${s.detail}</small></button>`).join('')}</div></section>
      <button type="button" class="organise-action" data-organise>${icon('organise')}Organiser mes contenus</button>
      <div class="profile-actions"><button type="button" class="setting-row" data-photo>${icon('photo')}<span><strong>Photo du profil</strong><small>Changer ou recadrer</small></span>${icon('chevron')}</button><button type="button" class="setting-row" data-teams>${icon('sport')}<span><strong>Équipes favorites</strong><small>Choisir mes équipes</small></span>${icon('chevron')}</button></div>
      <p id="profile-msg" class="msg" role="status" aria-live="polite"></p><section id="profile-editor" hidden aria-label="Modifier le profil"></section>`;
    root.querySelector('[data-switch]')!.addEventListener('click', () => { active = null; paint(); });
    root.querySelector('[data-organise]')!.addEventListener('click', () => opts.onOrganise(p.id));
    const editor = root.querySelector<HTMLElement>('#profile-editor')!;
    root.querySelectorAll('[data-photo]').forEach(b => b.addEventListener('click', () => opts.onPhoto(p, editor, paint)));
    root.querySelector('[data-teams]')!.addEventListener('click', () => opts.onTeams(p, editor));
    // Lecteur : seulement pour ce profil (réglage player_style) ; la télé le relit à son lancement.
    root.querySelectorAll<HTMLButtonElement>('[data-player]').forEach(b => b.addEventListener('click', async () => {
      const next = b.dataset.player as PlayerStyle;
      if (playerStyleOf(p.settings) === next) return;
      const controls = [...root.querySelectorAll<HTMLButtonElement>('[data-player]')];
      controls.forEach(c => { c.disabled = true; c.setAttribute('aria-pressed', String(c === b)); });
      const msg = root.querySelector<HTMLElement>('#profile-msg')!;
      msg.textContent = 'Enregistrement…'; msg.className = 'msg';
      const request = setPlayerStyle(opts.db, p.id, p.settings, next).then(s => { p.settings = s; });
      pending!.add(request);
      try { await request; msg.textContent = `Lecteur enregistré : ${PLAYER_STYLES.find(s => s.id === next)!.label}. Sur la télé : redémarre l’application.`; msg.className = 'msg ok'; }
      catch { msg.textContent = 'Impossible d’enregistrer. Réessaie dans un instant.'; msg.className = 'msg error'; }
      finally { pending!.delete(request); controls.forEach(c => { c.disabled = false; c.setAttribute('aria-pressed', String(c.dataset.player === playerStyleOf(p.settings))); }); }
    }));
    root.querySelectorAll<HTMLButtonElement>('[data-home]').forEach(b => b.addEventListener('click', async () => {
      const before = p.home; const next = b.dataset.home as Home;
      if (before === next) return;
      const allButtons = [...root.querySelectorAll<HTMLButtonElement>('button')];
      const controls = [...root.querySelectorAll<HTMLButtonElement>('[data-home]')];
      allButtons.forEach(c => { c.disabled = true; });
      controls.forEach(c => { c.disabled = true; c.setAttribute('aria-pressed', String(c === b)); });
      const msg = root.querySelector<HTMLElement>('#profile-msg')!;
      msg.textContent = 'Enregistrement…';
      const request = setProfileHome(opts.db, p.id, next);
      pending!.add(request);
      try { await request; p.home = next; msg.textContent = `Écran de départ enregistré : ${HOMES.find(h => h.id === next)!.label}.`; msg.className = 'msg ok'; }
      catch { msg.textContent = 'Impossible d’enregistrer. Réessaie dans un instant.'; msg.className = 'msg error'; }
      finally { pending!.delete(request); allButtons.forEach(c => { c.disabled = false; }); controls.forEach(c => { c.disabled = false; c.setAttribute('aria-pressed', String(c.dataset.home === p.home)); }); }
    }));
  };
  paint();
}
