// @vitest-environment jsdom
import { beforeEach, expect, test, vi } from 'vitest';
import { mountGroups } from '../src/org/groups-view';
import { demoDb } from '../src/org/demo';
import type { Db } from '../src/flow';
let root: HTMLElement;
let db: Db;
let h: string;
beforeEach(() => {
  document.body.innerHTML = '<main></main>';
  root = document.querySelector('main')!;
  const d = demoDb();
  db = d.db;
  h = d.householdId;
  vi.stubGlobal('confirm', () => true);
});
const click = (id: string) => root.querySelector<HTMLButtonElement>(id)!.click();
const settle = async () => {
  await new Promise((r) => setTimeout(r, 20));
};
async function open() {
  await mountGroups({ db, householdId: h, profiles: [], scope: 'p1', root });
}
async function select() {
  root.querySelectorAll<HTMLInputElement>('.copy-row input')[0].click();
  root.querySelectorAll<HTMLInputElement>('.copy-row input')[1].click();
  click('#copies-merge');
}
test('editer_puis_recharger_conserve_le_groupe', async () => {
  await open();
  await select();
  click('#copies-save');
  await settle();
  await open();
  expect(root.textContent).toContain('Corrigé');
});
test('separer_garde_une_exclusion', async () => {
  await open();
  await select();
  click('#copies-save');
  await settle();
  click('.copy-separate');
  click('#copies-save');
  await settle();
  const r = await db.rpc('live_group_page', { h, after_id: null, lim: 500 });
  expect(JSON.stringify(r.data)).toContain('blocked_auto_keys');
  expect(JSON.stringify(r.data)).not.toContain('"groups":[]');
});
test('annuler_necrit_rien', async () => {
  await open();
  await select();
  click('#copies-cancel');
  const r = await db.rpc('live_group_page', { h, after_id: null, lim: 500 });
  expect((r.data as any).groups).toHaveLength(0);
});
test('conflit_affiche_recharger_sans_ecraser', async () => {
  await open();
  await select();
  await db.rpc('live_group_edit', {
    h,
    expected_rev: 0,
    replacement: { remove_ids: [], groups: [] },
  });
  click('#copies-save');
  await settle();
  expect(root.textContent).toContain('Recharger');
  expect(root.textContent).not.toContain('Enregistré.');
});
test('echec_reseau_ne_marque_pas_enregistre', async () => {
  await open();
  await select();
  const old = db.rpc.bind(db);
  db.rpc = (async (n, a) =>
    n === 'live_group_edit' ? { data: null, error: { message: 'network' } } : old(n, a)) as Db['rpc'];
  click('#copies-save');
  await settle();
  expect(root.textContent).not.toContain('Enregistré.');
  expect(root.textContent).toContain('Impossible');
});
test('nom malveillant affiche comme texte', async () => {
  const old = db.rpc.bind(db);
  db.rpc = (async (n, a) =>
    n === 'live_catalog_page'
      ? {
          data: {
            catalog_rev: 1,
            channels: [
              {
                id: 'x',
                category_id: 'c1',
                name: '<img onerror=alert(1)>',
                position: 0,
              },
            ],
            next_cursor: null,
          },
          error: null,
        }
      : old(n, a)) as Db['rpc'];
  await open();
  expect(root.textContent).toContain('<img onerror=alert(1)>');
  expect(root.querySelector('img')).toBeNull();
});
test('cache ancien visible sans autoriser une edition', async () => {
  await open();
  const old = db.rpc.bind(db);
  db.rpc = (async (n, a) =>
    n === 'live_group_page' ? { data: null, error: { message: 'network' } } : old(n, a)) as Db['rpc'];
  await open();
  expect(root.textContent).toContain('ancienne ou incomplète');
  expect(root.querySelectorAll('.copy-row').length).toBeGreaterThan(0);
  expect([...root.querySelectorAll<HTMLInputElement>('.copy-row input')].every((e) => e.disabled)).toBe(true);
});
test('restaurer confirme les autres corrections et conserve leurs exclusions', async () => {
  await open();
  await select();
  click('#copies-save');
  await settle();
  const before = await db.rpc('live_group_page', {
    h,
    after_id: null,
    lim: 500,
  });
  const groups = (before.data as any).groups;
  expect(groups.length).toBeGreaterThan(1);
  const confirm = vi.fn(() => false);
  vi.stubGlobal('confirm', confirm);
  click('.copy-restore');
  expect(confirm).toHaveBeenCalledOnce();
  expect(root.querySelector<HTMLElement>('#copies-preview')!.hidden).toBe(true);
  confirm.mockReturnValue(true);
  click('.copy-restore');
  click('#copies-save');
  await settle();
  const after = await db.rpc('live_group_page', {
    h,
    after_id: null,
    lim: 500,
  });
  expect((after.data as any).groups).toHaveLength(groups.length - 1);
  for (const g of (after.data as any).groups)
    expect(g.blocked_auto_keys).toEqual(groups.find((b: any) => b.id === g.id).blocked_auto_keys);
});
test('cent mille sources restent fenetrees y compris la copie figee masquee', async () => {
  const old = db.rpc.bind(db);
  const rows = Array.from({ length: 100000 }, (_, i) => ({
    id: `s${i}`,
    name: `Chaîne fictive ${i}`,
    category_id: i === 0 ? 'hidden' : 'visible',
    category_name: 'Catégorie fictive',
    position: i,
  }));
  db.rpc = (async (n, a: any) =>
    n === 'profile_prefs'
      ? {
          data: {
            group_copies: true,
            hidden: [{ item_type: 'live_category', item_id: 'hidden' }],
          },
          error: null,
        }
      : n === 'live_catalog_page'
        ? {
            data: {
              catalog_rev: 1,
              channels: rows.slice(
                a.after_position === null ? 0 : a.after_position + 1,
                (a.after_position === null ? 0 : a.after_position + 1) + a.lim,
              ),
              next_cursor:
                (a.after_position === null ? 0 : a.after_position + 1) + a.lim < rows.length
                  ? {
                      position: (a.after_position === null ? 0 : a.after_position + 1) + a.lim - 1,
                      id: 'cursor',
                    }
                  : null,
            },
            error: null,
          }
        : old(n, a)) as Db['rpc'];
  await open();
  expect(root.textContent).toContain('100000 chaînes');
  expect(root.querySelectorAll('.copy-row').length).toBeLessThan(20);
  expect(root.textContent).toContain('Chaîne fictive 0');
});
test('source disparue et exclusions conservent leur groupe en edition partielle', async () => {
  const { editCopies } = await import('../src/org/groups-view');
  const s = {
    rev: 1,
    rulesVersion: 1,
    groups: [
      {
        id: 'stable',
        title: 'Groupe',
        sourceIds: ['a', 'disparue'],
        blockedAutoKeys: ['ancienne'],
      },
    ],
  };
  const next = editCopies(
    s,
    [
      {
        id: 'a',
        name: 'Alpha HD',
        categoryId: 'c',
        categoryName: 'C',
        position: 0,
      },
      {
        id: 'b',
        name: 'Beta',
        categoryId: 'c',
        categoryName: 'C',
        position: 1,
      },
    ],
    new Set(['a', 'b']),
  );
  expect(next.groups.find((g) => g.id === 'stable')?.sourceIds).toEqual(['disparue']);
  expect(next.groups.find((g) => g.id === 'stable')?.blockedAutoKeys).toContain('ancienne');
});
test('nom corrige visible apres rechargement', async () => {
  await open();
  await select();
  const input = root.querySelector<HTMLInputElement>('#copies-title')!;
  input.value = 'Nom corrigé fictif';
  input.dispatchEvent(new Event('input'));
  click('#copies-save');
  await settle();
  await open();
  expect(root.querySelector('.copy-row')!.textContent).toContain('Nom corrigé fictif');
});

