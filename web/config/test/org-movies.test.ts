// @vitest-environment jsdom
import { beforeEach, describe, expect, test, vi } from 'vitest';
import { TABS } from '../src/org/view';
import { CATEGORY_KINDS, confirmText, EMPTY_TEXT } from '../src/org/categories';
import { demoDb } from '../src/org/demo';
import {
  orgCategories, orgMovieCategories, orgMovieHideAllExcept, orgSeriesCategories, orgSetHidden, orgSetMovieCategoryOrder,
} from '../src/org/api';
import type { Db } from '../src/flow';

const H = 'h1';
type Call = { fn: string; args: Record<string, unknown> };
function spyDb(base: Db, fail: Record<string, string> = {}) {
  const calls: Call[] = [];
  const db: Db = {
    rpc: async (fn, args) => {
      calls.push({ fn, args: args as Record<string, unknown> });
      if (fail[fn]) return { data: null, error: { message: fail[fn] } };
      return base.rpc(fn, args);
    },
    from: (t) => base.from(t),
  };
  return { db, calls };
}
const settle = () => new Promise((r) => setTimeout(r, 30));
let root: HTMLElement;
beforeEach(() => { document.body.innerHTML = '<main></main>'; root = document.querySelector('main')!; });
const kindButton = (k: string) => root.querySelector<HTMLButtonElement>(`button[data-kind="${k}"]`)!;
async function openMovies(db: Db, scope: string | null) {
  const tab = TABS.find((t) => t.id === 'categories')!;
  await tab.render({ db, householdId: H, profiles: [{ id: 'p1', name: 'Toi', position: 0 }], scope, root });
  kindButton('movies').click();
  await settle();
}

describe('api films', () => {
  test('arguments_exacts_des_rpc', async () => {
    const { db, calls } = spyDb(demoDb().db);
    await orgMovieCategories(db, H, 'p1');
    await orgSetHidden(db, H, null, 'vod_category', ['m1'], true);
    await orgMovieHideAllExcept(db, H, 'p1', ['m2']);
    await orgSetMovieCategoryOrder(db, H, null, ['m2', 'm1']);
    expect(calls).toEqual([
      { fn: 'org_movie_categories', args: { h: H, p: 'p1' } },
      { fn: 'org_set_hidden', args: { h: H, p: null, item_type: 'vod_category', ids: ['m1'], hidden: true } },
      { fn: 'org_movie_hide_all_except', args: { h: H, p: 'p1', keep: ['m2'] } },
      { fn: 'org_set_movie_category_order', args: { h: H, p: null, ids: ['m2', 'm1'] } },
    ]);
  });
  test('lecture_des_lignes_sans_compteur', async () => {
    const db: Db = {
      rpc: async () => ({ data: [{ id: 7, name: 'Action', provider_position: 3, hidden_global: true, hidden_profile: false }], error: null }),
      from: () => ({ select: async () => ({ data: [], error: null }) }),
    };
    expect(await orgMovieCategories(db, H, null)).toEqual([
      { id: '7', name: 'Action', providerPosition: 3, hiddenGlobal: true, hiddenProfile: false, channelCount: 0 },
    ]);
  });
  test('erreurs_traduites', async () => {
    const { db } = spyDb(demoDb().db, { org_movie_categories: 'acces-refuse', org_set_movie_category_order: 'categorie-inconnue' });
    await expect(orgMovieCategories(db, H, 'p1')).rejects.toThrow("Tu n'as pas le droit de modifier cette sélection.");
    await expect(orgSetMovieCategoryOrder(db, H, 'p1', ['m1'])).rejects.toThrow("Cette catégorie n'existe plus. Recharge la liste.");
  });
  test('confirmation_tout_le_monde_films', () => {
    expect(confirmText(true, 1, 'movies')).toBe('Cette catégorie de films ne sera plus téléchargée par la télé, pour tout le monde. Continuer ?');
    expect(confirmText(true, 3, 'movies')).toBe('Ces 3 catégories de films ne seront plus téléchargées par la télé, pour tout le monde. Continuer ?');
    expect(confirmText(false, 3, 'movies')).toBeNull();
    expect(confirmText(true, 1)).toBe('Cette catégorie ne sera plus téléchargée par la télé, pour tout le monde. Continuer ?');
    expect(confirmText(true, 2, 'series')).toContain('Ces 2 catégories de séries ne');
  });
  test('trois_sections_declarees', () => {
    expect(CATEGORY_KINDS.map((k) => [k.id, k.label])).toEqual([['live', 'Direct'], ['series', 'Séries'], ['movies', 'Films']]);
    expect(EMPTY_TEXT.movies).toBe('Ouvre la section Films sur la télé une fois pour envoyer la liste.');
  });
});

