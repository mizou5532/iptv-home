-- B2 : une preuve d'arrêt est indispensable ; aucun nettoyage sur délai réseau.
create table public.playback_slot (
 household_id uuid primary key references households on delete cascade,
 generation uuid not null default gen_random_uuid(), state text not null default 'empty' check(state in ('empty','reserved','playing','stopping')),
 user_id uuid, device_hash bytea, device_name text, profile_id uuid, source_id text, label text,
 last_heartbeat timestamptz not null default now()
);
create table public.playback_request (
 id uuid primary key default gen_random_uuid(), household_id uuid not null references households on delete cascade,
 generation uuid not null, user_id uuid not null, device_hash bytea not null, device_name text not null,
 profile_id uuid not null, source_id text not null, label text not null,
 expires_at timestamptz not null default (now()+interval '30 seconds'),
 state text not null default 'waiting' check(state in ('waiting','ready','cancelled'))
);
create index playback_request_household on playback_request(household_id);
create table public.playback_signal (
 household_id uuid primary key references households on delete cascade,
 generation uuid not null, kind text not null check(kind in ('stop','revoked'))
);
alter table playback_slot enable row level security;
alter table playback_request enable row level security;
alter table playback_signal enable row level security;
-- Empreintes privées : seuls les champs publics des demandes sont sélectionnables.
revoke all on playback_slot, playback_request, playback_signal from anon, authenticated;
grant select(id,household_id,generation,user_id,device_name,profile_id,source_id,label,expires_at,state) on playback_request to authenticated;
grant select on playback_signal to authenticated;
create policy playback_requests_read on playback_request for select to authenticated using(is_member(household_id));
create policy playback_signal_read on playback_signal for select to authenticated using(is_member(household_id));
alter publication supabase_realtime add table playback_request (id,household_id,generation,user_id,device_name,profile_id,source_id,label,expires_at,state), playback_signal;

create function playback_key(k text) returns bytea language plpgsql immutable set search_path=public as $$
begin
 if k is null or length(k) not between 1 and 64 then raise exception 'cle-appareil-invalide'; end if;
 return sha256(convert_to(k,'UTF8'));
end $$;
create function playback_lock(h uuid) returns void language plpgsql security definer set search_path=public as $$
begin
 perform 1 from households where id=h for update;
 if not is_member(h) then raise exception 'acces-refuse' using errcode='42501'; end if;
end $$;
create function playback_allowed(h uuid,p uuid,source_id text,label text) returns void language plpgsql security definer set search_path=public as $$
begin
 if p is null or profile_household(p) is distinct from h then raise exception 'acces-refuse' using errcode='42501'; end if;
 if label is null or length(trim(label)) not between 1 and 200 then raise exception 'libelle-invalide'; end if;
 if not exists(select 1 from live_catalog_channel c where c.household_id=h and c.id=source_id) then raise exception 'source-inconnue'; end if;
 if exists(select 1 from live_catalog_channel c join hidden_items i on i.household_id=c.household_id
  and (i.profile_id is null or i.profile_id=p)
  and ((i.item_type='channel' and i.item_id=c.id) or (i.item_type='live_category' and i.item_id=c.category_id))
  where c.household_id=h and c.id=source_id) then raise exception 'source-masquee'; end if;
end $$;
create function playback_enable(h uuid) returns void language plpgsql security definer set search_path=public as $$
begin
 perform playback_lock(h);
 if not is_owner(h) then raise exception 'acces-refuse' using errcode='42501'; end if;
 insert into playback_slot(household_id) values(h) on conflict do nothing;
end $$;
create function playback_claim(h uuid,p uuid,device_key text,device_name text,source_id text,label text) returns jsonb
language plpgsql security definer set search_path=public as $$
declare s playback_slot; k bytea:=playback_key(device_key);
begin
 perform playback_lock(h); perform playback_allowed(h,p,source_id,label);
 if device_name is null or length(trim(device_name)) not between 1 and 40 then raise exception 'nom-appareil-invalide'; end if;
 select * into s from playback_slot where household_id=h;
 if not found then return jsonb_build_object('status','setup_required'); end if;
 if s.state<>'empty' or exists(select 1 from playback_request where household_id=h and state='ready' and expires_at>now() and generation=s.generation) then
 return jsonb_build_object('status','occupied','generation',s.generation,'occupant',jsonb_build_object('device_name',s.device_name,'profile_id',s.profile_id,'label',s.label),'stale',s.last_heartbeat<now()-interval '45 seconds'); end if;
 update playback_slot set generation=gen_random_uuid(),state='reserved',user_id=auth.uid(),device_hash=k,
 device_name=playback_claim.device_name,profile_id=p,source_id=playback_claim.source_id,label=playback_claim.label,last_heartbeat=now() where household_id=h returning * into s;
 return jsonb_build_object('status','ok','generation',s.generation);
