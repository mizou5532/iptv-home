// Panneau « Personnalisation » d'un profil (page iPhone) : aperçu de la télé, fond, couleur principale, lecteur.
// Chaque choix est enregistré tout de suite ; la télé le prend au prochain lancement.
import { BACKGROUNDS, PALETTE, backgroundOf, inkOn, setProfileLook, type Background } from './look';
import { PLAYER_STYLES, playerStyleOf, setPlayerStyle, type PlayerStyle, type ProfileHome, type ProfilesDb } from './profiles';
import { escapeHtml as esc } from './icons';

/** Aperçu miniature de l'accueil de la télé avec ces couleurs (barre des onglets, pastille du profil, bannière). */
export function tvPreview(p: ProfileHome): string {
  const dark = backgroundOf(p.theme) === 'sombre';
  const bg = dark ? '#0B0F17' : `color-mix(in srgb, ${p.color} 12%, white)`;
  const text = dark ? '#F2F4F8' : '#2B1D26';
  const surface = dark ? 'rgba(255,255,255,.08)' : '#FFFFFF';
  return `<div class="tvmini" style="background:${bg};color:${text}" aria-label="Aperçu de la télé">
    <div class="tvmini-bar"><span class="tvmini-tabs" style="background:${surface}"><b style="background:${esc(p.color)};color:${inkOn(p.color)}">Séries</b><i>Films</i><i>Sport</i></span>
      <span class="tvmini-pill" style="background:${surface}"><span style="background:${esc(p.color)};color:${inkOn(p.color)}">${esc(p.name.slice(0, 1).toUpperCase())}</span>${esc(p.name)}</span></div>
    <div class="tvmini-hero" style="background:${dark ? 'linear-gradient(90deg,#0B0F17 30%,#2a3550)' : `linear-gradient(90deg,${surface} 30%,color-mix(in srgb, ${p.color} 35%, white))`}">
      <small style="color:${dark ? esc(p.color) : text}">REPRENDRE</small><strong>Nuit sur la ville</strong>
      <span class="tvmini-btn" style="background:${esc(p.color)};color:${inkOn(p.color)}">▶ Reprendre</span></div>
    <div class="tvmini-row">${[0, 1, 2, 3].map((i) => `<span style="background:${surface};${i === 0 ? `box-shadow:0 0 0 2px ${dark ? '#fff' : text}` : ''}"></span>`).join('')}</div>
  </div>`;
}

export function openLookEditor(root: HTMLElement, p: ProfileHome, db: ProfilesDb, onChanged: () => void): void {
  root.hidden = false;
  const say = (text: string, ok = true) => { const m = root.querySelector<HTMLElement>('#lookmsg'); if (m) { m.textContent = text; m.className = `msg ${ok ? 'ok' : 'error'}`; } };
  const paint = (): void => {
    const custom = !PALETTE.some((c) => c.color.toUpperCase() === p.color.toUpperCase());
    root.innerHTML = `<div class="ped look">
      <h3>Personnalisation de ${esc(p.name)}</h3>
      ${tvPreview(p)}
      <h4>Fond</h4>
      <div class="seg" role="group" aria-label="Fond">${BACKGROUNDS.map((b) => `<button type="button" data-bg="${b.id}" aria-pressed="${backgroundOf(p.theme) === b.id}">${b.label}</button>`).join('')}</div>
      <h4>Couleur principale</h4>
      <div class="swatches" role="group" aria-label="Couleur principale">${PALETTE.map((c) => `<button type="button" class="swatch" data-color="${c.color}" title="${esc(c.name)}" aria-label="${esc(c.name)}" aria-pressed="${c.color.toUpperCase() === p.color.toUpperCase()}" style="background:${c.color}"></button>`).join('')}
        <label class="swatch other" aria-pressed="${custom}" style="${custom ? `background:${esc(p.color)}` : ''}" title="Autre couleur">+<input type="color" id="lookcustom" value="${esc(p.color)}"></label></div>
      <h4>Lecteur vidéo <small>films et séries</small></h4>
      <div class="seg" role="group" aria-label="Lecteur vidéo">${PLAYER_STYLES.map((s) => `<button type="button" data-player="${s.id}" aria-pressed="${playerStyleOf(p.settings) === s.id}">${s.label}</button>`).join('')}</div>
      <div class="tools"><button type="button" class="small secondary" id="lookclose">Fermer</button></div>
      <p id="lookmsg" class="msg" role="status" aria-live="polite"></p>
    </div>`;
  };
  const save = async (work: () => Promise<void>, done: string) => {
    say('Enregistrement…');
    try { await work(); paint(); onChanged(); say(`${done} Sur la télé : redémarre l’application.`); }
    catch (e) { paint(); say((e as Error).message, false); }
  };
  root.onclick = (e) => {
    const b = (e.target as HTMLElement).closest<HTMLElement>('button');
    if (!b) return;
    if (b.id === 'lookclose') { root.hidden = true; root.innerHTML = ''; root.onclick = null; return; }
    if (b.dataset.bg && b.dataset.bg !== backgroundOf(p.theme)) {
      const bg = b.dataset.bg as Background;
      void save(async () => { await setProfileLook(db, p.id, { theme: bg }); p.theme = bg; }, `Fond ${bg === 'clair' ? 'clair' : 'sombre'} enregistré.`);
    }
    if (b.dataset.color && b.dataset.color !== p.color) {
      const c = b.dataset.color;
      void save(async () => { await setProfileLook(db, p.id, { color: c }); p.color = c; }, 'Couleur enregistrée.');
    }
    if (b.dataset.player && b.dataset.player !== playerStyleOf(p.settings)) {
      const s = b.dataset.player as PlayerStyle;
      void save(async () => { p.settings = await setPlayerStyle(db, p.id, p.settings, s); }, `Lecteur « ${PLAYER_STYLES.find((x) => x.id === s)!.label} » enregistré.`);
    }
  };
  root.onchange = (e) => {
    const input = e.target as HTMLInputElement;
    if (input.id !== 'lookcustom') return;
    const c = input.value.toUpperCase();
    void save(async () => { await setProfileLook(db, p.id, { color: c }); p.color = c; }, 'Couleur enregistrée.');
  };
  paint();
  root.scrollIntoView({ behavior: 'smooth', block: 'start' });
}
