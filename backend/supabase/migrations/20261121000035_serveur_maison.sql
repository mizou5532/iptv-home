-- Serveur maison, étape 1 (21 nov. 2026) : un petit serveur à la maison (appairé comme une télé, rôle `device`)
-- déclare son adresse sur le réseau local (https) et l'empreinte de son certificat (sha256/<base64> de la clé
-- publique). Les télés lisent l'adresse et l'empreinte pour s'y connecter en vérifiant le certificat.
--
-- Jeton par télé : une télé demande un jeton aléatoire (32 octets, en hexadécimal) qu'elle présente au serveur
-- maison ; seul le serveur enregistré peut le faire vérifier ici. La session Supabase de la télé ne quitte jamais
-- la télé. On ne garde que l'empreinte sha256 du jeton ; un jeton cesse de valoir dès que l'appareil quitte le foyer
-- (revoke_device). Un nouvel appel de issue_home_server_token remplace l'ancien jeton du même appareil.
--
-- Aucun identifiant IPTV n'est stocké ici.

-- pgcrypto vit dans le schéma `extensions` sur Supabase (et en local, voir 0003).
create extension if not exists pgcrypto with schema extensions;

create table public.home_server (
  household_id uuid primary key references public.households on delete cascade,
  server_user_id uuid not null,
  url text not null,
  pin text not null,
  version text,
  last_seen timestamptz not null default now()
);
alter table public.home_server enable row level security;
create policy home_server_select on public.home_server for select to authenticated using (public.is_member(household_id));
revoke all on public.home_server from public, anon, authenticated;
grant select on public.home_server to authenticated;

-- Jetons : RLS sans politique, aucun droit de table ; accès par les fonctions seulement.
create table public.home_server_tokens (
  household_id uuid not null references public.households on delete cascade,
  device_user_id uuid not null,
  token_hash bytea not null,
  created_at timestamptz not null default now(),
  primary key (household_id, device_user_id)
);
alter table public.home_server_tokens enable row level security;
revoke all on public.home_server_tokens from public, anon, authenticated;

/** Le serveur maison (membre `device`) déclare son adresse https et l'empreinte de son certificat. */
create function public.register_home_server(h uuid, url text, pin text, version text) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not exists (select 1 from public.household_members m
                  where m.household_id = h and m.user_id = auth.uid() and m.role = 'device') then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  if url is null or url not like 'https://_%' then raise exception 'adresse-invalide'; end if;
  if pin is null or pin not like 'sha256/_%' then raise exception 'empreinte-invalide'; end if;
  insert into public.home_server (household_id, server_user_id, url, pin, version, last_seen)
  values (h, auth.uid(), left(url, 300), left(pin, 100), left(btrim(coalesce(version, '')), 20), now())
  on conflict (household_id) do update
    set server_user_id = excluded.server_user_id, url = excluded.url, pin = excluded.pin,
        version = excluded.version, last_seen = excluded.last_seen;
end
$$;

/** Un membre obtient son jeton pour le serveur maison (remplace l'ancien). Rend le jeton en clair, une seule fois. */
create function public.issue_home_server_token(h uuid) returns text
language plpgsql security definer set search_path = public as $$
declare
  token text;
begin
  if not public.is_member(h) then raise exception 'acces-refuse' using errcode = '42501'; end if;
  token := encode(extensions.gen_random_bytes(32), 'hex');
  insert into public.home_server_tokens (household_id, device_user_id, token_hash, created_at)
  values (h, auth.uid(), extensions.digest(token, 'sha256'), now())
  on conflict (household_id, device_user_id) do update
    set token_hash = excluded.token_hash, created_at = excluded.created_at;
  return token;
end
$$;

/** Le serveur enregistré vérifie un jeton : rend l'identifiant de la télé, ou null (faux jeton, appareil retiré). */
create function public.check_home_server_token(h uuid, token text) returns uuid
language plpgsql security definer set search_path = public as $$
declare
  dev uuid;
begin
  if auth.uid() is null or not exists (select 1 from public.home_server s
                                        where s.household_id = h and s.server_user_id = auth.uid()) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  if token is null or token = '' then return null; end if;
  select t.device_user_id into dev
    from public.home_server_tokens t
    join public.household_members m on m.household_id = t.household_id and m.user_id = t.device_user_id
   where t.household_id = h and t.token_hash = extensions.digest(token, 'sha256');
  return dev;
end
$$;

revoke all on function public.register_home_server(uuid, text, text, text) from public, anon;
grant execute on function public.register_home_server(uuid, text, text, text) to authenticated;
revoke all on function public.issue_home_server_token(uuid) from public, anon;
grant execute on function public.issue_home_server_token(uuid) to authenticated;
revoke all on function public.check_home_server_token(uuid, text) from public, anon;
grant execute on function public.check_home_server_token(uuid, text) to authenticated;
