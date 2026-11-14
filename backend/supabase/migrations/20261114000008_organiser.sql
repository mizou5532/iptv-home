-- Organiser depuis le téléphone (plan 3b1).
--
-- Codes d'erreur levés par les fonctions :
--   acces-refuse      : errcode 42501
--   categorie-inconnue : errcode P0001 (catégorie non publiée)

-- Copie du catalogue du direct
--
-- La télé publie ici les catégories et les chaînes du direct, pour que la page du téléphone
-- du propriétaire voie leurs noms. Lecture pour les membres ; aucune écriture directe,
-- uniquement par les deux fonctions ci-dessous.
-- Copie figée : une catégorie dont on ne republie pas les chaînes garde les chaînes déjà publiées.
-- Empreinte : écrite seulement au dernier morceau, donc une publication interrompue ne passe pas pour terminée.

create table public.live_catalog_category (
  household_id uuid not null references public.households on delete cascade,
  id text not null,
  name text not null,
  position int not null,
  fingerprint text,
  updated_at timestamptz not null default now(),
  primary key (household_id, id)
);

create table public.live_catalog_channel (
  household_id uuid not null,
  id text not null,
  category_id text not null,
  name text not null,
  norm text not null,
  number int,
  position int not null,
  primary key (household_id, id),
  foreign key (household_id, category_id)
    references public.live_catalog_category (household_id, id) on delete cascade
);
create index live_catalog_channel_category_idx
  on public.live_catalog_channel (household_id, category_id, position);

alter table public.live_catalog_category enable row level security;
alter table public.live_catalog_channel enable row level security;

create policy live_catalog_category_select on public.live_catalog_category
  for select to authenticated using (public.is_member(household_id));
create policy live_catalog_channel_select on public.live_catalog_channel
  for select to authenticated using (public.is_member(household_id));

revoke all on public.live_catalog_category, public.live_catalog_channel from public, anon, authenticated;
grant select on public.live_catalog_category, public.live_catalog_channel to authenticated;

-- cats = [{id, name, position}] : upsert de toutes, suppression des absentes (et de leurs chaînes).
-- Renvoie les empreintes connues, que la télé compare aux siennes pour ne republier que ce qui a changé.
create function public.publish_live_categories(h uuid, cats jsonb)
returns table (id text, fingerprint text)
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_member(h) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;

  -- Une liste vide (réponse transitoire du fournisseur) ne supprime rien : on rend les empreintes connues.
  if coalesce(jsonb_array_length(cats), 0) > 0 then
  insert into public.live_catalog_category as c (household_id, id, name, position, updated_at)
  select h, r.id, r.name, r.position, now()
    from jsonb_to_recordset(cats) as r(id text, name text, position int)
  on conflict on constraint live_catalog_category_pkey do update
    set name = excluded.name, position = excluded.position, updated_at = excluded.updated_at;

  delete from public.live_catalog_category c
   where c.household_id = h
     and c.id not in (select r.id from jsonb_to_recordset(cats) as r(id text));
  end if;

  return query
    select c.id, c.fingerprint from public.live_catalog_category c where c.household_id = h;
end
$$;

-- channels = [{id, name, norm, number, position}] d'une seule catégorie, par morceaux.
-- chunk = 0 efface d'abord les chaînes de la catégorie ET son empreinte (même transaction) ;
-- is_last écrit l'empreinte. Une publication interrompue ne passe donc jamais pour terminée.
-- Une chaîne déjà présente sous une autre catégorie est déplacée (dernier envoi gagne).
create function public.publish_live_channels(
  h uuid, category text, fingerprint text, chunk int, is_last boolean, channels jsonb
) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_member(h) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  if not exists (
    select 1 from public.live_catalog_category c where c.household_id = h and c.id = category
  ) then
    raise exception 'categorie-inconnue';
  end if;

  if chunk = 0 then
    delete from public.live_catalog_channel ch where ch.household_id = h and ch.category_id = category;
    update public.live_catalog_category c set fingerprint = null, updated_at = now()
     where c.household_id = h and c.id = category;
  end if;

  insert into public.live_catalog_channel as ch (household_id, id, category_id, name, norm, number, position)
  select h, r.id, category, r.name, r.norm, r.number, r.position
    from jsonb_to_recordset(channels) as r(id text, name text, norm text, number int, position int)
  on conflict on constraint live_catalog_channel_pkey do update
    set category_id = excluded.category_id, name = excluded.name, norm = excluded.norm,
        number = excluded.number, position = excluded.position;

  if coalesce(is_last, false) then
    update public.live_catalog_category c
       set fingerprint = publish_live_channels.fingerprint, updated_at = now()
     where c.household_id = h and c.id = category;
  end if;
