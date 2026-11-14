import { createClient } from '@supabase/supabase-js';
import './ui.css';
import { mountOrganiser } from './org/view';
import {
  approveCode, loadState, normalizePairingCode, revokeDevice, setupHousehold, updateCredentials,
  MAX_PROFILES, savePendingCode, loadPendingCode, clearPendingCode, type Db, type State, type KV,
} from './flow';

// Adresse et clé publiques du projet (publiques par conception ; la RLS protège les données).
const SUPABASE_URL = 'https://gisjeuukbrgjgkrmlyvs.supabase.co';
const SUPABASE_KEY = 'sb_publishable_Rfqg2UOqhABAF4ugGAfzHQ_84UIpABt';

const supabase = createClient(SUPABASE_URL, SUPABASE_KEY);
// ?demo : données inventées en mémoire, sans réseau ni compte (bandeau « Démo » affiché).
const demo = new URLSearchParams(location.search).has('demo');
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
function title(sub: string): string { return `<h1>IPTV Home</h1><p>${sub}</p>`; }

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
      ? await supabase.auth.signUp({ email, password, options: { emailRedirectTo: window.location.origin + window.location.pathname } })
      : await supabase.auth.signInWithPassword({ email, password });
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

type Tab = 'config' | 'org';
function tabBar(tab: Tab): string {
  const t = (id: Tab, label: string) => `<button type="button" role="tab" class="tab" data-tab="${id}" aria-selected="${tab === id}">${label}</button>`;
  return `${demo ? '<div class="demo">Démo : données inventées, rien n\'est enregistré.</div>' : ''}
  <div class="tabs" role="tablist">${t('config', 'Configuration')}${t('org', 'Organiser')}</div>`;
}
function bindTabs(state: State): void {
  app.querySelectorAll<HTMLButtonElement>('button[data-tab]').forEach((b) => b.addEventListener('click', () => showMain(state, b.dataset.tab as Tab)));
}

function showOrganiser(state: State): void {
  h(`${title('Organiser les chaînes')}${tabBar('org')}<div id="org"></div>`);
  bindTabs(state);
  void mountOrganiser(document.getElementById('org')!, db, state.householdId!);
}

function showMain(state: State, tab: Tab = 'config'): void {
  if (tab === 'org') return showOrganiser(state);
  const householdId = state.householdId!;
  const devices = state.devices.filter((d) => d.role === 'device');
  h(`${title('Configuration de la télé')}${tabBar('config')}
  <form class="card" id="f" autocomplete="off">
    <h2>Valider la télé</h2>
    <p>${pendingCode ? 'Vérifie le code puis valide.' : 'Tape le code affiché sur la télé (ou scanne à nouveau le QR code).'}</p>
    <input id="code" class="code" maxlength="8" autocapitalize="characters" autocorrect="off" value="${esc(pendingCode)}" aria-label="Code de la télé">
    <button type="submit">Valider la télé</button>
    <div id="msg" class="msg" role="status"></div>
  </form>
  <section class="card">
    <h2>Mes appareils</h2>
    ${devices.length ? `<ul>${devices.map((d) => `<li><span>${esc(d.name)}</span><button class="small secondary" data-id="${esc(d.userId)}">Retirer</button></li>`).join('')}</ul>` : '<p>Aucun appareil pour le moment.</p>'}
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
  <button class="link" id="out">Me déconnecter</button>`);
  bindTabs(state);
  const af = document.getElementById('f')!;
  af.addEventListener('submit', (e) => { e.preventDefault(); void guarded(af, async () => {
    say('…', 'ok');
    const r = await approveCode(db, householdId, $('code').value);
    if (!r.ok) return say(r.message);
    pendingCode = '';
    clearPendingCode(store);
    $('code').value = '';
    say(`${r.deviceName} est validée. Elle va s'ouvrir toute seule.`, 'ok');
    const fresh = await loadState(db).catch(() => null);
    if (fresh) setTimeout(() => showMain(fresh), 2500);
  }); });
  app.querySelectorAll<HTMLButtonElement>('button[data-id]').forEach((b) => b.addEventListener('click', async () => {
    if (!confirm('Retirer cet appareil ?')) return;
    try { await revokeDevice(db, householdId, b.dataset.id!); await route(); }
    catch (err) { say((err as Error).message); }
  }));
  document.getElementById('chg')!.addEventListener('click', () => { document.getElementById('cf')!.toggleAttribute('hidden'); });
  const cf = document.getElementById('cf')!;
  cf.addEventListener('submit', (e) => { e.preventDefault(); void guarded(cf, async () => {
    const cm = document.getElementById('cmsg')!;
    cm.textContent = '…'; cm.className = 'msg ok';
    try {
      await updateCredentials(db, householdId, { server: $('sv').value.trim(), username: $('un').value.trim(), password: $('pwd').value });
    } catch (err) { cm.textContent = (err as Error).message; cm.className = 'msg error'; return; }
    $('sv').value = ''; $('un').value = ''; $('pwd').value = '';
    cm.textContent = 'Identifiants enregistrés.'; cm.className = 'msg ok';
  }); });
  document.getElementById('out')!.addEventListener('click', async () => { await supabase.auth.signOut(); await route(); });
}

async function route(): Promise<void> {
  if (demo) {
    if (!demoHousehold) { const m = await import('./org/demo'); const d = m.demoDb(); db = d.db; demoHousehold = d.householdId; }
    return showMain({ householdId: demoHousehold, hasCredentials: true, devices: [] }, 'org');
  }
  const { data } = await supabase.auth.getSession();
  if (!data.session) return showSignIn();
  try {
    const state = await loadState(db);
    if (!state.householdId) showSetup(); else showMain(state);
  } catch {
    h(`${title('Configuration de la télé')}<p class="msg error">Impossible de joindre le serveur. Vérifie ta connexion puis recharge la page.</p>`);
  }
}

void route();
