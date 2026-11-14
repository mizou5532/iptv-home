import { it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { detectCodecs } from '../src/codecs';

const fx = (name: string) => new Uint8Array(readFileSync(new URL(`./fixtures/${name}`, import.meta.url)));

it('MKV h264 + eac3 (début de fichier réel)', () =>
  expect(detectCodecs(fx('debut-a.mkv'))).toEqual({ container: 'mkv', video: ['h264'], audio: ['eac3'] }));
it('MP4 hevc + aac', () =>
  expect(detectCodecs(fx('debut-b.mp4'))).toEqual({ container: 'mp4', video: ['hevc'], audio: ['aac'] }));
it('TS h264 + aac (PMT)', () =>
  expect(detectCodecs(fx('debut-c.mpegts'))).toEqual({ container: 'ts', video: ['h264'], audio: ['aac'] }));
it('octets quelconques', () =>
  expect(detectCodecs(new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8]))).toEqual({ container: 'inconnu', video: [], audio: [] }));
it('vide', () => expect(detectCodecs(new Uint8Array())).toEqual({ container: 'inconnu', video: [], audio: [] }));

it('début de 2 Mo (taille réelle de la sonde) sans planter', () => {
  const big = new Uint8Array(2 * 1024 * 1024);
  big.set(fx('debut-b.mp4'));
  expect(detectCodecs(big).video).toEqual(['hevc']);
});
