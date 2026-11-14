-- Appairage de la télé et retrait d'un appareil.
--
-- Codes d'erreur levés par les fonctions :
--   acces-refuse  : errcode 42501 (comme les migrations 1 et 2)
--   code-invalide : errcode P0001 (défaut de `raise exception`, comme identifiants-incomplets)
--   code-expire   : errcode P0001
-- Les clients distinguent les cas par le message, pas par le code SQL.
--
-- pgcrypto vit dans le schéma `extensions` sur Supabase ; on l'y place aussi en local.

create schema if not exists extensions;
create extension if not exists pgcrypto with schema extensions;

create table public.pairing_codes (
  code text primary key,
  device_user_id uuid not null references auth.users on delete cascade,
  device_name text not null,
  created_at timestamptz default now(),
  expires_at timestamptz not null,
  household_id uuid references public.households on delete cascade,
  claimed_at timestamptz
);

create index pairing_codes_device_idx on public.pairing_codes (device_user_id);

-- RLS activée sans politique : accès uniquement par les fonctions security definer.
alter table public.pairing_codes enable row level security;

create function public.create_pairing_code(device_name text) returns text
language plpgsql security definer set search_path = public as $$
declare
  uid uuid := auth.uid();
  alphabet constant text := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
  n constant int := length('ABCDEFGHJKMNPQRSTUVWXYZ23456789');
  name text := btrim(device_name);
  c text;
  bytes bytea;
  i int;
  tries int := 0;
begin
  if uid is null then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  if coalesce(name, '') = '' then
    raise exception 'nom-appareil-vide';
  end if;
  delete from public.pairing_codes where device_user_id = uid and claimed_at is null;
  loop
    bytes := extensions.gen_random_bytes(6);
    c := '';
    for i in 0 .. 5 loop
      -- Rejet des octets >= 248 (8 * 31) pour éviter le biais du modulo.
      while get_byte(bytes, i) >= 248 loop
        bytes := set_byte(bytes, i, get_byte(extensions.gen_random_bytes(1), 0));
      end loop;
      c := c || substr(alphabet, (get_byte(bytes, i) % n) + 1, 1);
    end loop;
    begin
      insert into public.pairing_codes (code, device_user_id, device_name, expires_at)
      values (c, uid, name, now() + interval '10 minutes');
      return c;
    exception when unique_violation then
      tries := tries + 1;
      if tries >= 10 then raise; end if;
    end;
  end loop;
end
$$;

create function public.approve_pairing(h uuid, code text) returns text
language plpgsql security definer set search_path = public as $$
declare
  norm text := upper(regexp_replace(coalesce(code, ''), '\s', '', 'g'));
  p public.pairing_codes;
begin
  if not public.is_owner(h) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  select * into p from public.pairing_codes pc where pc.code = norm for update;
  if not found or p.claimed_at is not null then
    raise exception 'code-invalide';
  end if;
  if p.expires_at <= now() then
    raise exception 'code-expire';
  end if;
  update public.pairing_codes pc set household_id = h, claimed_at = now() where pc.code = norm;
  insert into public.household_members (household_id, user_id, role, device_name)
  values (h, p.device_user_id, 'device', p.device_name)
  on conflict (household_id, user_id) do nothing;
  return p.device_name;
end
$$;

create function public.my_households() returns setof public.households
language sql stable security definer set search_path = public as $$
  select hh.* from public.households hh
  where exists (
    select 1 from public.household_members m
    where m.household_id = hh.id and m.user_id = auth.uid()
  )
$$;

create function public.revoke_device(h uuid, device uuid) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_owner(h) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  if exists (
    select 1 from public.household_members m
    where m.household_id = h and m.user_id = device and m.role = 'owner'
  ) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  delete from public.household_members where household_id = h and user_id = device and role = 'device';
end
$$;

revoke execute on function public.create_pairing_code(text) from public, anon;
grant execute on function public.create_pairing_code(text) to authenticated;
revoke execute on function public.approve_pairing(uuid, text) from public, anon;
grant execute on function public.approve_pairing(uuid, text) to authenticated;
revoke execute on function public.my_households() from public, anon;
grant execute on function public.my_households() to authenticated;
revoke execute on function public.revoke_device(uuid, uuid) from public, anon;
grant execute on function public.revoke_device(uuid, uuid) to authenticated;
