-- Correctifs après la revue d'ensemble du plan 2.
--
-- Codes d'erreur ajoutés :
--   foyer-existe-deja     : errcode 42501 (refus : ce service ne sert qu'une famille)
--   trop-de-profils       : errcode P0001 (entrée invalide, comme identifiants-incomplets)
--   nom-appareil-invalide : errcode P0001 (remplace nom-appareil-vide)

-- 2. Un seul foyer : personne d'autre ne peut devenir propriétaire en s'inscrivant.

alter table public.households
  add constraint households_name_check check (char_length(btrim(name)) between 1 and 40);

create or replace function public.create_household(name text, profile_names text[]) returns uuid
language plpgsql security definer set search_path = public as $$
declare
  uid uuid := auth.uid();
  h uuid;
  i int;
begin
  if uid is null or exists (select 1 from auth.users u where u.id = uid and u.is_anonymous) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  if coalesce(array_length(profile_names, 1), 0) > 10 then
    raise exception 'trop-de-profils';
  end if;
  -- Sérialise deux créations simultanées : la seconde voit le foyer de la première.
  lock table public.households in share row exclusive mode;
  if exists (select 1 from public.households) then
    raise exception 'foyer-existe-deja' using errcode = '42501';
  end if;
  insert into public.households (name, owner_id) values (btrim(create_household.name), uid) returning id into h;
  insert into public.household_members (household_id, user_id, role) values (h, uid, 'owner');
  for i in 1 .. coalesce(array_length(profile_names, 1), 0) loop
    insert into public.profiles (id, household_id, name, position)
    values (gen_random_uuid(), h, profile_names[i], i - 1);
  end loop;
  return h;
end
$$;

-- 1. Deux ordinateurs connectés au compte du propriétaire ne se chassent plus.
--    « Ma lecture précédente » = même utilisateur ET même installation (device_key, stable par installation).
--    Les lectures en cours sont éphémères (45 s) : on les vide pour ajouter la colonne obligatoire.

delete from public.active_streams;
alter table public.active_streams
  add column device_key text not null check (char_length(device_key) between 1 and 64);

drop function public.claim_stream(uuid, uuid, text, text, int, boolean);

create function public.claim_stream(
  h uuid, p uuid, device_name text, label text, max_connections int, device_key text, takeover boolean default false
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  me uuid := auth.uid();
  cap int := greatest(coalesce(max_connections, 1), 1);
  n int;
  occ jsonb;
  sid uuid;
begin
  if not public.is_member(h) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  if p is not null and public.profile_household(p) is distinct from h then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;

  -- Verrou par foyer : deux demandes simultanées sont sérialisées (revoke_device prend le même).
  perform 1 from public.households where id = h for update;

  -- 11. Un retrait d'appareil validé pendant l'attente du verrou est vu ici : pas de lecture fantôme.
  if not public.is_member(h) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;

  delete from public.active_streams s
   where s.household_id = h
     and (s.last_heartbeat < now() - interval '45 seconds'
          or (s.device_user_id = me and s.device_key = claim_stream.device_key));

  select count(*) into n from public.active_streams s where s.household_id = h;

  if n >= cap and not coalesce(takeover, false) then
    select coalesce(jsonb_agg(jsonb_build_object(
             'device_name', s.device_name, 'profile_name', pr.name, 'label', s.label)
             order by s.started_at), '[]'::jsonb)
      into occ
      from public.active_streams s
      left join public.profiles pr on pr.id = s.profile_id
     where s.household_id = h;
    return jsonb_build_object('status', 'occupe', 'occupants', occ);
  end if;

  if coalesce(takeover, false) then
    delete from public.active_streams s where s.household_id = h;
  end if;

  insert into public.active_streams (household_id, device_user_id, device_key, device_name, profile_id, label)
  values (h, me, claim_stream.device_key, claim_stream.device_name, p, claim_stream.label)
  returning id into sid;
  return jsonb_build_object('status', 'ok', 'stream_id', sid);
end
$$;

revoke execute on function public.claim_stream(uuid, uuid, text, text, int, text, boolean) from public, anon;
grant execute on function public.claim_stream(uuid, uuid, text, text, int, text, boolean) to authenticated;

-- 11. revoke_device prend le verrou du foyer avant de retirer le membre (même verrou que claim_stream).
create or replace function public.revoke_device(h uuid, device uuid) returns void
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
  perform 1 from public.households where id = h for update;
  delete from public.household_members where household_id = h and user_id = device and role = 'device';
end
$$;
