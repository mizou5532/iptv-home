import { it, expect } from 'vitest';
import { readFileSync } from 'node:fs';

const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');

it('les champs n\'ont pas d\'attribut name (jamais envoyés dans une URL)', () => {
  for (const input of html.match(/<input[^>]*>/g) ?? []) expect(input).not.toMatch(/\sname=/);
});
it('le formulaire est en POST vers # et le bouton désactivé tant que le script n\'a pas tourné', () => {
  expect(html).toMatch(/<form[^>]*method="post"[^>]*action="#"/);
  expect(html).toMatch(/<button id="start"[^>]*disabled/);
});
