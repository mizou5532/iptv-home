-- Accès pour les intelligences artificielles (19 nov. 2026, demande du propriétaire) : un « connecteur » que Claude
-- (ou une autre IA) utilise pour organiser la maison avec des ordres simples, sans passer par les boutons de la page.
--
-- * Accès par clé secrète créée sur la page iPhone (propriétaire), coupée à tout moment ; seule l'empreinte de la clé
--   est gardée. La clé n'est montrée qu'une fois.
-- * Organisation seulement : profils (lecture), catégories et chaînes publiées, masquages, favoris, playlists. Jamais
--   les identifiants IPTV, les appareils, les comptes ni les sauvegardes.
-- * Avant une série de changements : sauvegarde « avant IA » (une par 10 min) ; chaque changement est noté au journal ;
--   l'outil « annuler » remet l'organisation de la dernière sauvegarde « avant IA ».

create table public.ai_access (
  id uuid primary key default gen_random_uuid(),
  household_id uuid not null references public.households on delete cascade,
  label text not null check (char_length(btrim(label)) between 1 and 40),
  token_hash text not null unique,
  created_at timestamptz not null default now(),
  last_used_at timestamptz,
  revoked_at timestamptz
);
create index ai_access_household_idx on public.ai_access (household_id);
alter table public.ai_access enable row level security;
create policy ai_access_select on public.ai_access for select to authenticated using (public.is_owner(household_id));
revoke all on public.ai_access from public, anon, authenticated;
grant select (id, household_id, label, created_at, last_used_at, revoked_at) on public.ai_access to authenticated;

create table public.ai_log (
  id bigint generated always as identity primary key,
  household_id uuid not null references public.households on delete cascade,
  access_id uuid references public.ai_access on delete set null,
  at timestamptz not null default now(),
  tool text not null,
  summary text not null
);
create index ai_log_household_idx on public.ai_log (household_id, at desc);
alter table public.ai_log enable row level security;
create policy ai_log_select on public.ai_log for select to authenticated using (public.is_owner(household_id));
revoke all on public.ai_log from public, anon, authenticated;
grant select on public.ai_log to authenticated;

/** Nouvel accès (propriétaire) : rend la clé, montrée une seule fois. 5 accès actifs au plus. */
create function public.ai_create_access(h uuid, label text) returns text
language plpgsql security definer set search_path = public as $$
declare
  token text := replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', '');
begin
  if not public.is_owner(h) then raise exception 'acces-refuse' using errcode = '42501'; end if;
  if (select count(*) from public.ai_access a where a.household_id = h and a.revoked_at is null) >= 5 then raise exception 'trop-d-acces'; end if;
  insert into public.ai_access (household_id, label, token_hash)
  values (h, left(coalesce(nullif(btrim(label), ''), 'Assistant IA'), 40), encode(sha256(convert_to(token, 'UTF8')), 'hex'));
  return token;
end
$$;
revoke all on function public.ai_create_access(uuid, text) from public, anon;
grant execute on function public.ai_create_access(uuid, text) to authenticated;

create function public.ai_revoke_access(h uuid, access uuid) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_owner(h) then raise exception 'acces-refuse' using errcode = '42501'; end if;
  update public.ai_access a set revoked_at = now() where a.household_id = h and a.id = access and a.revoked_at is null;
end
$$;
revoke all on function public.ai_revoke_access(uuid, uuid) from public, anon;
grant execute on function public.ai_revoke_access(uuid, uuid) to authenticated;

/** Type d'élément masquable d'après la section (« direct », « films », « series ») et la sorte (« categorie », « titre »). */
create function public.ai_item_type(section text, kind text) returns text
language sql immutable as $$
  select case lower(coalesce(section, '')) || ':' || lower(coalesce(kind, ''))
    when 'direct:categorie' then 'live_category' when 'direct:chaine' then 'channel' when 'direct:titre' then 'channel'
    when 'films:categorie' then 'vod_category' when 'films:titre' then 'movie'
    when 'series:categorie' then 'series_category' when 'series:titre' then 'series'
  end
$$;

/** Profil de la maison par identifiant ou par nom (sans accents ni majuscules) ; erreur claire sinon. */
create function public.ai_profile(h uuid, ref text) returns uuid
language plpgsql stable security definer set search_path = public as $$
declare
  p uuid;
begin
  if ref is null or btrim(ref) = '' then return null; end if;
  select id into p from public.profiles where household_id = h and (id::text = btrim(ref) or lower(name) = lower(btrim(ref))) limit 1;
  if p is null then raise exception 'profil-inconnu: %', ref; end if;
  return p;
end
$$;
revoke execute on function public.ai_profile(uuid, text) from public, anon, authenticated;

/**
 * Point d'entrée unique des outils (appelé par la fonction « assistant » avec la clé publique) : la clé est vérifiée
 * à chaque appel. [tool] et [args] : voir la description des outils dans la fonction « assistant ».
 */
