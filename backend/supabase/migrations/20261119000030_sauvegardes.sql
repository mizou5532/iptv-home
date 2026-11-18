-- Sauvegardes de la maison (19 nov. 2026, demande du propriétaire : « un maximum de sécurité de sauvegarde »).
--
-- * Copie complète de ce qui compte : profils et réglages, progression (et « vu »), « Ma liste » et favoris,
--   playlists, masquages, ordre des catégories, chaînes regroupées à la main, votes du Sport.
-- * Jamais les identifiants IPTV (principal ou supplémentaires) ni les appareils : on les retape au besoin.
-- * Chaque nuit (3 h 17, heure du Québec) ; avant chaque changement d'identifiants IPTV, suppression de profil ou
--   restauration ; à la demande (page iPhone). Gardées : 30 nuits, 1 par mois sur 12 mois, et 40 autres au plus.
-- * Lisibles seulement par le propriétaire ; restauration par parties, elle-même précédée d'une sauvegarde.

create table public.household_backups (
  id uuid primary key default gen_random_uuid(),
  household_id uuid not null references public.households on delete cascade,
  created_at timestamptz not null default now(),
  -- 'nuit', 'manuelle', 'avant_identifiants', 'avant_suppression_profil', 'avant_restauration', 'avant_ia'
  reason text not null check (reason in ('nuit', 'manuelle', 'avant_identifiants', 'avant_suppression_profil', 'avant_restauration', 'avant_ia')),
  summary jsonb not null,
  fingerprint text not null,
  data jsonb not null
);
create index household_backups_household_idx on public.household_backups (household_id, created_at desc);

alter table public.household_backups enable row level security;
create policy household_backups_select on public.household_backups
  for select to authenticated using (public.is_owner(household_id));
revoke all on public.household_backups from public, anon, authenticated;
grant select on public.household_backups to authenticated;

/** Copie de la maison, en un objet JSON (format 1). Fonction interne. */
create function public.backup_snapshot(h uuid) returns jsonb
language sql stable security definer set search_path = public as $$
  with p as (select id from public.profiles where household_id = h)
  select jsonb_build_object(
    'format', 1,
    'household_id', h,
    'taken_at', now(),
    'household', (select jsonb_build_object('name', x.name, 'settings', x.settings) from public.households x where x.id = h),
    'profiles', coalesce((select jsonb_agg(to_jsonb(r) order by r.position, r.id) from public.profiles r where r.household_id = h), '[]'),
    'watch_progress', coalesce((select jsonb_agg(to_jsonb(r) order by r.profile_id, r.item_type, r.item_id) from public.watch_progress r where r.profile_id in (select id from p)), '[]'),
    'favorites', coalesce((select jsonb_agg(to_jsonb(r) order by r.profile_id, r.item_type, r.item_id) from public.favorites r where r.profile_id in (select id from p)), '[]'),
    'playlists', coalesce((select jsonb_agg(to_jsonb(r) order by r.profile_id, r.position, r.id) from public.playlists r where r.profile_id in (select id from p)), '[]'),
    'playlist_items', coalesce((select jsonb_agg(to_jsonb(r) order by r.playlist_id, r.position, r.item_id) from public.playlist_items r
                                 where r.playlist_id in (select id from public.playlists where profile_id in (select id from p))), '[]'),
    'hidden_items', coalesce((select jsonb_agg(to_jsonb(r) order by r.profile_id nulls first, r.item_type, r.item_id) from public.hidden_items r where r.household_id = h), '[]'),
    'category_order', coalesce((select jsonb_agg(to_jsonb(r) order by r.profile_id nulls first, r.section) from public.category_order r where r.household_id = h), '[]'),
    'live_group_override', coalesce((select jsonb_agg(to_jsonb(r) order by r.id) from public.live_group_override r where r.household_id = h), '[]'),
    'live_group_source', coalesce((select jsonb_agg(to_jsonb(r) order by r.group_id, r.source_id) from public.live_group_source r where r.household_id = h), '[]'),
    'sport_channel_vote', coalesce((select jsonb_agg(to_jsonb(r) order by r.team_key, r.channel_key) from public.sport_channel_vote r where r.household_id = h), '[]')
  )
$$;
revoke execute on function public.backup_snapshot(uuid) from public, anon, authenticated;

