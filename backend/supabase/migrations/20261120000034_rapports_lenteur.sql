-- Rapports de lenteur (20 nov. 2026, demande du propriétaire : « un bouton qui prend tous les renseignements possibles
-- quand c'est lent et les envoie sur le serveur »). La télé envoie un rapport quand on appuie sur « Signaler une
-- lenteur », ou toute seule après un gros blocage de l'écran (limité). Un rapport ne contient jamais d'adresse de
-- serveur, de nom d'utilisateur ni de mot de passe : seulement des mesures (mémoire, images lentes, blocages avec
-- l'endroit du code, délai des touches, temps de réponse par type, travail en cours, appareil, version).
--
-- Lisibles par le propriétaire seulement. Les 300 derniers rapports de la maison sont gardés.

create table public.diagnostic_reports (
  id bigint generated always as identity primary key,
  household_id uuid not null references public.households on delete cascade,
  device_user_id uuid not null,
  device_name text,
  created_at timestamptz not null default now(),
  kind text not null check (kind in ('manuel', 'auto')),
  app_version text,
  summary text not null default '',
  data jsonb not null
);
create index diagnostic_reports_household_idx on public.diagnostic_reports (household_id, created_at desc);
alter table public.diagnostic_reports enable row level security;
create policy diagnostic_reports_select on public.diagnostic_reports for select to authenticated using (public.is_owner(household_id));
revoke all on public.diagnostic_reports from public, anon, authenticated;
grant select on public.diagnostic_reports to authenticated;

-- Version de l'app sur chaque appareil (page iPhone, « Mes appareils ») : savoir si tout est à jour.
alter table public.household_members add column app_version text;
alter table public.household_members add column app_version_at timestamptz;

/** La télé dit quelle version elle a (une fois par lancement). */
create function public.report_app_version(h uuid, version text) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_member(h) then raise exception 'acces-refuse' using errcode = '42501'; end if;
  update public.household_members m set app_version = left(btrim(coalesce(version, '')), 20), app_version_at = now()
   where m.household_id = h and m.user_id = auth.uid();
end
$$;
revoke all on function public.report_app_version(uuid, text) from public, anon;
grant execute on function public.report_app_version(uuid, text) to authenticated;

/**
 * Un rapport de la télé ([kind] « manuel » ou « auto »). Au plus 30 par heure et par appareil ; les rapports
 * automatiques, 12 par jour et par appareil. 800 Ko au plus. Rend le numéro du rapport.
 */
create function public.report_diagnostic(h uuid, kind text, app_version text, summary text, data jsonb) returns bigint
language plpgsql security definer set search_path = public as $$
declare
  me uuid := auth.uid();
  new_id bigint;
begin
  if not public.is_member(h) then raise exception 'acces-refuse' using errcode = '42501'; end if;
  if kind is null or kind not in ('manuel', 'auto') then raise exception 'type-inconnu'; end if;
  if data is null or jsonb_typeof(data) <> 'object' then raise exception 'rapport-invalide'; end if;
  if octet_length(data::text) > 800000 then raise exception 'rapport-trop-gros'; end if;
  if (select count(*) from public.diagnostic_reports r where r.device_user_id = me and r.created_at > now() - interval '1 hour') >= 30 then
    raise exception 'trop-de-rapports';
  end if;
  if kind = 'auto' and (select count(*) from public.diagnostic_reports r where r.device_user_id = me and r.kind = 'auto' and r.created_at > now() - interval '1 day') >= 12 then
    raise exception 'trop-de-rapports';
  end if;
  insert into public.diagnostic_reports (household_id, device_user_id, device_name, kind, app_version, summary, data)
  values (h, me, (select m.device_name from public.household_members m where m.household_id = h and m.user_id = me),
          kind, left(btrim(coalesce(app_version, '')), 20), left(coalesce(summary, ''), 300), data)
  returning id into new_id;
  update public.household_members m set app_version = left(btrim(coalesce(report_diagnostic.app_version, '')), 20), app_version_at = now()
   where m.household_id = h and m.user_id = me and coalesce(report_diagnostic.app_version, '') <> '';
  delete from public.diagnostic_reports r where r.household_id = h and r.id not in (
    select id from public.diagnostic_reports where household_id = h order by created_at desc limit 300);
  return new_id;
end
$$;
revoke all on function public.report_diagnostic(uuid, text, text, text, jsonb) from public, anon;
grant execute on function public.report_diagnostic(uuid, text, text, text, jsonb) to authenticated;
