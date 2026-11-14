import { it, expect, afterEach } from 'vitest';
import { validateRequest } from '../api/guard';

afterEach(() => { delete process.env.AUTORISER_LOCAL; });

it.each(['http://localhost:8080', 'http://127.0.0.1', 'http://192.168.1.10:8080', 'http://10.0.0.5', 'http://172.20.1.1', 'http://169.254.169.254', 'http://[::1]:8080', 'http://[fd00::1]', 'ftp://tv.exemple.com'])
  ('refuse %s', (server) => expect(validateRequest({ server, username: 'u', password: 'p' }).ok).toBe(false));

it('accepte un serveur public et le normalise', () => {
  const r = validateRequest({ server: 'tv.exemple.com:8080/', username: 'u', password: 'p' });
  expect(r).toEqual({ ok: true, creds: { server: 'http://tv.exemple.com:8080', username: 'u', password: 'p' } });
});

it('accepte 172.32.x (hors plage privée)', () => {
  expect(validateRequest({ server: 'http://172.32.0.1', username: 'u', password: 'p' }).ok).toBe(true);
});

it('refuse un corps incomplet', () => expect(validateRequest({ server: 'x.com' }).ok).toBe(false));
it('refuse un corps non objet', () => expect(validateRequest('x').ok).toBe(false));

it('autorise localhost avec AUTORISER_LOCAL=1', () => {
  process.env.AUTORISER_LOCAL = '1';
  expect(validateRequest({ server: 'http://localhost:8899', username: 'u', password: 'p' }).ok).toBe(true);
});

it.each(['http://localhost.:9001', 'http://[::127.0.0.1]', 'http://100.64.1.1', 'http://a:b@tv.exemple.com'])
  ('refuse aussi %s', (server) => expect(validateRequest({ server, username: 'u', password: 'p' }).ok).toBe(false));

import { assertPublicUrl } from '../api/guard';
const fakeLookup = (map: Record<string, string[]>) => async (host: string) => (map[host] ?? []).map((address) => ({ address, family: address.includes(':') ? 6 : 4 }));

it('refuse un nom qui pointe vers une adresse privée', async () => {
  await expect(assertPublicUrl('http://127.0.0.1.nip.io/player_api.php', fakeLookup({ '127.0.0.1.nip.io': ['127.0.0.1'] }))).rejects.toThrow('adresse-refusee');
});
it('accepte un nom qui pointe vers une adresse publique', async () => {
  await expect(assertPublicUrl('http://tv.exemple.com/player_api.php', fakeLookup({ 'tv.exemple.com': ['93.184.216.34'] }))).resolves.toBeUndefined();
});
it('refuse si une des adresses est privée', async () => {
  await expect(assertPublicUrl('http://x.com/', fakeLookup({ 'x.com': ['93.184.216.34', '10.0.0.1'] }))).rejects.toThrow('adresse-refusee');
});