end
$$;

revoke execute on function public.publish_live_categories(uuid, jsonb) from public, anon;
revoke execute on function public.publish_live_channels(uuid, text, text, int, boolean, jsonb) from public, anon;
grant execute on function public.publish_live_categories(uuid, jsonb) to authenticated;
grant execute on function public.publish_live_channels(uuid, text, text, int, boolean, jsonb) to authenticated;

-- ---------------------------------------------------------------------------------------------
-- Organisation par profil (tâche 2)
--
-- Portée : p = null signifie « tout le monde » (le foyer). Écriture « tout le monde » = propriétaire
-- seulement (is_owner) ; écriture d'un profil = can_use_profile(p). Lecture = tout membre.
-- Pour les fonctions qui prennent h et p, p doit appartenir à h, sinon acces-refuse.
-- Identité d'une chaîne = stream_id Xtream en texte (item_type = 'channel'). « Masqué gagne ».
-- Ordre effectif des catégories : ordre du profil, sinon ordre du foyer, sinon ordre du fournisseur ;
-- les catégories absentes de l'ordre suivent dans l'ordre du fournisseur ; les identifiants inconnus sont ignorés.
-- Fonctions de lecture `stable` : tout ce qu'elles renvoient vient du même instantané.
-- ---------------------------------------------------------------------------------------------

alter table public.favorites add column position int not null default 0;

-- Révision des préférences : +1 à chaque changement de l'une des cinq tables, par foyer.
create table public.prefs_revision (
  household_id uuid primary key references public.households on delete cascade,
  rev bigint not null default 0,
  updated_at timestamptz not null default now()
);
alter table public.prefs_revision enable row level security;
create policy prefs_revision_select on public.prefs_revision
  for select to authenticated using (public.is_member(household_id));
revoke all on public.prefs_revision from public, anon, authenticated;
grant select on public.prefs_revision to authenticated;

create function public.bump_prefs_rev() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  r jsonb := case when tg_op = 'DELETE' then to_jsonb(old) else to_jsonb(new) end;
  h uuid;
begin
  h := case tg_table_name
    when 'hidden_items' then (r->>'household_id')::uuid
    when 'category_order' then (r->>'household_id')::uuid
    when 'favorites' then public.profile_household((r->>'profile_id')::uuid)
    when 'playlists' then public.profile_household((r->>'profile_id')::uuid)
    when 'playlist_items' then public.profile_household(public.playlist_profile((r->>'playlist_id')::uuid))
  end;
  -- Foyer ou profil en cours de suppression (cascade) : rien à compter.
  if h is not null and exists (select 1 from public.households where id = h) then
    insert into public.prefs_revision as pr (household_id, rev, updated_at) values (h, 1, now())
    on conflict (household_id) do update set rev = pr.rev + 1, updated_at = now();
  end if;
  return null;
end
$$;
revoke execute on function public.bump_prefs_rev() from public, anon, authenticated;

create trigger favorites_bump_rev after insert or update or delete on public.favorites
  for each row execute function public.bump_prefs_rev();
create trigger playlists_bump_rev after insert or update or delete on public.playlists
  for each row execute function public.bump_prefs_rev();
create trigger playlist_items_bump_rev after insert or update or delete on public.playlist_items
  for each row execute function public.bump_prefs_rev();
create trigger hidden_items_bump_rev after insert or update or delete on public.hidden_items
  for each row execute function public.bump_prefs_rev();
create trigger category_order_bump_rev after insert or update or delete on public.category_order
  for each row execute function public.bump_prefs_rev();