describe('selecteur', () => {
  test('direct_series_films', async () => {
    const tab = TABS.find((t) => t.id === 'categories')!;
    await tab.render({ db: demoDb().db, householdId: H, profiles: [], scope: 'p1', root });
    const buttons = [...root.querySelectorAll<HTMLButtonElement>('#kind button[data-kind]')];
    expect(buttons.map((b) => [b.dataset.kind, b.textContent])).toEqual([['live', 'Direct'], ['series', 'Séries'], ['movies', 'Films']]);
    expect(root.querySelector('#kind')!.getAttribute('aria-label')).toBe('Direct, Séries ou Films');
  });
  test('films_puis_series_puis_direct_chargent_la_bonne_liste', async () => {
    const { db, calls } = spyDb(demoDb().db);
    await openMovies(db, 'p1');
    expect(kindButton('movies').getAttribute('aria-pressed')).toBe('true');
    expect(calls.at(-1)).toEqual({ fn: 'org_movie_categories', args: { h: H, p: 'p1' } });
    expect(root.querySelector('.orow')!.getAttribute('data-id')).toBe('m1');
    kindButton('series').click(); await settle();
    expect(calls.at(-1)!.fn).toBe('org_series_categories');
    expect(root.querySelector('.orow')!.getAttribute('data-id')).toBe('s1');
    kindButton('live').click(); await settle();
    expect(calls.at(-1)!.fn).toBe('org_categories');
    expect(root.querySelector('.orow .osub')!.textContent).toMatch(/chaîne/);
  });
});

describe('ecran films', () => {
  test('liste_vide_message', async () => {
    const empty: Db = { rpc: async () => ({ data: [], error: null }), from: () => ({ select: async () => ({ data: [], error: null }) }) };
    await openMovies(empty, 'p1');
    expect(root.textContent).toContain('Ouvre la section Films sur la télé une fois pour envoyer la liste.');
    expect(root.querySelector('.orow')).toBeNull();
  });
  test('erreur_de_chargement_affichee', async () => {
    const { db } = spyDb(demoDb().db, { org_movie_categories: 'acces-refuse' });
    await openMovies(db, 'p1');
    expect(root.querySelector('.msg.error')!.textContent).toBe("Tu n'as pas le droit de modifier cette sélection.");
    expect(root.querySelector('.orow')).toBeNull();
  });
  test('erreur_d_ecriture_affichee_et_liste_rechargee', async () => {
    const { db, calls } = spyDb(demoDb().db, { org_set_hidden: 'type-invalide' });
    await openMovies(db, 'p1');
    const before = calls.filter((c) => c.fn === 'org_movie_categories').length;
    root.querySelector<HTMLInputElement>('.orow .cb:not(:disabled)')!.click();
    await settle();
    expect(root.querySelector('#omsg')!.textContent).toBe('Action non reconnue. Recharge la page.');
    expect(calls.filter((c) => c.fn === 'org_movie_categories').length).toBe(before + 1);
    expect(root.querySelector<HTMLInputElement>('.orow .cb:not(:disabled)')!.checked).toBe(true);
  });
  test('pas_de_compteur_ni_d_ouverture_de_categorie', async () => {
    const { db, calls } = spyDb(demoDb().db);
    await openMovies(db, 'p1');
    expect(root.querySelectorAll('.orow').length).toBeGreaterThan(3);
    expect(root.querySelector('#vlist')!.textContent).not.toMatch(/chaîne/);
    root.querySelector<HTMLElement>('.orow [data-open]')!.click();
    await settle();
    expect(calls.some((c) => c.fn === 'org_channels')).toBe(false);
    expect(root.querySelector('#vlist')).not.toBeNull();
  });
  test('masquer_pour_tout_le_monde_demande_confirmation', async () => {
    const { db, calls } = spyDb(demoDb().db);
    const ask = vi.fn(() => false);
    vi.stubGlobal('confirm', ask);
    await openMovies(db, null);
    root.querySelector<HTMLInputElement>('.orow .cb:not(:disabled)')!.click();
    await settle();
    expect(ask).toHaveBeenCalledTimes(1);
    expect(String(ask.mock.calls[0])).toBe('Cette catégorie de films ne sera plus téléchargée par la télé, pour tout le monde. Continuer ?');
    expect(calls.some((c) => c.fn === 'org_set_hidden')).toBe(false);
    ask.mockReturnValue(true);
    root.querySelector<HTMLInputElement>('.orow .cb:not(:disabled)')!.click(); await settle();
    expect(calls.find((c) => c.fn === 'org_set_hidden')!.args).toEqual({ h: H, p: null, item_type: 'vod_category', ids: ['m1'], hidden: true });
  });
  test('masquer_puis_reafficher_pour_un_profil_sans_confirmation', async () => {
    const { db, calls } = spyDb(demoDb().db);
    const ask = vi.fn(() => true);
    vi.stubGlobal('confirm', ask);
    await openMovies(db, 'p1');
    root.querySelector<HTMLInputElement>('.orow .cb:not(:disabled)')!.click(); await settle();
    expect(ask).not.toHaveBeenCalled();
    expect(calls.find((c) => c.fn === 'org_set_hidden')!.args).toEqual({ h: H, p: 'p1', item_type: 'vod_category', ids: ['m1'], hidden: true });
    root.querySelector<HTMLButtonElement>('[data-act="showall"]')!.click(); await settle();
    expect(calls.filter((c) => c.fn === 'org_set_hidden').at(-1)!.args).toEqual({ h: H, p: 'p1', item_type: 'vod_category', ids: ['m1'], hidden: false });
  });
  test('tout_masquer_sauf_profil_sans_confirmation', async () => {
    const { db, calls } = spyDb(demoDb().db);
    const ask = vi.fn(() => true);
    vi.stubGlobal('confirm', ask);
    await openMovies(db, 'p1');
    root.querySelector<HTMLButtonElement>('[data-act="pick"]')!.click();
    root.querySelector<HTMLInputElement>('.orow .cb')!.click();
    root.querySelector<HTMLButtonElement>('[data-act="apply"]')!.click();
    await settle();
    expect(ask).not.toHaveBeenCalled();
    expect(calls.find((c) => c.fn === 'org_movie_hide_all_except')!.args).toEqual({ h: H, p: 'p1', keep: ['m1'] });
    expect(calls.some((c) => c.fn === 'org_hide_all_except' || c.fn === 'org_series_hide_all_except')).toBe(false);
  });
  test('tout_masquer_sauf_tout_le_monde_confirme_avec_le_nombre', async () => {
    const { db, calls } = spyDb(demoDb().db);
    const ask = vi.fn(() => false);
    vi.stubGlobal('confirm', ask);
    await openMovies(db, null);
    root.querySelector<HTMLButtonElement>('[data-act="pick"]')!.click();
    root.querySelector<HTMLInputElement>('.orow .cb')!.click();
    root.querySelector<HTMLButtonElement>('[data-act="apply"]')!.click();
    await settle();
    expect(String(ask.mock.calls[0])).toMatch(/^Ces \d+ catégories de films ne seront plus téléchargées par la télé, pour tout le monde\. Continuer \?$/);
    expect(calls.some((c) => c.fn === 'org_movie_hide_all_except')).toBe(false);
    ask.mockReturnValue(true);
    root.querySelector<HTMLButtonElement>('[data-act="apply"]')!.click();
    await settle();
    expect(calls.find((c) => c.fn === 'org_movie_hide_all_except')!.args).toEqual({ h: H, p: null, keep: ['m1'] });
  });
  test('ordre_enregistre_avec_la_rpc_films', async () => {
    const { db, calls } = spyDb(demoDb().db);
    await openMovies(db, 'p1');
    root.querySelector<HTMLButtonElement>('[data-act="reorder"]')!.click();
    root.querySelector<HTMLButtonElement>('button[data-mv="1"]')!.click();
    root.querySelector<HTMLButtonElement>('[data-act="done"]')!.click();
    await settle();
    const w = calls.find((c) => c.fn === 'org_set_movie_category_order')!;
    expect(w.args).toMatchObject({ h: H, p: 'p1' });
    expect((w.args.ids as string[]).slice(0, 2)).toEqual(['m2', 'm1']);
    expect(calls.some((c) => c.fn === 'org_set_category_order' || c.fn === 'org_set_series_category_order')).toBe(false);
    expect(root.querySelector('#omsg')!.textContent).toBe('Ordre enregistré.');
  });
});

