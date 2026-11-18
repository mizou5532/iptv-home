-- Horaires du sport préparés par le serveur (19 nov. 2026, demande du propriétaire « 1 »). Au lieu de jusqu'à 59
-- demandes par télé à ESPN et à la LNH, la télé demande tout d'un coup à la fonction « sport-horaires » ; le serveur
-- garde les réponses pour toute la maison (2 min pour aujourd'hui, quelques heures pour les jours suivants et les
-- classements). Seulement des données publiques (horaires, scores, classements) : aucun identifiant IPTV.
--
-- Une mémoire par maison : une maison ne peut jamais changer ce que voit une autre.

create table public.sport_cache (
  household_id uuid not null references public.households on delete cascade,
  url text not null,
  body text not null,
  fetched_at timestamptz not null default now(),
  primary key (household_id, url)
);
alter table public.sport_cache enable row level security;
revoke all on public.sport_cache from public, anon, authenticated;

/** Adresses acceptées : horaires et classements de la LNH et d'ESPN seulement. */
create function public.sport_url_ok(u text) returns boolean
language sql immutable as $$
  select u is not null and char_length(u) <= 300 and (
    u ~ '^https://api-web\.nhle\.com/v1/(schedule/[0-9]{4}-[0-9]{2}-[0-9]{2}|standings/now)$'
    or u ~ '^https://site\.api\.espn\.com/apis/site/v2/sports/[a-z]+/[a-z0-9._]+/scoreboard\?dates=[0-9]{8}$'
    or u ~ '^https://site\.api\.espn\.com/apis/v2/sports/[a-z]+/[a-z0-9._]+/standings$'
  )
$$;

/** Réponses gardées pour la maison [h] (membres seulement). */
create function public.sport_cache_get(h uuid, urls text[]) returns table (url text, body text, fetched_at timestamptz)
language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_member(h) then raise exception 'acces-refuse' using errcode = '42501'; end if;
  return query select c.url, c.body, c.fetched_at from public.sport_cache c where c.household_id = h and c.url = any(urls);
end
$$;
revoke all on function public.sport_cache_get(uuid, text[]) from public, anon;
grant execute on function public.sport_cache_get(uuid, text[]) to authenticated;

/** Garde des réponses fraîches : [items] = [{ "url": …, "body": … }] (adresses acceptées, 2 Mo au plus chacune). */
create function public.sport_cache_put(h uuid, items jsonb) returns int
language plpgsql security definer set search_path = public as $$
declare
  n int;
begin
  if not public.is_member(h) then raise exception 'acces-refuse' using errcode = '42501'; end if;
  if jsonb_typeof(items) is distinct from 'array' or jsonb_array_length(items) > 100 then raise exception 'donnees-invalides'; end if;
  insert into public.sport_cache as c (household_id, url, body, fetched_at)
  select h, i->>'url', i->>'body', now() from jsonb_array_elements(items) i
   where public.sport_url_ok(i->>'url') and i->>'body' is not null and octet_length(i->>'body') <= 2000000 and left(ltrim(i->>'body'), 1) = '{'
  on conflict (household_id, url) do update set body = excluded.body, fetched_at = excluded.fetched_at;
  get diagnostics n = row_count;
  -- Rien de plus vieux que 3 jours (dates passées).
  delete from public.sport_cache c where c.household_id = h and c.fetched_at < now() - interval '3 days';
  return n;
end
$$;
revoke all on function public.sport_cache_put(uuid, jsonb) from public, anon;
grant execute on function public.sport_cache_put(uuid, jsonb) to authenticated;
