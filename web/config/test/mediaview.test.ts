// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest';
import { demoDb } from '../src/org/demo';
import { coverUrl, kindViewOf, loadContinue, move, saveKindView, searchTitles, sourceRows } from '../src/mediaview';
import { openMediaEditor } from '../src/mediaview-ui';
import type { SettingsDb } from '../src/avatar';
import type { Db } from '../src/flow';
import type { ProfileHome } from '../src/profiles';

describe('réglages Séries et films', () => {
  it('par défaut : défile toutes les 10 s, 5 titres, toutes les sources et rangées', () => {
    expect(kindViewOf({}, 'series')).toEqual({
      banner_mode: 'auto', banner_seconds: 10, banner_max: 5, banner_pinned: [], never: [],
      banner_sources: ['reprendre', 'nouveaux', 'suggestions', 'ma_liste'],
      rows: [{ key: 'reprendre', on: true }, { key: 'ma_liste', on: true }, { key: 'nouveaux', on: true }, { key: 'categories', on: true }],
    });
    expect(kindViewOf({}, 'movies').banner_sources).toEqual(['reprendre', 'suggestions', 'ma_liste', 'nouveautes']);
  });
  it('valeurs inconnues ignorées, rangées complétées, ancienne série épinglée reprise', () => {
    const v = kindViewOf({ pinned_series: '45', pinned_series_category: '7', media_view: { series: {
      banner_mode: 'fixe', banner_seconds: 99, banner_max: 8, banner_sources: ['ma_liste', 'x', 'ma_liste'], never: ['a', 'a', 3],
      rows: [{ key: 'categories' }, { key: 'reprendre', on: false }, { key: 'zz' }],
    } } }, 'series');
    expect(v.banner_mode).toBe('fixe'); expect(v.banner_seconds).toBe(10); expect(v.banner_max).toBe(8);
    expect(v.banner_sources).toEqual(['ma_liste']); expect(v.never).toEqual(['a']);
    expect(v.banner_pinned).toEqual([{ id: '45', category: '7' }]);
    expect(v.rows.map((r) => `${r.key}:${r.on}`)).toEqual(['categories:true', 'reprendre:false', 'ma_liste:true', 'nouveaux:true']);
    expect(sourceRows(v, 'series').map((r) => `${r.id}:${r.on}`)).toEqual(['ma_liste:true', 'reprendre:false', 'nouveaux:false', 'suggestions:false']);
  });
  it('enregistre sous media_view en gardant le reste ; l’ancienne épingle des séries est retirée', async () => {
    const calls: unknown[] = [];
    const db: SettingsDb = { from: () => ({ update: (v) => ({ eq: () => ({ select: async () => { calls.push(v); return { data: [{ id: 'p' }], error: null }; } }) }) }) };
    const view = { ...kindViewOf({}, 'series'), banner_mode: 'fixe' as const };
    const next = await saveKindView(db, 'p', { avatar_v: 1, pinned_series: '45', media_view: { movies: { banner_max: 3 } } }, 'series', view);
    expect(next).toEqual({ avatar_v: 1, media_view: { movies: { banner_max: 3 }, series: view } });
    expect(calls).toEqual([{ settings: next }]);
  });
  it('déplacer, affiches publiques seulement', () => {
    expect(move(['a', 'b', 'c'], 2, -1)).toEqual(['a', 'c', 'b']);
    expect(move(['a', 'b'], 0, -1)).toEqual(['a', 'b']);
    expect(coverUrl('/images/1.jpg')).toBeNull();
    expect(coverUrl('http://image.tmdb.org/a.jpg')).toBe('https://image.tmdb.org/a.jpg');
  });
  it('Reprendre et recherche passent par le serveur (démo)', async () => {
    const db = demoDb().db;
    const series = await loadContinue(db, 'p1', 'series');
    expect(series.map((s) => s.name)).toEqual(['Ferme Saint-Ours', 'La Brigade du Nord', 'Rivière sans retour']);
    expect(series[0].detail).toBe('S1 · É3 · 25 min restantes');
    expect((await loadContinue(db, 'p1', 'movies'))[0].detail).toBe('1 h 10 restantes');
    expect((await searchTitles(db, 'p1', 'series', 'Génies')).map((h) => h.name)).toEqual(['Petits Génies']);
    expect(await searchTitles(db, 'p1', 'series', 'g')).toEqual([]);
  });
});

describe('panneau Séries et films', () => {
  const setup = () => {
    const db = demoDb().db as Db & SettingsDb;
    const p: ProfileHome = { id: 'p1', name: 'Toi', home: 'direct', position: 0, color: '#214837', settings: {} } as ProfileHome;
    const root = document.createElement('section');
    root.scrollIntoView = vi.fn();
    document.body.append(root);
    openMediaEditor(root, p, db);
    return { root, p, db };
  };
  const click = (root: HTMLElement, sel: string) => (root.querySelector(sel) as HTMLButtonElement).click();
  const until = async (fn: () => boolean) => { for (let i = 0; i < 200 && !fn(); i++) await new Promise((r) => setTimeout(r, 5)); expect(fn()).toBe(true); };

  it('retire de Reprendre, avec confirmation pour tout retirer', async () => {
    const { root } = setup();
    await until(() => root.textContent!.includes('Ferme Saint-Ours'));
    click(root, '[data-dismiss="s2"]');
    await until(() => !root.textContent!.includes('Ferme Saint-Ours'));
    expect(root.querySelector('#mvmsg')!.textContent).toContain('Retiré de Reprendre');
    click(root, '[data-all]');
    expect(root.textContent).toContain('Retirer les 2 titres de Reprendre ?');
    click(root, '[data-allyes]');
    await until(() => root.textContent!.includes('Rien en cours.'));
  });

  it('ajoute à Ma liste par la recherche, épingle dans la bannière, puis passe aux films', async () => {
    const { root, p } = setup();
    await until(() => root.querySelector('#mvq') !== null);
    const input = root.querySelector<HTMLInputElement>('#mvq')!;
    input.value = 'station'; input.dispatchEvent(new Event('input'));
    await until(() => root.querySelector('[data-add="s10"]') !== null);
    click(root, '[data-add="s10"]');
    await until(() => root.textContent!.includes('« Station 9 » ajouté à Ma liste'));
    click(root, '.mlist [data-pin="s10"]');
    await until(() => JSON.stringify(p.settings).includes('s10'));
    expect(root.textContent).toContain('Titres choisis');
    expect((p.settings.media_view as any).series.banner_pinned).toEqual([{ id: 's10', category: 's5' }]);
    // Les réglages de la bannière automatique sont cachés quand des titres sont épinglés.
    expect(root.querySelector('[data-son]')).toBeNull();
    click(root, '[data-mode="fixe"]');
    await until(() => (p.settings.media_view as any).series.banner_mode === 'fixe');
    click(root, '[data-kind="movies"]');
    await until(() => root.textContent!.includes('Nuit polaire') || root.textContent!.includes('Ciel d’orage'));
    click(root, '[data-ron="2"]');
    await until(() => (p.settings.media_view as any).movies?.rows?.[2]?.on === false);
  });
});
