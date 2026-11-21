-- Regarder l'IPTV sur téléphone / navigateur (21 nov. 2026) :
-- 1. `home_server.public_url` : adresse HTTPS publique du tunnel du serveur maison (ex. Cloudflare Tunnel / Tailscale),
--    publiée automatiquement par le serveur maison et lue par la page iPhone.
-- 2. `home_server_web_tokens` + `issue_web_server_token(h, client_key)` : un jeton par téléphone/navigateur (identifié
--    par `client_key` gardé dans localStorage) pour que plusieurs téléphones du foyer puissent regarder ou naviguer en
--    même temps sans révoquer le jeton des autres téléphones ni des télés.
-- 3. `check_home_server_token(h, token)` accepte aussi bien les jetons des télés que ceux des téléphones du foyer.

alter table public.home_server add column if not exists public_url text;

create or replace function public.register_home_server_public_url(h uuid, pub_url text) returns void
language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null or not exists (
    select 1 from public.home_server s
    join public.household_members m on m.household_id = s.household_id and m.user_id = s.server_user_id
    where s.household_id = h and s.server_user_id = auth.uid() and m.role = 'device'
  ) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  if pub_url is not null and pub_url <> '' and pub_url not like 'https://_%' then
    raise exception 'adresse-invalide';
  end if;
  update public.home_server
     set public_url = case when pub_url is null or pub_url = '' then null else left(btrim(pub_url), 300) end,
         last_seen = now()
   where household_id = h;
end
$$;

revoke all on function public.register_home_server_public_url(uuid, text) from public, anon;
grant execute on function public.register_home_server_public_url(uuid, text) to authenticated;

create table if not exists public.home_server_web_tokens (
  household_id uuid not null references public.households on delete cascade,
  client_key text not null,
  user_id uuid not null,
  token_hash bytea not null,
  created_at timestamptz not null default now(),
  primary key (household_id, client_key)
);

alter table public.home_server_web_tokens enable row level security;
revoke all on public.home_server_web_tokens from public, anon, authenticated;

create or replace function public.issue_web_server_token(h uuid, client_key text) returns text
language plpgsql security definer set search_path = public as $$
declare
  token text;
  k text;
begin
  if not public.is_member(h) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  k := btrim(coalesce(client_key, ''));
  if k !~ '^[0-9A-Za-z_-]{8,64}$' then
    raise exception 'cle-invalide' using errcode = '22023';
  end if;
  token := encode(extensions.gen_random_bytes(32), 'hex');
  insert into public.home_server_web_tokens (household_id, client_key, user_id, token_hash, created_at)
  values (h, k, auth.uid(), extensions.digest(token, 'sha256'), now())
  on conflict (household_id, client_key) do update
    set user_id = excluded.user_id,
        token_hash = excluded.token_hash,
        created_at = excluded.created_at;
  return token;
end
$$;

revoke all on function public.issue_web_server_token(uuid, text) from public, anon;
grant execute on function public.issue_web_server_token(uuid, text) to authenticated;

create or replace function public.check_home_server_token(h uuid, token text) returns uuid
language plpgsql security definer set search_path = public as $$
declare
  dev uuid;
  th bytea;
begin
  if auth.uid() is null or not exists (
    select 1 from public.home_server s
    join public.household_members sm on sm.household_id = s.household_id and sm.user_id = s.server_user_id
    where s.household_id = h and s.server_user_id = auth.uid() and sm.role = 'device'
  ) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  if token is null or token = '' then return null; end if;
  th := extensions.digest(token, 'sha256');
  select t.device_user_id into dev
    from public.home_server_tokens t
    join public.household_members m on m.household_id = t.household_id and m.user_id = t.device_user_id
   where t.household_id = h and t.token_hash = th;
  if dev is not null then
    return dev;
  end if;
  select w.user_id into dev
    from public.home_server_web_tokens w
    join public.household_members m on m.household_id = w.household_id and m.user_id = w.user_id
   where w.household_id = h and w.token_hash = th
   limit 1;
  return dev;
end
$$;
