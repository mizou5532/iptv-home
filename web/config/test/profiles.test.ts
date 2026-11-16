import { describe, it, expect } from 'vitest';
import { loadProfileHomes, setProfileHome, toHome, type ProfilesDb } from '../src/profiles';
import type { DbResult } from '../src/flow';

function fakeDb(rows: object[], updateResult: DbResult = { data: [{ id: 'p1' }], error: null }) {
  const updates: { values: object; column: string; value: string }[] = [];
  const db: ProfilesDb = {
    from: () => ({
      select: async () => ({ data: rows, error: null }),
      update: (values) => ({ eq: (column, value) => ({ select: async () => { updates.push({ values, column, value }); return updateResult; } }) }),
    }),
  };
  return { db, updates };
}

describe('onglet d’ouverture des profils', () => {
  it('lit les profils dans l’ordre et ramène les anciennes valeurs à Direct', async () => {
    const { db } = fakeDb([
      { id: 'b', name: 'Maha', home: 'series', position: 1 },
      { id: 'a', name: 'Hamza', home: 'mixte', position: 0 },
      { id: 'c', name: 'Mahmoud', home: null, position: 2 },
    ]);
    expect(await loadProfileHomes(db)).toEqual([
      { id: 'a', name: 'Hamza', home: 'direct', position: 0 },
      { id: 'b', name: 'Maha', home: 'series', position: 1 },
      { id: 'c', name: 'Mahmoud', home: 'direct', position: 2 },
    ]);
  });
  it('enregistre le choix sur le bon profil', async () => {
    const { db, updates } = fakeDb([]);
    await setProfileHome(db, 'p1', 'sport');
    expect(updates).toEqual([{ values: { home: 'sport' }, column: 'id', value: 'p1' }]);
  });
  it('signale un profil introuvable ou une erreur', async () => {
    await expect(setProfileHome(fakeDb([], { data: [], error: null }).db, 'p1', 'films')).rejects.toThrow(/n'existe plus/);
    await expect(setProfileHome(fakeDb([], { data: null, error: { message: 'x' } }).db, 'p1', 'films')).rejects.toThrow(/enregistrer/);
  });
  it('refuse un onglet inconnu sans appel', async () => {
    const { db, updates } = fakeDb([]);
    await expect(setProfileHome(db, 'p1', 'radio' as never)).rejects.toThrow();
    expect(updates).toEqual([]);
    expect(toHome('films')).toBe('films');
  });
});