-- Aides internes (non appelables par les clients).
create function public.org_assert(h uuid, p uuid, w boolean) returns void
language plpgsql stable security definer set search_path = public as $$
begin
  if h is null or not public.is_member(h) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  if p is not null and public.profile_household(p) is distinct from h then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  if w then
    if p is null then
      if not public.is_owner(h) then
        raise exception 'acces-refuse' using errcode = '42501';
      end if;
    elsif not public.can_use_profile(p) then
      raise exception 'acces-refuse' using errcode = '42501';
    end if;
  end if;
end
$$;

create function public.org_order(h uuid, p uuid) returns text[]
language sql stable security definer set search_path = public as $$
  select coalesce(
    (select o.category_ids from public.category_order o
      where o.household_id = h and o.profile_id = p and o.section = 'live'),
    (select o.category_ids from public.category_order o
      where o.household_id = h and o.profile_id is null and o.section = 'live'))
$$;

revoke execute on function public.org_assert(uuid, uuid, boolean) from public, anon, authenticated;
revoke execute on function public.org_order(uuid, uuid) from public, anon, authenticated;

create function public.prefs_rev(h uuid) returns bigint
language plpgsql stable security definer set search_path = public as $$
begin
  perform public.org_assert(h, null, false);
  return coalesce((select r.rev from public.prefs_revision r where r.household_id = h), 0);
end
$$;

create function public.org_categories(h uuid, p uuid)
returns table (id text, name text, provider_position int, hidden_global boolean, hidden_profile boolean, channel_count int)
language plpgsql stable security definer set search_path = public as $$
declare
  ord text[];
begin
  perform public.org_assert(h, p, false);
  ord := public.org_order(h, p);
  return query
    select c.id, c.name, c.position,
      exists (select 1 from public.hidden_items x
               where x.household_id = h and x.profile_id is null and x.item_type = 'live_category' and x.item_id = c.id),
      p is not null and exists (select 1 from public.hidden_items x
               where x.household_id = h and x.profile_id = p and x.item_type = 'live_category' and x.item_id = c.id),
      coalesce(k.n, 0)
    from public.live_catalog_category c
    left join (select ch.category_id, count(*)::int n from public.live_catalog_channel ch
                where ch.household_id = h group by ch.category_id) k on k.category_id = c.id
    where c.household_id = h
    order by array_position(ord, c.id) nulls last, c.position, c.id;
end
$$;

create function public.org_channels(h uuid, category text, p uuid, off int, lim int)
returns table (id text, name text, number int, favorite boolean, hidden_global boolean, hidden_profile boolean)
language plpgsql stable security definer set search_path = public as $$
begin
  perform public.org_assert(h, p, false);
  return query
    select ch.id, ch.name, ch.number,
      p is not null and exists (select 1 from public.favorites f
               where f.profile_id = p and f.item_type = 'channel' and f.item_id = ch.id),
      exists (select 1 from public.hidden_items x
               where x.household_id = h and x.profile_id is null and x.item_type = 'channel' and x.item_id = ch.id),
      p is not null and exists (select 1 from public.hidden_items x
               where x.household_id = h and x.profile_id = p and x.item_type = 'channel' and x.item_id = ch.id)
    from public.live_catalog_channel ch
    where ch.household_id = h and ch.category_id = org_channels.category
    order by ch.position, ch.id
    offset greatest(coalesce(org_channels.off, 0), 0)
    limit least(greatest(coalesce(org_channels.lim, 100), 0), 1000);
end
$$;

-- q est déjà normalisé par le client ; %, _ et \ sont échappés pour être cherchés littéralement.
create function public.org_search(h uuid, q text, p uuid, lim int)
returns table (id text, name text, number int, category_id text, category_name text,
               favorite boolean, hidden_global boolean, hidden_profile boolean)
language plpgsql stable security definer set search_path = public as $$
declare
  pat text;
