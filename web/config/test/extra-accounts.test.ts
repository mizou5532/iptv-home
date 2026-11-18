import { describe, it, expect } from 'vitest';
import { addExtraAccount, extraSummary, loadExtraAccounts, removeExtraAccount } from '../src/extra-accounts';

const row = (o: Partial<Parameters<typeof extraSummary>[0]> = {}) => ({
  id: 'a', position: 2, check_status: 'a_verifier' as const, check_reason: null, expires_at: null, checked_at: null, ...o,
});

describe('comptes IPTV supplémentaires (Réglages)', () => {
  it('état en clair : en attente, vérifié, refusé avec la raison', () => {
    expect(extraSummary(row())).toMatchObject({ tone: 'muted', text: 'En attente de la télé' });
    expect(extraSummary(row({ check_status: 'ok', expires_at: new Date(2027, 2, 12).toISOString() })))
      .toMatchObject({ tone: 'ok', text: 'Vérifié : prêt à servir', detail: 'Actif jusqu’au 12 mars 2027' });
    expect(extraSummary(row({ check_status: 'refuse', check_reason: 'catalogue' })).detail).toContain('Pas le même catalogue');
    expect(extraSummary(row({ check_status: 'refuse', check_reason: 'identifiants' })).detail).toContain('mot de passe incorrect');
  });

  it('lecture : jamais les identifiants, triés par place', async () => {
    let cols = '';
    const db = { from: () => ({ select: async (c: string) => { cols = c; return { data: [row({ id: 'b', position: 3 }), row()], error: null }; } }) };
    const list = await loadExtraAccounts(db as never);
    expect(list.map((a) => a.position)).toEqual([2, 3]);
    expect(cols).not.toMatch(/username|password/);
  });

  it('ajout et retrait : appels au serveur et messages compréhensibles', async () => {
    const calls: unknown[] = [];
    let error: { message: string } | null = null;
    const db = { rpc: async (fn: string, args: object) => { calls.push([fn, args]); return { data: null, error }; }, from: () => ({ select: async () => ({ data: [], error: null }) }) };
    await addExtraAccount(db, 'h', ' second ', 'mdp');
    await removeExtraAccount(db, 'h', 'a');
    expect(calls).toEqual([['add_extra_account', { h: 'h', username: 'second', password: 'mdp' }], ['remove_extra_account', { h: 'h', account: 'a' }]]);
    await expect(addExtraAccount(db, 'h', '', 'x')).rejects.toThrow('Remplis le nom');
    error = { message: 'trop-de-comptes' };
    await expect(addExtraAccount(db, 'h', 'c', 'x')).rejects.toThrow('4 comptes supplémentaires au plus');
    error = { message: 'compte-deja-ajoute' };
    await expect(addExtraAccount(db, 'h', 'c', 'x')).rejects.toThrow('déjà enregistré');
  });
});
