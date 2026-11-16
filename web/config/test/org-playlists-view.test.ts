// @vitest-environment jsdom
import { beforeEach, expect, test } from 'vitest';
import { TABS } from '../src/org/view';
import { demoDb } from '../src/org/demo';
import { orgPlaylistCreate, orgPlaylists } from '../src/org/api';
import type { Db } from '../src/flow';

const H = 'h1';
const NAME = 'À supprimer';
const settle = () => new Promise((r) => setTimeout(r, 30));
let root: HTMLElement;
let db: Db;
beforeEach(() => {
  document.body.innerHTML = '<main></main>';
  root = document.querySelector('main')!;
  db = demoDb().db;
});
async function openPlaylists() {
  const tab = TABS.find((t) => t.id === 'playlists')!;
  await tab.render({
    db,
    householdId: H,
    profiles: [{ id: 'p1', name: 'Toi', position: 0 }],
    scope: 'p1',
    root,
  });
  await settle();
}
const names = async (): Promise<string[]> => (await orgPlaylists(db, 'p1')).map((p) => p.name);

test('suppression_playlist_confirmation_integree', async () => {
  await orgPlaylistCreate(db, 'p1', NAME);
  await openPlaylists();
  // La démo contient déjà « Matchs » et « Soirée cinéma » : on ouvre la bonne rangée.
  const row = [...root.querySelectorAll<HTMLElement>('.frow')].find(
    (r) => r.querySelector('.otitle')?.textContent === NAME,
  )!;
  row.querySelector<HTMLElement>('[data-open]')!.click();
  await settle();
  expect(root.querySelector('#ptitle')!.textContent).toBe(NAME);
  // Premier clic : la confirmation apparaît dans la page, rien n'est supprimé.
  root.querySelector<HTMLButtonElement>('#del')!.click();
  const zone = root.querySelector<HTMLElement>('#delconfirm')!;
  expect(zone.hidden).toBe(false);
  expect(zone.textContent).toContain(`Supprimer la playlist « ${NAME} » ?`);
  expect(zone.textContent).toContain('Les chaînes ne sont pas supprimées.');
  // Annuler : la playlist existe toujours.
  root.querySelector<HTMLButtonElement>('button[data-del="no"]')!.click();
  expect(zone.hidden).toBe(true);
  expect(await names()).toContain(NAME);
  // Confirmer : suppression effective et retour à la liste.
  root.querySelector<HTMLButtonElement>('#del')!.click();
  root.querySelector<HTMLButtonElement>('button[data-del="yes"]')!.click();
  await settle();
  expect(await names()).not.toContain(NAME);
  expect(await names()).toContain('Matchs');
  expect(root.textContent).toContain('Matchs');
});
