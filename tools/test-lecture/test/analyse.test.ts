import { it, expect, beforeAll, afterAll } from 'vitest';
import type { Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { analyse, AnalyseError } from '../api/analyse';
import { startFauxXtream, FAUX_CATALOGUE } from '../dev/faux-xtream';

let server: Server;
let base = '';
beforeAll(async () => {
  server = await startFauxXtream(0);
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});
afterAll(() => new Promise<void>((r) => server.close(() => r())));

it('résume le faux catalogue', async () => {
  const s = await analyse({ server: base, username: 'utilisateur-test', password: 'motdepasse-test' });
  expect(s.counts.movies).toBe(FAUX_CATALOGUE.movies.length);
  expect(s.counts.live).toBe(FAUX_CATALOGUE.live.length);
  expect(s.maxConnections).toBe(1);
  expect(s.samples.some((x) => x.kind === 'series')).toBe(true);
});

it('mauvais identifiants → fournisseur-refuse', async () => {
  await expect(analyse({ server: base, username: 'x', password: 'y' })).rejects.toEqual(new AnalyseError('fournisseur-refuse'));
});

it('serveur injoignable → fournisseur-injoignable', async () => {
  await expect(analyse({ server: 'http://127.0.0.1:1', username: 'x', password: 'y' })).rejects.toEqual(new AnalyseError('fournisseur-injoignable'));
});
