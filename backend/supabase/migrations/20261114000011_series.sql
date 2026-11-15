-- Section séries (plan 4A) : copie des catégories de séries, organisation par profil, « Ma liste »,
-- progression enrichie et marquer vu / non vu.
-- Ne dépend que de 0001 à 0009 (0010 reste locale et n'est pas appliquée en production).
--
-- Erreurs : acces-refuse (42501) ; horodatage-invalide, items-invalides, categories-invalides, type-invalide (P0001).

-- 1. Copie des catégories de séries publiée par la télé (jamais les séries elles-mêmes).
create table public.series_catalog_category (
  household_id uuid not null references public.households on delete cascade,
  id text not null,
  name text not null,
  position int not null,
  updated_at timestamptz not null default now(),
  primary key (household_id, id)
);
alter table public.series_catalog_category enable row level security;
create policy series_catalog_category_select on public.series_catalog_category
  for select to authenticated using (public.is_member(household_id));
revoke all on public.series_catalog_category from public, anon, authenticated;
grant select on public.series_catalog_category to authenticated;

-- cats = [{id, name, position}] : upsert de toutes, suppression des absentes. Une liste vide
-- (réponse transitoire du fournisseur) ne supprime rien.
create function public.publish_series_categories(h uuid, cats jsonb) returns void
language plpgsql security definer set search_path = public as $$
begin
  perform public.org_assert(h, null, false);
  if jsonb_typeof(cats) is distinct from 'array' then
    raise exception 'categories-invalides';
  end if;
  -- Rien de valide (liste vide, ou lignes sans id, nom ou position) : on ne supprime rien.
  if not exists (select 1 from jsonb_to_recordset(cats) as r(id text, name text, position int)
                  where r.id is not null and r.id <> '' and r.name is not null and r.position is not null) then
    return;
  end if;
  insert into public.series_catalog_category as c (household_id, id, name, position, updated_at)
  select h, d.id, d.name, d.position, now()
    from (select distinct on (r.id) r.id, r.name, r.position
            from jsonb_to_recordset(cats) as r(id text, name text, position int)
           where r.id is not null and r.id <> '' and r.name is not null and r.position is not null
           order by r.id, r.position) d
  on conflict on constraint series_catalog_category_pkey do update
    set name = excluded.name, position = excluded.position, updated_at = excluded.updated_at;
  delete from public.series_catalog_category c
   where c.household_id = h
     and c.id not in (select r.id from jsonb_to_recordset(cats) as r(id text, name text, position int)
                       where r.id is not null and r.id <> '' and r.name is not null and r.position is not null);
end
$$;

-- 2. Organisation des catégories de séries (section 'series' de category_order).
create function public.org_series_order(h uuid, p uuid) returns text[]
language sql stable security definer set search_path = public as $$
  select coalesce(
    (select o.category_ids from public.category_order o
      where o.household_id = h and o.profile_id = p and o.section = 'series'),
    (select o.category_ids from public.category_order o
      where o.household_id = h and o.profile_id is null and o.section = 'series'))
$$;
revoke execute on function public.org_series_order(uuid, uuid) from public, anon, authenticated;

-- Même forme que org_categories, sans channel_count : [{id, name, provider_position, hidden_global, hidden_profile}].
create function public.org_series_categories(h uuid, p uuid) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare
  ord text[];
begin
  perform public.org_assert(h, p, false);
  ord := public.org_series_order(h, p);
  return coalesce((
    select jsonb_agg(jsonb_build_object(
        'id', c.id, 'name', c.name, 'provider_position', c.position,
        'hidden_global', exists (select 1 from public.hidden_items x
               where x.household_id = h and x.profile_id is null and x.item_type = 'series_category' and x.item_id = c.id),
        'hidden_profile', p is not null and exists (select 1 from public.hidden_items x
               where x.household_id = h and x.profile_id = p and x.item_type = 'series_category' and x.item_id = c.id))
      order by array_position(ord, c.id) nulls last, c.position, c.id)
    from public.series_catalog_category c
    where c.household_id = h), '[]'::jsonb);
end
$$;

-- Remplace la version 0008 sans changer la signature : accepte aussi 'series_category'.
create or replace function public.org_set_hidden(h uuid, p uuid, item_type text, ids text[], hidden boolean)
returns int
language plpgsql security definer set search_path = public as $$
declare
  n int;
begin
  perform public.org_assert(h, p, true);
  if org_set_hidden.item_type not in ('live_category', 'channel', 'series_category') then
    raise exception 'type-invalide';
  end if;
  if coalesce(org_set_hidden.hidden, false) then
    insert into public.hidden_items (household_id, profile_id, item_type, item_id)
    select h, p, org_set_hidden.item_type, i.v from (select distinct unnest(org_set_hidden.ids) v) i
     where i.v is not null
    on conflict on constraint hidden_items_unique do nothing;
  else
    delete from public.hidden_items x
     where x.household_id = h and x.profile_id is not distinct from p
       and x.item_type = org_set_hidden.item_type and x.item_id = any (coalesce(org_set_hidden.ids, '{}'));
  end if;
  get diagnostics n = row_count;
  return n;
end
$$;

create function public.org_series_hide_all_except(h uuid, p uuid, keep text[]) returns int
language plpgsql security definer set search_path = public as $$
declare
  k text[] := coalesce(keep, '{}');
begin
  perform public.org_assert(h, p, true);
  insert into public.hidden_items (household_id, profile_id, item_type, item_id)
  select h, p, 'series_category', c.id from public.series_catalog_category c
   where c.household_id = h and c.id <> all (k)
  on conflict on constraint hidden_items_unique do nothing;
  delete from public.hidden_items x
   where x.household_id = h and x.profile_id is not distinct from p
     and x.item_type = 'series_category' and x.item_id = any (k);
  return (select count(*)::int from public.series_catalog_category c where c.household_id = h and c.id <> all (k));
end
$$;

create function public.org_set_series_category_order(h uuid, p uuid, ids text[]) returns void
language plpgsql security definer set search_path = public as $$
declare
  arr text[];
begin
  perform public.org_assert(h, p, true);
  select coalesce(array_agg(g.v order by g.ord), '{}') into arr
    from (select u.v, min(u.ord) ord from unnest(org_set_series_category_order.ids) with ordinality u(v, ord)
           where u.v is not null group by u.v) g;
  insert into public.category_order (household_id, profile_id, section, category_ids)
  values (h, p, 'series', arr)
  on conflict on constraint category_order_unique
  do update set category_ids = excluded.category_ids, updated_at = now();
end
$$;

-- 3. « Ma liste » : favoris d'un profil de type 'series', ajout en fin de liste.
create function public.org_set_series_list(p uuid, series_id text, on_ boolean) returns void
language plpgsql security definer set search_path = public as $$
begin
  perform public.org_assert(public.profile_household(p), p, true);
  perform pg_advisory_xact_lock(hashtextextended(p::text, 0));
  if coalesce(on_, false) then
    insert into public.favorites (profile_id, item_type, item_id, position)
    select p, 'series', org_set_series_list.series_id,
           coalesce((select max(f.position) + 1 from public.favorites f
                      where f.profile_id = p and f.item_type = 'series'), 0)
    on conflict (profile_id, item_type, item_id) do nothing;
  else
    delete from public.favorites f
     where f.profile_id = p and f.item_type = 'series' and f.item_id = org_set_series_list.series_id;
  end if;
end
$$;

create function public.org_reorder_series_list(p uuid, ids text[]) returns void
language plpgsql security definer set search_path = public as $$
begin
  perform public.org_assert(public.profile_household(p), p, true);
  perform pg_advisory_xact_lock(hashtextextended(p::text, 0));
  with given as (
    select u.v, min(u.ord) ord from unnest(org_reorder_series_list.ids) with ordinality u(v, ord) group by u.v
  ), ranked as (
    select f.item_id,
           (row_number() over (order by g.ord is null, g.ord, f.position, f.created_at, f.item_id) - 1)::int rn
      from public.favorites f left join given g on g.v = f.item_id
     where f.profile_id = p and f.item_type = 'series'
  )
  update public.favorites f set position = r.rn
    from ranked r
   where f.profile_id = p and f.item_type = 'series' and f.item_id = r.item_id and f.position is distinct from r.rn;
end
$$;

-- profile_prefs : version 0009 (group_copies et toutes les clés B1) + series_order et series_list.
create or replace function public.profile_prefs(p uuid) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare
  h uuid := public.profile_household(p);
begin
  perform public.org_assert(h, p, false);
  return jsonb_build_object(
    'group_copies', (select pr.group_copies from public.profiles pr where pr.id=p),
    'rev', coalesce((select r.rev from public.prefs_revision r where r.household_id = h), 0),
    'hidden', coalesce((
      select jsonb_agg(jsonb_build_object('item_type', x.item_type, 'item_id', x.item_id) order by x.item_type, x.item_id)
        from (select distinct hi.item_type, hi.item_id from public.hidden_items hi
               where hi.household_id = h and (hi.profile_id is null or hi.profile_id = p)) x), '[]'::jsonb),
    'favorites', coalesce((
      select jsonb_agg(f.item_id order by f.position, f.created_at, f.item_id)
        from public.favorites f where f.profile_id = p and f.item_type = 'channel'), '[]'::jsonb),
    'playlists', coalesce((
      select jsonb_agg(jsonb_build_object(
               'id', pl.id, 'name', pl.name,
               'items', coalesce((select jsonb_agg(i.item_id order by i.position, i.item_id)
                                    from public.playlist_items i
                                   where i.playlist_id = pl.id and i.item_type = 'channel'), '[]'::jsonb))
             order by pl.position, pl.name, pl.id)
        from public.playlists pl where pl.profile_id = p), '[]'::jsonb),
    'order', coalesce(to_jsonb(public.org_order(h, p)), 'null'::jsonb),
    'series_order', coalesce(to_jsonb(public.org_series_order(h, p)), 'null'::jsonb),
    'series_list', coalesce((
      select jsonb_agg(f.item_id order by f.position, f.created_at, f.item_id)
        from public.favorites f where f.profile_id = p and f.item_type = 'series'), '[]'::jsonb));
end
$$;

-- 4. Progression enrichie.
alter table public.watch_progress
  add column series_name text,
  add column series_cover text,
  add column series_category text;

-- Durée inconnue (0) admise : une position ne doit jamais être perdue parce que le lecteur ne connaît pas la durée.
-- Un épisode n'est « vu » que si la durée est connue (> 0) et la position atteint 90 %.
alter table public.watch_progress drop constraint watch_progress_duration_s_check;
alter table public.watch_progress add constraint watch_progress_duration_s_check check (duration_s >= 0);

-- Nouvelle surcharge à 12 arguments, SANS valeur par défaut : un appel à 6 à 9 arguments (télé 0.3.0)
-- ne correspond qu'à l'ancienne signature, un appel à 12 arguments qu'à celle-ci. Aucune ambiguïté
-- (ni en SQL ni pour PostgREST, qui choisit par jeu de noms). Les clients passent null au besoin.
-- Une valeur NULL de nom / affiche / catégorie conserve l'existante.
create function public.record_progress(
  p uuid, item_type text, item_id text, position_s int, duration_s int, client_updated_at timestamptz,
  series_id text, season int, episode int, series_name text, series_cover text, series_category text
) returns boolean
language plpgsql security definer set search_path = public as $$
declare
  n int;
  plafonne timestamptz;
begin
  if not public.can_use_profile(p) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  if record_progress.client_updated_at is null then
    raise exception 'horodatage-invalide';
  end if;
  plafonne := least(record_progress.client_updated_at, now() + interval '5 minutes');
  insert into public.watch_progress as w
    (profile_id, item_type, item_id, series_id, season, episode, position_s, duration_s, watched, client_updated_at,
     series_name, series_cover, series_category)
  values
    (p, record_progress.item_type, record_progress.item_id, record_progress.series_id, record_progress.season,
     record_progress.episode, record_progress.position_s, record_progress.duration_s,
     record_progress.duration_s > 0 and record_progress.position_s >= 0.9 * record_progress.duration_s, plafonne,
     record_progress.series_name, record_progress.series_cover, record_progress.series_category)
  on conflict on constraint watch_progress_pkey do update set
    series_id = coalesce(excluded.series_id, w.series_id),
    season = coalesce(excluded.season, w.season),
    episode = coalesce(excluded.episode, w.episode),
    series_name = coalesce(excluded.series_name, w.series_name),
    series_cover = coalesce(excluded.series_cover, w.series_cover),
    series_category = coalesce(excluded.series_category, w.series_category),
    position_s = excluded.position_s,
    duration_s = excluded.duration_s,
    watched = w.watched or excluded.watched,
    client_updated_at = excluded.client_updated_at
  where w.client_updated_at < excluded.client_updated_at;
  get diagnostics n = row_count;
  return n > 0;
end
$$;

-- Ancienne signature (télé 0.3.0) : mince enveloppe, mêmes règles.
create or replace function public.record_progress(
  p uuid, item_type text, item_id text, position_s int, duration_s int,
  client_updated_at timestamptz, series_id text default null, season int default null, episode int default null
) returns boolean
language plpgsql security definer set search_path = public as $$
begin
  return public.record_progress(p, item_type, item_id, position_s, duration_s, client_updated_at,
                                series_id, season, episode, null::text, null::text, null::text);
end
$$;

-- continue_watching : trois colonnes de plus ; une série dont la catégorie est masquée (tout le monde ou profil) est exclue.
create or replace function public.continue_watching(p uuid, max_rows int default 20)
returns setof public.watch_progress
language plpgsql stable security definer set search_path = public as $$
declare
  h uuid := public.profile_household(p);
begin
  if not public.can_use_profile(p) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  return query
    select r.profile_id, r.item_type, r.item_id, r.series_id, r.season, r.episode,
           r.position_s, r.duration_s, r.watched, r.client_updated_at,
           r.series_name, r.series_cover, r.cat
    from (
      -- cat : dernière catégorie connue de la série (une ligne récente sans catégorie, écrite par set_watched
      -- ou par un ancien client, ne doit pas contourner un masquage). Sans aucune catégorie connue : visible.
      select distinct on (w.series_id is null, coalesce(w.series_id, w.item_id)) w.*,
             coalesce(w.series_category, (
               select w2.series_category from public.watch_progress w2
                where w2.profile_id = p and w.series_id is not null and w2.series_id = w.series_id
                  and w2.series_category is not null
                order by w2.client_updated_at desc limit 1)) as cat
      from public.watch_progress w
      where w.profile_id = p
      order by w.series_id is null, coalesce(w.series_id, w.item_id), w.client_updated_at desc
    ) r
    where (r.item_type = 'episode' or (not r.watched and r.position_s > 0))
      and not (r.cat is not null and exists (
            select 1 from public.hidden_items x
             where x.household_id = h and (x.profile_id is null or x.profile_id = p)
               and x.item_type = 'series_category' and x.item_id = r.cat))
    order by r.client_updated_at desc
    limit least(greatest(coalesce(max_rows, 20), 0), 200);
end
$$;

-- 5. Marquer vu / non vu. items = [{item_id, series_id, season, episode, duration_s, series_name, series_cover, series_category}] (les trois derniers facultatifs ; une valeur déjà connue est conservée).
-- Ne touche jamais client_updated_at d'une ligne existante (la série ne remonte pas dans Reprendre) ;
-- une ligne nouvelle prend '2000-01-01' et position 0 ; duration_s absent ou <= 0 -> 1.
-- Démarquer remet watched à faux : seule exception à « vu ne redevient jamais faux » (record_progress,
-- lui, ne le fait jamais). Démarquer ne crée aucune ligne. Renvoie le nombre de lignes écrites.
create function public.set_watched(p uuid, items jsonb, watched boolean) returns int
language plpgsql security definer set search_path = public as $$
declare
  n int;
  w boolean := coalesce(set_watched.watched, false);
begin
  perform public.org_assert(public.profile_household(p), p, true);
  if jsonb_typeof(items) is distinct from 'array' then
    raise exception 'items-invalides';
  end if;
  insert into public.watch_progress as wp
    (profile_id, item_type, item_id, series_id, season, episode, position_s, duration_s, watched, client_updated_at,
     series_name, series_cover, series_category)
  select p, 'episode', d.item_id, d.series_id, d.season, d.episode, 0, greatest(coalesce(d.duration_s, 1), 1), true,
         timestamptz '2000-01-01 00:00:00+00', d.series_name, d.series_cover, d.series_category
    from (select distinct on (r.item_id) r.* from jsonb_to_recordset(items)
            as r(item_id text, series_id text, season int, episode int, duration_s int,
                 series_name text, series_cover text, series_category text)
           where r.item_id is not null order by r.item_id) d
   where w
  on conflict on constraint watch_progress_pkey do update set
    watched = true,
    series_id = coalesce(wp.series_id, excluded.series_id),
    season = coalesce(wp.season, excluded.season),
    episode = coalesce(wp.episode, excluded.episode),
    series_name = coalesce(wp.series_name, excluded.series_name),
    series_cover = coalesce(wp.series_cover, excluded.series_cover),
    series_category = coalesce(wp.series_category, excluded.series_category);
  get diagnostics n = row_count;
  if not w then
    -- La position repart de 0 : sinon l'épisode redeviendrait « vu » par la règle des 90 % côté client.
    update public.watch_progress wp set watched = false, position_s = 0
     where wp.profile_id = p and wp.item_type = 'episode'
       and wp.item_id in (select r.item_id from jsonb_to_recordset(items) as r(item_id text) where r.item_id is not null);
    get diagnostics n = row_count;
  end if;
  return n;
end
$$;

-- 6. Progression de TOUS les épisodes d'une série (continue_watching n'en rend qu'une ligne par série).
-- Un seul tableau jsonb (pas de plafond de 1 000 lignes), trié par saison, épisode, identifiant.
create function public.series_progress(p uuid, series_id text) returns jsonb
language plpgsql stable security definer set search_path = public as $$
begin
  if not public.can_use_profile(p) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  return coalesce((
    select jsonb_agg(jsonb_build_object(
             'item_id', w.item_id, 'season', w.season, 'episode', w.episode, 'position_s', w.position_s,
             'duration_s', w.duration_s, 'watched', w.watched, 'client_updated_at', w.client_updated_at,
             'series_category', w.series_category)
           order by w.season nulls last, w.episode nulls last, w.item_id)
      from public.watch_progress w
     where w.profile_id = p and w.item_type = 'episode' and w.series_id = series_progress.series_id), '[]'::jsonb);
end
$$;

-- Droits explicites.
revoke execute on function
  public.publish_series_categories(uuid, jsonb),
  public.org_series_categories(uuid, uuid),
  public.org_series_hide_all_except(uuid, uuid, text[]),
  public.org_set_series_category_order(uuid, uuid, text[]),
  public.org_set_series_list(uuid, text, boolean),
  public.org_reorder_series_list(uuid, text[]),
  public.record_progress(uuid, text, text, int, int, timestamptz, text, int, int, text, text, text),
  public.set_watched(uuid, jsonb, boolean),
  public.series_progress(uuid, text)
  from public, anon;
grant execute on function
  public.publish_series_categories(uuid, jsonb),
  public.org_series_categories(uuid, uuid),
  public.org_series_hide_all_except(uuid, uuid, text[]),
  public.org_set_series_category_order(uuid, uuid, text[]),
  public.org_set_series_list(uuid, text, boolean),
  public.org_reorder_series_list(uuid, text[]),
  public.record_progress(uuid, text, text, int, int, timestamptz, text, int, int, text, text, text),
  public.set_watched(uuid, jsonb, boolean),
  public.series_progress(uuid, text)
  to authenticated;
