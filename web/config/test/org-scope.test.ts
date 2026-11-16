// @vitest-environment jsdom
import { expect, test, vi } from 'vitest';
import { mountOrganiser } from '../src/org/view';
import { demoDb } from '../src/org/demo';
test('organiser reprend le profil choisi et transmet Tout le monde', async () => {
  document.body.innerHTML = '<main></main>';
  const root = document.querySelector('main')!;
  const changed = vi.fn();
  await mountOrganiser(root, demoDb().db, 'h', {initialScope:'p2', onScopeChange:changed});
  expect(root.querySelector('[data-scope="p2"]')!.getAttribute('aria-pressed')).toBe('true');
  root.querySelector<HTMLButtonElement>('[data-scope=""]')!.click();
  expect(changed).toHaveBeenCalledWith(null);
});
test('profil disparu ne bascule jamais implicitement vers un autre profil ou Tout le monde', async () => {
  document.body.innerHTML = '<main></main>';
  const root = document.querySelector('main')!;
  const db = demoDb().db; const calls = vi.spyOn(db, 'rpc');
  await mountOrganiser(root, db, 'h', {initialScope:'profil-supprime'});
  expect(root.textContent).toContain('profil');
  expect(root.querySelector('[data-scope][aria-pressed="true"]')).toBeNull();
  expect(calls).not.toHaveBeenCalled();
});
