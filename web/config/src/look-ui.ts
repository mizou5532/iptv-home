// Panneau « Personnalisation » d'un profil (page iPhone) : aperçu de la télé, fond, couleur principale, lecteur.
// Chaque choix est enregistré tout de suite ; la télé le prend au prochain lancement.
import { CORNERS, PALETTE, THEMES, inkOn, lookOf, setProfileLook, setThemeLook, themeColors, type Corners, type ThemeLook, type ThemePreset } from './look';
import { PLAYER_STYLES, playerStyleOf, setPlayerStyle, type PlayerStyle, type ProfileHome, type ProfilesDb } from './profiles';
import { escapeHtml as esc } from './icons';

/** Aperçu miniature de l'accueil de la télé avec ces couleurs (barre des onglets, pastille du profil, bannière). */
export function tvPreview(p: ProfileHome): string {
  const look = lookOf(p.settings, p.theme);
  const c = themeColors(look, p.color);
  const r = CORNERS.find((x) => x.id === look.corners)!.px;
  const surface = c.surface;
  return `<div class="tvmini" style="background:${esc(c.bg)};color:${c.text}" aria-label="Aperçu de la télé">
    <div class="tvmini-bar"><span class="tvmini-tabs" style="background:${surface}"><b style="background:${esc(p.color)};color:${inkOn(p.color)}">Séries</b><i>Films</i><i>Sport</i></span>
      <span class="tvmini-pill" style="background:${surface}"><span style="background:${esc(p.color)};color:${inkOn(p.color)}">${esc(p.name.slice(0, 1).toUpperCase())}</span>${esc(p.name)}</span></div>
    <div class="tvmini-hero" style="border-radius:${r}px;background:${c.light ? `linear-gradient(90deg,${surface} 30%,color-mix(in srgb, ${p.color} 35%, white))` : `linear-gradient(90deg,${esc(c.bg)} 30%,color-mix(in srgb, ${esc(c.bg)} 70%, #5a6a90))`}">
      <small style="color:${c.light ? c.text : esc(p.color)}">REPRENDRE</small><strong>Nuit sur la ville</strong>
      <span class="tvmini-btn" style="background:${esc(p.color)};color:${inkOn(p.color)}">▶ Reprendre</span></div>
    <div class="tvmini-row">${[0, 1, 2, 3].map((i) => `<span style="border-radius:${r}px;background:${surface};${i === 0 ? `box-shadow:0 0 0 2px ${c.light ? c.text : '#fff'}` : ''}"></span>`).join('')}</div>
  </div>`;
}

export function openLookEditor(root: HTMLElement, p: ProfileHome, db: ProfilesDb, onChanged: () => void): void {
  root.hidden = false;
  const say = (text: string, ok = true) => { const m = root.querySelector<HTMLElement>('#lookmsg'); if (m) { m.textContent = text; m.className = `msg ${ok ? 'ok' : 'error'}`; } };
  const paint = (): void => {
    const custom = !PALETTE.some((c) => c.color.toUpperCase() === p.color.toUpperCase());
    const look = lookOf(p.settings, p.theme);
    root.innerHTML = `<div class="ped look">
      <h3>Personnalisation de ${esc(p.name)}</h3>
      ${tvPreview(p)}
      <h4>Thème</h4>
      <div class="themes" role="group" aria-label="Thème">${THEMES.map((t) => `<button type="button" class="theme" data-preset="${t.id}" aria-pressed="${look.preset === t.id}"><span style="background:${t.bg ?? `color-mix(in srgb, ${esc(p.color)} 12%, white)`}"><i style="background:${esc(p.color)}"></i></span>${t.label}</button>`).join('')}
        <label class="theme" aria-pressed="${look.preset === 'perso'}"><span style="background:${look.background ? esc(look.background) : 'conic-gradient(#ff4d5e,#ffc46b,#4cd964,#36c2b4,#2f6bff,#c084fc,#ff4d5e)'}"><i style="background:${esc(p.color)}"></i></span>Fond libre<input type="color" id="lookbg" value="${esc(look.background ?? '#1E2A44')}"></label></div>
      <h4>Coins</h4>
      <div class="seg seg3" role="group" aria-label="Coins">${CORNERS.map((c) => `<button type="button" data-corners="${c.id}" aria-pressed="${look.corners === c.id}">${c.label}</button>`).join('')}</div>
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
  const saveLook = (next: ThemeLook, done: string) => save(async () => {
    p.settings = await setThemeLook(db, p.id, p.settings, next, p.color);
    p.theme = themeColors(next, p.color).light ? 'clair' : 'sombre';
  }, done);
  root.onclick = (e) => {
    const b = (e.target as HTMLElement).closest<HTMLElement>('button');
    if (!b) return;
    if (b.id === 'lookclose') { root.hidden = true; root.innerHTML = ''; root.onclick = null; return; }
    const now = lookOf(p.settings, p.theme);
    if (b.dataset.preset && b.dataset.preset !== now.preset) {
      const next: ThemeLook = { preset: b.dataset.preset as ThemePreset, corners: now.corners };
      void saveLook(next, `Thème « ${THEMES.find((t) => t.id === next.preset)!.label} » enregistré.`);
    }
    if (b.dataset.corners && b.dataset.corners !== now.corners) {
      void saveLook({ ...now, corners: b.dataset.corners as Corners }, 'Coins enregistrés.');
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
    if (input.id === 'lookbg') { void saveLook({ preset: 'perso', background: input.value.toUpperCase(), corners: lookOf(p.settings, p.theme).corners }, 'Fond enregistré.'); return; }
    if (input.id !== 'lookcustom') return;
    const c = input.value.toUpperCase();
    void save(async () => { await setProfileLook(db, p.id, { color: c }); p.color = c; }, 'Couleur enregistrée.');
  };
  paint();
  root.scrollIntoView({ behavior: 'smooth', block: 'start' });
}