/** Résumé lisible d'une copie (nombres d'éléments), pour la page. */
create function public.backup_summary(d jsonb) returns jsonb
language sql immutable set search_path = public as $$
  select jsonb_build_object(
    'profils', jsonb_array_length(coalesce(d->'profiles', '[]')),
    'progression', jsonb_array_length(coalesce(d->'watch_progress', '[]')),
    'favoris', jsonb_array_length(coalesce(d->'favorites', '[]')),
    'playlists', jsonb_array_length(coalesce(d->'playlists', '[]')),
    'masquages', jsonb_array_length(coalesce(d->'hidden_items', '[]')),
    'regroupements', jsonb_array_length(coalesce(d->'live_group_override', '[]'))
  )
$$;

/**
 * Garde 30 nuits, 1 copie par mois sur 12 mois, et pour chaque autre raison ses copies les plus récentes : 40
 * (manuelle, avant identifiants, avant suppression de profil), 15 (avant restauration, avant IA). Une raison ne pousse
 * jamais dehors les copies d'une autre (des changements répétés par une IA n'effacent pas les copies du propriétaire).
 * Fonction interne.
 */
create function public.backup_prune(h uuid) returns void
language sql security definer set search_path = public as $$
  delete from public.household_backups b
   where b.household_id = h
     and b.id not in (
       select id from (
         select id, row_number() over (partition by reason order by created_at desc) n, reason
           from public.household_backups where household_id = h
       ) r
        where r.n <= case r.reason when 'nuit' then 30 when 'avant_restauration' then 15 when 'avant_ia' then 15 else 40 end
       union
       select id from (
         select distinct on (date_trunc('month', created_at)) id from public.household_backups
          where household_id = h and created_at > now() - interval '12 months'
          order by date_trunc('month', created_at), created_at desc
       ) m
     );
$$;
revoke execute on function public.backup_prune(uuid) from public, anon, authenticated;

/**
 * Prend une copie de la maison [h] ; rend son identifiant (null si rien à faire). Une copie de nuit identique à la
 * précédente n'est pas refaite (rien n'a changé). Fonction interne.
 */
create function public.backup_take(h uuid, why text) returns uuid
language plpgsql security definer set search_path = public as $$
declare
  d jsonb;
  fp text;
  new_id uuid;
begin
  if not exists (select 1 from public.households where id = h) then return null; end if;
  d := public.backup_snapshot(h);
  fp := md5((d - 'taken_at')::text);
  if why = 'nuit' and exists (
    select 1 from public.household_backups b where b.household_id = h
       and b.created_at = (select max(created_at) from public.household_backups where household_id = h)
       and b.fingerprint = fp
  ) then return null; end if;
  -- Plusieurs suppressions d'un coup : une seule copie « avant » par minute et par raison.
  if why <> 'nuit' and why <> 'manuelle' and exists (
    select 1 from public.household_backups b where b.household_id = h and b.reason = why and b.created_at > now() - interval '1 minute' and b.fingerprint = fp
  ) then return null; end if;
  insert into public.household_backups (household_id, reason, summary, fingerprint, data)
  values (h, why, public.backup_summary(d), fp, d) returning id into new_id;
  perform public.backup_prune(h);
  return new_id;
end
$$;
revoke execute on function public.backup_take(uuid, text) from public, anon, authenticated;

/** Toutes les maisons (tâche de nuit). */
create function public.backup_all_households(why text default 'nuit') returns int
language plpgsql security definer set search_path = public as $$
declare
  n int := 0;
  h uuid;
begin
  for h in select id from public.households loop
    begin
      if public.backup_take(h, why) is not null then n := n + 1; end if;
    exception when others then
      -- Une maison en erreur n'empêche pas les autres ; l'erreur reste visible dans les journaux du serveur.
      raise warning 'sauvegarde de nuit impossible pour la maison % : %', h, sqlerrm;
    end;
  end loop;
  return n;
end
$$;
revoke execute on function public.backup_all_households(text) from public, anon, authenticated;

/** « Sauvegarder maintenant » (propriétaire). */
create function public.backup_now(h uuid) returns uuid
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_owner(h) then raise exception 'acces-refuse' using errcode = '42501'; end if;
  return public.backup_take(h, 'manuelle');
end
$$;
revoke all on function public.backup_now(uuid) from public, anon;
grant execute on function public.backup_now(uuid) to authenticated;