create function public.ai_call(token text, tool text, args jsonb default '{}') returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  acc public.ai_access;
  h uuid;
  p uuid;
  t text;
  ids text[];
  n int := 0;
  pl uuid;
  res jsonb;
  writes constant text[] := array['masquer', 'afficher', 'favoris_ajouter', 'favoris_retirer', 'playlist_creer', 'playlist_ajouter',
                                  'playlist_retirer', 'playlist_supprimer', 'annuler'];
begin
  select * into acc from public.ai_access a
   where a.token_hash = encode(sha256(convert_to(coalesce(token, ''), 'UTF8')), 'hex') and a.revoked_at is null;
  if acc.id is null then raise exception 'cle-invalide' using errcode = '42501'; end if;
  h := acc.household_id;
  update public.ai_access set last_used_at = now() where id = acc.id;
  args := coalesce(args, '{}');

  -- Avant une série de changements : une sauvegarde « avant IA » au plus toutes les 10 minutes.
  if tool = any(writes) and tool <> 'annuler' and not exists (
    select 1 from public.household_backups b where b.household_id = h and b.reason = 'avant_ia' and b.created_at > now() - interval '10 minutes'
  ) then
    perform public.backup_take(h, 'avant_ia');
  end if;

  case tool
  when 'profils' then
    return coalesce((select jsonb_agg(jsonb_build_object('id', id, 'nom', name) order by position, name) from public.profiles where household_id = h), '[]');

  when 'categories' then
    t := lower(coalesce(args->>'section', 'direct'));
    return coalesce((
      select jsonb_agg(jsonb_build_object(
        'id', c.id, 'nom', c.name,
        'masquee_pour_tous', exists (select 1 from public.hidden_items x where x.household_id = h and x.profile_id is null and x.item_type = public.ai_item_type(t, 'categorie') and x.item_id = c.id),
        'masquee_pour', (select coalesce(jsonb_agg(pr.name), '[]') from public.hidden_items x join public.profiles pr on pr.id = x.profile_id
                          where x.household_id = h and x.item_type = public.ai_item_type(t, 'categorie') and x.item_id = c.id)
      ) order by c.position)
      from (
        select id, name, position from public.live_catalog_category where household_id = h and t = 'direct'
        union all select id, name, position from public.movie_catalog_category where household_id = h and t = 'films'
        union all select id, name, position from public.series_catalog_category where household_id = h and t = 'series'
      ) c
      where args->>'recherche' is null or c.name ilike '%' || (args->>'recherche') || '%'
    ), '[]');

  when 'chaines' then
    return coalesce((
      select jsonb_agg(jsonb_build_object('id', ch.id, 'nom', ch.name, 'categorie', cat.name, 'categorie_id', ch.category_id) order by cat.position, ch.position)
      from (select * from public.live_catalog_channel ch
             where ch.household_id = h
               and (args->>'recherche' is null or ch.name ilike '%' || (args->>'recherche') || '%')
               and (args->>'categorie' is null or ch.category_id = args->>'categorie')
             order by ch.position
             limit least(coalesce((args->>'limite')::int, 100), 300)) ch
      join public.live_catalog_category cat on cat.household_id = h and cat.id = ch.category_id
    ), '[]');

  when 'masquages' then
    p := public.ai_profile(h, args->>'profil');
    return coalesce((select jsonb_agg(jsonb_build_object('type', x.item_type, 'id', x.item_id,
              'pour', coalesce((select name from public.profiles where id = x.profile_id), 'tout le monde')))
            from public.hidden_items x where x.household_id = h and (p is null or x.profile_id = p or x.profile_id is null)), '[]');

  when 'masquer', 'afficher' then
    p := public.ai_profile(h, args->>'profil');   -- absent = pour tout le monde
    t := public.ai_item_type(args->>'section', args->>'sorte');
    if t is null then raise exception 'section-ou-sorte-inconnue (section: direct, films ou series ; sorte: categorie ou titre)'; end if;
    ids := array(select jsonb_array_elements_text(coalesce(args->'ids', '[]')));
    if cardinality(ids) = 0 or cardinality(ids) > 2000 then raise exception 'ids-requis (1 à 2000)'; end if;
    if tool = 'masquer' then
      insert into public.hidden_items (household_id, profile_id, item_type, item_id)
      select h, p, t, i from unnest(ids) i on conflict do nothing;
    else
      delete from public.hidden_items x where x.household_id = h and x.profile_id is not distinct from p and x.item_type = t and x.item_id = any(ids);
    end if;
    get diagnostics n = row_count;
    res := jsonb_build_object('modifies', n);

  when 'favoris_ajouter', 'favoris_retirer' then
    p := public.ai_profile(h, args->>'profil');
    if p is null then raise exception 'profil-requis'; end if;
    ids := array(select jsonb_array_elements_text(coalesce(args->'chaines', '[]')));
    if tool = 'favoris_ajouter' then
      insert into public.favorites (profile_id, item_type, item_id) select p, 'channel', i from unnest(ids) i on conflict do nothing;
    else
      delete from public.favorites f where f.profile_id = p and f.item_type = 'channel' and f.item_id = any(ids);
    end if;
    get diagnostics n = row_count;
    res := jsonb_build_object('modifies', n);

  when 'playlists' then
    p := public.ai_profile(h, args->>'profil');
    return coalesce((select jsonb_agg(jsonb_build_object('id', l.id, 'nom', l.name, 'profil', pr.name,
              'chaines', (select coalesce(jsonb_agg(jsonb_build_object('id', i.item_id, 'nom', ch.name) order by i.position), '[]')
                            from public.playlist_items i left join public.live_catalog_channel ch on ch.household_id = h and ch.id = i.item_id
                           where i.playlist_id = l.id)) order by pr.position, l.position)
            from public.playlists l join public.profiles pr on pr.id = l.profile_id
           where pr.household_id = h and (p is null or l.profile_id = p)), '[]');

  when 'playlist_creer' then
    p := public.ai_profile(h, args->>'profil');
    if p is null then raise exception 'profil-requis'; end if;
    insert into public.playlists (profile_id, name, position)
    values (p, left(coalesce(nullif(btrim(args->>'nom'), ''), 'Playlist'), 40),
            coalesce((select max(position) + 1 from public.playlists where profile_id = p), 0))
    returning id into pl;
    ids := array(select jsonb_array_elements_text(coalesce(args->'chaines', '[]')));
    insert into public.playlist_items (playlist_id, item_type, item_id, position)
    select pl, 'channel', i, o - 1 from unnest(ids) with ordinality u(i, o) on conflict do nothing;
    get diagnostics n = row_count;
    res := jsonb_build_object('playlist', pl, 'chaines', n);

  when 'playlist_ajouter', 'playlist_retirer', 'playlist_supprimer' then
    select l.id into pl from public.playlists l join public.profiles pr on pr.id = l.profile_id
     where pr.household_id = h and l.id::text = args->>'playlist';
    if pl is null then raise exception 'playlist-inconnue'; end if;
    ids := array(select jsonb_array_elements_text(coalesce(args->'chaines', '[]')));
    if tool = 'playlist_ajouter' then
      insert into public.playlist_items (playlist_id, item_type, item_id, position)
      select pl, 'channel', i, coalesce((select max(position) from public.playlist_items where playlist_id = pl), -1) + o
        from unnest(ids) with ordinality u(i, o) on conflict do nothing;
    elsif tool = 'playlist_retirer' then
      delete from public.playlist_items i where i.playlist_id = pl and i.item_id = any(ids);
    else
      delete from public.playlists where id = pl;
    end if;
    get diagnostics n = row_count;
    res := jsonb_build_object('modifies', n);

  when 'annuler' then
    -- Seulement les changements récents d'une IA (moins d'une heure) : jamais un retour silencieux sur des jours de
    -- réglages faits à la main depuis, ni une sauvegarde plus récente du propriétaire ou une restauration.
    select b.id into pl from public.household_backups b
     where b.household_id = h and b.reason = 'avant_ia' and b.created_at > now() - interval '1 hour'
       and not exists (select 1 from public.household_backups o where o.household_id = h and o.reason in ('manuelle', 'avant_restauration') and o.created_at > b.created_at)
     order by b.created_at desc limit 1;
    if pl is null then raise exception 'rien-a-annuler (seulement dans l''heure qui suit les changements d''une IA)'; end if;
    res := public.backup_apply(h, (select data from public.household_backups where id = pl), array['organisation']);
    -- La sauvegarde utilisée est consommée : un deuxième « annuler » remonte d'un cran.
    delete from public.household_backups where id = pl;

  when 'journal' then
    return coalesce((select jsonb_agg(jsonb_build_object('quand', l.at, 'outil', l.tool, 'resume', l.summary) order by l.at desc)
            from (select * from public.ai_log where household_id = h order by at desc limit 30) l), '[]');

  else
    raise exception 'outil-inconnu: %', tool;
  end case;

  insert into public.ai_log (household_id, access_id, tool, summary)
  values (h, acc.id, tool, left(coalesce(args::text, '') || ' → ' || coalesce(res::text, ''), 500));
  -- Les télés relisent l'organisation (révision des réglages).
  insert into public.prefs_revision as pr (household_id, rev, updated_at) values (h, 1, now())
  on conflict (household_id) do update set rev = pr.rev + 1, updated_at = now();
  return res;
end
$$;
revoke all on function public.ai_call(text, text, jsonb) from public;
grant execute on function public.ai_call(text, text, jsonb) to anon, authenticated;
