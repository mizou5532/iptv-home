// Fonction « sport-horaires » : la télé demande d'un coup les horaires et classements dont elle a besoin
// ({ h: maison, urls: [...] }) ; le serveur rend ceux qu'il a gardés pour la maison et va chercher les autres chez la
// LNH et ESPN (8 à la fois), puis les garde. Aucun droit particulier : les lectures et écritures passent par la session
// de la télé (sport_cache_get / sport_cache_put, membres de la maison seulement). Données publiques seulement.
// Toute la logique est dans logic.ts (testée) ; ici, seulement les accès réseau.
import { relay } from './logic.ts';

const URL_ = Deno.env.get('SUPABASE_URL')!;
const ANON = Deno.env.get('SUPABASE_ANON_KEY')!;

Deno.serve(async (req) => {
  const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } });
  if (req.method !== 'POST') return json({ error: 'POST seulement' }, 405);
  const auth = req.headers.get('authorization') ?? '';
  const rpc = (fn: string, args: unknown) => fetch(`${URL_}/rest/v1/rpc/${fn}`, {
    method: 'POST', headers: { apikey: ANON, authorization: auth, 'content-type': 'application/json' }, body: JSON.stringify(args),
  });
  const input = await req.json().catch(() => null);
  const answer = await relay(input, {
    get: async (h, urls) => {
      const r = await rpc('sport_cache_get', { h, urls });
      if (r.status === 401 || r.status === 403) return null;
      if (!r.ok) throw new Error(`mémoire ${r.status}`);
      return await r.json();
    },
    put: async (h, items) => { await rpc('sport_cache_put', { h, items }); },
    fetchUrl: async (u) => {
      const r = await fetch(u, { signal: AbortSignal.timeout(6000), headers: { accept: 'application/json' } });
      return { ok: r.ok, text: r.ok ? await r.text() : '' };
    },
    now: () => Date.now(),
  });
  return json(answer.body, answer.status);
});
