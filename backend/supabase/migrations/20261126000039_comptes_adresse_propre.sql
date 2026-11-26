-- Comptes IPTV supplémentaires avec leur propre adresse (26 nov. 2026). Le fournisseur donne pour chaque abonnement
-- un lien différent (autre adresse de serveur, même catalogue). Jusqu'ici un compte supplémentaire empruntait toujours
-- l'adresse du compte principal. Désormais chaque compte peut garder la sienne ; vide = celle du compte principal.

alter table public.xtream_extra_accounts add column server text;

-- Nouvelle forme de add_extra_account : [server] facultatif (les anciens appels à 3 valeurs restent valables).
drop function public.add_extra_account(uuid, text, text);

/**
 * Le propriétaire ajoute un compte (au plus 4 de plus) ; rend son identifiant. [server] : adresse propre au compte
 * (« http://autre-lien.exemple:8080 ») ; vide ou identique à celle du compte principal = celle du compte principal.
 */
create function public.add_extra_account(h uuid, username text, password text, server text default null) returns uuid
language plpgsql security definer set search_path = public as $$
declare
  u text := btrim(username);
  p text := btrim(password);
  s text := nullif(btrim(coalesce(server, '')), '');
  main_server text;
  pos int;
  new_id uuid;
begin
  if not public.is_owner(h) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  if coalesce(u, '') = '' or coalesce(p, '') = '' then
    raise exception 'identifiants-incomplets';
  end if;
  select c.server into main_server from public.xtream_credentials c where c.household_id = h;
  if not found then
    raise exception 'compte-principal-absent';
  end if;
  if s is not null then
    -- Le fournisseur parle en http : « exemple.com:8080 » devient « http://exemple.com:8080 » ; « / » final retiré.
    if s !~* '^https?://' then s := 'http://' || s; end if;
    s := regexp_replace(s, '/+$', '');
    if s !~* '^https?://[^/\s?#@]+([/?#].*)?$' or s ~ '\s' then
      raise exception 'adresse-invalide';
    end if;
    if lower(s) = lower(regexp_replace(btrim(main_server), '/+$', '')) then s := null; end if;
  end if;
  if exists (select 1 from public.xtream_credentials c where c.household_id = h and c.username = u)
     or exists (select 1 from public.xtream_extra_accounts a where a.household_id = h and a.username = u) then
    raise exception 'compte-deja-ajoute';
  end if;
  -- Verrou du foyer : deux ajouts simultanés ne prennent pas la même place.
  perform 1 from public.households where households.id = h for update;
  if (select count(*) from public.xtream_extra_accounts a where a.household_id = h) >= 4 then
    raise exception 'trop-de-comptes';
  end if;
  -- Première place libre (un compte retiré laisse sa place au suivant ajouté).
  select min(g) into pos from generate_series(2, 9) g
   where not exists (select 1 from public.xtream_extra_accounts a where a.household_id = h and a.position = g);
  insert into public.xtream_extra_accounts (household_id, position, username, password, server)
  values (h, pos, u, p, s) returning xtream_extra_accounts.id into new_id;
  perform public.extra_accounts_bump(h);
  return new_id;
end
$$;
revoke all on function public.add_extra_account(uuid, text, text, text) from public, anon;
grant execute on function public.add_extra_account(uuid, text, text, text) to authenticated;