/**
 * Remet une copie [d] dans la maison [h], par parties : 'profils' (profils et réglages de la maison), 'progression'
 * (positions et « vu » : on ne remplace jamais une position plus récente), 'organisation' (Ma liste et favoris,
 * playlists, masquages, ordre des catégories, regroupements, votes du Sport : remplacés par ceux de la copie).
 * Une copie « avant restauration » est prise d'abord : la restauration peut elle-même être annulée.
 */
create function public.backup_apply(h uuid, d jsonb, parts text[]) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  restored jsonb := '{}';
  n int;
begin
  if d is null or (d->>'format')::int is distinct from 1 or d->>'household_id' is distinct from h::text then
    raise exception 'sauvegarde-invalide';
  end if;
  if parts is null or cardinality(parts) = 0 or not parts <@ array['profils', 'progression', 'organisation'] then
    raise exception 'parties-invalides';
  end if;
  -- Copie incomplète (fichier coupé ou modifié) : refusée avant d'effacer quoi que ce soit.
  if 'profils' = any(parts) and jsonb_typeof(d->'profiles') is distinct from 'array' then raise exception 'sauvegarde-invalide'; end if;
  if 'progression' = any(parts) and jsonb_typeof(d->'watch_progress') is distinct from 'array' then raise exception 'sauvegarde-invalide'; end if;
  if 'organisation' = any(parts) and exists (
    select 1 from unnest(array['favorites', 'playlists', 'playlist_items', 'hidden_items', 'category_order', 'live_group_override', 'live_group_source', 'sport_channel_vote']) k
     where jsonb_typeof(d->k) is distinct from 'array'
  ) then raise exception 'sauvegarde-invalide'; end if;
  -- Lignes d'un profil supprimé depuis : jamais perdues en silence ; il faut restaurer aussi les profils.
  if not ('profils' = any(parts)) and exists (
    select 1 from (
      select x->>'profile_id' pid from jsonb_array_elements(case when 'progression' = any(parts) then d->'watch_progress' else '[]' end) x
      union all select x->>'profile_id' from jsonb_array_elements(case when 'organisation' = any(parts) then d->'favorites' else '[]' end) x
      union all select x->>'profile_id' from jsonb_array_elements(case when 'organisation' = any(parts) then d->'playlists' else '[]' end) x
    ) q where q.pid is not null and not exists (select 1 from public.profiles pr where pr.id::text = q.pid and pr.household_id = h)
  ) then raise exception 'profils-absents'; end if;

  perform 1 from public.households where id = h for update;
  perform public.backup_take(h, 'avant_restauration');

  if 'profils' = any(parts) then
    update public.households x set settings = coalesce(d->'household'->'settings', x.settings) where x.id = h;
    -- Profils de la copie : mis à jour, ou recréés (même identifiant) s'ils ont été supprimés ; 10 au plus.
    insert into public.profiles as pr (id, household_id, name, color, home, theme, settings, position, group_copies)
    select r.id, h, r.name, r.color, r.home, r.theme, coalesce(r.settings, '{}'), coalesce(r.position, 0), coalesce(r.group_copies, true)
      from jsonb_populate_recordset(null::public.profiles, coalesce(d->'profiles', '[]')) r
     where r.id is not null
       and not exists (select 1 from public.profiles o where o.id = r.id and o.household_id is distinct from h)
    on conflict (id) do update set name = excluded.name, color = excluded.color, home = excluded.home, theme = excluded.theme,
      settings = excluded.settings, position = excluded.position, group_copies = excluded.group_copies;
    get diagnostics n = row_count;
    if (select count(*) from public.profiles where household_id = h) > 10 then raise exception 'trop-de-profils'; end if;
    restored := restored || jsonb_build_object('profils', n);
  end if;

  if 'progression' = any(parts) then
    insert into public.watch_progress as w
    select r.* from jsonb_populate_recordset(null::public.watch_progress, coalesce(d->'watch_progress', '[]')) r
     where public.profile_household(r.profile_id) = h
    on conflict (profile_id, item_type, item_id) do update set
      series_id = excluded.series_id, season = excluded.season, episode = excluded.episode,
      position_s = excluded.position_s, duration_s = excluded.duration_s, watched = w.watched or excluded.watched,
      client_updated_at = excluded.client_updated_at,
      series_name = coalesce(excluded.series_name, w.series_name), series_cover = coalesce(excluded.series_cover, w.series_cover),
      series_category = coalesce(excluded.series_category, w.series_category),
      movie_name = coalesce(excluded.movie_name, w.movie_name), movie_cover = coalesce(excluded.movie_cover, w.movie_cover),
      movie_category = coalesce(excluded.movie_category, w.movie_category), dismissed_at = excluded.dismissed_at
      -- Jamais une position plus ancienne que celle d'aujourd'hui.
      where excluded.client_updated_at > w.client_updated_at;
    get diagnostics n = row_count;
    restored := restored || jsonb_build_object('progression', n);
  end if;

  if 'organisation' = any(parts) then
    delete from public.favorites where profile_id in (select id from public.profiles where household_id = h);
    delete from public.playlists where profile_id in (select id from public.profiles where household_id = h);
    delete from public.hidden_items where household_id = h;
    delete from public.category_order where household_id = h;
    delete from public.live_group_override where household_id = h;
    delete from public.sport_channel_vote where household_id = h;
    insert into public.favorites select r.* from jsonb_populate_recordset(null::public.favorites, coalesce(d->'favorites', '[]')) r
     where public.profile_household(r.profile_id) = h on conflict do nothing;
    insert into public.playlists select r.* from jsonb_populate_recordset(null::public.playlists, coalesce(d->'playlists', '[]')) r
     where public.profile_household(r.profile_id) = h on conflict do nothing;
    insert into public.playlist_items select r.* from jsonb_populate_recordset(null::public.playlist_items, coalesce(d->'playlist_items', '[]')) r
     where public.playlist_profile(r.playlist_id) is not null and public.profile_household(public.playlist_profile(r.playlist_id)) = h on conflict do nothing;
    insert into public.hidden_items (household_id, profile_id, item_type, item_id, created_at)
    select h, r.profile_id, r.item_type, r.item_id, r.created_at from jsonb_populate_recordset(null::public.hidden_items, coalesce(d->'hidden_items', '[]')) r
     where r.profile_id is null or public.profile_household(r.profile_id) = h on conflict do nothing;
    insert into public.category_order (household_id, profile_id, section, category_ids, updated_at)
    select h, r.profile_id, r.section, r.category_ids, r.updated_at from jsonb_populate_recordset(null::public.category_order, coalesce(d->'category_order', '[]')) r
     where r.profile_id is null or public.profile_household(r.profile_id) = h on conflict do nothing;
    insert into public.live_group_override (household_id, id, title, blocked_auto_keys)
    select h, r.id, r.title, coalesce(r.blocked_auto_keys, '{}') from jsonb_populate_recordset(null::public.live_group_override, coalesce(d->'live_group_override', '[]')) r
    on conflict do nothing;
    insert into public.live_group_source (household_id, source_id, group_id)
    select h, r.source_id, r.group_id from jsonb_populate_recordset(null::public.live_group_source, coalesce(d->'live_group_source', '[]')) r
     where exists (select 1 from public.live_group_override o where o.household_id = h and o.id = r.group_id) on conflict do nothing;
    insert into public.sport_channel_vote (household_id, team_key, channel_key, score, updated_at)
    select h, r.team_key, r.channel_key, r.score, r.updated_at from jsonb_populate_recordset(null::public.sport_channel_vote, coalesce(d->'sport_channel_vote', '[]')) r
    on conflict do nothing;
    restored := restored || jsonb_build_object('organisation', true);
    -- Les télés relisent l'organisation (révision des réglages) et les regroupements.
    insert into public.prefs_revision as pr (household_id, rev, updated_at) values (h, 1, now())
    on conflict (household_id) do update set rev = pr.rev + 1, updated_at = now();
  end if;
  return restored;
