// Connecteur pour IA de la maison (protocole MCP). Adresse à donner à l'IA :
//   https://<projet>.supabase.co/functions/v1/assistant?cle=<clé créée sur la page iPhone>
// (ou la clé dans l'en-tête « Authorization: Bearer … » quand l'IA le permet). L'adresse est un secret.
// La clé est vérifiée par le serveur (ai_call) à chaque appel ; cette fonction n'a aucun droit particulier : elle
// appelle le serveur avec la clé publique, comme la page.
import { handle, type Rpc } from './logic.ts';

const URL_ = Deno.env.get('SUPABASE_URL')!;
const ANON = Deno.env.get('SUPABASE_ANON_KEY')!;

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' },
});

function keyOf(req: Request): string {
  const url = new URL(req.url);
  return url.searchParams.get('cle') ?? (req.headers.get('authorization') ?? '').replace(/^Bearer\s+/i, '');
}

Deno.serve(async (req) => {
  if (req.method === 'GET') return new Response('Connecteur IPTV de la maison : utilise POST (protocole MCP).', { status: 405, headers: { allow: 'POST' } });
  if (req.method === 'DELETE') return new Response(null, { status: 204 });
  if (req.method !== 'POST') return json({ error: 'POST seulement' }, 405);
  const key = keyOf(req);
  if (!/^[0-9a-f]{64}$/.test(key)) return json({ jsonrpc: '2.0', id: null, error: { code: -32001, message: 'Clé d’accès manquante : ajoute ?cle=… à l’adresse (page iPhone → Réglages → Accès pour les IA).' } }, 401);
  const body = await req.json().catch(() => null);
  if (!body) return json({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'JSON illisible' } }, 400);
  const call = async (tool: string, args: Record<string, unknown>) => {
    const r = await fetch(`${URL_}/rest/v1/rpc/ai_call`, {
      method: 'POST',
      headers: { apikey: ANON, authorization: `Bearer ${ANON}`, 'content-type': 'application/json' },
      body: JSON.stringify({ token: key, tool, args }),
    });
    const text = await r.text();
    if (!r.ok) throw new Error(text);
    return JSON.parse(text);
  };
  const messages: unknown[] = Array.isArray(body) ? body : [body];
  // Une à la fois, dans l'ordre (des changements groupés ne se chevauchent jamais) ; un élément illisible est refusé.
  const replies: object[] = [];
  for (const m of messages) {
    if (!m || typeof m !== 'object' || typeof (m as Rpc).method !== 'string') { replies.push({ jsonrpc: '2.0', id: null, error: { code: -32600, message: 'Requête invalide' } }); continue; }
    const r = await handle(m as Rpc, call);
    if (r !== null) replies.push(r);
  }
  if (!replies.length) return new Response(null, { status: 202 });
  return json(Array.isArray(body) ? replies : replies[0]);
});
