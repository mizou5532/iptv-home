-- Organiser depuis le téléphone (plan 3b1).
--
-- Codes d'erreur levés par les fonctions :
--   acces-refuse      : errcode 42501
--   categorie-inconnue : errcode P0001 (catégorie non publiée)

-- Copie du catalogue du direct
--
-- La télé publie ici les catégories et les chaînes du direct, pour que la page du téléphone
-- du propriétaire voie leurs noms. Lecture pour les membres ; aucune écriture directe,
-- uniquement par les deux fonctions ci-dessous.
-- Copie figée : une catégorie dont on ne republie pas les chaînes garde les chaînes déjà publiées.
-- Empreinte : écrite seulement au dernier morceau, donc une publication interrompue ne passe pas pour terminée.

create table public.live_catalog_category (
  household_id uuid not null references public.households on delete cascade,
  id text not null,
  name text not null,
  position int not null,
  fingerprint text,
  updated_at timestamptz not null default now(),
  primary key (household_id, id)
);

create table public.live_catalog_channel (
  household_id uuid not null,
  id text not null,
  category_id text not null,
  name text not null,
  norm text not null,
  number int,
  position int not null,
  primary key (household_id, id),
  foreign key (household_id, category_id)
    references public.live_catalog_category (household_id, id) on delete cascade
);
create index live_catalog_channel_category_idx
  on public.live_catalog_channel (household_id, category_id, position);

alter table public.live_catalog_category enable row level security;
alter table public.live_catalog_channel enable row level security;

create policy live_catalog_category_select on public.live_catalog_category
  for select to authenticated using (public.is_member(household_id));
create policy live_catalog_channel_select on public.live_catalog_channel
  for select to authenticated using (public.is_member(household_id));

revoke all on public.live_catalog_category, public.live_catalog_channel from public, anon, authenticated;
grant select on public.live_catalog_category, public.live_catalog_channel to authenticated;

-- cats = [{id, name, position}] : upsert de toutes, suppression des absentes (et de leurs chaînes).
-- Renvoie les empreintes connues, que la télé compare aux siennes pour ne republier que ce qui a changé.
create function public.publish_live_categories(h uuid, cats jsonb)
returns table (id text, fingerprint text)
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_member(h) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;

  insert into public.live_catalog_category as c (household_id, id, name, position, updated_at)
  select h, r.id, r.name, r.position, now()
    from jsonb_to_recordset(cats) as r(id text, name text, position int)
  on conflict on constraint live_catalog_category_pkey do update
    set name = excluded.name, position = excluded.position, updated_at = excluded.updated_at;

  delete from public.live_catalog_category c
   where c.household_id = h
     and c.id not in (select r.id from jsonb_to_recordset(cats) as r(id text));

  return query
    select c.id, c.fingerprint from public.live_catalog_category c where c.household_id = h;
end
$$;

-- channels = [{id, name, norm, number, position}] d'une seule catégorie, par morceaux.
-- chunk = 0 efface d'abord les chaînes de la catégorie ; is_last écrit l'empreinte.
-- Une chaîne déjà présente sous une autre catégorie est déplacée (dernier envoi gagne).
create function public.publish_live_channels(
  h uuid, category text, fingerprint text, chunk int, is_last boolean, channels jsonb
) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_member(h) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  if not exists (
    select 1 from public.live_catalog_category c where c.household_id = h and c.id = category
  ) then
    raise exception 'categorie-inconnue';
  end if;

  if chunk = 0 then
    delete from public.live_catalog_channel ch where ch.household_id = h and ch.category_id = category;
  end if;

  insert into public.live_catalog_channel as ch (household_id, id, category_id, name, norm, number, position)
  select h, r.id, category, r.name, r.norm, r.number, r.position
    from jsonb_to_recordset(channels) as r(id text, name text, norm text, number int, position int)
  on conflict on constraint live_catalog_channel_pkey do update
    set category_id = excluded.category_id, name = excluded.name, norm = excluded.norm,
        number = excluded.number, position = excluded.position;

  if coalesce(is_last, false) then
    update public.live_catalog_category c
       set fingerprint = publish_live_channels.fingerprint, updated_at = now()
     where c.household_id = h and c.id = category;
  end if;
end
$$;

revoke execute on function public.publish_live_categories(uuid, jsonb) from public, anon;
revoke execute on function public.publish_live_channels(uuid, text, text, int, boolean, jsonb) from public, anon;
grant execute on function public.publish_live_categories(uuid, jsonb) to authenticated;
grant execute on function public.publish_live_channels(uuid, text, text, int, boolean, jsonb) to authenticated;
