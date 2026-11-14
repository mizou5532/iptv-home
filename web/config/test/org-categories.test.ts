import { describe, it, expect } from 'vitest';
import { filterCategories, moveCategory, hideAllExceptPlan, confirmText, createGate } from '../src/org/categories';
import { orgHideAllExcept, orgCategories, orgSetHidden } from '../src/org/api';
import type { Category } from '../src/org/api';
import type { Db } from '../src/flow';

const cat = (id: string, name: string, over: Partial<Category> = {}): Category => ({
  id, name, providerPosition: 0, hiddenGlobal: false, hiddenProfile: false, channelCount: 1, ...over,
});

function fakeDb(handlers: Record<string, (args?: object) => unknown> = {}) {
  const calls: { fn: string; args?: object }[] = [];
  const db: Db = {
    async rpc(fn, args) {
      calls.push({ fn, args });
      try { return { data: handlers[fn]?.(args) ?? null, error: null }; }
      catch (e) { return { data: null, error: { message: (e as Error).message } }; }
    },
    from() { return { select: async () => ({ data: [], error: null }) }; },
  };
  return { db, calls };
}

describe('filterCategories', () => {
  it('filterCategories_insensible_aux_accents', () => {
    const list = [cat('1', 'Télé-Québec'), cat('2', 'Sports'), cat('3', 'Cœur')];
    expect(filterCategories(list, 'TELE').map((c) => c.id)).toEqual(['1']);
    expect(filterCategories(list, 'coeur').map((c) => c.id)).toEqual(['3']);
    expect(filterCategories(list, '  ')).toEqual(list);
  });
});

describe('moveCategory', () => {
  it('moveCategory_haut_bas_bornes', () => {
    const ids = ['a', 'b', 'c'];
    expect(moveCategory(ids, 1, 0)).toEqual(['b', 'a', 'c']);
    expect(moveCategory(ids, 1, 2)).toEqual(['a', 'c', 'b']);
    expect(moveCategory(ids, 0, -1)).toEqual(['a', 'b', 'c']);
    expect(moveCategory(ids, 2, 5)).toEqual(['a', 'b', 'c']);
    expect(moveCategory(ids, 9, 0)).toEqual(['a', 'b', 'c']);
    expect(ids).toEqual(['a', 'b', 'c']);
  });
});

describe('hideAllExceptPlan', () => {
  it('hideAllExceptPlan_compte', () => {
    const list = [cat('1', 'A'), cat('2', 'B', { hiddenGlobal: true }), cat('3', 'C'), cat('4', 'D')];
    expect(hideAllExceptPlan(list, new Set(['1', '2']), true)).toEqual({ toHide: 2, toShow: 1 });
  });
  it('ignore les categories verrouillees en portee profil', () => {
    const list = [cat('1', 'A'), cat('2', 'B', { hiddenGlobal: true }), cat('3', 'C', { hiddenProfile: true })];
    expect(hideAllExceptPlan(list, new Set(['3']), false)).toEqual({ toHide: 1, toShow: 1 });
  });
});

describe('confirmText', () => {
  it('confirmText_seulement_pour_tout_le_monde', () => {
    expect(confirmText(false, 5)).toBeNull();
    expect(confirmText(true, 12)).toBe('Ces 12 catégories ne seront plus téléchargées par la télé, pour tout le monde. Continuer ?');
  });
});

describe('confirmText singulier', () => {
  it('confirmText_singulier_pour_une_categorie', () => {
    expect(confirmText(true, 1)).toBe('Cette catégorie ne sera plus téléchargée par la télé, pour tout le monde. Continuer ?');
    expect(confirmText(true, 2)).toMatch(/^Ces 2 catégories/);
    expect(confirmText(false, 1)).toBeNull();
  });
});

describe('createGate', () => {
  it('createGate_jeton_perime_apres_un_nouveau_rendu', () => {
    const g = createGate();
    const a = g.next();
    expect(g.isCurrent(a)).toBe(true);
    const b = g.next();
    expect(g.isCurrent(a)).toBe(false);
    expect(g.isCurrent(b)).toBe(true);
  });
});

describe('api', () => {
  it('orgHideAllExcept_envoie_p_null_pour_tout_le_monde', async () => {
    const { db, calls } = fakeDb({ org_hide_all_except: () => 7 });
    const n = await orgHideAllExcept(db, 'h1', null, ['a', 'b']);
    expect(n).toBe(7);
    expect(calls).toEqual([{ fn: 'org_hide_all_except', args: { h: 'h1', p: null, keep: ['a', 'b'] } }]);
  });
  it('erreur_acces_refuse_traduite', async () => {
    const { db } = fakeDb({ org_set_hidden: () => { throw new Error('acces-refuse'); } });
    await expect(orgSetHidden(db, 'h1', null, 'live_category', ['a'], true)).rejects.toThrow(/droit/);
  });
  it('orgCategories convertit les colonnes', async () => {
    const { db } = fakeDb({ org_categories: () => [{ id: 'c1', name: 'Sports', provider_position: 2, hidden_global: true, hidden_profile: false, channel_count: 9 }] });
    expect(await orgCategories(db, 'h1', 'p1')).toEqual([cat('c1', 'Sports', { providerPosition: 2, hiddenGlobal: true, channelCount: 9 })]);
  });
});