end $$;
create function playback_request_stop(h uuid,generation uuid,p uuid,device_key text,device_name text,source_id text,label text) returns uuid
language plpgsql security definer set search_path=public as $$
declare s playback_slot; rid uuid; k bytea:=playback_key(device_key);
begin
 perform playback_lock(h); perform playback_allowed(h,p,source_id,label);
 if device_name is null or length(trim(device_name)) not between 1 and 40 then raise exception 'nom-appareil-invalide'; end if;
 select * into s from playback_slot where household_id=h;
 if not found or s.generation is distinct from generation or s.state='empty' then raise exception 'generation-invalide'; end if;
 if exists(select 1 from playback_request r where r.household_id=h and r.state in ('waiting','ready') and r.expires_at>now()) then raise exception 'demande-en-cours'; end if;
 insert into playback_request(household_id,generation,user_id,device_hash,device_name,profile_id,source_id,label)
 values(h,generation,auth.uid(),k,device_name,p,source_id,label) returning id into rid;
 update playback_slot set state='stopping' where household_id=h;
 insert into playback_signal values(h,generation,'stop') on conflict(household_id) do update set generation=excluded.generation,kind=excluded.kind;
 return rid;
end $$;
create function playback_stopped(h uuid,generation uuid,device_key text,request_id uuid default null) returns void
language plpgsql security definer set search_path=public as $$
declare s playback_slot; k bytea:=playback_key(device_key);
begin
 -- Accusé après révocation autorisé uniquement pour le titulaire authentifié exact.
 perform 1 from households where id=h for update;
 select * into s from playback_slot where household_id=h;
 if not found or s.generation is distinct from generation then return; end if;
 if s.user_id is distinct from auth.uid() or s.device_hash is distinct from k or auth.uid() is null then raise exception 'acces-refuse' using errcode='42501'; end if;
 if request_id is not null and not exists(select 1 from playback_request r where r.id=request_id and r.household_id=h and r.generation=s.generation) then raise exception 'demande-invalide'; end if;
 update playback_slot set state='empty' where household_id=h;
 update playback_request r set state='ready' where r.household_id=h and r.generation=s.generation and r.state='waiting' and r.expires_at>now();
end $$;
create function playback_promote(request_id uuid,device_key text) returns jsonb
language plpgsql security definer set search_path=public as $$
declare r playback_request; s playback_slot; k bytea:=playback_key(device_key);
begin
 select * into r from playback_request where id=request_id;
 perform playback_lock(r.household_id);
 select * into r from playback_request where id=request_id;
 if r.user_id is distinct from auth.uid() or r.device_hash is distinct from k then raise exception 'acces-refuse' using errcode='42501'; end if;
 select * into s from playback_slot where household_id=r.household_id;
 if r.state<>'ready' or r.expires_at<=now() or s.generation<>r.generation or s.state<>'empty' then raise exception 'demande-invalide'; end if;
 perform playback_allowed(r.household_id,r.profile_id,r.source_id,r.label);
 update playback_request set state='cancelled' where id=request_id;
 update playback_slot set generation=gen_random_uuid(),state='reserved',user_id=r.user_id,device_hash=r.device_hash,device_name=r.device_name,
 profile_id=r.profile_id,source_id=r.source_id,label=r.label,last_heartbeat=now() where household_id=r.household_id returning * into s;
 return jsonb_build_object('status','ok','generation',s.generation);
