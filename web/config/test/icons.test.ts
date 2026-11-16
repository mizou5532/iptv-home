// @vitest-environment jsdom
import { expect, test } from 'vitest';
import { icon } from '../src/icons';

test('les pictogrammes fournissent leur véritable tracé, sans masque CSS dépendant des URL SVG du build', () => {
  const names = ['direct', 'series', 'films', 'sport', 'profiles', 'settings', 'photo', 'edit', 'chevron', 'exit', 'organise'] as const;
  for (const name of names) {
    const el = document.createElement('div');
    el.innerHTML = icon(name);
    const graphic = el.querySelector('svg');
    // Le SVG de la bibliothèque apporte la géométrie : un fond de couleur seul n'est pas une icône.
    expect(graphic, name).not.toBeNull();
    expect(graphic!.getAttribute('viewBox'), name).toBe('0 0 256 256');
    expect(graphic!.querySelector('path')!.getAttribute('d')!.length, name).toBeGreaterThan(20);
    expect(el.querySelector('.icon')!.getAttribute('aria-hidden'), name).toBe('true');
    expect(el.querySelector('.icon')!.hasAttribute('style'), name).toBe(false);
  }
});
