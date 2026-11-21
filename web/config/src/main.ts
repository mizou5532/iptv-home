import { createClient } from '@supabase/supabase-js';
import './ui.css';
import { mountOrganiser, unmountOrganiser } from './org/view';
import { mountWatch, unmountWatch } from './watch-ui';
import type { HomeServerDb } from './watch';
import { openAvatarEditor } from './avatar-ui';
import { openStickerEditor } from './sticker-ui';
import { openWallpaperEditor } from './studio-ui';
import { openTeamsEditor } from './teams-ui';
import type { AvatarStorage, SettingsDb } from './avatar';
import { loadProfileHomes, type ProfileHome, type ProfilesDb } from './profiles';
import { avatarBadge } from './avatar-ui';
import { mountProfileView } from './profile-view';
import { siteHeader, siteNav, type SiteTab } from './shell';
import { icon } from './icons';
import { mountDevices } from './device-view';
import { accountSummary, loadIptvAccount, usageText, type AccountDb, type IptvAccountRow } from './account';
import { reunite, splitNames, splitsOf, type Split } from './splits';
import { accessState, connectorUrl, createAccess, loadAccesses, loadAiLog, logLabel, revokeAccess, type AiAccess, type AiDb, type AiLog } from './ai-access';
import { backupContent, backupFileName, backupNow, backupTitle, backupWhen, loadBackupData, loadBackups, PARTS, restoreBackup, restoreFile, type BackupDb, type BackupRow, type Part } from './backups';
import { addExtraAccount, extraSummary, loadExtraAccounts, MAX_EXTRA, removeExtraAccount, type ExtraAccountRow, type ExtraDb } from './extra-accounts';
import { loadHouseholdSettings, type HouseholdDb } from './look';
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
  unmountWatch();
  h(`${siteHeader()}${demo ? '<p class="demo">Démo · données fictives, aucun changement sur ta télé.</p>' : ''}${body}${siteNav(tab)}`);
  app.querySelectorAll<HTMLElement>('[data-nav]').forEach(b => b.addEventListener('click', e => {
    e.preventDefault();
    if (b.dataset.nav === 'profiles' && b.closest('.site-header')) selectedProfile = null;
    showMain(state, b.dataset.nav as SiteTab);
    window.scrollTo({top:0});
  }));
  void fillSide(state, tab);
}
/** Ordinateur : les profils sous la navigation de gauche (masqués sur téléphone). Dernière liste montrée aussitôt. */
let sideList: ProfileHome[] | null = null;
function paintSide(state: State, tab: SiteTab): void {
  const box = app.querySelector<HTMLElement>('#side-profiles');
  if (!box || !sideList) return;
  const base = demo ? '' : SUPABASE_URL;
  box.innerHTML = `<span class="side-title">Profils</span>${sideList.map(p => `<button type="button" class="side-profile" data-side-profile="${esc(p.id)}" ${tab === 'profiles' && selectedProfile === p.id ? 'aria-current="true"' : ''}>${avatarBadge(base, p, 28)}<span>${esc(p.name)}</span></button>`).join('')}`;
  box.querySelectorAll<HTMLButtonElement>('[data-side-profile]').forEach(b => b.addEventListener('click', () => {
    selectedProfile = b.dataset.sideProfile!; organiserScope = selectedProfile;
    showProfiles(state); window.scrollTo({top:0});
  }));
}
async function fillSide(state: State, tab: SiteTab): Promise<void> {
  paintSide(state, tab);
  try { sideList = await loadProfileHomes(db as unknown as ProfilesDb); } catch { return; }
  paintSide(state, tab);
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
    onSelect: id => { selectedProfile = id; organiserScope = id; paintSide(state, 'profiles'); },
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
function showWatch(state: State): void {
  frame(state, 'watch', '<div id="watch-page"></div>');
  void mountWatch(document.getElementById('watch-page')!, {
    db: db as unknown as HomeServerDb & ProfilesDb,
    base: demo ? '' : SUPABASE_URL,
    householdId: state.householdId!,
    selectedProfileId: selectedProfile,
    onSelectProfile: (id) => { selectedProfile = id; organiserScope = id; paintSide(state, 'watch'); },
    demo,
  });
}
function showMain(state: State, tab: SiteTab = pendingCode ? 'settings' : 'profiles'): void {
  if (tab === 'watch') return showWatch(state);
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
    <div id="iptv-account" class="account" role="status"></div>
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
  <section class="card" id="extra-card">
    <h2>Comptes supplémentaires</h2>
    <p>Un abonnement IPTV = une seule vidéo à la fois. Ajoute d’autres comptes du même fournisseur pour regarder sur plusieurs télés en même temps : la première télé prend le compte principal, la suivante le compte 2, etc.</p>
    <div id="extras"></div>
    <button class="secondary" id="xadd">Ajouter un compte</button>
    <form id="xf" hidden autocomplete="off">
      <label for="xun">Nom d'utilisateur</label>
      <input id="xun" autocapitalize="off" autocorrect="off" autocomplete="off">
      <label for="xpwd">Mot de passe</label>
      <input id="xpwd" type="password" autocomplete="new-password">
      <p class="hint">Même serveur que le compte principal. La télé vérifie ensuite que le compte fonctionne et donne les mêmes chaînes, films et séries, puis le revérifie chaque jour.</p>
      <button type="submit">Ajouter</button>
    </form>
    <div id="xmsg" class="msg" role="status"></div>
  </section>
  <section class="card" id="backup-card">
    <h2>Sauvegardes</h2>
    <p>Une copie de tout ce qui compte (progression, Ma liste, favoris, playlists, masquages, profils) chaque nuit et avant chaque changement important. Jamais tes identifiants IPTV.</p>
    <div class="tools"><button type="button" class="secondary" id="bknow">Sauvegarder maintenant</button>
      <label class="secondary filebtn">Restaurer un fichier<input type="file" id="bkfile" accept="application/json,.json" hidden></label></div>
    <div id="bkmsg" class="msg" role="status"></div>
    <div id="bkfilebox"></div>
    <div id="bklist"></div>
  </section>
  <section class="card" id="ai-card">
    <h2>Accès pour les IA</h2>
    <p>Un « connecteur » pour qu’une IA (Claude) organise la télé avec des ordres simples : « masque les catégories arabes pour Maha », « crée une playlist Hockey ». Jamais tes identifiants IPTV. Une sauvegarde est faite avant ses changements, et elle peut tout annuler.</p>
    <div class="tools"><button type="button" class="secondary" id="ainew">Créer un accès</button></div>
    <div id="aibox"></div>
    <div id="aimsg" class="msg" role="status"></div>
    <div id="ailist"></div>
    <div id="ailog"></div>
  </section>
  <section class="card" id="splits-card" hidden>
    <h2>Versions séparées</h2>
    <p>Titres séparés sur la télé avec « Ce n'est pas le même titre ». « Réunir » les remet avec leurs autres langues.</p>
    <div id="splits"></div><div id="smsg" class="msg" role="status"></div>
  </section>
  <button class="link logout" id="out">${icon('exit')}Me déconnecter</button>`);
  if (!demo) void (async () => {
    const card = document.getElementById('splits-card');
    const list = document.getElementById('splits');
    if (!card || !list) return;
    let splits: Split[] = [];
    try { splits = splitsOf(await loadHouseholdSettings(db as unknown as HouseholdDb, householdId)); } catch { return; }
    if (!splits.length || !list.isConnected) return;
    const firstProfile = sideList?.[0]?.id ?? (await loadProfileHomes(db as unknown as ProfilesDb).catch(() => []))[0]?.id ?? null;
    const names = await splitNames(db as unknown as Db, firstProfile, splits);
    if (!list.isConnected) return;
    card.hidden = false;
    list.innerHTML = splits.map((sp) => `<div class="setting-row"><span><strong>${esc(names.get(`${sp.kind}:${sp.id}`) ?? '')}</strong><small>${sp.kind === 'movie' ? 'Film' : 'Série'}</small></span>
      <button type="button" class="small secondary" data-split="${sp.kind}:${sp.id}">Réunir</button></div>`).join('');
    list.querySelectorAll<HTMLButtonElement>('[data-split]').forEach((b) => b.addEventListener('click', () => {
      const [kind, id] = b.dataset.split!.split(':') as [Split['kind'], string];
      const msg = document.getElementById('smsg')!;
      b.disabled = true;
      reunite(db as unknown as Db, householdId, { kind, id }).then(() => {
        b.closest('.setting-row')?.remove();
        msg.textContent = 'Réuni. La télé le regroupera à la prochaine ouverture de « Qui regarde ? ».'; msg.className = 'msg ok';
        if (!list.querySelector('[data-split]')) card.hidden = true;
      }, (e: Error) => { b.disabled = false; msg.textContent = e.message; msg.className = 'msg error'; });
    }));
  })();
  void (async () => {
    const list = document.getElementById('ailist'), box = document.getElementById('aibox'), msg = document.getElementById('aimsg'), log = document.getElementById('ailog');
    if (!list || !box || !msg || !log) return;
    const say = (text: string, ok: boolean) => { msg.textContent = text; msg.className = `msg ${ok ? 'ok' : 'error'}`; };
    const draw = async () => {
      let rows: AiAccess[] = []; let logs: AiLog[] = [];
      if (demo) rows = [{ id: 'demo', label: 'Claude', created_at: new Date().toISOString(), last_used_at: new Date().toISOString(), revoked_at: null }];
      else { try { rows = await loadAccesses(db as unknown as AiDb); logs = await loadAiLog(db as unknown as AiDb); } catch (e) { list.textContent = (e as Error).message; return; } }
      if (!list.isConnected) return;
      list.innerHTML = rows.map((a) => `<div class="setting-row"><span><strong>${esc(a.label)}</strong><small>${esc(accessState(a))}</small></span>
        ${a.revoked_at ? '' : `<button type="button" class="small secondary" data-cut="${esc(a.id)}">Couper</button>`}</div>`).join('');
      log.innerHTML = logs.length ? `<h3>Derniers changements par une IA</h3>${logs.map((l) => `<div class="ailog"><small>${esc(logLabel(l))}</small></div>`).join('')}` : '';
      list.querySelectorAll<HTMLButtonElement>('[data-cut]').forEach((b) => b.addEventListener('click', async () => {
        if (demo) return; b.disabled = true;
        try { await revokeAccess(db as unknown as Db, householdId, b.dataset.cut!); say('Accès coupé : l’IA ne peut plus rien faire avec cette adresse.', true); await draw(); }
        catch (e) { b.disabled = false; say((e as Error).message, false); }
      }));
    };
    document.getElementById('ainew')!.addEventListener('click', () => {
      box.innerHTML = `<label for="ailabel">Nom de l’accès</label><input id="ailabel" value="Claude" maxlength="40"><button type="button" id="aigo">Créer</button>`;
      document.getElementById('aigo')!.addEventListener('click', async (ev) => {
        const b = ev.currentTarget as HTMLButtonElement;
        if (demo) { say('Démo : rien n’est créé.', true); return; }
        b.disabled = true;
        try {
          const key = await createAccess(db as unknown as Db, householdId, (document.getElementById('ailabel') as HTMLInputElement).value);
          const url = connectorUrl(SUPABASE_URL, key);
          box.innerHTML = `<p class="hint"><strong>Adresse du connecteur (montrée une seule fois, garde-la secrète) :</strong></p>
            <textarea id="aiurl" readonly rows="3">${esc(url)}</textarea>
            <button type="button" class="small" id="aicopy">Copier l’adresse</button>
            <p class="hint">Dans Claude : Réglages → Connecteurs → Ajouter un connecteur personnalisé → colle cette adresse. Ensuite, demande simplement ce que tu veux changer.</p>`;
          document.getElementById('aicopy')!.addEventListener('click', async () => {
            try { await navigator.clipboard.writeText(url); say('Adresse copiée.', true); } catch { (document.getElementById('aiurl') as HTMLTextAreaElement).select(); }
          });
          await draw();
        } catch (e) { b.disabled = false; say((e as Error).message, false); }
      });
    });
    await draw();
  })();
  void (async () => {
    const list = document.getElementById('bklist');
    const msg = document.getElementById('bkmsg');
    if (!list || !msg) return;
    const say = (text: string, ok: boolean) => { msg.textContent = text; msg.className = `msg ${ok ? 'ok' : 'error'}`; };
    const partsForm = (id: string) => `<div class="bkparts" data-for="${id}">${PARTS.map((p) => `<label class="check"><input type="checkbox" value="${p.id}" ${p.id === 'progression' ? 'checked' : ''}> <span><strong>${esc(p.label)}</strong><small>${esc(p.detail)}</small></span></label>`).join('')}
      <button type="button" class="small" data-go="${id}">Restaurer ces parties</button> <button type="button" class="small secondary" data-cancel="${id}">Annuler</button></div>`;
    const chosen = (box: Element) => [...box.querySelectorAll<HTMLInputElement>('input[type=checkbox]:checked')].map((i) => i.value as Part);
    const draw = async () => {
      let rows: BackupRow[] = [];
      if (demo) rows = [{ id: 'demo', created_at: new Date().toISOString(), reason: 'nuit', summary: { progression: 412, playlists: 3, masquages: 1404, profils: 3 } }];
      else { try { rows = await loadBackups(db as unknown as BackupDb, householdId); } catch (e) { list.textContent = (e as Error).message; return; } }
      if (!list.isConnected) return;
      list.innerHTML = rows.length ? rows.map((b) => `<div class="bkrow"><div><strong>${esc(backupTitle(b))}</strong><small>${esc(backupWhen(b))} · ${esc(backupContent(b))}</small></div>
        <div class="tools"><button type="button" class="small secondary" data-dl="${b.id}">Télécharger</button><button type="button" class="small secondary" data-restore="${b.id}">Restaurer…</button></div>
        <div data-slot="${b.id}"></div></div>`).join('') : '<p class="hint">Aucune sauvegarde pour l’instant : la première se fera cette nuit, ou maintenant avec le bouton.</p>';
      list.querySelectorAll<HTMLButtonElement>('[data-dl]').forEach((btn) => btn.addEventListener('click', async () => {
        if (demo) { say('Démo : rien à télécharger.', true); return; }
        const b = rows.find((x) => x.id === btn.dataset.dl)!;
        try {
          const data = await loadBackupData(db as unknown as BackupDb, householdId, b.id);
          const url = URL.createObjectURL(new Blob([JSON.stringify(data)], { type: 'application/json' }));
          const a = document.createElement('a'); a.href = url; a.download = backupFileName(b); document.body.appendChild(a); a.click(); a.remove();
          setTimeout(() => URL.revokeObjectURL(url), 30_000);
          say('Fichier prêt : garde-le dans Fichiers ou iCloud.', true);
        } catch (e) { say((e as Error).message, false); }
      }));
      list.querySelectorAll<HTMLButtonElement>('[data-restore]').forEach((btn) => btn.addEventListener('click', () => {
        const id = btn.dataset.restore!; const slot = list.querySelector(`[data-slot="${id}"]`)!;
        slot.innerHTML = partsForm(id);
        slot.querySelector('[data-cancel]')!.addEventListener('click', () => { slot.innerHTML = ''; });
        slot.querySelector<HTMLButtonElement>('[data-go]')!.addEventListener('click', async (ev) => {
          const go = ev.currentTarget as HTMLButtonElement;
          if (demo) { say('Démo : rien n’est restauré.', true); return; }
          go.disabled = true; say('…', true);
          try { await restoreBackup(db as unknown as Db, householdId, id, chosen(slot)); say('Restauré. Une sauvegarde « avant restauration » a été faite : tu peux revenir en arrière.', true); slot.innerHTML = ''; await draw(); }
          catch (e) { go.disabled = false; say((e as Error).message, false); }
        });
      }));
    };
    document.getElementById('bknow')!.addEventListener('click', async (ev) => {
      const b = ev.currentTarget as HTMLButtonElement;
      if (demo) { say('Démo : rien n’est enregistré.', true); return; }
      b.disabled = true;
      try { await backupNow(db as unknown as Db, householdId); say('Sauvegarde faite.', true); await draw(); } catch (e) { say((e as Error).message, false); } finally { b.disabled = false; }
    });
    const file = document.getElementById('bkfile') as HTMLInputElement;
    file.addEventListener('change', async () => {
      const f = file.files?.[0]; if (!f) return;
      const text = await f.text(); file.value = '';
      const box = document.getElementById('bkfilebox')!;
      box.innerHTML = `<p class="hint">Fichier « ${esc(f.name)} » : que veux-tu restaurer ?</p>` + partsForm('fichier');
      box.querySelector('[data-cancel]')!.addEventListener('click', () => { box.innerHTML = ''; });
      box.querySelector<HTMLButtonElement>('[data-go]')!.addEventListener('click', async (ev) => {
        const go = ev.currentTarget as HTMLButtonElement;
        if (demo) { say('Démo : rien n’est restauré.', true); return; }
        go.disabled = true; say('…', true);
        try { await restoreFile(db as unknown as Db, householdId, text, chosen(box)); say('Fichier restauré. Une sauvegarde « avant restauration » a été faite.', true); box.innerHTML = ''; await draw(); }
        catch (e) { go.disabled = false; say((e as Error).message, false); }
      });
    });
    await draw();
  })();
  void (async () => {
    const list = document.getElementById('extras');
    const form = document.getElementById('xf') as HTMLFormElement | null;
    const msg = document.getElementById('xmsg');
    if (!list || !form || !msg) return;
    const say = (text: string, ok: boolean) => { msg.textContent = text; msg.className = `msg ${ok ? 'ok' : 'error'}`; };
    const draw = async () => {
      let rows: ExtraAccountRow[] = [];
      if (demo) { const end = new Date(); end.setDate(end.getDate() + 20); rows = [{ id: 'demo', position: 2, check_status: 'ok', check_reason: null, expires_at: end.toISOString(), checked_at: new Date().toISOString(), active_connections: 0, status_at: new Date().toISOString() }]; }
      else { try { rows = await loadExtraAccounts(db as unknown as ExtraDb); } catch { list.textContent = 'Impossible de lire les comptes supplémentaires.'; return; } }
      if (!list.isConnected) return;
      list.innerHTML = rows.length ? rows.map((a) => { const s = extraSummary(a); const use = usageText(a.active_connections, a.status_at, 1);
        const extra = [use, a.provider_message ? `Message du fournisseur : ${a.provider_message}` : null].filter(Boolean).map((t) => `<small>${esc(t!)}</small>`).join('');
        return `<div class="setting-row"><span class="account account-${s.tone}"><strong>Compte ${a.position} · ${esc(s.text)}</strong><small>${esc(s.detail)}</small>${extra}</span>
        <button type="button" class="small secondary" data-extra="${esc(a.id)}">Retirer</button></div>`; }).join('')
        : '<p class="hint">Aucun pour l’instant : une seule télé à la fois peut regarder.</p>';
      document.getElementById('xadd')!.toggleAttribute('hidden', rows.length >= MAX_EXTRA);
      list.querySelectorAll<HTMLButtonElement>('[data-extra]').forEach((b) => b.addEventListener('click', () => {
        if (demo) return;
        b.disabled = true;
        removeExtraAccount(db as unknown as Db, householdId, b.dataset.extra!).then(() => { say('Compte retiré.', true); void draw(); },
          (e: Error) => { b.disabled = false; say(e.message, false); });
      }));
    };
    document.getElementById('xadd')!.addEventListener('click', () => form.toggleAttribute('hidden'));
    form.addEventListener('submit', (e) => { e.preventDefault(); void guarded(form, async () => {
      const u = form.querySelector<HTMLInputElement>('#xun')!; const p = form.querySelector<HTMLInputElement>('#xpwd')!;
      if (demo) { say('Démo : rien n’est enregistré.', true); return; }
      say('…', true);
      try { await addExtraAccount(db as unknown as Db, householdId, u.value, p.value); } catch (err) { say((err as Error).message, false); return; }
      u.value = ''; p.value = ''; form.hidden = true;
      say('Compte ajouté. Allume une télé sur l’app : elle le vérifie en quelques secondes.', true);
      await draw();
      // Résultat de la vérification par la télé : relu quelques fois (hors du verrou des formulaires).
      void (async () => { for (const wait of [5_000, 10_000, 20_000, 30_000]) { await new Promise((r) => setTimeout(r, wait)); if (!list.isConnected) return; await draw(); } })();
    }); });
    await draw();
  })();
  void (async () => {
    const box = document.getElementById('iptv-account');
    if (!box) return;
    let row: IptvAccountRow | null = null;
    if (demo) { const end = new Date(); end.setMonth(end.getMonth() + 4); row = { expires_at: end.toISOString(), status: 'Active', max_connections: 1, checked_at: new Date().toISOString(), active_connections: 1, provider_message: 'Maintenance prévue dimanche de 3 h à 4 h.' }; }
    else { try { row = await loadIptvAccount(db as unknown as AccountDb); } catch { return; } }
    if (!box.isConnected) return;
    const s = accountSummary(row, new Date());
    box.className = `account account-${s.tone}`;
    const use = row ? usageText(row.active_connections, row.checked_at, row.max_connections) : null;
    const extra = [use, row?.provider_message ? `Message du fournisseur : ${row.provider_message}` : null].filter(Boolean).map((t) => `<small>${esc(t!)}</small>`).join('');
    box.innerHTML = `<strong>${esc(s.text)}</strong><small>${esc(s.detail)}</small>${extra}`;
  })();
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
