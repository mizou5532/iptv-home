// Fonction media-meta : fiches TMDB (images, titre et résumé français) et note IMDb (OMDb) des films et séries.
// Appelée par la télé avec sa session ; réservée aux membres d'un foyer. Les clés TMDB_TOKEN et OMDB_KEY sont des
// secrets de la fonction : jamais dans l'appli ni dans la page. Résultats gardés dans public.media_meta.
import { cleanTitle, frenchText, keyOf, omdbRating, personPick, pickCandidate, pickImage, staleness, variants, type Candidate, type Kind, type Translation } from './logic.ts';

const URL_ = Deno.env.get('SUPABASE_URL')!;
const SERVICE = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!;
const TMDB = Deno.env.get('TMDB_TOKEN') ?? '';
const OMDB = Deno.env.get('OMDB_KEY') ?? '';
const MAX_ITEMS = 60;      // éléments par appel
const MAX_LOOKUPS = 12;    // recherches TMDB nouvelles par appel (le reste : « en attente », redemandé plus tard)
const PARALLEL = 4;

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const db = (path: string, init: RequestInit = {}) => fetch(`${URL_}/rest/v1/${path}`, {
  ...init, headers: { apikey: SERVICE, authorization: `Bearer ${SERVICE}`, 'content-type': 'application/json', ...(init.headers ?? {}) },
});

async function memberOf(req: Request): Promise<boolean> {
  const token = (req.headers.get('authorization') ?? '').replace(/^Bearer\s+/i, '');
  if (!token) return false;
  const u = await fetch(`${URL_}/auth/v1/user`, { headers: { apikey: SERVICE, authorization: `Bearer ${token}` } });
  if (!u.ok) return false;
  const id = (await u.json())?.id;
  if (typeof id !== 'string') return false;
  const r = await db(`household_members?user_id=eq.${id}&select=user_id&limit=1`);
  return r.ok && (await r.json()).length > 0;
}

async function tmdb(path: string): Promise<any> {
  const r = await fetch(`https://api.themoviedb.org/3${path}`, { headers: { authorization: `Bearer ${TMDB}`, accept: 'application/json' } });
  if (r.status === 404) return null;
  if (!r.ok) throw new Error(`tmdb ${r.status}`);
  return r.json();
}

async function omdb(imdbId: string) {
  if (!OMDB) return { rating: null, votes: null };
  const r = await fetch(`https://www.omdbapi.com/?i=${encodeURIComponent(imdbId)}&apikey=${encodeURIComponent(OMDB)}`);
  return omdbRating(r.ok ? await r.json() : null);
}

type Row = Record<string, unknown> & { key: string };

