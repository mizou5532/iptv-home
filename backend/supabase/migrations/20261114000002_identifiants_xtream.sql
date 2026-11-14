-- Identifiants Xtream partagés par le foyer.

create table public.xtream_credentials (
  household_id uuid primary key references public.households on delete cascade,
  server text not null,
  username text not null,
  password text not null,
  updated_at timestamptz default now()
);

alter table public.xtream_credentials enable row level security;

-- Lecture par tous les membres (propriétaire et appareils) ; aucune écriture directe.
create policy xtream_credentials_select on public.xtream_credentials
  for select to authenticated using (public.is_member(household_id));

create function public.set_xtream_credentials(h uuid, server text, username text, password text) returns void
language plpgsql security definer set search_path = public as $$
declare
  s text := btrim(server);
  u text := btrim(username);
  p text := btrim(password);
begin
  if not public.is_owner(h) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  if coalesce(s, '') = '' or coalesce(u, '') = '' or coalesce(p, '') = '' then
    raise exception 'identifiants-incomplets';
  end if;
  insert into public.xtream_credentials (household_id, server, username, password)
  values (h, s, u, p)
  on conflict (household_id) do update
    set server = excluded.server, username = excluded.username,
        password = excluded.password, updated_at = now();
end
$$;

revoke execute on function public.set_xtream_credentials(uuid, text, text, text) from public, anon;
grant execute on function public.set_xtream_credentials(uuid, text, text, text) to authenticated;
