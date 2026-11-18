-- Comptes IPTV supplémentaires (19 nov. 2026). Le fournisseur n'autorise qu'une vidéo à la fois par compte : pour
-- regarder à deux en même temps, le foyer ajoute d'autres comptes du même fournisseur (même serveur que le compte
-- principal). Le catalogue est toujours lu avec le compte principal ; les autres servent seulement à regarder.
--
-- Vérification : le fournisseur ne parle qu'en http et seule la télé peut l'interroger. Un compte ajouté sur l'iPhone
-- est « à vérifier » ; la télé teste qu'il est accepté, actif, et qu'il donne le même catalogue que le compte
-- principal, puis rapporte le résultat. Seul un compte « ok » sera utilisé.

create table public.xtream_extra_accounts (
  id uuid primary key default gen_random_uuid(),
  household_id uuid not null references public.households on delete cascade,
  position int not null check (position between 2 and 9),   -- ordre d'utilisation : compte 2, compte 3…
  username text not null,
  password text not null,
  check_status text not null default 'a_verifier' check (check_status in ('a_verifier', 'ok', 'refuse')),
  -- Raison courte d'un refus : 'identifiants', 'expire', 'catalogue', 'meme_compte'.
  check_reason text check (check_reason is null or check_reason in ('identifiants', 'expire', 'catalogue', 'meme_compte')),
  expires_at timestamptz,
  checked_at timestamptz,
  created_at timestamptz not null default now(),
  unique (household_id, position)
);
create index xtream_extra_accounts_household_idx on public.xtream_extra_accounts (household_id);

