-- Corrections communes au foyer ; aucune URL ou donnée fournisseur sensible.
alter table public.profiles add column group_copies boolean not null default true;
create table public.live_group_revision (
  household_id uuid primary key references public.households on delete cascade,
  rev bigint not null default 0 check (rev >= 0)
);
create table public.live_group_override (
  household_id uuid not null references public.households on delete cascade,
  id uuid not null,
  title text not null check (length(btrim(title)) > 0),
  blocked_auto_keys text[] not null default '{}',
  primary key (household_id,id),
  check (array_position(blocked_auto_keys,null) is null)
);
create table public.live_group_source (
  household_id uuid not null,
  source_id text not null check (length(source_id)>0),
  group_id uuid not null,
  primary key (household_id,source_id),
  foreign key (household_id,group_id) references public.live_group_override(household_id,id) on delete cascade
);
create index live_group_source_group_idx on public.live_group_source(household_id,group_id,source_id);
create table public.live_catalog_revision (
  household_id uuid primary key references public.households on delete cascade,
  rev bigint not null default 0 check (rev >= 0)
);
-- Une seule incrémentation par foyer et instruction, même pour 100 000 chaînes.
create function public.bump_live_catalog_rev() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  insert into public.live_catalog_revision as r(household_id,rev)
  select distinct c.household_id,1 from changed c
   where exists(select 1 from public.households h where h.id=c.household_id)
  on conflict (household_id) do update set rev=r.rev+1;
  return null;
end
$$;
revoke all on function public.bump_live_catalog_rev() from public,anon,authenticated;
create trigger live_category_insert_rev after insert on public.live_catalog_category referencing new table as changed for each statement execute function public.bump_live_catalog_rev();
create trigger live_category_update_rev after update on public.live_catalog_category referencing new table as changed for each statement execute function public.bump_live_catalog_rev();
create trigger live_category_delete_rev after delete on public.live_catalog_category referencing old table as changed for each statement execute function public.bump_live_catalog_rev();
create trigger live_channel_insert_rev after insert on public.live_catalog_channel referencing new table as changed for each statement execute function public.bump_live_catalog_rev();
create trigger live_channel_update_rev after update on public.live_catalog_channel referencing new table as changed for each statement execute function public.bump_live_catalog_rev();
create trigger live_channel_delete_rev after delete on public.live_catalog_channel referencing old table as changed for each statement execute function public.bump_live_catalog_rev();

alter table public.live_group_revision enable row level security;
alter table public.live_group_override enable row level security;
alter table public.live_group_source enable row level security;
alter table public.live_catalog_revision enable row level security;
create policy live_group_revision_read on public.live_group_revision for select to authenticated using(public.is_member(household_id));
create policy live_group_override_read on public.live_group_override for select to authenticated using(public.is_member(household_id));
create policy live_group_source_read on public.live_group_source for select to authenticated using(public.is_member(household_id));
create policy live_catalog_revision_read on public.live_catalog_revision for select to authenticated using(public.is_member(household_id));
revoke all on public.live_group_revision,public.live_group_override,public.live_group_source,public.live_catalog_revision from public,anon,authenticated;
grant select on public.live_group_revision,public.live_group_override,public.live_group_source,public.live_catalog_revision to authenticated;

create function public.live_group_page(h uuid, after_id uuid, lim int default 500) returns jsonb
language plpgsql stable security definer set search_path = public, pg_temp as $$
declare result jsonb;
begin
  perform public.org_assert(h,null,false);
  if lim is null or lim < 1 or lim > 1000 then raise exception 'page-invalide'; end if;
  with batch as (
    select g.* from public.live_group_override g where g.household_id=h and (after_id is null or g.id>after_id)
    order by g.id limit lim
  )
  select jsonb_build_object(
    'rev',coalesce((select r.rev from public.live_group_revision r where r.household_id=h),0),
    'rules_version',1,
    'groups',coalesce((select jsonb_agg(jsonb_build_object('id',g.id,'title',g.title,
      'source_ids',coalesce((select jsonb_agg(s.source_id order by s.source_id) from public.live_group_source s where s.household_id=h and s.group_id=g.id),'[]'::jsonb),
      'blocked_auto_keys',g.blocked_auto_keys) order by g.id) from batch g),'[]'::jsonb),
    'next_id',(select max_id from (select g.id max_id from batch g order by g.id desc limit 1) last
      where exists(select 1 from public.live_group_override x where x.household_id=h and x.id>last.max_id))) into result;
  return result;
