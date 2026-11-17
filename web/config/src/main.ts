import { createClient } from '@supabase/supabase-js';
import './ui.css';
import { mountOrganiser, unmountOrganiser } from './org/view';
import { openAvatarEditor } from './avatar-ui';
import { openStickerEditor } from './sticker-ui';
import { openWallpaperEditor } from './wallpaper-ui';
import { openTeamsEditor } from './teams-ui';
import type { AvatarStorage, SettingsDb } from './avatar';
import type { ProfilesDb } from './profiles';
import { mountProfileView } from './profile-view';
import { siteHeader, siteNav, type SiteTab } from './shell';
import { icon } from './icons';
import { mountDevices } from './device-view';
import {
  approveCode, loadState, normalizePairingCode, revokeDevice, setupHousehold, updateCredentials,
  MAX_PROFILES, savePendingCode, loadPendingCode, clearPendingCode, type Db, type State, type KV,
} from './flow';

// Adresse et clé publiques du projet (publiques par conception ; la RLS protège les données).
const SUPABASE_URL = 'https://gisjeuukbrgjgkrmlyvs.supabase.co';
const SUPABASE_KEY = 'sb_publishable_Rfqg2UOqhABAF4ugGAfzHQ_84UIpABt';

// ?demo : données inventées en mémoire, sans réseau ni compte (bandeau « Démo » affiché).
const demo = import.meta.env.VITE_DEMO_ONLY === '1' || new URLSearchParams(location.search).has('demo');
// La démo n’instancie même pas le client distant (auth et stockage compris).
const supabase = demo ? null : createClient(SUPABASE_URL, SUPABASE_KEY);
let db = supabase as unknown as Db;
let demoHousehold = '';
const app = document.getElementById('app')!;
// Le code vient du QR (?code=). On le garde en mémoire puis on nettoie l'adresse.
const store: KV = {
  getItem: (k) => { try { return sessionStorage.getItem(k); } catch { return null; } },
  setItem: (k, v) => { try { sessionStorage.setItem(k, v); } catch { /* indisponible */ } },
  removeItem: (k) => { try { sessionStorage.removeItem(k); } catch { /* indisponible */ } },
};
let pendingCode = normalizePairingCode(new URLSearchParams(location.search).get('code') ?? '');
if (pendingCode) savePendingCode(store, pendingCode); else pendingCode = loadPendingCode(store);
if (location.search && !demo) history.replaceState(null, '', location.pathname);
let busy = false;
// Garde anti double envoi : désactive le bouton le temps de la requête.
async function guarded(form: HTMLElement, fn: () => Promise<void>): Promise<void> {
  if (busy) return;
  busy = true;
  const btns = [...form.querySelectorAll<HTMLButtonElement>('button')];
  btns.forEach((b) => (b.disabled = true));
  try { await fn(); } finally { busy = false; btns.forEach((b) => { if (b.isConnected) b.disabled = false; }); }
}

function h(html: string): void { app.innerHTML = html; }
function $(id: string): HTMLInputElement { return document.getElementById(id) as HTMLInputElement; }
function say(text: string, kind: 'error' | 'ok' = 'error'): void {
  const m = document.getElementById('msg');
  if (m) { m.textContent = text; m.className = `msg ${kind}`; }
}
function esc(s: string): string { return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!); }
function title(sub: string): string { return `${siteHeader()}<div class="page-intro"><h1>${sub}</h1></div>`; }

function authError(message: string): string {
  const m = message.toLowerCase();
  if (m.includes('invalid login')) return 'E-mail ou mot de passe incorrect.';
  if (m.includes('already registered')) return 'Ce compte existe déjà. Appuie sur « Me connecter ».';
  if (m.includes('password')) return 'Mot de passe trop court (6 caractères au moins).';
  if (m.includes('email')) return 'Adresse e-mail non valide.';
  return 'Connexion impossible. Réessaie dans un instant.';
}

function showSignIn(): void {
  h(`${title('Configuration de la télé')}
  <form class="card" id="f" autocomplete="on">
    <h2>Connexion</h2>
    <label for="email">E-mail</label>
    <input id="email" type="email" autocomplete="username" inputmode="email" required>
    <label for="pw">Mot de passe</label>
    <input id="pw" type="password" autocomplete="current-password" required>
    <button type="submit" id="in">Me connecter</button>
    <button type="button" id="up" class="secondary">Créer le compte</button>
    <div id="msg" class="msg" role="status"></div>
  </form>`);
  const run = async (signUp: boolean) => {
    const email = $('email').value.trim(), password = $('pw').value;
    if (!email || !password) return say('Saisis ton e-mail et ton mot de passe.');
    say('…', 'ok');
    const { data, error } = signUp
      ? await supabase!.auth.signUp({ email, password, options: { emailRedirectTo: window.location.origin + window.location.pathname } })
      : await supabase!.auth.signInWithPassword({ email, password });
    if (error) return say(authError(error.message));
    if (signUp && !data.session) return say('Ouvre le lien reçu par e-mail, puis reviens ici.', 'ok');
    $('pw').value = '';
    await route();
  };
  const f = document.getElementById('f')!;
  f.addEventListener('submit', (e) => { e.preventDefault(); void guarded(f, () => run(false)); });
  document.getElementById('up')!.addEventListener('click', () => void guarded(f, () => run(true)));
}

