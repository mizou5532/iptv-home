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
