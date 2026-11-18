// Fonction « sport-horaires » : la télé demande d'un coup les horaires et classements dont elle a besoin
// ({ h: maison, urls: [...] }) ; le serveur rend ceux qu'il a gardés pour la maison et va chercher les autres chez la
// LNH et ESPN (8 à la fois), puis les garde. Aucun droit particulier : les lectures et écritures passent par la session
// de la télé (sport_cache_get / sport_cache_put, membres de la maison seulement). Données publiques seulement.
import { allowed, fresh, MAX_URLS } from './logic.ts';

const URL_ = Deno.env.get('SUPABASE_URL')!;
const ANON = Deno.env.get('SUPABASE_ANON_KEY')!;
const PARALLEL = 8;

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } });

Deno.serve(async (req) => {
  if (req.method !== 'POST') return json({ error: 'POST seulement' }, 405);
  const auth = req.headers.get('authorization') ?? '';
  const body = await req.json().catch(() => null) as { h?: unknown; urls?: unknown } | null;
  const h = typeof body?.h === 'string' && /^[0-9a-f-]{36}$/.test(body.h) ? body.h : null;
  const urls = Array.isArray(body?.urls) ? [...new Set(body!.urls.filter(allowed))].slice(0, MAX_URLS) : [];
  if (!h) return json({ error: 'maison-requise' }, 400);
  const rpc = (fn: string, args: unknown) => fetch(`${URL_}/rest/v1/rpc/${fn}`, {
    method: 'POST', headers: { apikey: ANON, authorization: auth, 'content-type': 'application/json' }, body: JSON.stringify(args),
  });
  const got = await rpc('sport_cache_get', { h, urls });
  // Pas membre de la maison (ou serveur indisponible) : rien n'est demandé à la LNH ni à ESPN.
  if (got.status === 401 || got.status === 403) return json({ error: 'acces-refuse' }, 403);
  if (!got.ok) return json({ error: 'indisponible' }, 502);
  const kept: { url: string; body: string; fetched_at: string }[] = await got.json();
  const now = Date.now();
  const out: Record<string, string> = {};
  const stale: Record<string, string> = {};
  for (const k of kept) (fresh(k.url, k.fetched_at, now) ? out : stale)[k.url] = k.body;
  const missing = urls.filter((u) => !(u in out));
  const fetched: { url: string; body: string }[] = [];
  let i = 0;
  async function worker() {
    while (i < missing.length) {
      const u = missing[i++];
      try {
        const r = await fetch(u, { signal: AbortSignal.timeout(6000), headers: { accept: 'application/json' } });
        const t = r.ok ? await r.text() : '';
        if (t.trimStart().startsWith('{')) { out[u] = t; fetched.push({ url: u, body: t }); }
        else if (u in stale) out[u] = stale[u];
      } catch {
        // Site injoignable : l'ancienne réponse gardée vaut mieux que rien ; sinon la télé ira la chercher elle-même.
        if (u in stale) out[u] = stale[u];
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(PARALLEL, missing.length) }, worker));
  if (fetched.length) await rpc('sport_cache_put', { h, items: fetched }).catch(() => null);
  return json({ bodies: out, fetched: fetched.length });
});
