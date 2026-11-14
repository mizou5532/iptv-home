-- Favoris, listes, masquage et ordre des catégories.

create function public.profile_household(p uuid) returns uuid
language sql stable security definer set search_path = public as $$
  select household_id from public.profiles where id = p
$$;

create function public.can_use_profile(p uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select coalesce(public.is_member(public.profile_household(p)), false)
$$;

revoke execute on function public.profile_household(uuid) from public, anon;
revoke execute on function public.can_use_profile(uuid) from public, anon;
grant execute on function public.profile_household(uuid) to authenticated;
grant execute on function public.can_use_profile(uuid) to authenticated;

create table public.favorites (
  profile_id uuid not null references public.profiles on delete cascade,
  item_type text not null check (item_type in ('channel', 'movie', 'series')),
  item_id text not null,
  created_at timestamptz default now(),
  primary key (profile_id, item_type, item_id)
);

create table public.playlists (
  id uuid primary key default gen_random_uuid(),
  profile_id uuid not null references public.profiles on delete cascade,
  name text not null check (char_length(btrim(name)) between 1 and 40),
  position int not null default 0
);
create index playlists_profile_idx on public.playlists (profile_id);

create function public.playlist_profile(pl uuid) returns uuid
language sql stable security definer set search_path = public as $$
  select profile_id from public.playlists where id = pl
$$;

revoke execute on function public.playlist_profile(uuid) from public, anon;
grant execute on function public.playlist_profile(uuid) to authenticated;

create table public.playlist_items (
  playlist_id uuid not null references public.playlists on delete cascade,
  item_type text not null check (item_type in ('channel', 'movie', 'series')),
  item_id text not null,
  position int not null default 0,
  primary key (playlist_id, item_type, item_id)
);

create table public.hidden_items (
  id uuid primary key default gen_random_uuid(),
  household_id uuid not null references public.households on delete cascade,
  profile_id uuid references public.profiles on delete cascade,
  item_type text not null check (item_type in ('live_category', 'vod_category', 'series_category', 'channel', 'movie', 'series')),
  item_id text not null,
  created_at timestamptz default now()
);
create unique index hidden_items_unique on public.hidden_items
  (household_id, coalesce(profile_id, '00000000-0000-0000-0000-000000000000'), item_type, item_id);
create index hidden_items_profile_idx on public.hidden_items (profile_id);

create table public.category_order (
  household_id uuid not null references public.households on delete cascade,
  profile_id uuid references public.profiles on delete cascade,
  section text not null check (section in ('live', 'vod', 'series')),
  category_ids text[] not null,
  updated_at timestamptz default now()
);
create unique index category_order_unique on public.category_order
  (household_id, coalesce(profile_id, '00000000-0000-0000-0000-000000000000'), section);
create index category_order_profile_idx on public.category_order (profile_id);

alter table public.favorites enable row level security;
alter table public.playlists enable row level security;
alter table public.playlist_items enable row level security;
alter table public.hidden_items enable row level security;
alter table public.category_order enable row level security;

create policy favorites_all on public.favorites
  for all to authenticated
  using (public.can_use_profile(profile_id))
  with check (public.can_use_profile(profile_id));

create policy playlists_all on public.playlists
  for all to authenticated
  using (public.can_use_profile(profile_id))
  with check (public.can_use_profile(profile_id));

create policy playlist_items_all on public.playlist_items
  for all to authenticated
  using (public.can_use_profile(public.playlist_profile(playlist_id)))
  with check (public.can_use_profile(public.playlist_profile(playlist_id)));

-- Écriture : profil nul = propriétaire seulement ; sinon le profil doit appartenir au foyer de la ligne.
create policy hidden_items_select on public.hidden_items
  for select to authenticated using (public.is_member(household_id));
create policy hidden_items_write on public.hidden_items
  for all to authenticated
  using (case when profile_id is null then public.is_owner(household_id) else public.can_use_profile(profile_id) end)
  with check (case when profile_id is null then public.is_owner(household_id)
    else public.can_use_profile(profile_id) and public.profile_household(profile_id) = household_id end);

create policy category_order_select on public.category_order
  for select to authenticated using (public.is_member(household_id));
create policy category_order_write on public.category_order
  for all to authenticated
  using (case when profile_id is null then public.is_owner(household_id) else public.can_use_profile(profile_id) end)
  with check (case when profile_id is null then public.is_owner(household_id)
    else public.can_use_profile(profile_id) and public.profile_household(profile_id) = household_id end);

create function public.hidden_for(p uuid) returns table (item_type text, item_id text)
language plpgsql stable security definer set search_path = public as $$
declare
  h uuid := public.profile_household(p);
begin
  if h is null or not public.is_member(h) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  return query
    select hi.item_type, hi.item_id from public.hidden_items hi
    where hi.household_id = h and (hi.profile_id is null or hi.profile_id = p);
end
$$;

revoke execute on function public.hidden_for(uuid) from public, anon;
grant execute on function public.hidden_for(uuid) to authenticated;
