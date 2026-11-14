// Faux serveur Xtream pour développer et tester sans vrai compte.
// Identifiants acceptés : utilisateur-test / motdepasse-test
import { createServer, type Server } from 'node:http';
import { createReadStream, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const USER = 'utilisateur-test';
const PASS = 'motdepasse-test';
const here = dirname(fileURLToPath(import.meta.url));
const MEDIA = join(here, 'media', 'test.mp4');
const FIXTURES = join(here, '..', 'test', 'fixtures');
const FAKE_FILES: Record<string, string> = { mkv: join(FIXTURES, 'debut-a.mkv'), ts: join(FIXTURES, 'debut-c.mpegts') };

export const FAUX_CATALOGUE = {
  live: [1, 2, 3, 4, 5].map((i) => ({ stream_id: 100 + i, name: `Chaîne test ${i}` })),
  movies: [
    { stream_id: 201, name: 'Film MP4 A', container_extension: 'mp4' },
    { stream_id: 202, name: 'Film MP4 B', container_extension: 'mp4' },
    { stream_id: 203, name: 'Film MKV', container_extension: 'mkv' },
    { stream_id: 204, name: 'Film AVI', container_extension: 'avi' },
  ],
  series: [{ series_id: 301, name: 'Série A' }, { series_id: 302, name: 'Série B' }],
  seriesInfo: {
    '301': { info: { name: 'Série A' }, episodes: { '1': [{ id: '3011', title: 'É1', container_extension: 'mp4' }, { id: '3012', title: 'É2', container_extension: 'mkv' }] } },
    '302': { info: { name: 'Série B' }, episodes: { '1': [{ id: '3021', title: 'É1', container_extension: 'mp4' }] } },
  } as Record<string, unknown>,
};

export function startFauxXtream(port = 8899): Promise<Server> {
  const server = createServer((req, res) => {
    const url = new URL(req.url ?? '/', 'http://localhost');
    const json = (body: unknown) => { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); };

    if (url.pathname === '/player_api.php') {
      const who = url.searchParams.get('username');
      if (who === 'redirige') { res.writeHead(302, { Location: 'http://169.254.169.254/player_api.php' }); res.end(); return; }
      if (who === 'utilisateur-lent') { setTimeout(() => json({ user_info: { auth: 1 } }), 1000); return; }
      const ok = url.searchParams.get('username') === USER && url.searchParams.get('password') === PASS;
      if (!ok) return json({ user_info: { auth: 0 } });
      switch (url.searchParams.get('action')) {
        case null: return json({ user_info: { auth: 1, max_connections: '1', allowed_output_formats: ['m3u8', 'ts'] }, server_info: { server_protocol: 'http' } });
        case 'get_live_streams': return json(FAUX_CATALOGUE.live);
        case 'get_vod_streams': return json(FAUX_CATALOGUE.movies);
        case 'get_series': return json(FAUX_CATALOGUE.series);
        case 'get_series_info': return json(FAUX_CATALOGUE.seriesInfo[url.searchParams.get('series_id') ?? ''] ?? {});
        default: return json([]);
      }
    }

    const ua = String(req.headers['user-agent'] ?? '');
    const allowed = (u: string, p: string) => decodeURIComponent(p) === PASS
      && (decodeURIComponent(u) === USER || (decodeURIComponent(u) === 'exige-vlc' && ua.startsWith('VLC')));
    const media = url.pathname.match(/^\/(movie|series)\/([^/]+)\/([^/]+)\/[^/]+\.mp4$/);
    if (media && allowed(media[2], media[3]) && existsSync(MEDIA)) {
      res.writeHead(200, { 'Content-Type': 'video/mp4' });
      createReadStream(MEDIA).pipe(res);
      return;
    }
    const other = url.pathname.match(/^\/(movie|series|live)\/([^/]+)\/([^/]+)\/[^/]+\.(mkv|ts)$/);
    if (other && allowed(other[2], other[3])) {
      res.writeHead(200, { 'Content-Type': other[4] === 'mkv' ? 'video/x-matroska' : 'video/mp2t' });
      createReadStream(FAKE_FILES[other[4]]).pipe(res);
      return;
    }
    res.writeHead(404, { 'Content-Type': 'text/html' }); res.end('<html><body><h1>Stream not found</h1></body></html>');
  });
  return new Promise((resolve) => server.listen(port, () => resolve(server)));
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  startFauxXtream().then(() => console.log('Faux serveur Xtream : http://localhost:8899 (utilisateur-test / motdepasse-test)'));
}
