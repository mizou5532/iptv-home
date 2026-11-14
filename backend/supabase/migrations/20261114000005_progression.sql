-- Progression de lecture et « Continuer à regarder ».

create table public.watch_progress (
  profile_id uuid not null references public.profiles on delete cascade,
  item_type text not null check (item_type in ('movie', 'episode')),
  item_id text not null,
  series_id text,
  season int,
  episode int,
  position_s int not null check (position_s >= 0),
  duration_s int not null check (duration_s > 0),
  watched boolean not null default false,
  client_updated_at timestamptz not null,
  primary key (profile_id, item_type, item_id)
);
create index watch_progress_recent_idx on public.watch_progress (profile_id, client_updated_at desc);

-- Lecture seule en direct : les écritures passent par record_progress.
alter table public.watch_progress enable row level security;
create policy watch_progress_select on public.watch_progress
  for select to authenticated using (public.can_use_profile(profile_id));

-- Le plus récent gagne, de façon atomique ; « vu » ne redevient jamais faux.
create function public.record_progress(
  p uuid, item_type text, item_id text, position_s int, duration_s int,
  client_updated_at timestamptz, series_id text default null, season int default null, episode int default null
) returns boolean
language plpgsql security definer set search_path = public as $$
declare
  n int;
begin
  if not public.can_use_profile(p) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  insert into public.watch_progress as w
    (profile_id, item_type, item_id, series_id, season, episode, position_s, duration_s, watched, client_updated_at)
  values
    (p, record_progress.item_type, record_progress.item_id, record_progress.series_id, record_progress.season,
     record_progress.episode, record_progress.position_s, record_progress.duration_s,
     record_progress.position_s >= 0.9 * record_progress.duration_s, record_progress.client_updated_at)
  on conflict on constraint watch_progress_pkey do update set
    series_id = excluded.series_id,
    season = excluded.season,
    episode = excluded.episode,
    position_s = excluded.position_s,
    duration_s = excluded.duration_s,
    watched = w.watched or excluded.watched,
    client_updated_at = excluded.client_updated_at
  where w.client_updated_at < excluded.client_updated_at;
  get diagnostics n = row_count;
  return n > 0;
end
$$;

create function public.continue_watching(p uuid, max_rows int default 20)
returns setof public.watch_progress
language plpgsql stable security definer set search_path = public as $$
begin
  if not public.can_use_profile(p) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  return query
    select r.profile_id, r.item_type, r.item_id, r.series_id, r.season, r.episode,
           r.position_s, r.duration_s, r.watched, r.client_updated_at
    from (
      select distinct on (coalesce(w.series_id, w.item_type || ':' || w.item_id)) w.*
      from public.watch_progress w
      where w.profile_id = p and not w.watched and w.position_s > 0
      order by coalesce(w.series_id, w.item_type || ':' || w.item_id), w.client_updated_at desc
    ) r
    order by r.client_updated_at desc
    limit greatest(max_rows, 0);
end
$$;

revoke execute on function public.record_progress(uuid, text, text, int, int, timestamptz, text, int, int) from public, anon;
revoke execute on function public.continue_watching(uuid, int) from public, anon;
grant execute on function public.record_progress(uuid, text, text, int, int, timestamptz, text, int, int) to authenticated;
grant execute on function public.continue_watching(uuid, int) to authenticated;