function profileRow(value: string): string {
  return `<div class="row prof"><input class="pn" value="${esc(value)}" maxlength="30" aria-label="Nom du profil"></div>`;
}

function showSetup(): void {
  h(`${title('Première configuration')}
  <form class="card" id="f" autocomplete="off">
    <h2>Ton foyer</h2>
    <label for="hn">Nom du foyer</label>
    <input id="hn" maxlength="40" value="Maison">
    <label>Profils</label>
    <div id="profs">${['Toi', 'Sœur', 'Frère'].map(profileRow).join('')}</div>
    <button type="button" id="add" class="secondary">Ajouter un profil</button>
    <h2 style="margin-top:20px">Ton abonnement IPTV</h2>
    <label for="sv">Adresse du serveur</label>
    <input id="sv" type="url" inputmode="url" autocapitalize="off" autocorrect="off" placeholder="http://exemple.tv:8080">
    <label for="un">Nom d'utilisateur</label>
    <input id="un" autocapitalize="off" autocorrect="off" autocomplete="off">
    <label for="pwd">Mot de passe</label>
    <input id="pwd" type="password" autocomplete="new-password">
    <button type="submit">Créer le foyer</button>
    <div id="msg" class="msg" role="status"></div>
  </form>`);
  document.getElementById('add')!.addEventListener('click', () => {
    const list = document.getElementById('profs')!;
    if (list.children.length >= MAX_PROFILES) return say('Dix profils au plus.');
    list.insertAdjacentHTML('beforeend', profileRow(''));
  });
  const sf = document.getElementById('f')!;
  sf.addEventListener('submit', (e) => { e.preventDefault(); void guarded(sf, async () => {
    const profiles = [...document.querySelectorAll<HTMLInputElement>('.pn')].map((i) => i.value);
    const creds = { server: $('sv').value.trim(), username: $('un').value.trim(), password: $('pwd').value };
    say('…', 'ok');
    try {
      await setupHousehold(db, { name: $('hn').value, profiles, ...creds });
    } catch (err) {
      const msg = (err as Error).message;
      // Si le foyer a été créé avant l'erreur, on passe à l'écran principal.
      const st = await loadState(db).catch(() => null);
      if (st?.householdId) { showMain(st); say(msg); return; }
      return say(msg);
    }
    $('sv').value = ''; $('un').value = ''; $('pwd').value = '';
    await route();
  }); });
}

