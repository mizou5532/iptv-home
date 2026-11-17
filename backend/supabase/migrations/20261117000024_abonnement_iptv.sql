-- Date de fin de l'abonnement IPTV (17 nov. 2026). Le fournisseur ne parle qu'en http et seule la télé l'interroge :
-- elle rapporte ce qu'il dit du compte (date de fin, état, connexions permises) ; la page iPhone l'affiche dans
-- Réglages. Une ligne par foyer, lisible par ses membres, écrite seulement par la fonction ci-dessous.
create table public.iptv_account (
  household_id uuid primary key references public.households(id) on delete cascade,
  expires_at timestamptz,          -- null = pas de date de fin donnée par le fournisseur
  status text,                     -- « Active », « Expired »… tel que le fournisseur l'écrit (court)
  max_connections int,
  checked_at timestamptz not null default now()
);
alter table public.iptv_account enable row level security;
create policy iptv_account_read on public.iptv_account for select to authenticated using (public.is_member(household_id));

create function public.report_iptv_account(h uuid, expires_at timestamptz, status text, max_connections int) returns void
language plpgsql security definer set search_path = public as $$
begin
  perform public.org_assert(h, null, false);
  insert into public.iptv_account as a (household_id, expires_at, status, max_connections, checked_at)
  values (h, report_iptv_account.expires_at, left(nullif(trim(report_iptv_account.status), ''), 40),
          case when report_iptv_account.max_connections between 0 and 100 then report_iptv_account.max_connections end, now())
  on conflict (household_id) do update
    set expires_at = excluded.expires_at, status = excluded.status, max_connections = excluded.max_connections, checked_at = now();
end
$$;
revoke all on function public.report_iptv_account(uuid, timestamptz, text, int) from public, anon;
grant execute on function public.report_iptv_account(uuid, timestamptz, text, int) to authenticated;