end $$;
create function playback_started(h uuid,generation uuid,device_key text) returns void
language plpgsql security definer set search_path=public as $$
declare s playback_slot; k bytea:=playback_key(device_key);
begin
 perform playback_lock(h); select * into s from playback_slot where household_id=h;
 if s.generation is distinct from generation or s.user_id is distinct from auth.uid() or s.device_hash is distinct from k or s.state not in ('reserved','playing') then raise exception 'acces-refuse' using errcode='42501'; end if;
 perform playback_allowed(h,s.profile_id,s.source_id,s.label);
 update playback_slot set state='playing' where household_id=h;
end $$;

-- Aucun helper SECURITY DEFINER n'est une API publique.
revoke execute on function playback_key(text), playback_lock(uuid), playback_allowed(uuid,uuid,text,text) from public,anon,authenticated;
revoke execute on function playback_enable(uuid), playback_claim(uuid,uuid,text,text,text,text), playback_request_stop(uuid,uuid,uuid,text,text,text,text), playback_stopped(uuid,uuid,text,uuid), playback_promote(uuid,text), playback_started(uuid,uuid,text) from public,anon;
grant execute on function playback_enable(uuid), playback_claim(uuid,uuid,text,text,text,text), playback_request_stop(uuid,uuid,uuid,text,text,text,text), playback_stopped(uuid,uuid,text,uuid), playback_promote(uuid,text), playback_started(uuid,uuid,text) to authenticated;

create function playback_control(h uuid,generation uuid,device_key text,beat boolean default false) returns jsonb
language plpgsql security definer set search_path=public as $$
declare s playback_slot; rid uuid; k bytea:=playback_key(device_key);
begin
 perform playback_lock(h); select * into s from playback_slot where household_id=h;
 if s.user_id is distinct from auth.uid() or s.device_hash is distinct from k then raise exception 'acces-refuse' using errcode='42501'; end if;
 if s.generation is distinct from generation or s.state='empty' then return jsonb_build_object('status','stop'); end if;
 begin
  perform playback_allowed(h,s.profile_id,s.source_id,s.label);
 exception when others then
  update playback_slot set state='stopping' where household_id=h; s.state:='stopping';
 end;
 if s.state='stopping' then
  select r.id into rid from playback_request r where r.household_id=h and r.generation=s.generation and r.state='waiting' and r.expires_at>now() limit 1;
  return jsonb_strip_nulls(jsonb_build_object('status','stop','request_id',rid));
 end if;
 if beat then update playback_slot set last_heartbeat=now() where household_id=h; end if;
 return jsonb_build_object('status','keep');
end $$;
create function playback_update(h uuid,generation uuid,device_key text,source_id text,label text) returns void
language plpgsql security definer set search_path=public as $$
declare s playback_slot; k bytea:=playback_key(device_key);
begin
 perform playback_lock(h); select * into s from playback_slot where household_id=h;
 if s.generation is distinct from generation or s.user_id is distinct from auth.uid() or s.device_hash is distinct from k or s.state not in ('reserved','playing') then raise exception 'acces-refuse' using errcode='42501'; end if;
 perform playback_allowed(h,s.profile_id,source_id,label);
 update playback_slot set source_id=playback_update.source_id,label=playback_update.label where household_id=h;
end $$;
create function playback_poll_request(request_id uuid,device_key text) returns jsonb
language plpgsql security definer set search_path=public as $$
declare r playback_request; s playback_slot; k bytea:=playback_key(device_key);
begin
 select * into r from playback_request where id=request_id;
 perform playback_lock(r.household_id);
 select * into r from playback_request where id=request_id;
 if r.user_id is distinct from auth.uid() or r.device_hash is distinct from k then raise exception 'acces-refuse' using errcode='42501'; end if;
 select * into s from playback_slot where household_id=r.household_id;
 return jsonb_build_object('status',case when r.expires_at<=now() or s.generation<>r.generation then 'cancelled' else r.state end);
end $$;
create function playback_cancel_request(request_id uuid,device_key text) returns void
language plpgsql security definer set search_path=public as $$
declare r playback_request; k bytea:=playback_key(device_key);
begin
 select * into r from playback_request where id=request_id;
 perform playback_lock(r.household_id);
 select * into r from playback_request where id=request_id;
 if r.user_id is distinct from auth.uid() or r.device_hash is distinct from k then raise exception 'acces-refuse' using errcode='42501'; end if;
 update playback_request set state='cancelled' where id=request_id;
 -- Ne jamais réanimer une lecture qui a déjà reçu stop.
