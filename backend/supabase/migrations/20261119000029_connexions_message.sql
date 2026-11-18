-- Connexions en cours et message du fournisseur (19 nov. 2026, demande du propriétaire). La télé relit l'état de chaque
-- compte (principal et supplémentaires) toutes les 10 min quand l'app est à l'écran : nombre de vidéos ouvertes en ce
-- moment sur le compte (« active_cons » du fournisseur, y compris par un appareil hors de la maison) et le message
-- éventuel du fournisseur (annonce, maintenance). La page iPhone les affiche avec l'heure du relevé.

alter table public.iptv_account add column active_connections int;
alter table public.iptv_account add column provider_message text;

drop function public.report_iptv_account(uuid, timestamptz, text, int);
create function public.report_iptv_account(
  h uuid, expires_at timestamptz, status text, max_connections int,
  active_connections int default null, message text default null
) returns void
language plpgsql security definer set search_path = public as $$
begin
  perform public.org_assert(h, null, false);
  insert into public.iptv_account as a (household_id, expires_at, status, max_connections, active_connections, provider_message, checked_at)
  values (h, report_iptv_account.expires_at, left(nullif(trim(report_iptv_account.status), ''), 40),
          case when report_iptv_account.max_connections between 0 and 100 then report_iptv_account.max_connections end,
          case when report_iptv_account.active_connections between 0 and 100 then report_iptv_account.active_connections end,
          left(nullif(trim(report_iptv_account.message), ''), 300), now())
  on conflict (household_id) do update
    set expires_at = excluded.expires_at, status = excluded.status, max_connections = excluded.max_connections,
        active_connections = excluded.active_connections, provider_message = excluded.provider_message, checked_at = now();
end
$$;
revoke all on function public.report_iptv_account(uuid, timestamptz, text, int, int, text) from public, anon;
grant execute on function public.report_iptv_account(uuid, timestamptz, text, int, int, text) to authenticated;

alter table public.xtream_extra_accounts add column active_connections int;
alter table public.xtream_extra_accounts add column provider_message text;
alter table public.xtream_extra_accounts add column status_at timestamptz;

/** Relevé léger d'un compte supplémentaire (sans changer sa vérification) : connexions en cours, message, date de fin. */
create function public.report_extra_status(h uuid, account uuid, active_connections int, message text, expires_at timestamptz) returns void
language plpgsql security definer set search_path = public as $$
begin
  perform public.org_assert(h, null, false);
  update public.xtream_extra_accounts a
     set active_connections = case when report_extra_status.active_connections between 0 and 100 then report_extra_status.active_connections end,
         provider_message = left(nullif(trim(report_extra_status.message), ''), 300),
         expires_at = coalesce(report_extra_status.expires_at, a.expires_at),
         status_at = now()
   where a.household_id = h and a.id = account;
end
$$;
revoke all on function public.report_extra_status(uuid, uuid, int, text, timestamptz) from public, anon;
grant execute on function public.report_extra_status(uuid, uuid, int, text, timestamptz) to authenticated;
