-- Versions séparées à la main (18 nov. 2026). La télé regroupe les versions d'un même titre (langues, 4K) d'après leur
-- nom ; quand deux titres différents sont réunis par erreur, « Ce n'est pas le même titre » sur la fiche sépare cette
-- version pour toute la maison. Liste « type:identifiant » dans households.settings.version_splits (500 au plus) ;
-- la page iPhone la montre (Réglages) et peut tout réunir de nouveau.
create function public.set_version_split(h uuid, kind text, item_id text, split boolean) returns void
language plpgsql security definer set search_path = public as $$
declare
  k text;
  cur jsonb;
begin
  perform public.org_assert(h, null, false);
  if kind not in ('movie', 'series') or item_id !~ '^[0-9A-Za-z_-]{1,40}$' then
    raise exception 'version invalide' using errcode = '22023';
  end if;
  k := kind || ':' || item_id;
  select coalesce(settings -> 'version_splits', '[]'::jsonb) into cur from public.households where id = h for update;
  if jsonb_typeof(cur) <> 'array' then cur := '[]'::jsonb; end if;
  cur := coalesce((select jsonb_agg(e) from jsonb_array_elements(cur) e where e <> to_jsonb(k)), '[]'::jsonb);
  if split then
    if jsonb_array_length(cur) >= 500 then raise exception 'trop de versions séparées' using errcode = '54000'; end if;
    cur := cur || to_jsonb(k);
  end if;
  update public.households set settings = jsonb_set(coalesce(settings, '{}'::jsonb), '{version_splits}', cur) where id = h;
end
$$;
revoke all on function public.set_version_split(uuid, text, text, boolean) from public, anon;
grant execute on function public.set_version_split(uuid, text, text, boolean) to authenticated;