alter table public.xtream_extra_accounts enable row level security;
-- Lecture par les membres (la télé a besoin des identifiants ; la page ne lit que l'état) ; aucune écriture directe.
create policy xtream_extra_accounts_select on public.xtream_extra_accounts
  for select to authenticated using (public.is_member(household_id));
revoke all on public.xtream_extra_accounts from public, anon, authenticated;
grant select on public.xtream_extra_accounts to authenticated;

-- Les télés suivent la révision des réglages du foyer : un compte ajouté ou retiré est vu (et vérifié) en quelques secondes.
create function public.extra_accounts_bump(h uuid) returns void
language sql security definer set search_path = public as $$
  insert into public.prefs_revision as pr (household_id, rev, updated_at) values (h, 1, now())
  on conflict (household_id) do update set rev = pr.rev + 1, updated_at = now();
$$;
revoke execute on function public.extra_accounts_bump(uuid) from public, anon, authenticated;

/** Le propriétaire ajoute un compte (au plus 4 de plus) ; rend son identifiant. */
create function public.add_extra_account(h uuid, username text, password text) returns uuid
language plpgsql security definer set search_path = public as $$
declare
  u text := btrim(username);
  p text := btrim(password);
  pos int;
  new_id uuid;
begin
  if not public.is_owner(h) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  if coalesce(u, '') = '' or coalesce(p, '') = '' then
    raise exception 'identifiants-incomplets';
  end if;
  if not exists (select 1 from public.xtream_credentials c where c.household_id = h) then
    raise exception 'compte-principal-absent';
  end if;
  if exists (select 1 from public.xtream_credentials c where c.household_id = h and c.username = u)
     or exists (select 1 from public.xtream_extra_accounts a where a.household_id = h and a.username = u) then
    raise exception 'compte-deja-ajoute';
  end if;
  -- Verrou du foyer : deux ajouts simultanés ne prennent pas la même place.
  perform 1 from public.households where households.id = h for update;
  if (select count(*) from public.xtream_extra_accounts a where a.household_id = h) >= 4 then
    raise exception 'trop-de-comptes';
  end if;
  -- Première place libre (un compte retiré laisse sa place au suivant ajouté).
  select min(g) into pos from generate_series(2, 9) g
   where not exists (select 1 from public.xtream_extra_accounts a where a.household_id = h and a.position = g);
  insert into public.xtream_extra_accounts (household_id, position, username, password)
  values (h, pos, u, p) returning xtream_extra_accounts.id into new_id;
  perform public.extra_accounts_bump(h);
  return new_id;
end
$$;
revoke all on function public.add_extra_account(uuid, text, text) from public, anon;
grant execute on function public.add_extra_account(uuid, text, text) to authenticated;

create function public.remove_extra_account(h uuid, account uuid) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_owner(h) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  delete from public.xtream_extra_accounts a where a.household_id = h and a.id = account;
  perform public.extra_accounts_bump(h);
end
$$;
revoke all on function public.remove_extra_account(uuid, uuid) from public, anon;
grant execute on function public.remove_extra_account(uuid, uuid) to authenticated;

/** La télé rapporte le résultat de sa vérification ('ok' ou 'refuse' avec une raison). */
create function public.report_extra_account(h uuid, account uuid, ok boolean, reason text, expires_at timestamptz) returns void
language plpgsql security definer set search_path = public as $$
begin
  perform public.org_assert(h, null, false);
  if not ok and (reason is null or reason not in ('identifiants', 'expire', 'catalogue', 'meme_compte')) then
    raise exception 'raison-inconnue';
  end if;
  update public.xtream_extra_accounts a
     set check_status = case when ok then 'ok' else 'refuse' end,
         check_reason = case when ok then null else reason end,
         expires_at = report_extra_account.expires_at,
         checked_at = now()
   where a.household_id = h and a.id = account;
end
$$;
revoke all on function public.report_extra_account(uuid, uuid, boolean, text, timestamptz) from public, anon;
grant execute on function public.report_extra_account(uuid, uuid, boolean, text, timestamptz) to authenticated;

-- Compte principal changé (autre fournisseur, autre serveur) : les comptes supplémentaires sont à revérifier.
create function public.extra_accounts_recheck() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if tg_op = 'UPDATE' and new.server is not distinct from old.server and new.username is not distinct from old.username
     and new.password is not distinct from old.password then
    return null;
  end if;
  update public.xtream_extra_accounts a set check_status = 'a_verifier', check_reason = null
   where a.household_id = new.household_id;
  return null;
end
$$;
revoke execute on function public.extra_accounts_recheck() from public, anon, authenticated;
create trigger xtream_credentials_recheck_extra after insert or update on public.xtream_credentials
  for each row execute function public.extra_accounts_recheck();

-- Attribution d'un compte au moment de regarder. Une télé qui lance une vidéo réserve le premier compte libre
-- (1 = principal, puis les comptes « ok » dans l'ordre) dans active_streams ; elle confirme toutes les 15 s
-- (heartbeat_stream) et le rend en arrêtant (release_stream). Une réservation sans signe de vie depuis 45 s est libre.
-- Une installation a au plus une réservation (sa demande remplace la précédente).
delete from public.active_streams;
alter table public.active_streams add column account_position int not null default 1;

create function public.claim_account(h uuid, p uuid, device_name text, label text, device_key text) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  me uuid := auth.uid();
  chosen int;
  occ jsonb;
  sid uuid;
begin
  if not public.is_member(h) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  if p is not null and public.profile_household(p) is distinct from h then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  -- Même verrou que claim_stream et revoke_device : deux télés ne prennent jamais le même compte.
  perform 1 from public.households where id = h for update;
  if not public.is_member(h) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;

  delete from public.active_streams s
   where s.household_id = h
     and (s.last_heartbeat < now() - interval '45 seconds'
          or (s.device_user_id = me and s.device_key = claim_account.device_key));

  select min(c.pos) into chosen
    from (select 1 as pos
          union all
          select a.position from public.xtream_extra_accounts a where a.household_id = h and a.check_status = 'ok') c
   where c.pos not in (select s.account_position from public.active_streams s where s.household_id = h);

  if chosen is null then
    select coalesce(jsonb_agg(jsonb_build_object(
             'device_name', s.device_name, 'profile_name', pr.name, 'label', s.label)
             order by s.started_at), '[]'::jsonb)
      into occ
      from public.active_streams s
      left join public.profiles pr on pr.id = s.profile_id
     where s.household_id = h;
    return jsonb_build_object('status', 'occupe', 'occupants', occ);
  end if;

  insert into public.active_streams (household_id, device_user_id, device_key, device_name, profile_id, label, account_position)
  values (h, me, claim_account.device_key, left(coalesce(nullif(btrim(claim_account.device_name), ''), 'Télé'), 60), p, left(coalesce(claim_account.label, ''), 120), chosen)
  returning id into sid;
  return jsonb_build_object('status', 'ok', 'stream_id', sid, 'position', chosen);
end
$$;
revoke all on function public.claim_account(uuid, uuid, text, text, text) from public, anon;
grant execute on function public.claim_account(uuid, uuid, text, text, text) to authenticated;