end
$$;

create function public.live_catalog_page(h uuid, after_position int, after_id text, lim int default 500) returns jsonb
language plpgsql stable security definer set search_path = public, pg_temp as $$
declare result jsonb;
begin
  perform public.org_assert(h,null,false);
  if lim is null or lim<1 or lim>1000 or (after_position is null) <> (after_id is null) then raise exception 'page-invalide'; end if;
  with batch as (
    select ch.id,ch.name,ch.category_id,c.name category_name,ch.position,ch.number
    from public.live_catalog_channel ch join public.live_catalog_category c on c.household_id=ch.household_id and c.id=ch.category_id
    where ch.household_id=h and (after_position is null or (ch.position,ch.id)>(after_position,after_id))
    order by ch.position,ch.id limit lim
  )
  select jsonb_build_object('catalog_rev',coalesce((select r.rev from public.live_catalog_revision r where r.household_id=h),0),
    'channels',coalesce((select jsonb_agg(to_jsonb(b) order by b.position,b.id) from batch b),'[]'::jsonb),
    'next_cursor',(select jsonb_build_object('position',b.position,'id',b.id) from (select * from batch order by position desc,id desc limit 1) b
      where exists(select 1 from public.live_catalog_channel x where x.household_id=h and (x.position,x.id)>(b.position,b.id))
      order by b.position desc,b.id desc limit 1)) into result;
  return result;
end
$$;
create index live_catalog_channel_page_idx on public.live_catalog_channel(household_id,position,id);

