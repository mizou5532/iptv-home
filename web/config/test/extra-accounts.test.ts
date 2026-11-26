import { describe, it, expect } from 'vitest';
import { addExtraAccount, extraSummary, loadExtraAccounts, parseProviderLink, removeExtraAccount } from '../src/extra-accounts';

const row = (o: Partial<Parameters<typeof extraSummary>[0]> = {}) => ({
  id: 'a', position: 2, check_status: 'a_verifier' as const, check_reason: null, expires_at: null, checked_at: null, ...o,
});

describe('comptes IPTV supplémentaires (Réglages)', () => {
  const now = new Date(2026, 10, 18, 18, 0);
  const ok = (end: Date | null) => row({ check_status: 'ok', expires_at: end?.toISOString() ?? null, checked_at: new Date(2026, 10, 18, 9).toISOString() });

  it('état en clair : en attente, refusé avec la raison (films, séries, chaînes, expiré)', () => {
    expect(extraSummary(row(), now)).toMatchObject({ tone: 'muted', text: 'En attente de la télé' });
    expect(extraSummary(row({ check_status: 'refuse', check_reason: 'catalogue' }), now).detail).toContain('Pas le même catalogue');
    expect(extraSummary(row({ check_status: 'refuse', check_reason: 'identifiants' }), now).detail).toContain('mot de passe incorrect');
    expect(extraSummary(row({ check_status: 'refuse', check_reason: 'films' }), now).detail).toContain('pas les mêmes films');
    expect(extraSummary(row({ check_status: 'refuse', check_reason: 'series' }), now).detail).toContain('pas les mêmes séries');
    expect(extraSummary(row({ check_status: 'refuse', check_reason: 'films_series' }), now).detail).toContain('ni les mêmes séries');
    expect(extraSummary(row({ check_status: 'refuse', check_reason: 'expire', expires_at: new Date(2026, 9, 1).toISOString() }), now).detail)
      .toBe('Abonnement expiré ou inactif. Fin le 1 octobre 2026.');
  });

  it('jours restants comme le compte principal : vert, orange à 30 jours, rouge à 7, terminé', () => {
    expect(extraSummary(ok(new Date(2027, 2, 12)), now)).toMatchObject({ tone: 'ok', text: 'Actif jusqu’au 12 mars 2027 (dans 114 jours)', detail: 'Prêt à servir · vérifié par la télé le 18 novembre 2026' });
    expect(extraSummary(ok(new Date(2026, 11, 8)), now)).toMatchObject({ tone: 'warn', text: 'Actif jusqu’au 8 décembre 2026 (dans 20 jours)' });
    expect(extraSummary(ok(new Date(2026, 10, 19, 12)), now)).toMatchObject({ tone: 'bad', text: 'Se termine le 19 novembre 2026 (dans 1 jour)' });
    expect(extraSummary(ok(new Date(2026, 10, 10)), now)).toMatchObject({ tone: 'bad', text: 'Abonnement terminé depuis le 10 novembre 2026' });
    expect(extraSummary(ok(null), now)).toMatchObject({ tone: 'ok', text: 'Vérifié · abonnement sans date de fin' });
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
    error = { message: 'adresse-invalide' };
    await expect(addExtraAccount(db, 'h', 'c', 'x', 'http://a b')).rejects.toThrow('adresse du lien');
  });

  it('lien propre à un abonnement (26 nov.) : adresse envoyée, lien complet du fournisseur compris', async () => {
    const calls: unknown[] = [];
    const db = { rpc: async (fn: string, args: object) => { calls.push([fn, args]); return { data: null, error: null }; }, from: () => ({ select: async () => ({ data: [], error: null }) }) };
    await addExtraAccount(db, 'h', 'deux', 'pw', ' http://lien2.exemple:8080/ ');
    await addExtraAccount(db, 'h', '', '', 'http://lien3.exemple:2095/get.php?username=trois&password=p%40ss&type=m3u_plus&output=ts');
    await addExtraAccount(db, 'h', 'quatre', 'pw', '');
    expect(calls).toEqual([
      ['add_extra_account', { h: 'h', username: 'deux', password: 'pw', server: 'http://lien2.exemple:8080' }],
      ['add_extra_account', { h: 'h', username: 'trois', password: 'p@ss', server: 'http://lien3.exemple:2095' }],
      ['add_extra_account', { h: 'h', username: 'quatre', password: 'pw' }],
    ]);
    expect(parseProviderLink('exemple.com:8080')).toEqual({ server: 'http://exemple.com:8080' });
    expect(parseProviderLink('   ')).toEqual({ server: '' });
  });
});
