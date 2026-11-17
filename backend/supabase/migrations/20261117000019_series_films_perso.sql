-- Personnalisation Séries et Films (page iPhone) : retirer un titre de « Reprendre », titres du catalogue publiés par
-- la télé (recherche et noms affichés sur l'iPhone), listes avec noms et affiches.
-- Les réglages d'affichage (bannière, rangées) vivent dans profiles.settings.media_view : pas de fonction ici.
--
-- Erreurs : acces-refuse (42501) ; type-invalide, titres-invalides (P0001).

-- 1. « Retirer de Reprendre ». La progression est gardée (relancer le titre reprend où on en était) : on note
-- seulement l'heure du retrait. Une lecture plus récente que le retrait fait revenir le titre.
alter table public.watch_progress add column dismissed_at timestamptz;

-- Retiré tant que le retrait est au moins aussi récent que la dernière lecture.
create function public.progress_dismissed(d timestamptz, c timestamptz) returns boolean
language sql immutable as $$ select d is not null and d >= c $$;

-- kind 'series' : ids = séries (tous leurs épisodes) ; kind 'movie' : ids = films. dismissed faux : remis.
-- greatest(now(), client_updated_at) : une télé dont l'horloge avance un peu ne fait pas revenir le titre.
create function public.dismiss_continue(p uuid, kind text, ids text[], dismissed boolean default true) returns int
language plpgsql security definer set search_path = public as $$
declare
  n int;
begin
  perform public.org_assert(public.profile_household(p), p, true);
  if kind is null or kind not in ('series', 'movie') then
    raise exception 'type-invalide';
  end if;
  update public.watch_progress w
     set dismissed_at = case when coalesce(dismiss_continue.dismissed, true) then greatest(now(), w.client_updated_at) end
   where w.profile_id = p
     and case when kind = 'series' then w.item_type = 'episode' and w.series_id = any (coalesce(ids, '{}'))
              else w.item_type = 'movie' and w.item_id = any (coalesce(ids, '{}')) end;
  get diagnostics n = row_count;
  return n;
end
$$;

-- Même définition que 0012, plus le retrait (et la nouvelle colonne, la fonction rend des lignes de la table).
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
           r.series_name, r.series_cover, r.cat, r.movie_name, r.movie_cover, r.movie_category, r.dismissed_at
    from (
      select distinct on (w.item_type, w.series_id is null, coalesce(w.series_id, w.item_id)) w.*,
             coalesce(w.series_category, (
               select w2.series_category from public.watch_progress w2
                where w2.profile_id = p and w2.item_type = w.item_type and w.series_id is not null and w2.series_id = w.series_id
                  and w2.series_category is not null
                order by w2.client_updated_at desc limit 1)) as cat
      from public.watch_progress w
      where w.profile_id = p
      order by w.item_type, w.series_id is null, coalesce(w.series_id, w.item_id), w.client_updated_at desc
    ) r
    where (r.item_type = 'episode' or (not r.watched and r.position_s > 0))
      and not public.progress_dismissed(r.dismissed_at, r.client_updated_at)
      and not ((case when r.item_type = 'movie' then r.movie_category else r.cat end) is not null and exists (
            select 1 from public.hidden_items x
             where x.household_id = h and (x.profile_id is null or x.profile_id = p)
               and x.item_type = case when r.item_type = 'movie' then 'vod_category' else 'series_category' end and x.item_id = case when r.item_type = 'movie' then r.movie_category else r.cat end))
    order by r.client_updated_at desc
    limit least(greatest(coalesce(max_rows, 20), 0), 200);
end
$$;

create or replace function public.continue_movies(p uuid, max_rows int default 20) returns setof public.watch_progress
language plpgsql stable security definer set search_path = public as $$
declare h uuid := public.profile_household(p);
begin
  perform public.org_assert(h, p, false);
  return query select w.* from public.watch_progress w
  where w.profile_id = p and w.item_type = 'movie' and not w.watched and w.position_s > 0
    and not public.progress_dismissed(w.dismissed_at, w.client_updated_at)
    and not exists (select 1 from public.hidden_items x where x.household_id = h and (x.profile_id is null or x.profile_id = p)
      and x.item_type = 'vod_category' and x.item_id = w.movie_category)
  order by w.client_updated_at desc, w.item_id limit least(greatest(coalesce(max_rows, 20), 0), 200);
end
$$;

-- 2. Titres du catalogue (séries, films) des catégories que la télé télécharge : jamais une catégorie masquée pour
-- tout le monde (la télé ne les télécharge pas). Sert à chercher et à nommer depuis l'iPhone.
-- Affiches : adresse publique, ou chemin seul pour une image du fournisseur (jamais son adresse).
create table public.catalog_title (
  household_id uuid not null references public.households on delete cascade,
  kind text not null check (kind in ('series', 'movie')),
  item_id text not null,
  name text not null,
  norm text not null,          -- nom normalisé par la télé (searchNorm), pour chercher
  cover text,
  category_id text,
  year text,
  rating numeric(4, 2),
  gen bigint not null,         -- publication qui a écrit la ligne
  primary key (household_id, kind, item_id)
);
alter table public.catalog_title enable row level security;
create policy catalog_title_select on public.catalog_title
  for select to authenticated using (public.is_member(household_id));
revoke all on public.catalog_title from public, anon, authenticated;
grant select on public.catalog_title to authenticated;

-- rows = [{id, name, norm, cover, cat, year, rating}] (au plus 3000 par appel). gen : heure de la publication (ms) ;
-- done : dernier lot, les titres d'une publication plus ancienne qui n'y sont plus sont supprimés.
-- Un lot vide avec done ne supprime rien (catalogue pas encore lu).
create function public.publish_catalog_titles(h uuid, kind text, gen bigint, rows jsonb, done boolean default false) returns int
language plpgsql security definer set search_path = public as $$
declare
  n int;
  wrote int := 0;
begin
  perform public.org_assert(h, null, false);
  if kind is null or kind not in ('series', 'movie') then
    raise exception 'type-invalide';
  end if;
  if gen is null or jsonb_typeof(rows) is distinct from 'array' or jsonb_array_length(rows) > 3000 then
    raise exception 'titres-invalides';
  end if;
  insert into public.catalog_title as t (household_id, kind, item_id, name, norm, cover, category_id, year, rating, gen)
  select h, publish_catalog_titles.kind, d.id, left(d.name, 300), left(d.norm, 300), left(d.cover, 500), d.cat, left(d.year, 10),
         case when d.rating ~ '^[0-9]+([.,][0-9]+)?$' then least(replace(d.rating, ',', '.')::numeric, 99.99) end, publish_catalog_titles.gen
    from (select distinct on (r.id) r.* from jsonb_to_recordset(rows) as r(id text, name text, norm text, cover text, cat text, year text, rating text)
           where coalesce(r.id, '') <> '' and coalesce(btrim(r.name), '') <> '' and r.norm is not null order by r.id) d
  on conflict on constraint catalog_title_pkey do update
    set name = excluded.name, norm = excluded.norm, cover = excluded.cover, category_id = excluded.category_id,
        year = excluded.year, rating = excluded.rating, gen = excluded.gen
    where t.gen <= excluded.gen;
  get diagnostics wrote = row_count;
  if coalesce(done, false) and exists (select 1 from public.catalog_title t
                                        where t.household_id = h and t.kind = publish_catalog_titles.kind and t.gen = publish_catalog_titles.gen) then
    delete from public.catalog_title t
     where t.household_id = h and t.kind = publish_catalog_titles.kind and t.gen < publish_catalog_titles.gen;
    get diagnostics n = row_count;
  end if;
  return wrote;
end
$$;

-- Titres visibles pour un profil : catégorie masquée (pour tous ou pour ce profil) exclue.
create function public.catalog_visible(h uuid, p uuid, kind text, category_id text) returns boolean
language sql stable security definer set search_path = public as $$
  select category_id is null or not exists (
    select 1 from public.hidden_items x
     where x.household_id = h and (x.profile_id is null or x.profile_id = p)
       and x.item_type = case when kind = 'movie' then 'vod_category' else 'series_category' end and x.item_id = category_id)
$$;

-- q : déjà normalisé par la page (même règle que la télé). Ceux qui commencent par q d'abord, puis les mieux notés.
create function public.search_catalog(p uuid, kind text, q text, max_rows int default 30) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare
  h uuid := public.profile_household(p);
  needle text := btrim(coalesce(q, ''));
  pattern text;
begin
  perform public.org_assert(h, p, false);
  if kind is null or kind not in ('series', 'movie') then
    raise exception 'type-invalide';
  end if;
  if char_length(needle) < 2 then
    return '[]'::jsonb;
  end if;
  pattern := '%' || replace(replace(replace(needle, '\', '\\'), '%', '\%'), '_', '\_') || '%';
  return coalesce((
    select jsonb_agg(jsonb_build_object('id', s.item_id, 'name', s.name, 'cover', s.cover, 'category_id', s.category_id,
             'year', s.year, 'in_list', s.in_list) order by s.starts desc, s.rating desc nulls last, s.name)
      from (select t.item_id, t.name, t.cover, t.category_id, t.year, t.rating, t.norm like needle || '%' as starts,
                   exists (select 1 from public.favorites f where f.profile_id = p and f.item_type = search_catalog.kind and f.item_id = t.item_id) as in_list
              from public.catalog_title t
             where t.household_id = h and t.kind = search_catalog.kind and t.norm like pattern
               and public.catalog_visible(h, p, search_catalog.kind, t.category_id)
             order by t.norm like needle || '%' desc, t.rating desc nulls last, t.name
             limit least(greatest(coalesce(max_rows, 30), 1), 100)) s), '[]'::jsonb);
end
$$;

-- Ma liste des deux types, dans l'ordre, avec nom et affiche (copie du catalogue, sinon ce que garde la liste).
-- known : faux si le titre n'est plus dans le catalogue publié (retiré chez le fournisseur ou catégorie masquée).
create function public.media_lists(p uuid) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare
  h uuid := public.profile_household(p);
begin
  perform public.org_assert(h, p, false);
  return jsonb_build_object(
    'series', coalesce((
      select jsonb_agg(jsonb_build_object('id', f.item_id, 'name', t.name, 'cover', t.cover, 'category_id', t.category_id,
               'year', t.year, 'known', t.item_id is not null) order by f.position, f.created_at, f.item_id)
        from public.favorites f
        left join public.catalog_title t on t.household_id = h and t.kind = 'series' and t.item_id = f.item_id
       where f.profile_id = p and f.item_type = 'series'), '[]'::jsonb),
    'movies', coalesce((
      select jsonb_agg(jsonb_build_object('id', f.item_id, 'name', coalesce(t.name, f.movie_name), 'cover', coalesce(t.cover, f.movie_cover),
               'category_id', coalesce(t.category_id, f.movie_category), 'year', t.year, 'known', t.item_id is not null or f.movie_name is not null)
               order by f.position, f.created_at, f.item_id)
        from public.favorites f
        left join public.catalog_title t on t.household_id = h and t.kind = 'movie' and t.item_id = f.item_id
       where f.profile_id = p and f.item_type = 'movie'), '[]'::jsonb));
end
$$;

-- Noms et affiches de quelques titres (titres épinglés de la bannière, « ne plus proposer ») : [{kind, id}].
create function public.catalog_titles_by_ids(p uuid, items jsonb) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare
  h uuid := public.profile_household(p);
begin
  perform public.org_assert(h, p, false);
  if jsonb_typeof(items) is distinct from 'array' then
    raise exception 'titres-invalides';
  end if;
  return coalesce((
    select jsonb_agg(jsonb_build_object('kind', t.kind, 'id', t.item_id, 'name', t.name, 'cover', t.cover, 'category_id', t.category_id, 'year', t.year))
      from public.catalog_title t
      join (select distinct r.kind, r.id from jsonb_to_recordset(items) as r(kind text, id text)) i on i.kind = t.kind and i.id = t.item_id
     where t.household_id = h), '[]'::jsonb);
end
$$;

revoke execute on function
  public.dismiss_continue(uuid, text, text[], boolean),
  public.publish_catalog_titles(uuid, text, bigint, jsonb, boolean),
  public.catalog_visible(uuid, uuid, text, text),
  public.search_catalog(uuid, text, text, int),
  public.media_lists(uuid),
  public.catalog_titles_by_ids(uuid, jsonb)
  from public, anon;
grant execute on function
  public.dismiss_continue(uuid, text, text[], boolean),
  public.publish_catalog_titles(uuid, text, bigint, jsonb, boolean),
  public.search_catalog(uuid, text, text, int),
  public.media_lists(uuid),
  public.catalog_titles_by_ids(uuid, jsonb)
  to authenticated;
revoke execute on function public.catalog_visible(uuid, uuid, text, text) from authenticated;