begin
  perform public.org_assert(h, p, false);
  if coalesce(btrim(org_search.q), '') = '' then
    return;
  end if;
  pat := '%' || replace(replace(replace(org_search.q, '\', '\\'), '%', '\%'), '_', '\_') || '%';
  return query
    select ch.id, ch.name, ch.number, ch.category_id, c.name,
      p is not null and exists (select 1 from public.favorites f
               where f.profile_id = p and f.item_type = 'channel' and f.item_id = ch.id),
      exists (select 1 from public.hidden_items x
               where x.household_id = h and x.profile_id is null and x.item_type = 'channel' and x.item_id = ch.id),
      p is not null and exists (select 1 from public.hidden_items x
               where x.household_id = h and x.profile_id = p and x.item_type = 'channel' and x.item_id = ch.id)
    from public.live_catalog_channel ch
    join public.live_catalog_category c on c.household_id = ch.household_id and c.id = ch.category_id
    where ch.household_id = h and ch.norm like pat escape '\'
    order by c.position, c.id, ch.position, ch.id
    limit least(greatest(coalesce(org_search.lim, 50), 0), 200);
end
$$;

-- Masque (hidden = true) ou réaffiche des catégories du direct ou des chaînes dans la portée.
-- Renvoie le nombre de lignes changées.
create function public.org_set_hidden(h uuid, p uuid, item_type text, ids text[], hidden boolean)
returns int
language plpgsql security definer set search_path = public as $$
declare
  n int;
begin
  perform public.org_assert(h, p, true);
  if org_set_hidden.item_type not in ('live_category', 'channel') then
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

-- Masque dans la portée toutes les catégories publiées hors keep, réaffiche celles de keep.
-- Renvoie le nombre de catégories publiées hors keep (donc masquées dans la portée après l'appel).
create function public.org_hide_all_except(h uuid, p uuid, keep text[]) returns int
language plpgsql security definer set search_path = public as $$
declare
  k text[] := coalesce(keep, '{}');
begin
  perform public.org_assert(h, p, true);
  insert into public.hidden_items (household_id, profile_id, item_type, item_id)
  select h, p, 'live_category', c.id from public.live_catalog_category c
   where c.household_id = h and c.id <> all (k)
  on conflict on constraint hidden_items_unique do nothing;
  delete from public.hidden_items x
   where x.household_id = h and x.profile_id is not distinct from p
     and x.item_type = 'live_category' and x.item_id = any (k);
  return (select count(*)::int from public.live_catalog_category c where c.household_id = h and c.id <> all (k));
end
$$;

create function public.org_set_category_order(h uuid, p uuid, ids text[]) returns void
language plpgsql security definer set search_path = public as $$
declare
  arr text[];
begin
  perform public.org_assert(h, p, true);
  -- Sans doublons, ordre de première apparition.
  select coalesce(array_agg(g.v order by g.ord), '{}') into arr
    from (select u.v, min(u.ord) ord from unnest(org_set_category_order.ids) with ordinality u(v, ord)
           where u.v is not null group by u.v) g;
  insert into public.category_order (household_id, profile_id, section, category_ids)
  values (h, p, 'live', arr)
  on conflict on constraint category_order_unique
  do update set category_ids = excluded.category_ids, updated_at = now();
end
$$;

-- Favoris d'un profil : ajout en fin de liste ; retrait.
create function public.org_set_favorite(p uuid, channel text, on_ boolean) returns void
language plpgsql security definer set search_path = public as $$
begin
  perform public.org_assert(public.profile_household(p), p, true);
  perform pg_advisory_xact_lock(hashtextextended(p::text, 0));
  if coalesce(on_, false) then
    insert into public.favorites (profile_id, item_type, item_id, position)
    select p, 'channel', org_set_favorite.channel,
           coalesce((select max(f.position) + 1 from public.favorites f
                      where f.profile_id = p and f.item_type = 'channel'), 0)
    on conflict (profile_id, item_type, item_id) do nothing;
  else
    delete from public.favorites f
     where f.profile_id = p and f.item_type = 'channel' and f.item_id = org_set_favorite.channel;
  end if;
end
$$;

-- Les favoris de ids passent en tête dans cet ordre ; les autres suivent dans leur ordre actuel.
create function public.org_reorder_favorites(p uuid, ids text[]) returns void
language plpgsql security definer set search_path = public as $$
begin
  perform public.org_assert(public.profile_household(p), p, true);
  perform pg_advisory_xact_lock(hashtextextended(p::text, 0));
  with given as (
    select u.v, min(u.ord) ord from unnest(org_reorder_favorites.ids) with ordinality u(v, ord) group by u.v
  ), ranked as (
    select f.item_id,
           (row_number() over (order by g.ord is null, g.ord, f.position, f.created_at, f.item_id) - 1)::int rn
      from public.favorites f left join given g on g.v = f.item_id
     where f.profile_id = p and f.item_type = 'channel'
  )
  update public.favorites f set position = r.rn
    from ranked r
   where f.profile_id = p and f.item_type = 'channel' and f.item_id = r.item_id and f.position is distinct from r.rn;
end
$$;

create function public.org_playlist_create(p uuid, name text) returns uuid
language plpgsql security definer set search_path = public as $$
declare
  new_id uuid;
begin
  perform public.org_assert(public.profile_household(p), p, true);
  perform pg_advisory_xact_lock(hashtextextended(p::text, 0));
  insert into public.playlists (profile_id, name, position)
  values (p, btrim(org_playlist_create.name),
          coalesce((select max(pl.position) + 1 from public.playlists pl where pl.profile_id = p), 0))
  returning playlists.id into new_id;
  return new_id;
end
$$;

create function public.org_playlist_rename(id uuid, name text) returns void
language plpgsql security definer set search_path = public as $$
declare
  p uuid := public.playlist_profile(org_playlist_rename.id);
begin
  perform public.org_assert(public.profile_household(p), p, true);
  update public.playlists pl set name = btrim(org_playlist_rename.name) where pl.id = org_playlist_rename.id;
end
$$;

create function public.org_playlist_delete(id uuid) returns void
language plpgsql security definer set search_path = public as $$
declare
  p uuid := public.playlist_profile(org_playlist_delete.id);
begin
  perform public.org_assert(public.profile_household(p), p, true);
  delete from public.playlists pl where pl.id = org_playlist_delete.id;
end
$$;

create function public.org_playlists_reorder(p uuid, ids uuid[]) returns void
language plpgsql security definer set search_path = public as $$
begin
  perform public.org_assert(public.profile_household(p), p, true);
  perform pg_advisory_xact_lock(hashtextextended(p::text, 0));
  with given as (
    select u.v, min(u.ord) ord from unnest(org_playlists_reorder.ids) with ordinality u(v, ord) group by u.v
  ), ranked as (
    select pl.id,
           (row_number() over (order by g.ord is null, g.ord, pl.position, pl.name, pl.id) - 1)::int rn
      from public.playlists pl left join given g on g.v = pl.id
     where pl.profile_id = p
  )
  update public.playlists pl set position = r.rn
    from ranked r
   where pl.id = r.id and pl.position is distinct from r.rn;
end
$$;

-- Remplace la liste ordonnée des chaînes de la liste de lecture.
create function public.org_playlist_set_items(id uuid, channel_ids text[]) returns void
language plpgsql security definer set search_path = public as $$
declare
  p uuid := public.playlist_profile(org_playlist_set_items.id);
begin
  perform public.org_assert(public.profile_household(p), p, true);
  perform pg_advisory_xact_lock(hashtextextended(p::text, 0));
  delete from public.playlist_items i
   where i.playlist_id = org_playlist_set_items.id and i.item_type = 'channel';
  insert into public.playlist_items (playlist_id, item_type, item_id, position)
  select org_playlist_set_items.id, 'channel', g.v, (row_number() over (order by g.ord) - 1)::int
    from (select u.v, min(u.ord) ord from unnest(org_playlist_set_items.channel_ids) with ordinality u(v, ord)
           where u.v is not null group by u.v) g;
end
$$;

-- items : même contenu que channel_ids, avec nom, numéro, catégorie et état (mêmes états et même « masqué gagne » que org_favorites).
create function public.org_playlists(p uuid)
returns table (id uuid, name text, "position" int, channel_ids text[], items jsonb)
language plpgsql stable security definer set search_path = public as $$
declare
  h uuid := public.profile_household(p);
begin
  perform public.org_assert(h, p, false);
  return query
    select pl.id, pl.name, pl.position,
           coalesce((select array_agg(i.item_id order by i.position, i.item_id)
                       from public.playlist_items i
                      where i.playlist_id = pl.id and i.item_type = 'channel'), '{}'),
           coalesce((select jsonb_agg(jsonb_build_object(
                        'id', i.item_id, 'name', ch.name, 'number', ch.number, 'category_id', ch.category_id,
                        'state', case
                          when ch.id is null then 'n-existe-plus'
                          when exists (select 1 from public.hidden_items x
                                        where x.household_id = h and (x.profile_id is null or x.profile_id = p)
                                          and x.item_type = 'channel' and x.item_id = i.item_id) then 'masquee'
                          when exists (select 1 from public.hidden_items x
                                        where x.household_id = h and (x.profile_id is null or x.profile_id = p)
                                          and x.item_type = 'live_category' and x.item_id = ch.category_id) then 'categorie-masquee'
                          else 'ok' end)
                      order by i.position, i.item_id)
                      from public.playlist_items i
                      left join public.live_catalog_channel ch on ch.household_id = h and ch.id = i.item_id
                     where i.playlist_id = pl.id and i.item_type = 'channel'), '[]'::jsonb)
      from public.playlists pl
     where pl.profile_id = p
     order by pl.position, pl.name, pl.id;
end
$$;

-- state : ok | categorie-masquee | masquee | n-existe-plus (la chaîne a disparu de la copie).
create function public.org_favorites(p uuid)
returns table (id text, name text, number int, category_id text, state text)
language plpgsql stable security definer set search_path = public as $$
declare
  h uuid := public.profile_household(p);
begin
  perform public.org_assert(h, p, false);
  return query
    select f.item_id, ch.name, ch.number, ch.category_id,
      case
        when ch.id is null then 'n-existe-plus'
        when exists (select 1 from public.hidden_items x
                      where x.household_id = h and (x.profile_id is null or x.profile_id = p)
                        and x.item_type = 'channel' and x.item_id = f.item_id) then 'masquee'
        when exists (select 1 from public.hidden_items x
                      where x.household_id = h and (x.profile_id is null or x.profile_id = p)
                        and x.item_type = 'live_category' and x.item_id = ch.category_id) then 'categorie-masquee'
        else 'ok'
      end
    from public.favorites f
    left join public.live_catalog_channel ch on ch.household_id = h and ch.id = f.item_id
    where f.profile_id = p and f.item_type = 'channel'
    order by f.position, f.created_at, f.item_id;
end
$$;

-- Instantané que la télé télécharge : masqué (foyer + profil), favoris, listes, ordre (profil, sinon foyer, sinon null).
create function public.profile_prefs(p uuid) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare
  h uuid := public.profile_household(p);
begin
  perform public.org_assert(h, p, false);
  return jsonb_build_object(
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
    'order', coalesce(to_jsonb(public.org_order(h, p)), 'null'::jsonb));
end
$$;

revoke execute on function
  public.prefs_rev(uuid),
  public.org_categories(uuid, uuid),
  public.org_channels(uuid, text, uuid, int, int),
  public.org_search(uuid, text, uuid, int),
  public.org_set_hidden(uuid, uuid, text, text[], boolean),
  public.org_hide_all_except(uuid, uuid, text[]),
  public.org_set_category_order(uuid, uuid, text[]),
  public.org_set_favorite(uuid, text, boolean),
  public.org_reorder_favorites(uuid, text[]),
  public.org_playlist_create(uuid, text),
  public.org_playlist_rename(uuid, text),
  public.org_playlist_delete(uuid),
  public.org_playlists_reorder(uuid, uuid[]),
  public.org_playlist_set_items(uuid, text[]),
  public.org_playlists(uuid),
  public.org_favorites(uuid),
  public.profile_prefs(uuid)
  from public, anon;
grant execute on function
  public.prefs_rev(uuid),
  public.org_categories(uuid, uuid),
  public.org_channels(uuid, text, uuid, int, int),
  public.org_search(uuid, text, uuid, int),
  public.org_set_hidden(uuid, uuid, text, text[], boolean),
  public.org_hide_all_except(uuid, uuid, text[]),
  public.org_set_category_order(uuid, uuid, text[]),
  public.org_set_favorite(uuid, text, boolean),
  public.org_reorder_favorites(uuid, text[]),
  public.org_playlist_create(uuid, text),
  public.org_playlist_rename(uuid, text),
  public.org_playlist_delete(uuid),
  public.org_playlists_reorder(uuid, uuid[]),
  public.org_playlist_set_items(uuid, text[]),
  public.org_playlists(uuid),
  public.org_favorites(uuid),
  public.profile_prefs(uuid)
  to authenticated;
