import { it, expect } from 'vitest';
import { buildReport, type TestRun } from '../src/report';
import type { CatalogSummary } from '../src/summary';

const summary: CatalogSummary = {
  serverProtocol: 'http', maxConnections: 1, allowedOutputFormats: ['m3u8', 'ts'],
  counts: { live: 900, movies: 12000, series: 3000 },
  movieExtensions: { mkv: 8000, mp4: 4000 }, episodeExtensions: { mkv: 10 },
  samples: [],
};

function runAvecDetail(detail: string): TestRun {
  return {
    date: '2026-11-14T20:00:00Z', userAgent: 'Safari iPhone', pageProtocol: 'https:', directApi: 'contenu-mixte', summary,
    results: [
      { sample: { kind: 'movie', id: '1', ext: 'mp4', label: 'Film A' }, engine: 'natif', ok: true, msToFirstFrame: 1200 },
      { sample: { kind: 'movie', id: '2', ext: 'mkv', label: 'Film B' }, engine: 'natif', ok: false, reason: 'format', detail },
    ],
  };
}

it('le rapport ne contient pas les identifiants', () => {
  const r = buildReport(runAvecDetail('échec http://x/movie/jean%40x/secret/1.mkv'), { username: 'jean@x', password: 'secret' });
  expect(r).not.toContain('secret');
  expect(r).not.toContain('jean');
  expect(r).toContain('1 lectures réussies sur 2');
});

it('le rapport contient les chiffres clés et le tableau', () => {
  const r = buildReport(runAvecDetail(''), { username: 'u1', password: 'p1' });
  expect(r).toContain('Safari iPhone');
  expect(r).toContain('12000');
  expect(r).toContain('mkv : 8000');
  expect(r).toContain('| Type | Extension | Moteur | Résultat | Raison | Délai |');
  expect(r).toContain('| Film | mkv | natif | ✗ | format |');
});

it('rapport avec erreur d\'analyse', () => {
  const run = { ...runAvecDetail(''), summary: { error: 'fournisseur-refuse' }, results: [] };
  const r = buildReport(run, { username: 'u1', password: 'p1' });
  expect(r).toContain('fournisseur-refuse');
  expect(r).toContain('0 lectures réussies sur 0');
});

it('section sonde, sans identifiants', () => {
  const run = { ...runAvecDetail(''), results: [], probes: [
    { sample: { kind: 'movie' as const, id: '1', ext: 'mkv', label: 'Film u1' }, httpStatus: 200, contentType: 'video/x-matroska', bytes: 2097152, codecs: { container: 'mkv' as const, video: ['h264'], audio: ['eac3'] } },
    { sample: { kind: 'live' as const, id: '2', ext: 'ts', label: 'RDS' }, httpStatus: null, contentType: '', bytes: 0, codecs: { container: 'inconnu' as const, video: [], audio: [] }, error: 'fournisseur-injoignable' },
  ] };
  const r = buildReport(run, { username: 'u1', password: 'p1' });
  expect(r).toContain('## Sonde depuis un serveur');
  expect(r).toContain('| Type | Extension | HTTP | Octets reçus | Conteneur | Vidéo | Audio |');
  expect(r).toContain('| Film | mkv | 200 | 2097152 | mkv | h264 | eac3 |');
  expect(r).toContain('| Direct | ts | fournisseur-injoignable | 0 | inconnu | — | — |');
  expect(r).not.toContain('u1');
});

it('lecture navigateur non testée', () => {
  const r = buildReport({ ...runAvecDetail(''), results: [], browserSkipped: true }, { username: 'u1', password: 'p1' });
  expect(r).toContain('non testée');
});
