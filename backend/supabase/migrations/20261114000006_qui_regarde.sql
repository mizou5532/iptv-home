-- « Qui regarde » : un seul flux à la fois sur le compte IPTV ; prise de la main entre appareils.
-- Une lecture est périmée après 45 s sans battement.

create table public.active_streams (
  id uuid primary key default gen_random_uuid(),
  household_id uuid not null references public.households on delete cascade,
  device_user_id uuid not null,
  device_name text not null,
  profile_id uuid references public.profiles on delete set null,
  label text not null,
  started_at timestamptz default now(),
  last_heartbeat timestamptz default now()
);
create index active_streams_household_idx on public.active_streams (household_id);
create index active_streams_device_idx on public.active_streams (device_user_id);

-- Les appareils écoutent la suppression de leur ligne pour s'arrêter.
alter table public.active_streams replica identity full;
alter publication supabase_realtime add table public.active_streams;

-- Lecture par les membres ; aucune écriture directe (tout passe par les fonctions).
alter table public.active_streams enable row level security;
create policy active_streams_select on public.active_streams
  for select to authenticated using (public.is_member(household_id));

create function public.claim_stream(
  h uuid, p uuid, device_name text, label text, max_connections int, takeover boolean default false
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

  -- Verrou par foyer : deux demandes simultanées sont sérialisées.
  perform 1 from public.households where id = h for update;

  delete from public.active_streams
   where household_id = h
     and (last_heartbeat < now() - interval '45 seconds' or device_user_id = me);

  select count(*) into n from public.active_streams where household_id = h;

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
    delete from public.active_streams where household_id = h;
  end if;

  insert into public.active_streams (household_id, device_user_id, device_name, profile_id, label)
  values (h, me, claim_stream.device_name, p, claim_stream.label)
  returning id into sid;
  return jsonb_build_object('status', 'ok', 'stream_id', sid);
end
$$;

create function public.heartbeat_stream(id uuid) returns boolean
language plpgsql security definer set search_path = public as $$
declare
  n int;
begin
  update public.active_streams s set last_heartbeat = now()
   where s.id = heartbeat_stream.id and s.device_user_id = auth.uid();
  get diagnostics n = row_count;
  return n > 0;
end
$$;

create function public.release_stream(id uuid) returns void
language plpgsql security definer set search_path = public as $$
begin
  delete from public.active_streams s
   where s.id = release_stream.id and s.device_user_id = auth.uid();
end
$$;

revoke execute on function public.claim_stream(uuid, uuid, text, text, int, boolean) from public, anon;
revoke execute on function public.heartbeat_stream(uuid) from public, anon;
revoke execute on function public.release_stream(uuid) from public, anon;
grant execute on function public.claim_stream(uuid, uuid, text, text, int, boolean) to authenticated;
grant execute on function public.heartbeat_stream(uuid) to authenticated;
grant execute on function public.release_stream(uuid) to authenticated;
