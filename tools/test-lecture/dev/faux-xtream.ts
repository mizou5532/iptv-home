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

    const media = url.pathname.match(/^\/(movie|series)\/([^/]+)\/([^/]+)\/[^/]+\.mp4$/);
    if (media && decodeURIComponent(media[2]) === USER && decodeURIComponent(media[3]) === PASS && existsSync(MEDIA)) {
      res.writeHead(200, { 'Content-Type': 'video/mp4' });
      createReadStream(MEDIA).pipe(res);
      return;
    }
    res.writeHead(404); res.end();
  });
  return new Promise((resolve) => server.listen(port, () => resolve(server)));
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  startFauxXtream().then(() => console.log('Faux serveur Xtream : http://localhost:8899 (utilisateur-test / motdepasse-test)'));
}