end
$$;
revoke execute on function public.backup_apply(uuid, jsonb, text[]) from public, anon, authenticated;

/** Restaurer une sauvegarde gardée sur le serveur (propriétaire). */
create function public.backup_restore(h uuid, backup uuid, parts text[]) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  d jsonb;
begin
  if not public.is_owner(h) then raise exception 'acces-refuse' using errcode = '42501'; end if;
  select b.data into d from public.household_backups b where b.id = backup and b.household_id = h;
  if d is null then raise exception 'sauvegarde-introuvable'; end if;
  return public.backup_apply(h, d, parts);
end
$$;
revoke all on function public.backup_restore(uuid, uuid, text[]) from public, anon;
grant execute on function public.backup_restore(uuid, uuid, text[]) to authenticated;

/** Restaurer un fichier téléchargé auparavant (propriétaire) : il doit venir de cette maison. */
create function public.backup_restore_file(h uuid, d jsonb, parts text[]) returns jsonb
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_owner(h) then raise exception 'acces-refuse' using errcode = '42501'; end if;
  if octet_length(d::text) > 20000000 then raise exception 'sauvegarde-invalide'; end if;
  return public.backup_apply(h, d, parts);
end
$$;
revoke all on function public.backup_restore_file(uuid, jsonb, text[]) from public, anon;
grant execute on function public.backup_restore_file(uuid, jsonb, text[]) to authenticated;

