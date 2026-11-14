import { it, expect, beforeAll, afterAll } from 'vitest';
import type { Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { probe, validateSamples } from '../api/sonde';
import { pickProbeSamples, browserPlaybackPointless } from '../src/probe-plan';
import { startFauxXtream } from '../dev/faux-xtream';
import type { Sample } from '../src/summary';

let server: Server;
let base = '';
beforeAll(async () => {
  process.env.AUTORISER_LOCAL = '1';
  server = await startFauxXtream(0);
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});
afterAll(() => { delete process.env.AUTORISER_LOCAL; return new Promise<void>((r) => server.close(() => r())); });

const creds = () => ({ server: base, username: 'utilisateur-test', password: 'motdepasse-test' });
const s = (kind: Sample['kind'], id: string, ext: string): Sample => ({ kind, id, ext, label: `${kind} ${id}` });

it('sonde un direct ts et des mkv, une à la fois, codecs détectés', async () => {
  const r = await probe(creds(), [s('live', '101', 'ts'), s('movie', '203', 'mkv'), s('series', '3012', 'mkv')], { pauseMs: 0 });
  expect(r.map((x) => x.httpStatus)).toEqual([200, 200, 200]);
  expect(r[0].codecs).toEqual({ container: 'ts', video: ['h264'], audio: ['aac'] });
  expect(r[1].codecs).toEqual({ container: 'mkv', video: ['h264'], audio: ['eac3'] });
  expect(r.every((x) => x.bytes > 0)).toBe(true);
});

it('ne lit pas plus que maxBytes', async () => {
  const [r] = await probe(creds(), [s('movie', '203', 'mkv')], { pauseMs: 0, maxBytes: 4096 });
  expect(r.bytes).toBeLessThanOrEqual(4096);
  expect(r.bytes).toBeGreaterThan(0);
});

it('mauvais identifiants : statut rapporté, pas d\'exception', async () => {
  const [r] = await probe({ ...creds(), password: 'faux' }, [s('movie', '203', 'mkv')], { pauseMs: 0 });
  expect(r.httpStatus).toBe(404);
  expect(r.codecs.container).toBe('inconnu');
});

it('validateSamples', () => {
  expect(validateSamples([s('movie', '1', 'mkv')])).toEqual([s('movie', '1', 'mkv')]);
  expect(validateSamples([s('movie', 'abc', 'mkv')])).toBeNull();
  expect(validateSamples([s('movie', '1', 'exe')])).toBeNull();
  expect(validateSamples([{ ...s('movie', '1', 'mkv'), kind: 'x' }])).toBeNull();
  expect(validateSamples(Array.from({ length: 7 }, (_, i) => s('movie', String(i), 'mkv')))).toBeNull();
  expect(validateSamples('x')).toBeNull();
});

it('choix des échantillons : 1 direct en ts, 2 films mkv + 1 mp4, 2 épisodes', () => {
  const all = [s('live', '1', 'm3u8'), s('live', '2', 'm3u8'), s('movie', '10', 'mp4'), s('movie', '11', 'mkv'), s('movie', '12', 'mkv'), s('movie', '13', 'mkv'),
    s('series', '20', 'mkv'), s('series', '21', 'mp4'), s('series', '22', 'mkv')];
  expect(pickProbeSamples(all).map((x) => `${x.kind}:${x.id}:${x.ext}`)).toEqual([
    'live:1:ts', 'movie:11:mkv', 'movie:12:mkv', 'movie:10:mp4', 'series:20:mkv', 'series:22:mkv',
  ]);
});

it('lecture navigateur inutile si page https et fournisseur http', () => {
  expect(browserPlaybackPointless('https:', 'http')).toBe(true);
  expect(browserPlaybackPointless('http:', 'http')).toBe(false);
  expect(browserPlaybackPointless('https:', 'https')).toBe(false);
});

it('réessaie en se présentant comme VLC si le fournisseur refuse', async () => {
  const [r] = await probe({ ...creds(), username: 'exige-vlc' }, [s('movie', '203', 'mkv')], { pauseMs: 0 });
  expect(r.attempts.map((a) => a.status)).toEqual([404, 200]);
  expect(r.attempts[1].agent).toMatch(/^VLC/);
  expect(r.httpStatus).toBe(200);
  expect(r.codecs.container).toBe('mkv');
});

it('garde le début du message d\'erreur du fournisseur', async () => {
  const [r] = await probe({ ...creds(), password: 'faux' }, [s('movie', '203', 'mkv')], { pauseMs: 0 });
  expect(r.attempts.map((a) => a.status)).toEqual([404, 404]);
  expect(r.message).toContain('Stream not found');
});