test('visibilite indisponible ne rend jamais le cache', async () => {
  await open();
  const old = db.rpc.bind(db);
  db.rpc = (async (n, a) =>
    n === 'profile_prefs' ? { data: null, error: { message: 'network' } } : old(n, a)) as Db['rpc'];
  await open();
  expect(root.querySelectorAll('.copy-row')).toHaveLength(0);
  expect(root.textContent).toContain('Réessayer');
});

test('separation volumineuse distribue les exclusions sans produit cartesien', async () => {
  const { editCopies } = await import('../src/org/groups-view');
  const sources = Array.from({ length: 1000 }, (_, i) => ({
    id: `id${i}`,
    name: `Nom ${i}`,
    categoryId: 'c',
    categoryName: 'C',
    position: i,
  }));
  const keys = sources.map((s) => `nom ${s.position}`);
  const snapshot = {
    rev: 1,
    rulesVersion: 1,
    groups: [{ id: 'stable', title: 'Groupe', sourceIds: sources.map((s) => s.id), blockedAutoKeys: keys }],
  };
  const next = editCopies(snapshot, sources, new Set(sources.map((s) => s.id)), true);
  expect(next.groups.reduce((n, g) => n + g.blockedAutoKeys.length, 0)).toBeLessThanOrEqual(2000);
  expect(new Set(next.groups.flatMap((g) => g.blockedAutoKeys))).toEqual(new Set(keys));
});

