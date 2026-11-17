import { describe, it, expect } from 'vitest';
import { accountSummary, loadIptvAccount } from '../src/account';

const now = new Date(2026, 10, 17, 18, 0); // 17 nov. 2026, 18 h (heure locale)
const row = (expires: Date | null, conns: number | null = 2) => ({ expires_at: expires?.toISOString() ?? null, status: 'Active', max_connections: conns, checked_at: new Date(2026, 10, 17, 9).toISOString() });

describe('abonnement IPTV (Réglages)', () => {
  it('pas encore lu par la télé', () => {
    expect(accountSummary(null, now)).toMatchObject({ tone: 'muted', text: 'Date de fin pas encore connue' });
  });
  it('lointain, proche, très proche, aujourd’hui, terminé, sans fin', () => {
    expect(accountSummary(row(new Date(2027, 2, 12)), now)).toMatchObject({ tone: 'ok', text: 'Actif jusqu’au 12 mars 2027' });
    expect(accountSummary(row(new Date(2026, 11, 7)), now)).toMatchObject({ tone: 'warn', text: 'Actif jusqu’au 7 décembre 2026 (dans 20 jours)' });
    expect(accountSummary(row(new Date(2026, 10, 22, 12)), now)).toMatchObject({ tone: 'warn', text: 'Se termine le 22 novembre 2026 (dans 5 jours)' });
    expect(accountSummary(row(new Date(2026, 10, 18, 12)), now).text).toBe('Se termine le 18 novembre 2026 (dans 1 jour)');
    expect(accountSummary(row(new Date(2026, 10, 17, 23)), now)).toMatchObject({ tone: 'bad', text: 'L’abonnement se termine aujourd’hui' });
    expect(accountSummary(row(new Date(2026, 10, 15)), now)).toMatchObject({ tone: 'bad', text: 'Abonnement terminé depuis le 15 novembre 2026' });
    expect(accountSummary(row(null, 1), now)).toMatchObject({ tone: 'ok', text: 'Abonnement sans date de fin', detail: 'Vérifié par la télé le 17 novembre 2026 · 1 connexion à la fois' });
  });
  it('lecture : première ligne ou rien', async () => {
    const db = (data: unknown[]) => ({ from: () => ({ select: async () => ({ data, error: null }) }) });
    expect(await loadIptvAccount(db([]) as never)).toBeNull();
    expect((await loadIptvAccount(db([row(null)]) as never))!.status).toBe('Active');
  });
});