let selectedProfile: string | null = null;
let organiserScope: string | null | undefined;
function frame(state: State, tab: SiteTab, body: string): void {
  unmountOrganiser();
  h(`${siteHeader()}${demo ? '<p class="demo">Démo · données fictives, aucun changement sur ta télé.</p>' : ''}${body}${siteNav(tab)}`);
  app.querySelectorAll<HTMLElement>('[data-nav]').forEach(b => b.addEventListener('click', e => {
    e.preventDefault();
    if (b.dataset.nav === 'profiles' && b.closest('.site-header')) selectedProfile = null;
    showMain(state, b.dataset.nav as SiteTab);
    window.scrollTo({top:0});
  }));
}
function showOrganiser(state: State): void {
  frame(state, 'org', '<div class="page-intro"><h1>Organiser</h1><p>Les contenus à ta façon.</p></div><div id="org"></div>');
  void mountOrganiser(document.getElementById('org')!, db, state.householdId!, {
    initialScope: organiserScope === undefined ? selectedProfile ?? undefined : organiserScope,
    onScopeChange: id => { organiserScope = id; if (id) selectedProfile = id; },
  });
}
function showProfiles(state: State): void {
  frame(state, 'profiles', '<div id="profile-page"></div>');
  void mountProfileView(document.getElementById('profile-page')!, {
    db: db as unknown as ProfilesDb, base: demo ? '' : SUPABASE_URL, selectedId: selectedProfile, householdId: state.householdId ?? undefined,
    onSelect: id => { selectedProfile = id; organiserScope = id; },
    onOrganise: id => { selectedProfile = id; organiserScope = id; showOrganiser(state); },
    onPhoto: (p, editor, refresh) => {
      if (demo) {
        editor.hidden = false;
        editor.innerHTML = '<div class="ped"><h3>Photo du profil</h3><p>Le choix et le recadrage de la photo sont disponibles une fois connecté. Aucune photo n’est envoyée en démo.</p><button class="small secondary" type="button">Fermer</button></div>';
        editor.querySelector('button')!.onclick = () => { editor.hidden = true; };
        return;
      }
      openAvatarEditor(editor, SUPABASE_URL, p, supabase!.storage.from('web') as unknown as AvatarStorage, db as unknown as SettingsDb,
        settings => { p.settings = settings; refresh(); const msg = document.getElementById('profile-msg'); if (msg) msg.textContent = 'Photo enregistrée. Sur la télé, change de profil ou redémarre l’application.'; });
    },
    onWallpaper: (p, editor) => {
      if (demo) {
        editor.hidden = false;
        editor.innerHTML = '<div class="ped"><h3>Fond d’écran</h3><p>Le fond d’écran est disponible une fois connecté. Aucune image n’est envoyée en démo.</p><button class="small secondary" type="button">Fermer</button></div>';
        editor.querySelector('button')!.onclick = () => { editor.hidden = true; };
        return;
      }
      openWallpaperEditor(editor, SUPABASE_URL, p, supabase!.storage.from('web') as unknown as AvatarStorage, db as unknown as SettingsDb,
        settings => { p.settings = settings; const msg = document.getElementById('profile-msg'); if (msg) msg.textContent = settings.wallpaper === false ? 'Fond d’écran retiré.' : 'Fond d’écran enregistré. Sur la télé, change de profil ou redémarre l’application.'; });
    },
    onSticker: (p, editor) => {
      if (demo) {
        editor.hidden = false;
        editor.innerHTML = '<div class="ped"><h3>Autocollant</h3><p>Le choix de l’autocollant est disponible une fois connecté. Aucune image n’est envoyée en démo.</p><button class="small secondary" type="button">Fermer</button></div>';
        editor.querySelector('button')!.onclick = () => { editor.hidden = true; };
        return;
      }
      openStickerEditor(editor, SUPABASE_URL, p, supabase!.storage.from('web') as unknown as AvatarStorage, db as unknown as SettingsDb,
        settings => { p.settings = settings; const msg = document.getElementById('profile-msg'); if (msg) msg.textContent = settings.sticker === false ? 'Autocollant retiré.' : 'Autocollant enregistré. Sur la télé, change de profil ou redémarre l’application.'; });
    },
    onTeams: (p, editor) => { void openTeamsEditor(editor, p, db as unknown as SettingsDb, () => {
      const msg = document.getElementById('profile-msg');
      if (msg) msg.textContent = demo ? 'Équipes enregistrées dans cette démo uniquement.' : 'Équipes enregistrées. Sur la télé, redémarre l’application.';
    }); },
  });
}
function showMain(state: State, tab: SiteTab = pendingCode ? 'settings' : 'profiles'): void {
  if (tab === 'org') return showOrganiser(state);
  if (tab === 'profiles') return showProfiles(state);
  const householdId = state.householdId!;
  const devices = state.devices.filter((d) => d.role === 'device');
  frame(state, 'settings', `<div class="page-intro"><h1>Réglages</h1><p>La télé et le compte de la maison.</p></div>
  <form class="card" id="f" autocomplete="off">
    <h2>Associer une télé</h2>
    <p>${pendingCode ? 'Vérifie le code puis valide.' : 'Tape le code affiché sur la télé (ou scanne à nouveau le QR code).'}</p>
    <input id="code" class="code" maxlength="8" autocapitalize="characters" autocorrect="off" value="${esc(pendingCode)}" aria-label="Code de la télé">
    <button type="submit">Valider la télé</button>
    <div id="msg" class="msg" role="status"></div>
  </form>
  <section class="card">
    <h2>Mes appareils</h2>
    <div id="devices"></div><div class="tools" id="devconfirm" hidden></div>
  </section>
  <section class="card">
    <h2>Mes identifiants IPTV</h2>
    <p>${state.hasCredentials ? 'Enregistrés. Pour les changer :' : 'Pas encore enregistrés.'}</p>
    <button class="secondary" id="chg">Modifier mes identifiants IPTV</button>
    <form id="cf" hidden autocomplete="off">
      <label for="sv">Adresse du serveur</label>
      <input id="sv" type="url" inputmode="url" autocapitalize="off" autocorrect="off">
      <label for="un">Nom d'utilisateur</label>
      <input id="un" autocapitalize="off" autocorrect="off" autocomplete="off">
      <label for="pwd">Mot de passe</label>
      <input id="pwd" type="password" autocomplete="new-password">
      <button type="submit">Enregistrer</button>
      <div id="cmsg" class="msg" role="status"></div>
    </form>
  </section>
  <button class="link logout" id="out">${icon('exit')}Me déconnecter</button>`);
  if (demo) {
    app.querySelectorAll<HTMLInputElement | HTMLButtonElement>('#f input, #f button, #chg, #out, [data-id]').forEach(el => { el.disabled = true; });
    document.getElementById('msg')!.textContent = 'Association et accès au compte disponibles après connexion.';
  }
  const af = document.getElementById('f')!;
  const codeInput = af.querySelector<HTMLInputElement>('#code')!;
  const pairingMsg = af.querySelector<HTMLElement>('#msg')!;
  af.addEventListener('submit', (e) => { e.preventDefault(); void guarded(af, async () => {
    pairingMsg.textContent = 'Validation…';
    const r = await approveCode(db, householdId, codeInput.value);
    if (!r.ok) { pairingMsg.textContent = r.message; pairingMsg.className = 'msg error'; return; }
    pendingCode = '';
    clearPendingCode(store);
    codeInput.value = '';
    pairingMsg.textContent = `${r.deviceName} est validée. Elle va s'ouvrir toute seule.`;
    pairingMsg.className = 'msg ok';
    const fresh = await loadState(db).catch(() => null);
    if (fresh) { state.devices = fresh.devices; state.hasCredentials = fresh.hasCredentials; }
  }); });
  // Retrait d'un appareil : confirmation intégrée à la page (pas de confirm()
  // native, rejetée d'office dans certains contextes).
  const devZone = document.getElementById('devconfirm');
  let devPending: string | null = null;
  const closeDevConfirm = (): void => {
    devPending = null;
    if (devZone) { devZone.hidden = true; devZone.innerHTML = ''; }
  };
  mountDevices(document.getElementById('devices')!, {db, householdId, devices, onRemove: device => {
    if (demo) return;
    devPending = device.userId;
    devZone!.innerHTML = `<span class="ctext">${esc(`Retirer l’appareil « ${device.name} » ?`)}</span>
      <button type="button" class="small" id="devyes">Confirmer</button><button type="button" class="small secondary" id="devno">Annuler</button>`;
    devZone!.hidden = false;
    document.getElementById('devno')!.onclick = closeDevConfirm;
    const yes = document.getElementById('devyes') as HTMLButtonElement;
    yes.onclick = async () => {
      const id = devPending; if (!id) return; yes.disabled = true;
      try { await revokeDevice(db, householdId, id); state.devices = state.devices.filter(d => d.userId !== id); if (devZone!.isConnected) showMain(state,'settings'); }
      catch { if(devZone!.isConnected) { devZone!.hidden=false; devZone!.textContent='Impossible de retirer cet appareil. Réessaie.'; } }
    };
  }});
  document.getElementById('chg')!.addEventListener('click', () => { document.getElementById('cf')!.toggleAttribute('hidden'); });
  const cf = document.getElementById('cf')!;
  const serverInput = cf.querySelector<HTMLInputElement>('#sv')!;
  const userInput = cf.querySelector<HTMLInputElement>('#un')!;
  const passwordInput = cf.querySelector<HTMLInputElement>('#pwd')!;
  const credentialMsg = cf.querySelector<HTMLElement>('#cmsg')!;
  cf.addEventListener('submit', (e) => { e.preventDefault(); void guarded(cf, async () => {
    const cm = credentialMsg;
    cm.textContent = '…'; cm.className = 'msg ok';
    try {
      await updateCredentials(db, householdId, { server: serverInput.value.trim(), username: userInput.value.trim(), password: passwordInput.value });
    } catch (err) { cm.textContent = (err as Error).message; cm.className = 'msg error'; return; }
    serverInput.value = ''; userInput.value = ''; passwordInput.value = '';
    state.hasCredentials = true;
    cm.textContent = 'Identifiants enregistrés.'; cm.className = 'msg ok';
  }); });
  document.getElementById('out')!.addEventListener('click', async () => { await supabase!.auth.signOut(); await route(); });
}

async function route(): Promise<void> {
  if (demo) {
    if (!demoHousehold) { const m = await import('./org/demo'); const d = m.demoDb(); db = d.db; demoHousehold = d.householdId; }
    return showMain({ householdId: demoHousehold, hasCredentials: true, devices: [{userId:'demo-tv',name:'Télé du salon',role:'device'}] });
  }
  const { data } = await supabase!.auth.getSession();
  if (!data.session) return showSignIn();
  try {
    const state = await loadState(db);
    if (!state.householdId) showSetup(); else showMain(state);
  } catch {
    h(`${title('Configuration de la télé')}<p class="msg error">Impossible de joindre le serveur. Vérifie ta connexion puis recharge la page.</p>`);
  }
}

void route();
