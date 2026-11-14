-- Foyer, membres et profils.

create table public.households (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  owner_id uuid not null references auth.users,
  created_at timestamptz default now()
);

create table public.household_members (
  household_id uuid references public.households on delete cascade,
  user_id uuid references auth.users on delete cascade,
  role text check (role in ('owner', 'device')),
  device_name text,
  joined_at timestamptz default now(),
  primary key (household_id, user_id)
);

create table public.profiles (
  id uuid primary key,
  household_id uuid references public.households on delete cascade,
  name text check (char_length(btrim(name)) between 1 and 30),
  color text not null default '#2C64F0',
  home text check (home in ('sport', 'series', 'films', 'mixte')) default 'mixte',
  theme text check (theme in ('sombre', 'clair', 'fun')) default 'sombre',
  settings jsonb not null default '{}',
  position int not null default 0,
  created_at timestamptz default now()
);

create index profiles_household_idx on public.profiles (household_id);
create index household_members_user_idx on public.household_members (user_id);

create function public.is_member(h uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from public.household_members
    where household_id = h and user_id = auth.uid()
  )
$$;

create function public.is_owner(h uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from public.household_members
    where household_id = h and user_id = auth.uid() and role = 'owner'
  )
$$;

create function public.create_household(name text, profile_names text[]) returns uuid
language plpgsql security definer set search_path = public as $$
declare
  uid uuid := auth.uid();
  h uuid;
  i int;
begin
  if uid is null or exists (select 1 from auth.users u where u.id = uid and u.is_anonymous) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  insert into public.households (name, owner_id) values (name, uid) returning id into h;
  insert into public.household_members (household_id, user_id, role) values (h, uid, 'owner');
  for i in 1 .. coalesce(array_length(profile_names, 1), 0) loop
    insert into public.profiles (id, household_id, name, position)
    values (gen_random_uuid(), h, profile_names[i], i - 1);
  end loop;
  return h;
end
$$;

revoke execute on function public.create_household(text, text[]) from public, anon;
grant execute on function public.create_household(text, text[]) to authenticated;

alter table public.households enable row level security;
alter table public.household_members enable row level security;
alter table public.profiles enable row level security;

create policy households_select on public.households
  for select to authenticated using (public.is_member(id));
create policy households_update on public.households
  for update to authenticated using (public.is_owner(id)) with check (public.is_owner(id));

create policy members_select on public.household_members
  for select to authenticated using (public.is_member(household_id));

create policy profiles_select on public.profiles
  for select to authenticated using (public.is_member(household_id));
create policy profiles_insert on public.profiles
  for insert to authenticated with check (public.is_member(household_id));
create policy profiles_update on public.profiles
  for update to authenticated using (public.is_member(household_id)) with check (public.is_member(household_id));
create policy profiles_delete on public.profiles
  for delete to authenticated using (public.is_member(household_id));