end $$;
create function playback_confirm_physical_stop(request_id uuid) returns void
language plpgsql security definer set search_path=public as $$
declare r playback_request; s playback_slot;
begin
 select * into r from playback_request where id=request_id;
 perform playback_lock(r.household_id);
 if not is_owner(r.household_id) then raise exception 'acces-refuse' using errcode='42501'; end if;
 select * into r from playback_request where id=request_id;
 select * into s from playback_slot where household_id=r.household_id;
 if r.state<>'waiting' or r.expires_at<=now() or r.generation<>s.generation or s.state<>'stopping' then raise exception 'demande-invalide'; end if;
 update playback_slot set state='empty' where household_id=r.household_id;
 update playback_request set state='ready' where id=request_id;
end $$;
create function playback_admin_state(h uuid) returns jsonb
language plpgsql security definer set search_path=public as $$
declare s playback_slot; reqs jsonb;
begin
 perform playback_lock(h);
 if not is_owner(h) then raise exception 'acces-refuse' using errcode='42501'; end if;
 select * into s from playback_slot where household_id=h;
 select coalesce(jsonb_agg(jsonb_build_object('id',r.id,'generation',r.generation,'device_name',r.device_name,'label',r.label,'expires_at',r.expires_at,'state',r.state) order by r.expires_at),'[]'::jsonb) into reqs
 from playback_request r where r.household_id=h and r.generation=s.generation and r.state='waiting' and r.expires_at>now();
 return jsonb_build_object('enabled',s.household_id is not null,'slot',case when s.household_id is null then null else jsonb_build_object('generation',s.generation,'state',s.state,'device_name',s.device_name,'label',s.label,'stale',s.last_heartbeat<now()-interval '45 seconds') end,'requests',reqs);
end $$;

-- Un profil ou membre disparu laisse un marqueur bloquant et un signal de stop.
create function playback_revoke() returns trigger language plpgsql security definer set search_path=public as $$
declare s playback_slot;
begin
 perform 1 from households where id=old.household_id for update;
 if not found then return old; end if;
 for s in update playback_slot set state='stopping' where household_id=old.household_id and state<>'empty'
  and ((tg_table_name='profiles' and profile_id=(to_jsonb(old)->>'id')::uuid)
   or (tg_table_name='household_members' and user_id=(to_jsonb(old)->>'user_id')::uuid)) returning *
 loop
  insert into playback_signal values(s.household_id,s.generation,'revoked') on conflict(household_id) do update set generation=excluded.generation,kind=excluded.kind;
 end loop;
 return old;
end $$;
create trigger playback_profile_deleted before delete on profiles for each row execute function playback_revoke();
create trigger playback_member_deleted before delete on household_members for each row execute function playback_revoke();

-- Le wrapper conserve intégralement le comportement v1 des foyers non activés.
alter function claim_stream(uuid,uuid,text,text,integer,text,boolean) rename to claim_stream_v1;
revoke all on function claim_stream_v1(uuid,uuid,text,text,integer,text,boolean) from public,anon,authenticated;
create function claim_stream(h uuid,p uuid,device_name text,label text,max_connections int,device_key text,takeover boolean default false) returns jsonb
language plpgsql security definer set search_path=public as $$
begin
 perform playback_lock(h);
 if exists(select 1 from playback_slot where household_id=h) then raise exception 'protocole-obsolete'; end if;
 return claim_stream_v1(h,p,device_name,label,max_connections,device_key,takeover);
end $$;
revoke all on function playback_revoke() from public,anon,authenticated;
revoke execute on function playback_control(uuid,uuid,text,boolean),playback_update(uuid,uuid,text,text,text),playback_poll_request(uuid,text),playback_cancel_request(uuid,text),playback_confirm_physical_stop(uuid),playback_admin_state(uuid),claim_stream(uuid,uuid,text,text,integer,text,boolean) from public,anon;
grant execute on function playback_control(uuid,uuid,text,boolean),playback_update(uuid,uuid,text,text,text),playback_poll_request(uuid,text),playback_cancel_request(uuid,text),playback_confirm_physical_stop(uuid),playback_admin_state(uuid),claim_stream(uuid,uuid,text,text,integer,text,boolean) to authenticated;