-- Avant un changement d'identifiants IPTV (autre fournisseur, abonnement renouvelé) : copie automatique.
create function public.backup_before_credentials() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if new.server is distinct from old.server or new.username is distinct from old.username or new.password is distinct from old.password then
    perform public.backup_take(old.household_id, 'avant_identifiants');
  end if;
  return new;
end
$$;
revoke execute on function public.backup_before_credentials() from public, anon, authenticated;
create trigger xtream_credentials_backup before update on public.xtream_credentials
  for each row execute function public.backup_before_credentials();

-- Avant la suppression d'un profil (sa progression partirait avec lui) : copie automatique. Rien quand la maison
-- entière est supprimée (elle n'existe déjà plus).
create function public.backup_before_profile_delete() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if old.household_id is not null and exists (select 1 from public.households where id = old.household_id) then
    perform public.backup_take(old.household_id, 'avant_suppression_profil');
  end if;
  return old;
end
$$;
revoke execute on function public.backup_before_profile_delete() from public, anon, authenticated;
create trigger profiles_backup before delete on public.profiles
  for each row execute function public.backup_before_profile_delete();

-- Chaque nuit à 3 h 17 (heure du Québec, 8 h 17 UTC), quand l'extension de tâches planifiées est là (production).
do $$
begin
  if exists (select 1 from pg_available_extensions where name = 'pg_cron') then
    execute 'create extension if not exists pg_cron';
    execute $c$select cron.schedule('sauvegardes-nuit', '17 8 * * *', 'select public.backup_all_households(''nuit'')')$c$;
  end if;
end
$$;

-- Progression retrouvée après un changement de fournisseur : la télé fait correspondre les anciens titres aux
-- nouveaux (même nom, même année ; même série, saison et épisode) et recopie les positions sous les nouveaux
-- numéros. L'ancienne ligne reste ; une position plus récente déjà enregistrée sous le nouveau numéro gagne.
create function public.progress_copy(p uuid, items jsonb) returns int
language plpgsql security definer set search_path = public as $$
declare
  n int;
begin
  if not public.can_use_profile(p) then raise exception 'acces-refuse' using errcode = '42501'; end if;
  if jsonb_typeof(items) is distinct from 'array' or jsonb_array_length(items) > 5000 then raise exception 'donnees-invalides'; end if;
  insert into public.watch_progress as w (profile_id, item_type, item_id, series_id, season, episode, position_s, duration_s, watched,
    client_updated_at, series_name, series_cover, series_category, movie_name, movie_cover, movie_category)
  select p, r.item_type, r.item_id, r.series_id, r.season, r.episode, greatest(r.position_s, 0), greatest(r.duration_s, 0), coalesce(r.watched, false),
         r.client_updated_at, r.series_name, r.series_cover, r.series_category, r.movie_name, r.movie_cover, r.movie_category
    from jsonb_populate_recordset(null::public.watch_progress, items) r
   where r.item_type in ('movie', 'episode') and coalesce(r.item_id, '') <> '' and r.client_updated_at is not null
  on conflict (profile_id, item_type, item_id) do update set
    position_s = excluded.position_s, duration_s = excluded.duration_s, watched = w.watched or excluded.watched,
    client_updated_at = excluded.client_updated_at, series_id = excluded.series_id, season = excluded.season, episode = excluded.episode
    where excluded.client_updated_at > w.client_updated_at;
  get diagnostics n = row_count;
  return n;
end
$$;
revoke all on function public.progress_copy(uuid, jsonb) from public, anon;
grant execute on function public.progress_copy(uuid, jsonb) to authenticated;