/** Cherche et remplit une fiche (TMDB), puis la note (OMDb). */
async function lookup(kind: Kind, title: string, year: number | null, tmdbId: number | null, key: string): Promise<Row> {
  const t = kind === 'movie' ? 'movie' : 'tv';
  let id = tmdbId;
  if (!id) {
    // Français d'abord ; puis anglais (un anime connu en français sous son nom japonais : « My Dress-Up Darling »).
    search: for (const lang of ['fr-CA', 'en-US']) {
      for (const v of variants(title, year)) {
        const q = `query=${encodeURIComponent(v.title)}&include_adult=false&language=${lang}`;
        const yearParam = v.year ? (t === 'movie' ? `&primary_release_year=${v.year}` : `&first_air_date_year=${v.year}`) : '';
        let res: Candidate[] = (await tmdb(`/search/${t}?${q}${yearParam}`))?.results ?? [];
        let c = pickCandidate(res, v.title, v.year);
        if (!c && yearParam) { res = (await tmdb(`/search/${t}?${q}`))?.results ?? []; c = pickCandidate(res, v.title, v.year); }
        if (c) { id = c.id; break search; }
      }
    }
  }
  const base = { key, kind, query_title: title, query_year: year, fetched_at: new Date().toISOString() };
  if (!id) return { ...base, status: 'not_found' };
  const d = await tmdb(`/${t}/${id}?language=fr-CA&append_to_response=images,external_ids,translations&include_image_language=fr,en,null`);
  if (!d) return { ...base, status: 'not_found' };
  const imdbId: string | null = d.imdb_id || d.external_ids?.imdb_id || null;
  const text = frenchText(d.translations?.translations as Translation[], d.title || d.name || null, d.overview || null, d.original_title || d.original_name || null, title);
  const date: string = d.release_date || d.first_air_date || '';
  const row: Row = {
    ...base, status: 'ok', tmdb_id: id, imdb_id: imdbId, title: text.title, overview: text.overview,
    year: Number(date.slice(0, 4)) || null,
    poster: pickImage(d.images?.posters, ['fr', 'en', null]) ?? d.poster_path ?? null,
    backdrop: pickImage(d.images?.backdrops, [null, 'fr', 'en']) ?? d.backdrop_path ?? null,
    logo: pickImage(d.images?.logos, ['fr', 'en', null], (p) => p.endsWith('.png')),
  };
  if (imdbId) { const r = await omdb(imdbId); Object.assign(row, { imdb_rating: r.rating, imdb_votes: r.votes, rating_at: new Date().toISOString() }); }
  return row;
}

async function pool<T>(items: T[], n: number, work: (t: T) => Promise<void>) {
  const queue = [...items];
  await Promise.all(Array.from({ length: Math.min(n, queue.length) }, async () => { while (queue.length) await work(queue.shift()!); }));
}

/**
 * Recherche par personne (acteur, actrice, réalisateur) : la personne la plus connue dont le nom correspond, et ses
 * films et séries (titre français et original, année). La télé cherche ensuite ces titres dans son catalogue.
 */
async function person(query: string) {
  const q = query.trim().slice(0, 80);
  if (q.length < 3) return { person: null, credits: [] };
  const found: any[] = (await tmdb(`/search/person?query=${encodeURIComponent(q)}&include_adult=false&language=fr-CA`))?.results ?? [];
  const p = personPick(found, q);
  if (!p) return { person: null, credits: [] };
  const c = await tmdb(`/person/${p.id}/combined_credits?language=fr-CA`);
  const credits = [
    ...((c?.cast ?? []) as any[]).map((x) => ({ ...x, role: 'acteur' })),
    ...((c?.crew ?? []) as any[]).filter((x) => x.job === 'Director').map((x) => ({ ...x, role: 'realisateur' })),
  ].filter((x) => x.media_type === 'movie' || x.media_type === 'tv')
    // Talk-shows, remises de prix, apparitions : pas des œuvres où la personne joue.
    .filter((x) => !(x.media_type === 'tv' && (x.genre_ids ?? []).some((g: number) => g === 10767 || g === 10763)))
    .sort((a, b) => (b.popularity ?? 0) - (a.popularity ?? 0))
    .slice(0, 150)
    .map((x) => ({
      kind: x.media_type === 'movie' ? 'movie' : 'series',
      title: x.title ?? x.name ?? '', original_title: x.original_title ?? x.original_name ?? null,
      year: Number(String(x.release_date ?? x.first_air_date ?? '').slice(0, 4)) || null,
      tmdb_id: x.id, role: x.role,
    }));
  return { person: { name: p.name, department: p.known_for_department ?? null }, credits };
}

