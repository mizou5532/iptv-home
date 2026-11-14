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
