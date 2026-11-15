// @vitest-environment jsdom
import { beforeEach, describe, expect, test, vi } from 'vitest';
import { TABS } from '../src/org/view';
import { confirmText } from '../src/org/categories';
import { demoDb } from '../src/org/demo';
import { orgSeriesCategories, orgSeriesHideAllExcept, orgSetHidden, orgSetSeriesCategoryOrder } from '../src/org/api';
import type { Db } from '../src/flow';

const H = 'h1';
function spyDb(base: Db) {
  const calls: { fn: string; args: Record<string, unknown> }[] = [];
  const db: Db = { rpc: async (fn, args) => { calls.push({ fn, args: args as Record<string, unknown> }); return base.rpc(fn, args); }, from: (t) => base.from(t) };
  return { db, calls };
}
const settle = () => new Promise((r) => setTimeout(r, 30));
let root: HTMLElement;
beforeEach(() => { document.body.innerHTML = '<main></main>'; root = document.querySelector('main')!; });
async function openSeries(db: Db, scope: string | null) {
  const tab = TABS.find((t) => t.id === 'categories')!;
  await tab.render({ db, householdId: H, profiles: [], scope, root });
  root.querySelector<HTMLButtonElement>('button[data-kind="series"]')!.click();
  await settle();
}

describe('api series', () => {
  test('arguments_exacts_des_rpc', async () => {
    const { db, calls } = spyDb(demoDb().db);
    await orgSeriesCategories(db, H, 'p1');
    await orgSetHidden(db, H, null, 'series_category', ['s1'], true);
    await orgSeriesHideAllExcept(db, H, 'p1', ['s2']);
    await orgSetSeriesCategoryOrder(db, H, null, ['s2', 's1']);
    expect(calls).toEqual([
      { fn: 'org_series_categories', args: { h: H, p: 'p1' } },
      { fn: 'org_set_hidden', args: { h: H, p: null, item_type: 'series_category', ids: ['s1'], hidden: true } },
      { fn: 'org_series_hide_all_except', args: { h: H, p: 'p1', keep: ['s2'] } },
      { fn: 'org_set_series_category_order', args: { h: H, p: null, ids: ['s2', 's1'] } },
    ]);
  });
  test('confirmation_tout_le_monde_series', () => {
    expect(confirmText(true, 1, 'series')).toBe('Cette catégorie de séries ne sera plus téléchargée par la télé, pour tout le monde. Continuer ?');
    expect(confirmText(true, 3, 'series')).toBe('Ces 3 catégories de séries ne seront plus téléchargées par la télé, pour tout le monde. Continuer ?');
    expect(confirmText(false, 3, 'series')).toBeNull();
    expect(confirmText(true, 2)).toContain('Ces 2 catégories ne');
  });
});

describe('ecran series', () => {
  test('liste_vide_message', async () => {
    const empty: Db = { rpc: async () => ({ data: [], error: null }), from: () => ({ select: async () => ({ data: [], error: null }) }) };
    await openSeries(empty, 'p1');
    expect(root.textContent).toContain('Ouvre la section Séries sur la télé une fois pour envoyer la liste.');
  });
  test('masquer_pour_tout_le_monde_demande_confirmation', async () => {
    const { db, calls } = spyDb(demoDb().db);
    await openSeries(db, null);
    const box = root.querySelector<HTMLInputElement>('.orow .cb:not(:disabled)')!;
    box.click();
    await settle();
    // Confirmation intégrée à la page : le texte est affiché dans les outils, rien n'est encore écrit.
    expect(root.querySelector('.tools')!.textContent).toContain('catégorie de séries');
    expect(calls.some((c) => c.fn === 'org_set_hidden')).toBe(false);
    // Annuler ne change rien.
    root.querySelector<HTMLButtonElement>('[data-act="no"]')!.click();
    await settle();
    expect(calls.some((c) => c.fn === 'org_set_hidden')).toBe(false);
    // Recommencer puis Confirmer : l'écriture part.
    const box2 = root.querySelector<HTMLInputElement>('.orow .cb:not(:disabled)')!;
    box2.click(); await settle();
    root.querySelector<HTMLButtonElement>('[data-act="yes"]')!.click();
    await settle();
    const w = calls.find((c) => c.fn === 'org_set_hidden')!;
    expect(w.args).toMatchObject({ h: H, p: null, item_type: 'series_category', hidden: true });
  });
  test('profil_sans_confirmation_et_tout_masquer_sauf', async () => {
    const { db, calls } = spyDb(demoDb().db);
    const ask = vi.fn(() => true);
    vi.stubGlobal('confirm', ask);
    await openSeries(db, 'p1');
    root.querySelector<HTMLButtonElement>('[data-act="pick"]')!.click();
    root.querySelector<HTMLInputElement>('.orow .cb')!.click();
    root.querySelector<HTMLButtonElement>('[data-act="apply"]')!.click();
    await settle();
    expect(ask).not.toHaveBeenCalled();
    const w = calls.find((c) => c.fn === 'org_series_hide_all_except')!;
    expect(w.args).toEqual({ h: H, p: 'p1', keep: ['s1'] });
  });
  test('ordre_enregistre_avec_la_rpc_series', async () => {
    const { db, calls } = spyDb(demoDb().db);
    await openSeries(db, 'p1');
    root.querySelector<HTMLButtonElement>('[data-act="reorder"]')!.click();
    root.querySelector<HTMLButtonElement>('button[data-mv="1"]')!.click();
    root.querySelector<HTMLButtonElement>('[data-act="done"]')!.click();
    await settle();
    const w = calls.find((c) => c.fn === 'org_set_series_category_order')!;
    expect((w.args.ids as string[]).slice(0, 2)).toEqual(['s2', 's1']);
    expect(calls.some((c) => c.fn === 'org_set_category_order')).toBe(false);
  });
});