create function public.live_group_edit(h uuid, expected_rev bigint, replacement jsonb) returns jsonb
language plpgsql security definer set search_path = public, pg_temp as $$
declare current_rev bigint; ids uuid[];
begin
  perform public.org_assert(h,null,true);
  -- Même verrou pour toutes les éditions de ce foyer, y compris sa première révision.
  perform 1 from public.households where id=h for update;
  select coalesce((select r.rev from public.live_group_revision r where r.household_id=h),0) into current_rev;
  if expected_rev is null or expected_rev<>current_rev then raise exception 'conflit-revision'; end if;
  if jsonb_typeof(replacement) is distinct from 'object'
     or jsonb_typeof(replacement->'groups') is distinct from 'array'
     or jsonb_typeof(replacement->'remove_ids') is distinct from 'array' then raise exception 'correction-invalide'; end if;
  -- Validation stricte avant toute suppression, notamment pour les anciennes sources disparues.
  if exists(select 1 from jsonb_array_elements(replacement->'groups') g where
       jsonb_typeof(g) is distinct from 'object' or jsonb_typeof(g->'id') is distinct from 'string'
       or jsonb_typeof(g->'title') is distinct from 'string' or length(btrim(g->>'title'))=0
       or jsonb_typeof(g->'source_ids') is distinct from 'array'
       or jsonb_typeof(g->'blocked_auto_keys') is distinct from 'array') then raise exception 'correction-invalide'; end if;
  if exists(select 1 from jsonb_array_elements(replacement->'groups') g where jsonb_array_length(g->'source_ids')=0)
     or exists(select 1 from jsonb_array_elements(replacement->'groups') g,
       lateral jsonb_array_elements((g->'source_ids')||(g->'blocked_auto_keys')) s where jsonb_typeof(s) <> 'string' or s #>> '{}' = '')
     or exists(select 1 from jsonb_array_elements(replacement->'remove_ids') s where jsonb_typeof(s)<>'string')
     or exists(select 1 from jsonb_array_elements(replacement->'groups') g group by (g->>'id')::uuid having count(*)>1)
     or exists(select 1 from jsonb_array_elements(replacement->'groups') g,lateral jsonb_array_elements_text(g->'source_ids') s group by s having count(*)>1)
     then raise exception 'correction-invalide'; end if;
  if exists(select 1 from jsonb_array_elements(replacement->'groups') g,lateral jsonb_array_elements_text(g->'source_ids') s(source_id)
      where not exists(select 1 from public.live_catalog_channel ch where ch.household_id=h and ch.id=s.source_id)
      and not exists(select 1 from public.live_group_source old where old.household_id=h and old.source_id=s.source_id)) then raise exception 'source-inconnue'; end if;
  select coalesce(array_agg(i),'{}') into ids from (
    select value::uuid i from jsonb_array_elements_text(replacement->'remove_ids')
    union select (g->>'id')::uuid from jsonb_array_elements(replacement->'groups') g) all_ids;
  delete from public.live_group_override g where g.household_id=h and g.id=any(ids);
  insert into public.live_group_override(household_id,id,title,blocked_auto_keys)
    select h,(g->>'id')::uuid,g->>'title',array(select jsonb_array_elements_text(g->'blocked_auto_keys')) from jsonb_array_elements(replacement->'groups') g;
  insert into public.live_group_source(household_id,source_id,group_id)
    select h,s.source_id,(g->>'id')::uuid from jsonb_array_elements(replacement->'groups') g,lateral jsonb_array_elements_text(g->'source_ids') s(source_id);
  insert into public.live_group_revision(household_id,rev) values(h,current_rev+1)
    on conflict(household_id) do update set rev=excluded.rev;
  insert into public.prefs_revision as r(household_id,rev) values(h,1)
    on conflict(household_id) do update set rev=r.rev+1,updated_at=now();
  return jsonb_build_object('rev',current_rev+1);
end
$$;

create function public.set_group_copies(p uuid, enabled boolean) returns void
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  perform public.org_assert(public.profile_household(p),p,true);
  if enabled is null then raise exception 'reglage-invalide'; end if;
  update public.profiles set group_copies=enabled where id=p;
end
$$;
-- Couvre également une éventuelle édition directe du profil autorisée par B1.
create function public.bump_group_copies_rev() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  insert into public.prefs_revision as r(household_id,rev) values(new.household_id,1)
    on conflict(household_id) do update set rev=r.rev+1,updated_at=now();
  return null;
end
$$;
revoke all on function public.bump_group_copies_rev() from public,anon,authenticated;
create trigger group_copies_rev after update of group_copies on public.profiles for each row
  when(old.group_copies is distinct from new.group_copies) execute function public.bump_group_copies_rev();

-- Préserve exactement les clés B1 ; ajoute uniquement le réglage.
create or replace function public.profile_prefs(p uuid) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare
  h uuid := public.profile_household(p);
begin
  perform public.org_assert(h, p, false);
  return jsonb_build_object(
    'group_copies', (select pr.group_copies from public.profiles pr where pr.id=p),
    'rev', coalesce((select r.rev from public.prefs_revision r where r.household_id = h), 0),
    'hidden', coalesce((
      select jsonb_agg(jsonb_build_object('item_type', x.item_type, 'item_id', x.item_id) order by x.item_type, x.item_id)
        from (select distinct hi.item_type, hi.item_id from public.hidden_items hi
               where hi.household_id = h and (hi.profile_id is null or hi.profile_id = p)) x), '[]'::jsonb),
    'favorites', coalesce((
      select jsonb_agg(f.item_id order by f.position, f.created_at, f.item_id)
        from public.favorites f where f.profile_id = p and f.item_type = 'channel'), '[]'::jsonb),
    'playlists', coalesce((
      select jsonb_agg(jsonb_build_object(
               'id', pl.id, 'name', pl.name,
               'items', coalesce((select jsonb_agg(i.item_id order by i.position, i.item_id)
                                    from public.playlist_items i
                                   where i.playlist_id = pl.id and i.item_type = 'channel'), '[]'::jsonb))
             order by pl.position, pl.name, pl.id)
        from public.playlists pl where pl.profile_id = p), '[]'::jsonb),
    'order', coalesce(to_jsonb(public.org_order(h, p)), 'null'::jsonb));
end
$$;


revoke all on function public.live_group_page(uuid,uuid,int),public.live_catalog_page(uuid,int,text,int),public.live_group_edit(uuid,bigint,jsonb),public.set_group_copies(uuid,boolean) from public,anon,authenticated;
grant execute on function public.live_group_page(uuid,uuid,int),public.live_catalog_page(uuid,int,text,int),public.live_group_edit(uuid,bigint,jsonb),public.set_group_copies(uuid,boolean) to authenticated;
