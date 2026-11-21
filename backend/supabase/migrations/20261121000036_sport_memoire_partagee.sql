-- Mémoire des chaînes du Sport partagée entre les télés de la maison, par profil (étape 4 du serveur maison).
-- Remplace le stockage isolé par télé (AndroidSportStore) : ce qu'une télé apprend ou met en étoile profite à toutes,
-- et reste disponible si le serveur maison est éteint. Fusion sans perte au premier lancement de chaque télé.

create table public.sport_profile_preferred (
  profile_id uuid not null references public.profiles on delete cascade,
  team_key text not null check (length(team_key) between 1 and 80),
  -- NULL = étoile retirée volontairement (évite qu'une autre télé non migrée la remette au premier envoi).
  group_id text check (group_id is null or length(group_id) between 1 and 200),
  updated_at timestamptz not null default now(),
  primary key (profile_id, team_key)
);
alter table public.sport_profile_preferred enable row level security;
create policy sport_profile_preferred_select on public.sport_profile_preferred
  for select to authenticated using (public.can_use_profile(profile_id));
revoke all on public.sport_profile_preferred from public, anon, authenticated;
grant select on public.sport_profile_preferred to authenticated;

create table public.sport_profile_learned (
  profile_id uuid not null references public.profiles on delete cascade,
  league_key text not null check (length(league_key) between 1 and 40),
  group_id text not null check (length(group_id) between 1 and 200),
  used_at timestamptz not null default now(),
  primary key (profile_id, league_key, group_id)
);
alter table public.sport_profile_learned enable row level security;
create policy sport_profile_learned_select on public.sport_profile_learned
  for select to authenticated using (public.can_use_profile(profile_id));
revoke all on public.sport_profile_learned from public, anon, authenticated;
grant select on public.sport_profile_learned to authenticated;

-- Lecture groupée en JSON (jamais tronquée par la limite de 1 000 lignes de PostgREST).
create function public.sport_memory_get(p uuid) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  pref_json jsonb;
  learned_json jsonb;
begin
  if not public.can_use_profile(p) then raise exception 'forbidden' using errcode = '42501'; end if;

  select coalesce(jsonb_object_agg(team_key, group_id), '{}'::jsonb) into pref_json
  from public.sport_profile_preferred
  where profile_id = p and group_id is not null;

  select coalesce(jsonb_object_agg(league_key, groups), '{}'::jsonb) into learned_json
  from (
    select league_key, jsonb_agg(group_id order by used_at desc, group_id asc) as groups
    from (
      select league_key, group_id, used_at,
             row_number() over (partition by league_key order by used_at desc, group_id asc) as rn
      from public.sport_profile_learned
      where profile_id = p
    ) ranked
    where rn <= 6
    group by league_key
  ) by_league;

  return jsonb_build_object('preferred', pref_json, 'learned', learned_json);
end $$;
revoke all on function public.sport_memory_get(uuid) from public, anon;
grant execute on function public.sport_memory_get(uuid) to authenticated;

-- Enregistre ou retire (group_id = null) la chaîne préférée (étoile) d'une équipe pour ce profil.
create function public.sport_set_preferred(p uuid, team_key text, group_id text) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not public.can_use_profile(p) then raise exception 'forbidden' using errcode = '42501'; end if;
  if team_key is null or length(team_key) not between 1 and 80 then
    raise exception 'bad team_key' using errcode = '22023';
  end if;
  if group_id is not null and length(group_id) not between 1 and 200 then
    raise exception 'bad group_id' using errcode = '22023';
  end if;
  insert into public.sport_profile_preferred (profile_id, team_key, group_id, updated_at)
  values (p, sport_set_preferred.team_key, sport_set_preferred.group_id, now())
  on conflict on constraint sport_profile_preferred_pkey do update
    set group_id = excluded.group_id, updated_at = now();
end $$;
revoke all on function public.sport_set_preferred(uuid, text, text) from public, anon;
grant execute on function public.sport_set_preferred(uuid, text, text) to authenticated;

-- Ajoute une chaîne apprise pour une ligue (gardée en tête, au plus 6 par ligue).
create function public.sport_learn_channel(p uuid, league_key text, group_id text) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not public.can_use_profile(p) then raise exception 'forbidden' using errcode = '42501'; end if;
  if league_key is null or length(league_key) not between 1 and 40 then
    raise exception 'bad league_key' using errcode = '22023';
  end if;
  if group_id is null or length(group_id) not between 1 and 200 then
    raise exception 'bad group_id' using errcode = '22023';
  end if;
  insert into public.sport_profile_learned (profile_id, league_key, group_id, used_at)
  values (p, sport_learn_channel.league_key, sport_learn_channel.group_id, now())
  on conflict on constraint sport_profile_learned_pkey do update
    set used_at = now();

  delete from public.sport_profile_learned as t
  where t.profile_id = p
    and t.league_key = sport_learn_channel.league_key
    and t.group_id in (
      select l.group_id
      from public.sport_profile_learned l
      where l.profile_id = p and l.league_key = sport_learn_channel.league_key
      order by l.used_at desc, l.group_id asc
      offset 6
    );
end $$;
revoke all on function public.sport_learn_channel(uuid, text, text) from public, anon;
grant execute on function public.sport_learn_channel(uuid, text, text) to authenticated;

-- Retire une chaîne apprise (ex. « Pas le bon match »).
create function public.sport_forget_learned(p uuid, league_key text, group_id text) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not public.can_use_profile(p) then raise exception 'forbidden' using errcode = '42501'; end if;
  delete from public.sport_profile_learned as t
  where t.profile_id = p
    and t.league_key = sport_forget_learned.league_key
    and t.group_id = sport_forget_learned.group_id;
end $$;
revoke all on function public.sport_forget_learned(uuid, text, text) from public, anon;
grant execute on function public.sport_forget_learned(uuid, text, text) to authenticated;

-- Fusion initiale sans perte : une télé envoie sa mémoire locale ; ce qui existe déjà sur le serveur est préservé,
-- les ajouts locaux complètent les équipes absentes et fusionnent les chaînes apprises (jusqu'à 6 par ligue).
create function public.sport_merge_memory(p uuid, pref jsonb, learned jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  k text;
  v text;
  lg text;
  arr jsonb;
  gid text;
  idx int;
  n int := 0;
  nl int := 0;
begin
  if not public.can_use_profile(p) then raise exception 'forbidden' using errcode = '42501'; end if;

  if pref is not null and jsonb_typeof(pref) = 'object' then
    for k, v in select key, value from jsonb_each_text(pref) loop
      n := n + 1;
      exit when n > 200;
      if length(k) between 1 and 80 and v is not null and length(v) between 1 and 200 then
        insert into public.sport_profile_preferred (profile_id, team_key, group_id, updated_at)
        values (p, k, v, now())
        on conflict on constraint sport_profile_preferred_pkey do nothing;
      end if;
    end loop;
  end if;

  if learned is not null and jsonb_typeof(learned) = 'object' then
    for lg, arr in select key, value from jsonb_each(learned) loop
      nl := nl + 1;
      exit when nl > 50;
      if length(lg) between 1 and 40 and jsonb_typeof(arr) = 'array' then
        idx := 0;
        for gid in select value from jsonb_array_elements_text(arr) loop
          exit when idx >= 6;
          if gid is not null and length(gid) between 1 and 200 then
            -- Décalage d'une seconde par rang pour garder l'ordre local tout en laissant les plus récentes gagner.
            insert into public.sport_profile_learned (profile_id, league_key, group_id, used_at)
            values (p, lg, gid, now() - make_interval(secs => idx + 1))
            on conflict on constraint sport_profile_learned_pkey do nothing;
          end if;
          idx := idx + 1;
        end loop;

        delete from public.sport_profile_learned
        where profile_id = p
          and league_key = lg
          and group_id in (
            select l.group_id
            from public.sport_profile_learned l
            where l.profile_id = p and l.league_key = lg
            order by l.used_at desc, l.group_id asc
            offset 6
          );
      end if;
    end loop;
  end if;

  return public.sport_memory_get(p);
end $$;
revoke all on function public.sport_merge_memory(uuid, jsonb, jsonb) from public, anon;
grant execute on function public.sport_merge_memory(uuid, jsonb, jsonb) to authenticated;