Deno.serve(async (req) => {
  if (req.method !== 'POST') return json({ error: 'POST seulement' }, 405);
  if (!TMDB) return json({ error: 'clé TMDB manquante' }, 503);
  if (!(await memberOf(req))) return json({ error: 'réservé aux membres d’un foyer' }, 401);
  const body = await req.json().catch(() => null);
  if (typeof body?.person === 'string') {
    try { return json(await person(body.person)); } catch { return json({ person: null, credits: [] }); }
  }
  const raw: any[] = Array.isArray(body?.items) ? body.items.slice(0, MAX_ITEMS) : [];
  const items = raw.map((it) => {
    const kind: Kind = it?.kind === 'series' ? 'series' : 'movie';
    const c = cleanTitle(String(it?.title ?? ''), Number(it?.year) || null);
    const tmdbId = Number(it?.tmdb) > 0 ? Number(it.tmdb) : null;
    return { kind, ...c, tmdbId, key: tmdbId ? `${kind}:tmdb:${tmdbId}` : keyOf(kind, c.title, c.year) };
  }).filter((it) => it.title.length > 0);
  const keys = [...new Set(items.map((i) => i.key))];
  const rows = new Map<string, Row>();
  if (keys.length) {
    const r = await db(`media_meta?key=in.(${keys.map((k) => `"${k.replace(/"/g, '')}"`).join(',')})`);
    if (r.ok) for (const row of await r.json()) rows.set(row.key, row);
  }
  const now = Date.now();
  const todo: typeof items = [], ratings: Row[] = [];
  for (const it of items) {
    const s = staleness(rows.get(it.key) as any, now);
    if (s.meta) { if (!todo.some((x) => x.key === it.key)) todo.push(it); }
    else if (s.rating && !ratings.some((x) => x.key === it.key)) ratings.push(rows.get(it.key)!);
  }
  const fresh: Row[] = [];
  await pool(todo.slice(0, MAX_LOOKUPS), PARALLEL, async (it) => {
    try { const row = await lookup(it.kind, it.title, it.year, it.tmdbId, it.key); rows.set(it.key, row); fresh.push(row); } catch { /* réessayé au prochain appel */ }
  });
  await pool(ratings.slice(0, MAX_LOOKUPS), PARALLEL, async (row) => {
    try { const r = await omdb(String(row.imdb_id)); const next = { ...row, imdb_rating: r.rating ?? row.imdb_rating, imdb_votes: r.votes ?? row.imdb_votes, rating_at: new Date().toISOString() }; rows.set(row.key, next); fresh.push(next); } catch { /* idem */ }
  });
  if (fresh.length) {
    const cols = ['key', 'kind', 'query_title', 'query_year', 'status', 'tmdb_id', 'imdb_id', 'title', 'overview', 'year', 'poster', 'backdrop', 'logo', 'imdb_rating', 'imdb_votes', 'fetched_at', 'rating_at'];
    const payload = fresh.map((r) => Object.fromEntries(cols.map((c) => [c, r[c] ?? null])));
    await db('media_meta?on_conflict=key', { method: 'POST', headers: { prefer: 'resolution=merge-duplicates,return=minimal' }, body: JSON.stringify(payload) });
  }
  // Réponse alignée sur la demande : la fiche, { status: 'pending' } (pas encore cherchée) ou { status: 'not_found' }.
  const pick = (r: Row | undefined) => r ? { status: r.status, title: r.title ?? null, overview: r.overview ?? null, year: r.year ?? null,
    poster: r.poster ?? null, backdrop: r.backdrop ?? null, logo: r.logo ?? null, imdb_rating: r.imdb_rating != null ? Number(r.imdb_rating) : null,
    tmdb_id: r.tmdb_id ?? null } : { status: 'pending' };
  const byKey = new Map(items.map((i) => [i.key, i]));
  return json({ items: raw.map((it) => {
    const kind: Kind = it?.kind === 'series' ? 'series' : 'movie';
    const c = cleanTitle(String(it?.title ?? ''), Number(it?.year) || null);
    const tmdbId = Number(it?.tmdb) > 0 ? Number(it.tmdb) : null;
    const key = tmdbId ? `${kind}:tmdb:${tmdbId}` : keyOf(kind, c.title, c.year);
    return byKey.has(key) ? pick(rows.get(key)) : { status: 'not_found' };
  }) });
});
