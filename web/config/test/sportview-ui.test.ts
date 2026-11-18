// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest';
import { openSportViewEditor } from '../src/sportview-ui';
import { FEATURED, sportViewOf } from '../src/sportview';
import type { SettingsDb } from '../src/avatar';
import type { ProfileHome } from '../src/profiles';

const settle = () => new Promise((r) => setTimeout(r, 10));
let root: HTMLElement;
let saved: Record<string, unknown>[];
let fail = false;
const db: SettingsDb = {
  from: () => ({ update: (v: { settings: Record<string, unknown> }) => ({ eq: () => ({ select: async () => {
    if (fail) return { data: null, error: { message: 'panne' } };
    saved.push(v.settings); return { data: [{ id: 'p' }], error: null };
  } }) }) }),
} as unknown as SettingsDb;
const profile = (settings: Record<string, unknown> = {}): ProfileHome => ({ id: 'p', name: 'Hamza', home: 'sport', position: 0, color: '#3366ff', settings });
const btn = (sel: string) => root.querySelector<HTMLButtonElement>(sel)!;

beforeEach(() => { document.body.innerHTML = '<main></main>'; root = document.querySelector('main')!; root.scrollIntoView = () => {}; saved = []; fail = false; });

describe('Réglages du sport : affiche et scores cachés', () => {
  it('trois choix pour le match à l’affiche, « Automatique » par défaut, avec son explication', () => {
    openSportViewEditor(root, profile(), db, () => {});
    const buttons = [...root.querySelectorAll<HTMLButtonElement>('button[data-featured]')];
    expect(buttons.map((b) => b.textContent)).toEqual(['Automatique', 'Mes équipes', 'Le direct']);
    expect(btn('button[data-featured="auto"]').getAttribute('aria-pressed')).toBe('true');
    expect(root.textContent).toContain(FEATURED[0]!.hint);
  });

  it('choisir « Le direct » puis « Automatique » : enregistré, le reste des réglages gardé', async () => {
    const p = profile({ avatar_v: 3, sport_view: { theme: 'sombre' } });
    openSportViewEditor(root, p, db, () => {});
    btn('button[data-featured="direct"]').click(); await settle();
    expect(saved.at(-1)).toMatchObject({ avatar_v: 3, sport_view: { theme: 'sombre', featured: 'direct' } });
    expect(btn('button[data-featured="direct"]').getAttribute('aria-pressed')).toBe('true');
    btn('button[data-featured="auto"]').click(); await settle();
    expect((saved.at(-1)!.sport_view as Record<string, unknown>).featured).toBe('auto');
  });

  it('cacher les scores : Oui / Non, explication, enregistré, message', async () => {
    let last: Record<string, unknown> = {};
    openSportViewEditor(root, profile(), db, (s) => { last = s; });
    expect(btn('button[data-hidescores="0"]').getAttribute('aria-pressed')).toBe('true');
    expect(root.textContent).toContain('Scores et classements visibles.');
    btn('button[data-hidescores="1"]').click(); await settle();
    expect(sportViewOf(last).hide_scores).toBe(true);
    expect(root.textContent).toContain('touche Info');
    expect(root.querySelector('#svmsg')!.textContent).toContain('Scores cachés.');
    btn('button[data-hidescores="0"]').click(); await settle();
    expect(sportViewOf(last).hide_scores).toBe(false);
    expect(root.querySelector('#svmsg')!.textContent).toContain('Scores visibles.');
  });

  it('enregistrement impossible : message d’erreur, rien de changé à l’écran', async () => {
    fail = true;
    openSportViewEditor(root, profile(), db, () => {});
    btn('button[data-hidescores="1"]').click(); await settle();
    expect(root.querySelector('#svmsg')!.className).toContain('error');
    expect(btn('button[data-hidescores="0"]').getAttribute('aria-pressed')).toBe('true');
    expect(saved).toEqual([]);
  });

  it('réglage déjà enregistré « mes_equipes » (ancienne page) : gardé tel quel', () => {
    openSportViewEditor(root, profile({ sport_view: { featured: 'mes_equipes', hide_scores: true } }), db, () => {});
    expect(btn('button[data-featured="mes_equipes"]').getAttribute('aria-pressed')).toBe('true');
    expect(btn('button[data-hidescores="1"]').getAttribute('aria-pressed')).toBe('true');
  });
});
