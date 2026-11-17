import { avatarBadge } from './avatar-ui';
import { HOMES, loadProfileHomes, setProfileHome, type Home, type ProfileHome, type ProfilesDb } from './profiles';
import { openLookEditor } from './look-ui';
import { openSportViewEditor } from './sportview-ui';
import { openMediaEditor } from './mediaview-ui';
import type { Db } from './flow';
import { mountSportCategories } from './sportcats-ui';
import type { CategoryDb } from './sportcats';
import type { SettingsDb } from './avatar';
import { WELCOME_THEMES, loadHouseholdSettings, setWelcomeTheme, welcomeOf, type HouseholdDb } from './look';
import { escapeHtml as esc, icon } from './icons';
export interface ProfileViewOptions {
  db: ProfilesDb; base: string; selectedId: string | null;
  onSelect(id: string): void; onOrganise(id: string): void;
  onPhoto(p: ProfileHome, editor: HTMLElement, refresh: () => void): void;
  onTeams(p: ProfileHome, editor: HTMLElement): void;
  /** Foyer : thème de l'écran « Qui regarde ? » (toute la maison). */
  householdId?: string;
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
      root.innerHTML = `<div class="page-intro"><p class="eyebrow">À LA MAISON</p><h1>Chacun son espace.</h1><p>Choisis un profil pour personnaliser sa télé.</p></div><div class="profile-list">${list.map(p => `<button type="button" class="profile-choice" data-profile="${esc(p.id)}">${avatarBadge(opts.base, p, 56)}<span><strong>${esc(p.name)}</strong><small>S’ouvre sur ${HOMES.find(h => h.id === p.home)!.label}</small></span>${icon('chevron')}</button>`).join('')}</div><p class="footnote">La même télé, les goûts de chacun.</p><p class="footnote credits">Images, titres et résumés des films et séries : <a href="https://www.themoviedb.org" target="_blank" rel="noopener">TMDB</a> (ce produit utilise l’API de TMDB sans être approuvé ni certifié par TMDB). Notes IMDb : OMDb.</p>
        <section class="opening-section welcome-section"><h2>Écran « Qui regarde ? »</h2><p>Le thème de l’écran de choix du profil, pour toute la maison.</p>
        <div class="home-picker player-picker" id="welcome" role="group" aria-label="Thème de l’écran Qui regarde ?">${WELCOME_THEMES.map(w => `<button type="button" data-welcome="${w.id}" aria-pressed="false"><span class="wprev wprev-${w.id}"><i></i><i></i><i></i></span><span>${w.label}</span><small>${w.detail}</small></button>`).join('')}</div>
        <p id="welcome-msg" class="msg" role="status" aria-live="polite"></p></section>
        <section class="opening-section sportcats-section"><h2>Chaînes du Sport</h2><p>Les catégories du fournisseur où la télé cherche les chaînes des matchs (événements, chaînes d’équipes), même masquées dans le Direct. Pour toute la maison.</p>
        <div class="sc-body"><p class="loading" role="status">Chargement…</p></div></section>`;
      void mountWelcome(root, opts);
      { const sec = root.querySelector<HTMLElement>('.sportcats-section')!;
        if (opts.householdId) void mountSportCategories(sec, opts.db as unknown as HouseholdDb & CategoryDb, opts.householdId); else sec.remove(); }
      root.querySelectorAll<HTMLButtonElement>('[data-profile]').forEach(b => b.addEventListener('click', () => {
        active = list.find(p => p.id === b.dataset.profile)!; opts.onSelect(active.id); paint();
      }));
      return;
    }
    const p = active;
    root.innerHTML = `<section class="profile-hero"><button class="avatar-edit" data-photo aria-label="Modifier la photo de ${esc(p.name)}">${avatarBadge(opts.base, p, 64)}<span class="edit-badge">${icon('edit')}</span></button><div><h1>${esc(p.name)}</h1><p>Mon espace télé</p><button type="button" class="text-action" data-switch>Changer de profil ${icon('chevron')}</button></div></section>
      <section class="opening-section"><h2>À l’ouverture de la télé</h2><p>Mon écran de départ</p><div class="home-picker" role="group" aria-label="Écran de départ de ${esc(p.name)}">${HOMES.map(h => `<button type="button" data-home="${h.id}" aria-pressed="${p.home === h.id}">${icon(h.id)}<span>${h.label}</span></button>`).join('')}</div></section>
      <button type="button" class="organise-action" data-organise>${icon('organise')}Organiser mes contenus</button>
      <div class="profile-actions"><button type="button" class="setting-row" data-look>${icon('edit')}<span><strong>Personnalisation</strong><small>Thème, couleur principale, lecteur</small></span>${icon('chevron')}</button><button type="button" class="setting-row" data-photo>${icon('photo')}<span><strong>Photo du profil</strong><small>Changer ou recadrer</small></span>${icon('chevron')}</button><button type="button" class="setting-row" data-media>${icon('organise')}<span><strong>Séries et films</strong><small>Reprendre, Ma liste, bannière, rangées</small></span>${icon('chevron')}</button><button type="button" class="setting-row" data-teams>${icon('sport')}<span><strong>Équipes favorites</strong><small>Choisir et ordonner mes équipes</small></span>${icon('chevron')}</button><button type="button" class="setting-row" data-sportview>${icon('sport')}<span><strong>Réglages du sport</strong><small>Fond de l'affiche, ordre des sports, bande du bas</small></span>${icon('chevron')}</button></div>
      <p id="profile-msg" class="msg" role="status" aria-live="polite"></p><section id="profile-editor" hidden aria-label="Modifier le profil"></section>`;
    root.querySelector('[data-switch]')!.addEventListener('click', () => { active = null; paint(); });
    root.querySelector('[data-organise]')!.addEventListener('click', () => opts.onOrganise(p.id));
    const editor = root.querySelector<HTMLElement>('#profile-editor')!;
    root.querySelectorAll('[data-photo]').forEach(b => b.addEventListener('click', () => opts.onPhoto(p, editor, paint)));
    root.querySelector('[data-media]')!.addEventListener('click', () => openMediaEditor(editor, p, opts.db as unknown as Db & SettingsDb));
    root.querySelector('[data-teams]')!.addEventListener('click', () => opts.onTeams(p, editor));
    root.querySelector('[data-sportview]')!.addEventListener('click', () => openSportViewEditor(editor, p, opts.db as unknown as SettingsDb, () => {}));
    root.querySelector('[data-look]')!.addEventListener('click', () => openLookEditor(editor, p, opts.db, () => {
      const hero = root.querySelector<HTMLElement>('.profile-hero .avatar');
      if (hero) hero.style.background = p.color;
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

/** Choix du thème de « Qui regarde ? » (réglage du foyer, propriétaire seulement). */
async function mountWelcome(root: HTMLElement, opts: ProfileViewOptions): Promise<void> {
  const box = root.querySelector<HTMLElement>('#welcome');
  if (!box || !opts.householdId) { root.querySelector('.welcome-section')?.remove(); return; }
  const hdb = opts.db as unknown as HouseholdDb;
  let settings: Record<string, unknown> = {};
  try { settings = await loadHouseholdSettings(hdb, opts.householdId); } catch { /* thème par défaut */ }
  const mark = () => box.querySelectorAll<HTMLButtonElement>('[data-welcome]').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.welcome === welcomeOf(settings))));
  mark();
  box.querySelectorAll<HTMLButtonElement>('[data-welcome]').forEach(b => b.addEventListener('click', async () => {
    const msg = root.querySelector<HTMLElement>('#welcome-msg')!;
    const next = b.dataset.welcome as 'doux' | 'aurore';
    if (next === welcomeOf(settings)) return;
    msg.textContent = 'Enregistrement…'; msg.className = 'msg';
    try { settings = await setWelcomeTheme(hdb, opts.householdId!, settings, next); mark(); msg.textContent = `Thème « ${WELCOME_THEMES.find(w => w.id === next)!.label} » enregistré. Sur la télé : redémarre l’application.`; msg.className = 'msg ok'; }
    catch (e) { msg.textContent = (e as Error).message; msg.className = 'msg error'; }
  }));
}
