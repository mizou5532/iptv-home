import { avatarBadge } from './avatar-ui';
import { saveSections, sectionsOf, sectionsSummary, toggleSection } from './sections';
import { HOMES, loadProfileHomes, setProfileHome, type Home, type ProfileHome, type ProfilesDb } from './profiles';
import { openLookEditor } from './look-ui';
import { openSportViewEditor } from './sportview-ui';
import { openMediaEditor } from './mediaview-ui';
import type { Db } from './flow';
import { mountSportCategories } from './sportcats-ui';
import type { CategoryDb } from './sportcats';
import type { SettingsDb } from './avatar';
import { languagesOf, languagesSummary } from './languages';
import { openLanguagesEditor } from './languages-ui';
import { SEASONS, nextWindows, saveSeasons, seasonAt, seasonsOf, windowLabel, type SeasonId, type SeasonSettings } from './seasons';
import { WELCOME_THEMES, loadHouseholdSettings, setWelcomeTheme, welcomeOf, type HouseholdDb, type WelcomeTheme } from './look';
import { escapeHtml as esc, icon } from './icons';
export interface ProfileViewOptions {
  db: ProfilesDb; base: string; selectedId: string | null;
  onSelect(id: string): void; onOrganise(id: string): void;
  onPhoto(p: ProfileHome, editor: HTMLElement, refresh: () => void): void;
  onTeams(p: ProfileHome, editor: HTMLElement): void;
  /** Autocollant du profil (coin des accueils Séries et Films de la télé). */
  onSticker?(p: ProfileHome, editor: HTMLElement): void;
  /** Fond d'écran du profil (derrière les accueils Séries et Films de la télé). */
  onWallpaper?(p: ProfileHome, editor: HTMLElement): void;
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
        <section class="opening-section welcome-section"><h2 class="sect">Écran « Qui regarde ? »</h2><p>Le thème de l’écran de choix du profil, pour toute la maison.</p>
        <div class="home-picker player-picker" id="welcome" role="group" aria-label="Thème de l’écran Qui regarde ?">${WELCOME_THEMES.map(w => `<button type="button" data-welcome="${w.id}" aria-pressed="false"><span class="wprev wprev-${w.id}"><i></i><i></i><i></i></span><span>${w.label}</span><small>${w.detail}</small></button>`).join('')}</div>
        <p id="welcome-msg" class="msg" role="status" aria-live="polite"></p></section>
        <section class="opening-section seasons-section"><h2 class="sect">Thèmes de saison</h2><p>Un décor de fête sur l’écran « Qui regarde ? » aux dates de chaque fête, pour toute la maison.</p>
        <div class="ss-body"><p class="loading" role="status">Chargement…</p></div></section>
        <section class="opening-section sportcats-section"><h2 class="sect">Chaînes du Sport</h2><p>Les catégories du fournisseur où la télé cherche les chaînes des matchs (événements, chaînes d’équipes), même masquées dans le Direct. Pour toute la maison.</p>
        <div class="sc-body"><p class="loading" role="status">Chargement…</p></div></section>`;
      void mountWelcome(root, opts);
      void mountSeasons(root, opts);
      { const sec = root.querySelector<HTMLElement>('.sportcats-section')!;
        if (opts.householdId) void mountSportCategories(sec, opts.db as unknown as HouseholdDb & CategoryDb, opts.householdId); else sec.remove(); }
      root.querySelectorAll<HTMLButtonElement>('[data-profile]').forEach(b => b.addEventListener('click', () => {
        active = list.find(p => p.id === b.dataset.profile)!; opts.onSelect(active.id); paint();
      }));
      return;
    }
    const p = active;
    const secs = sectionsOf(p.settings);
    const tile = (attr: string, ic: Parameters<typeof icon>[0], title: string, sub: string) =>
      `<button type="button" class="setting-tile" ${attr}>${icon(ic)}<span><strong>${title}</strong><small>${sub}</small></span></button>`;
    const row = (attr: string, ic: Parameters<typeof icon>[0], title: string, sub: string) =>
      `<button type="button" class="setting-row" ${attr}>${icon(ic)}<span><strong>${title}</strong><small>${sub}</small></span>${icon('chevron')}</button>`;
    root.innerHTML = `<section class="profile-hero"><button class="avatar-edit" data-photo aria-label="Modifier la photo de ${esc(p.name)}">${avatarBadge(opts.base, p, 72)}<span class="edit-badge">${icon('edit')}</span></button><div><h1>${esc(p.name)}</h1><p>Mon espace télé</p><button type="button" class="text-action" data-switch>Changer de profil ${icon('chevron')}</button></div></section>
      <section class="opening-section"><h2 class="sect">Sections sur la télé</h2><p class="sect-note">Touche une section pour l’afficher ou la cacher dans la barre du haut de ${esc(p.name)}.</p><div class="home-picker" role="group" aria-label="Sections de ${esc(p.name)}">${HOMES.map(h => `<button type="button" data-section="${h.id}" aria-pressed="${secs.includes(h.id)}">${icon(h.id)}<span>${h.label}</span></button>`).join('')}</div></section>
      <section class="opening-section"><h2 class="sect">La télé s’ouvre sur</h2><div class="home-picker" role="group" aria-label="Écran de départ de ${esc(p.name)}">${HOMES.filter(h => secs.includes(h.id)).map(h => `<button type="button" data-home="${h.id}" aria-pressed="${p.home === h.id}">${icon(h.id)}<span>${h.label}</span></button>`).join('')}</div></section>
      <div class="profile-grid">
        <section><h2 class="sect">Apparence sur la télé</h2><div class="tiles">
          ${tile('data-look', 'palette', 'Thème et couleur', 'Fond, couleur, coins, lecteur')}
          ${tile('data-wallpaper', 'monitor', 'Fond d’écran', p.settings.wallpaper === true ? 'Activé · atelier' : 'Photo, modèle, atelier')}
          ${tile('data-photo', 'photo', 'Photo', 'Changer ou recadrer')}
          ${tile('data-sticker', 'sticker', 'Autocollant', p.settings.sticker === true ? 'Activé' : 'Coin de l’accueil')}
        </div></section>
        <section><h2 class="sect">Contenus</h2><div class="profile-actions">
          ${row('data-media', 'films', 'Séries et films', 'Reprendre, Ma liste, bannière, rangées')}
          ${row('data-langs', 'series', 'Langues', languagesSummary(languagesOf(p.settings)))}
          ${row('data-teams', 'sport', 'Équipes favorites', 'Choisir et ordonner mes équipes')}
          ${row('data-sportview', 'sport', 'Réglages du sport', 'Fond de l’affiche, ordre des sports, bande du bas')}
          ${row('data-organise', 'organise', 'Organiser les chaînes', 'Masquer, trier, copies, favoris')}
        </div></section>
      </div>
      <p id="profile-msg" class="msg" role="status" aria-live="polite"></p><section id="profile-editor" hidden aria-label="Modifier le profil"></section>`;
    root.querySelector('[data-switch]')!.addEventListener('click', () => { active = null; paint(); });
    root.querySelector('[data-organise]')!.addEventListener('click', () => opts.onOrganise(p.id));
    const editor = root.querySelector<HTMLElement>('#profile-editor')!;
    root.querySelectorAll('[data-photo]').forEach(b => b.addEventListener('click', () => opts.onPhoto(p, editor, paint)));
    root.querySelector('[data-wallpaper]')!.addEventListener('click', () => opts.onWallpaper?.(p, editor));
    root.querySelector('[data-sticker]')!.addEventListener('click', () => opts.onSticker?.(p, editor));
    root.querySelector('[data-langs]')!.addEventListener('click', () => openLanguagesEditor(editor, p, opts.db as unknown as SettingsDb, () => {
      const small = root.querySelector<HTMLElement>('[data-langs] small'); if (small) small.textContent = languagesSummary(languagesOf(p.settings));
    }));
    root.querySelector('[data-media]')!.addEventListener('click', () => openMediaEditor(editor, p, opts.db as unknown as Db & SettingsDb));
    root.querySelector('[data-teams]')!.addEventListener('click', () => opts.onTeams(p, editor));
    root.querySelector('[data-sportview]')!.addEventListener('click', () => openSportViewEditor(editor, p, opts.db as unknown as SettingsDb, () => {}));
    root.querySelector('[data-look]')!.addEventListener('click', () => openLookEditor(editor, p, opts.db, () => {
      const hero = root.querySelector<HTMLElement>('.profile-hero .avatar');
      if (hero) hero.style.background = p.color;
    }));
    root.querySelectorAll<HTMLButtonElement>('[data-section]').forEach(b => b.addEventListener('click', async () => {
      const msg = root.querySelector<HTMLElement>('#profile-msg')!;
      const r = toggleSection(secs, p.home, b.dataset.section as Home);
      if ('error' in r) { msg.textContent = r.error; msg.className = 'msg error'; return; }
      const allButtons = [...root.querySelectorAll<HTMLButtonElement>('button')];
      allButtons.forEach(c => { c.disabled = true; });
      msg.textContent = 'Enregistrement…'; msg.className = 'msg';
      const before = p.home;
      const request = saveSections(opts.db as unknown as SettingsDb, p.id, p.settings, r.sections, r.home, before).then(next => { p.settings = next; p.home = r.home; });
      pending!.add(request);
      try {
        await request;
        paint();
        const m = root.querySelector<HTMLElement>('#profile-msg');
        if (m) {
          m.textContent = `Sections de ${p.name} : ${sectionsSummary(r.sections)}.` + (r.home !== before ? ` La télé s’ouvre maintenant sur ${HOMES.find(h => h.id === r.home)!.label}.` : '') + ' Visible au prochain choix du profil sur la télé.';
          m.className = 'msg ok';
        }
      } catch (e) { msg.textContent = (e as Error).message; msg.className = 'msg error'; allButtons.forEach(c => { c.disabled = false; }); }
      finally { pending!.delete(request); }
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

/** Thèmes de saison (réglage du foyer, propriétaire seulement) : interrupteur, fêtes une à une, essai immédiat. */
async function mountSeasons(root: HTMLElement, opts: ProfileViewOptions): Promise<void> {
  const sec = root.querySelector<HTMLElement>('.seasons-section');
  const body = sec?.querySelector<HTMLElement>('.ss-body');
  if (!sec || !body || !opts.householdId) { sec?.remove(); return; }
  const hdb = opts.db as unknown as HouseholdDb;
  let settings: Record<string, unknown> = {};
  try { settings = await loadHouseholdSettings(hdb, opts.householdId); } catch { /* valeurs par défaut */ }
  if (!body.isConnected) return;
  const now = new Date();
  const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
  const windows = nextWindows(today);
  const current = seasonAt(today);
  const paint = (msg = '', ok = true) => {
    const s = seasonsOf(settings);
    body.innerHTML = `<button type="button" class="switch" id="ss-on" aria-pressed="${s.on}"><span class="switch-dot"></span><span>${s.on ? 'Activés' : 'Désactivés'}</span></button>
      <div class="ss-list" ${s.on ? '' : 'aria-disabled="true"'}>${SEASONS.map(x => { const w = windows[x.id];
        return `<label class="ss-row"><input type="checkbox" data-season="${x.id}" ${s.off.includes(x.id) ? '' : 'checked'} ${s.on ? '' : 'disabled'}><span><strong>${esc(x.label)}${current === x.id ? ' · en ce moment' : ''}</strong><small>${w ? esc(windowLabel(w)) : 'Pas cette année (une autre fête tombe en même temps)'} · ${esc(x.detail)}</small></span></label>`; }).join('')}</div>
      <label for="ss-force">Essayer sur la télé maintenant</label>
      <select id="ss-force"><option value="">Non (selon la date)</option>${SEASONS.map(x => `<option value="${x.id}" ${s.force === x.id ? 'selected' : ''}>${esc(x.label)}</option>`).join('')}</select>
      <p class="hint">${s.force ? 'Tant qu’un essai est choisi, la télé montre cette fête. Remets « Non » après l’essai.' : 'La télé prend le changement à son prochain démarrage.'}</p>
      <p class="msg ${ok ? 'ok' : 'error'}" role="status" aria-live="polite">${esc(msg)}</p>`;
    const save = async (next: SeasonSettings, done: string) => {
      try { settings = await saveSeasons(hdb, opts.householdId!, settings, next); paint(done); }
      catch (e) { paint((e as Error).message, false); }
    };
    body.querySelector('#ss-on')!.addEventListener('click', () => void save({ ...s, on: !s.on }, !s.on ? 'Thèmes de saison activés.' : 'Thèmes de saison désactivés.'));
    body.querySelectorAll<HTMLInputElement>('[data-season]').forEach(c => c.addEventListener('change', () => {
      const id = c.dataset.season as SeasonId;
      const off = c.checked ? s.off.filter(x => x !== id) : [...s.off, id];
      void save({ ...s, off }, `${SEASONS.find(x => x.id === id)!.label} : ${c.checked ? 'activé' : 'retiré'}.`);
    }));
    body.querySelector<HTMLSelectElement>('#ss-force')!.addEventListener('change', (e) => {
      const v = (e.target as HTMLSelectElement).value as SeasonId | '';
      void save({ ...s, force: v || null }, v ? 'Essai enregistré : redémarre l’application sur la télé pour le voir.' : 'Essai arrêté.');
    });
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
    const next = b.dataset.welcome as WelcomeTheme;
    if (next === welcomeOf(settings)) return;
    msg.textContent = 'Enregistrement…'; msg.className = 'msg';
    try { settings = await setWelcomeTheme(hdb, opts.householdId!, settings, next); mark(); msg.textContent = `Thème « ${WELCOME_THEMES.find(w => w.id === next)!.label} » enregistré. Sur la télé : redémarre l’application.`; msg.className = 'msg ok'; }
    catch (e) { msg.textContent = (e as Error).message; msg.className = 'msg error'; }
  }));
}
