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