describe('demo films', () => {
  test('categories_inventees_locales', async () => {
    const { db } = demoDb();
    const list = await orgMovieCategories(db, 'demo', 'p1');
    expect(list.length).toBeGreaterThanOrEqual(8);
    expect(list.every((c) => /^m\d+$/.test(c.id) && c.name.length > 0)).toBe(true);
    expect(list.some((c) => c.hiddenGlobal)).toBe(true);
  });
  test('masquage_ordre_et_tout_masquer_sauf_restent_separes_du_direct_et_des_series', async () => {
    const { db } = demoDb();
    const series0 = await orgSeriesCategories(db, 'demo', 'p1');
    const live0 = await orgCategories(db, 'demo', 'p1');
    await orgSetHidden(db, 'demo', 'p1', 'vod_category', ['m2'], true);
    expect((await orgMovieCategories(db, 'demo', 'p1')).find((c) => c.id === 'm2')!.hiddenProfile).toBe(true);
    expect((await orgMovieCategories(db, 'demo', 'p2')).find((c) => c.id === 'm2')!.hiddenProfile).toBe(false);
    const ids = (await orgMovieCategories(db, 'demo', 'p1')).map((c) => c.id);
    const swapped = [ids[1], ids[0], ...ids.slice(2)];
    await orgSetMovieCategoryOrder(db, 'demo', 'p1', swapped);
    expect((await orgMovieCategories(db, 'demo', 'p1')).map((c) => c.id)).toEqual(swapped);
    const n = await orgMovieHideAllExcept(db, 'demo', null, ['m1']);
    const after = await orgMovieCategories(db, 'demo', null);
    expect(n).toBe(after.length - 1);
    expect(after.filter((c) => !c.hiddenGlobal).map((c) => c.id)).toEqual(['m1']);
    expect(await orgSeriesCategories(db, 'demo', 'p1')).toEqual(series0);
    expect(await orgCategories(db, 'demo', 'p1')).toEqual(live0);
  });
});
