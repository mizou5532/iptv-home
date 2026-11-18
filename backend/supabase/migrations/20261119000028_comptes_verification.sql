-- Comptes IPTV supplémentaires, vérification complète (19 nov. 2026, demande du propriétaire) :
-- * films et séries comparés aussi (raisons « films », « series », « films_series » : mêmes chaînes du Direct, mais pas
--   les mêmes films et/ou séries que le compte principal) ;
-- * la télé revérifie chaque compte une fois par jour (date de fin, compte devenu inactif) ;
-- * un compte dont la date de fin est passée n'est jamais donné à une télé, même avant sa prochaine vérification.

alter table public.xtream_extra_accounts drop constraint xtream_extra_accounts_check_reason_check;
alter table public.xtream_extra_accounts add constraint xtream_extra_accounts_check_reason_check
  check (check_reason is null or check_reason in ('identifiants', 'expire', 'catalogue', 'meme_compte', 'films', 'series', 'films_series'));

create or replace function public.report_extra_account(h uuid, account uuid, ok boolean, reason text, expires_at timestamptz) returns void
language plpgsql security definer set search_path = public as $$
begin
  perform public.org_assert(h, null, false);
  if not ok and (reason is null or reason not in ('identifiants', 'expire', 'catalogue', 'meme_compte', 'films', 'series', 'films_series')) then
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

create or replace function public.claim_account(h uuid, p uuid, device_name text, label text, device_key text) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  me uuid := auth.uid();
  chosen int;
  cap int;
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

  -- Aucun compte supplémentaire : rien n'est réservé ni refusé (comme avant cette fonction).
  if not exists (select 1 from public.xtream_extra_accounts a where a.household_id = h) then
    return jsonb_build_object('status', 'ok', 'stream_id', null, 'position', 1);
  end if;

  -- Compte principal : autant de vidéos que le fournisseur en permet (lu par la télé, 1 si inconnu).
  select greatest(coalesce(i.max_connections, 1), 1) into cap from public.iptv_account i where i.household_id = h;
  cap := coalesce(cap, 1);
  if (select count(*) from public.active_streams s where s.household_id = h and s.account_position = 1) < cap then
    chosen := 1;
  else
    -- Jamais un compte dont la date de fin est passée (même s'il n'a pas encore été revérifié).
    select min(a.position) into chosen from public.xtream_extra_accounts a
     where a.household_id = h and a.check_status = 'ok'
       and (a.expires_at is null or a.expires_at > now())
       and a.position not in (select s.account_position from public.active_streams s where s.household_id = h);
  end if;

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
