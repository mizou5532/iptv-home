-- Prendre la main (19 nov. 2026, demande du propriétaire). Avant, une vidéo n'était réservée que si la maison avait
-- des comptes supplémentaires : avec un seul compte, deux télés pouvaient lancer une vidéo en même temps et le
-- fournisseur coupait l'une des deux sans prévenir. Maintenant :
--
-- * chaque vidéo réserve un compte, même sans compte supplémentaire ;
-- * quand tout est pris, la télé montre qui regarde quoi et propose « Prendre la main » ou « Laisser regarder » ;
-- * « Prendre la main » libère la place de l'autre télé et la lui prend ; l'autre télé l'apprend à son prochain signe
--   de vie (5 s au plus), arrête sa vidéo et affiche qui a pris la main ;
-- * la télé qui regarde envoie ce qu'elle regarde avec son signe de vie (le changement de chaîne est donc visible).

create table public.stream_takeovers (
  stream_id uuid primary key,
  household_id uuid not null references public.households on delete cascade,
  victim_user_id uuid not null,
  by_device text not null,
  by_profile text,
  at timestamptz not null default now(),
  acked_at timestamptz
);
alter table public.stream_takeovers enable row level security;
revoke all on public.stream_takeovers from public, anon, authenticated;

drop function public.claim_account(uuid, uuid, text, text, text);
create function public.claim_account(h uuid, p uuid, device_name text, label text, device_key text, takeover uuid default null) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  me uuid := auth.uid();
  chosen int;
  cap int;
  occ jsonb;
  sid uuid;
  victim public.active_streams;
  dev text := left(coalesce(nullif(btrim(claim_account.device_name), ''), 'Télé'), 60);
begin
  if not public.is_member(h) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  if p is not null and public.profile_household(p) is distinct from h then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  -- Même verrou que revoke_device : deux télés ne prennent jamais le même compte.
  perform 1 from public.households where id = h for update;
  if not public.is_member(h) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;

  delete from public.active_streams s
   where s.household_id = h
     and (s.last_heartbeat < now() - interval '45 seconds'
          or (s.device_user_id = me and s.device_key = claim_account.device_key));
  delete from public.stream_takeovers t where t.household_id = h and t.at < now() - interval '1 day';

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

  -- Tout est pris et la personne a choisi « Prendre la main » sur une télé précise : sa place est libérée et donnée.
  if chosen is null and takeover is not null then
    select * into victim from public.active_streams s where s.id = takeover and s.household_id = h;
    if victim.id is not null then
      delete from public.active_streams s where s.id = victim.id;
      insert into public.stream_takeovers (stream_id, household_id, victim_user_id, by_device, by_profile)
      values (victim.id, h, victim.device_user_id, dev, (select name from public.profiles where id = p))
      on conflict (stream_id) do nothing;
      chosen := victim.account_position;
    end if;
  end if;

  if chosen is null then
    select coalesce(jsonb_agg(jsonb_build_object(
             'id', s.id, 'device_name', s.device_name, 'profile_name', pr.name, 'label', s.label, 'started_at', s.started_at)
             order by s.started_at), '[]'::jsonb)
      into occ
      from public.active_streams s
      left join public.profiles pr on pr.id = s.profile_id
     where s.household_id = h;
    return jsonb_build_object('status', 'occupe', 'occupants', occ);
  end if;

  insert into public.active_streams (household_id, device_user_id, device_key, device_name, profile_id, label, account_position)
  values (h, me, claim_account.device_key, dev, p, left(coalesce(claim_account.label, ''), 120), chosen)
  returning id into sid;
  return jsonb_build_object('status', 'ok', 'stream_id', sid, 'position', chosen,
                            'replaced', case when victim.id is not null then victim.id end);
end
$$;
revoke all on function public.claim_account(uuid, uuid, text, text, text, uuid) from public, anon;
grant execute on function public.claim_account(uuid, uuid, text, text, text, uuid) to authenticated;

-- Signe de vie, avec ce qui est regardé en ce moment (facultatif : les anciennes télés n'envoient que l'identifiant).
drop function public.heartbeat_stream(uuid);
create function public.heartbeat_stream(id uuid, label text default null) returns boolean
language plpgsql security definer set search_path = public as $$
declare
  n int;
begin
  update public.active_streams s
     set last_heartbeat = now(), label = coalesce(left(heartbeat_stream.label, 120), s.label)
   where s.id = heartbeat_stream.id and s.device_user_id = auth.uid()
     and public.is_member(s.household_id);
  get diagnostics n = row_count;
  return n > 0;
end
$$;
revoke all on function public.heartbeat_stream(uuid, text) from public, anon;
grant execute on function public.heartbeat_stream(uuid, text) to authenticated;

/** La télé dont la vidéo a été reprise : qui a pris la main (null si personne : simple coupure). */
create function public.stream_takeover(id uuid) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  t public.stream_takeovers;
begin
  update public.stream_takeovers x set acked_at = coalesce(x.acked_at, now())
   where x.stream_id = stream_takeover.id and x.victim_user_id = auth.uid()
  returning * into t;
  if t.stream_id is null then return null; end if;
  return jsonb_build_object('device_name', t.by_device, 'profile_name', t.by_profile);
end
$$;
revoke all on function public.stream_takeover(uuid) from public, anon;
grant execute on function public.stream_takeover(uuid) to authenticated;

/** La télé qui a pris la main : l'autre a-t-elle arrêté sa vidéo ? (elle attend quelques secondes au plus) */
create function public.takeover_done(id uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select coalesce((select t.acked_at is not null from public.stream_takeovers t
                    where t.stream_id = takeover_done.id and public.is_member(t.household_id)), true)
$$;
revoke all on function public.takeover_done(uuid) from public, anon;
grant execute on function public.takeover_done(uuid) to authenticated;
