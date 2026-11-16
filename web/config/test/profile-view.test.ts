// @vitest-environment jsdom
import { beforeEach, expect, test, vi } from 'vitest';
import { mountProfileView } from '../src/profile-view';
import { demoDb } from '../src/org/demo';
import { loadProfileHomes, type ProfilesDb } from '../src/profiles';
const settle = () => new Promise(r => setTimeout(r, 25));
let root: HTMLElement;
beforeEach(() => { document.body.innerHTML = '<main></main>'; root = document.querySelector('main')!; });
const setup = (extra = {}) => ({ db: demoDb().db as unknown as ProfilesDb, base: '', selectedId: null, onSelect: vi.fn(), onOrganise: vi.fn(), onPhoto: vi.fn(), onTeams: vi.fn(), ...extra });
test('choisir un profil ouvre son espace et son organisation', async () => {
  const options = setup();
  await mountProfileView(root, options);
  root.querySelector<HTMLButtonElement>('[data-profile="p2"]')!.click();
  expect(root.querySelector('h1')!.textContent).toBe('Sœur');
  expect(options.onSelect).toHaveBeenCalledWith('p2');
  root.querySelector<HTMLButtonElement>('[data-organise]')!.click();
  expect(options.onOrganise).toHaveBeenCalledWith('p2');
  root.querySelector<HTMLButtonElement>('[data-switch]')!.click();
  expect(root.querySelectorAll('[data-profile]')).toHaveLength(3);
});
test('changer ouverture modifie uniquement le profil sélectionné', async () => {
  const options = setup({ selectedId: 'p2' });
  await mountProfileView(root, options);
  root.querySelector<HTMLButtonElement>('[data-home="sport"]')!.click();
  await settle();
  const rows = await loadProfileHomes(options.db);
  expect(rows.find(p => p.id === 'p2')!.home).toBe('sport');
  expect(rows.find(p => p.id === 'p1')!.home).toBe('direct');
});
test('échec écriture restaure choix et affiche erreur sans effacer profil', async () => {
  const db = demoDb().db;
  const failing = { from: (t: string) => ({ ...db.from(t), update: () => ({ eq: () => ({ select: async () => ({data: null, error: {message: 'refus'}}) }) }) }) };
  await mountProfileView(root, setup({ db: failing, selectedId: 'p2' }));
  root.querySelector<HTMLButtonElement>('[data-home="sport"]')!.click();
  await settle();
  expect(root.querySelector('[data-home="direct"]')!.getAttribute('aria-pressed')).toBe('true');
  expect(root.querySelector('[role="status"]')!.textContent).toContain('Impossible');
});
test('aucun profil et erreur de lecture ont une issue explicite', async () => {
  await mountProfileView(root, setup({ db: { from: () => ({ select: async () => ({data: [], error:null}) }) } }));
  expect(root.textContent).toContain('Aucun profil');
  await mountProfileView(root, setup({ db: { from: () => ({ select: async () => ({data: null, error:{message:'refus'}}) }) } }));
  expect(root.textContent).toContain('Impossible');
  expect(root.querySelector('button')!.textContent).toContain('Réessayer');
});
test('un chargement terminé après démontage ne réécrit pas la page', async () => {
  let resolve!: (v: unknown) => void;
  const db = { from: () => ({ select: () => new Promise(r => { resolve = r; }) }) };
  const pending = mountProfileView(root, setup({ db }));
  // Le parent est retiré pendant la navigation.
  root.remove();
  resolve({data:[{id:'p1',name:'Toi',position:0}], error:null});
  await pending;
  expect(root.querySelector('[data-profile]')).toBeNull();
});
test('initiale reste lisible sur une couleur sombre de profil', async () => {
  const options = setup({selectedId:'p1'});
  await mountProfileView(root, options);
  expect(root.querySelector<HTMLElement>('.avatar-letter')!.style.color).toBe('rgb(255, 255, 255)');
});
test('revenir pendant une écriture attend son résultat au lieu de montrer un choix périmé', async () => {
  const base = demoDb().db as unknown as ProfilesDb;
  let finish!: () => void;
  const wait = new Promise<void>(r => {finish = r;});
  const db: ProfilesDb = { from: table => ({
    select: cols => base.from(table).select(cols),
    update: values => ({eq:(col,id)=>({select:async cols => { await wait; return base.from(table).update(values).eq(col,id).select(cols); }})}),
  }) };
  await mountProfileView(root, setup({db,selectedId:'p2'}));
  root.querySelector<HTMLButtonElement>('[data-home="sport"]')!.click();
  expect(root.querySelector<HTMLButtonElement>('[data-switch]')!.disabled).toBe(true);
  root.remove(); document.body.innerHTML='<main></main>'; root=document.querySelector('main')!;
  const reopen = mountProfileView(root, setup({db,selectedId:'p2'}));
  await settle();
  expect(root.querySelector('[data-home]')).toBeNull();
  finish(); await reopen;
  expect(root.querySelector('[data-home="sport"]')!.getAttribute('aria-pressed')).toBe('true');
});
