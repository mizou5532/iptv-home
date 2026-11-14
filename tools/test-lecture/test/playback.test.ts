import { it, expect } from 'vitest';
import { classifyFailure, enginesFor } from '../src/playback';

it('contenu mixte prioritaire', () =>
  expect(classifyFailure({ pageProtocol: 'https:', url: 'http://x/1.mp4', mediaErrorCode: 4 })).toBe('contenu-mixte'));
it('format (code 4)', () => expect(classifyFailure({ pageProtocol: 'http:', url: 'http://x/1.mkv', mediaErrorCode: 4 })).toBe('format'));
it('format (code 3, décodage)', () => expect(classifyFailure({ pageProtocol: 'https:', url: 'https://x/1.mkv', mediaErrorCode: 3 })).toBe('format'));
it('délai', () => expect(classifyFailure({ pageProtocol: 'https:', url: 'https://x/1', timedOut: true })).toBe('delai'));
it('réseau du moteur', () => expect(classifyFailure({ pageProtocol: 'https:', url: 'https://x/1', engineNetworkError: true })).toBe('cors-ou-reseau'));
it('inconnu', () => expect(classifyFailure({ pageProtocol: 'https:', url: 'https://x/1' })).toBe('inconnu'));

it('moteurs du direct selon le support', () =>
  expect(enginesFor({ kind: 'live', id: '7', ext: 'm3u8', label: 'RDS' }, { hls: true, mpegts: false }))
    .toEqual([{ engine: 'natif', ext: 'm3u8' }, { engine: 'hls.js', ext: 'm3u8' }]));
it('direct avec mpegts', () =>
  expect(enginesFor({ kind: 'live', id: '7', ext: 'm3u8', label: 'RDS' }, { hls: false, mpegts: true }))
    .toEqual([{ engine: 'natif', ext: 'm3u8' }, { engine: 'mpegts.js', ext: 'ts' }]));
it('film : natif avec son extension', () =>
  expect(enginesFor({ kind: 'movie', id: '1', ext: 'mkv', label: 'F' }, { hls: true, mpegts: true }))
    .toEqual([{ engine: 'natif', ext: 'mkv' }]));

import { isProgressing, httpRefusal } from '../src/playback';

it('pas de succès sur un simple saut de position (hls.js au direct)', () => {
  expect(isProgressing({ paused: true, seeking: false, currentTime: 120, t0: null })).toBe(false);
  expect(isProgressing({ paused: false, seeking: false, currentTime: 120, t0: null })).toBe(false);
  expect(isProgressing({ paused: false, seeking: true, currentTime: 121, t0: 120 })).toBe(false);
  expect(isProgressing({ paused: false, seeking: false, currentTime: 120.2, t0: 120 })).toBe(false);
  expect(isProgressing({ paused: false, seeking: false, currentTime: 120.4, t0: 120 })).toBe(true);
});

it('refus du fournisseur (codes HTTP) distingué du CORS', () => {
  expect(classifyFailure({ pageProtocol: 'https:', url: 'https://x/1', engineNetworkError: true, httpStatus: 403 })).toBe('refus-fournisseur');
  expect(classifyFailure({ pageProtocol: 'https:', url: 'https://x/1', engineNetworkError: true, httpStatus: 509 })).toBe('refus-fournisseur');
  expect(classifyFailure({ pageProtocol: 'https:', url: 'https://x/1', engineNetworkError: true, httpStatus: 0 })).toBe('cors-ou-reseau');
  expect(httpRefusal(429)).toBe(true);
  expect(httpRefusal(404)).toBe(false);
});

it('extension inconnue : aucun essai', () =>
  expect(enginesFor({ kind: 'movie', id: '1', ext: 'inconnu', label: 'F' }, { hls: true, mpegts: true })).toEqual([]));