test('copie figee masquee reste selectionnable et correction commune interprofils', async () => {
  await mountGroups({
    db,
    householdId: h,
    profiles: [{ id: 'p1', name: 'Profil', position: 0 }],
    scope: null,
    root,
  });
  expect(root.textContent).toContain('Copies des chaînes');
  expect(root.querySelector('#copies-enabled')).toBeNull();
  const q = root.querySelector<HTMLInputElement>('#copies-search')!;
  q.value = 'Québec';
  q.dispatchEvent(new Event('input'));
  expect(root.querySelector('.copy-row')!.textContent).toContain('Masquée');
  expect(root.querySelector<HTMLInputElement>('.copy-row input')!.disabled).toBe(false);
  q.value = '';
  q.dispatchEvent(new Event('input'));
  await select();
  click('#copies-save');
  await settle();
  await mountGroups({ db, householdId: h, profiles: [], scope: 'p2', root });
  expect(root.textContent).toContain('Corrigé');
});

test('selection garde le focus sur sa source et permet le controle suivant', async () => {
  await open();
  const input = root.querySelector<HTMLInputElement>('.copy-row input')!;
  const source = input.dataset.source;
  input.focus();
  input.click();
  expect(document.activeElement).toBe(root.querySelector('.copy-row input'));
  expect((document.activeElement as HTMLInputElement).dataset.source).toBe(source);
  const next = document
    .activeElement!.closest('.copy-row')!
    .querySelector<HTMLButtonElement>('.copy-separate')!;
  next.focus();
  next.click();
  expect(document.activeElement).toBe(root.querySelector('.copy-separate'));
  expect(
    document.activeElement!.closest('.copy-row')!.querySelector<HTMLInputElement>('input')!.dataset.source,
  ).toBe(source);
});

test('restaurer garde le focus dans la meme source si le bouton disparait', async () => {
  await open();
  await select();
  const button = root.querySelector<HTMLButtonElement>('.copy-restore')!;
  const source = button.closest('.copy-row')!.querySelector<HTMLInputElement>('input')!.dataset.source;
  button.focus();
  button.click();
  expect(document.activeElement?.tagName).toBe('INPUT');
  expect((document.activeElement as HTMLInputElement).dataset.source).toBe(source);
});

test('defilement garde le controle actif ou revient a la liste hors fenetre', async () => {
  await open();
  const list = root.querySelector<HTMLElement>('#copies-list')!;
  const input = root.querySelector<HTMLInputElement>('.copy-row input')!;
  input.focus();
  list.dispatchEvent(new Event('scroll'));
  expect(document.activeElement).toBe(root.querySelector('.copy-row input'));
  list.scrollTop = 9600;
  list.dispatchEvent(new Event('scroll'));
  expect(document.activeElement).toBe(list);
  expect(root.querySelectorAll('.copy-row')).toHaveLength(14);
});
