-- Retourne toutes les catégories masquées pour tout le monde (Direct, Films et Séries), en une seule RPC
-- (évite le plafond de 1 000 lignes de PostgREST). Sans toucher à `global_hidden_live_categories(uuid)` pour ne
-- pas casser les anciennes versions de l'application télé.

create or replace function public.global_hidden_categories(h uuid) returns jsonb
language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_member(h) then
    raise exception 'acces-refuse' using errcode = '42501';
  end if;
  return coalesce((
    select jsonb_agg(
      jsonb_build_object('item_type', hi.item_type, 'item_id', hi.item_id)
      order by hi.item_type, hi.item_id
    )
    from public.hidden_items hi
    where hi.household_id = h
      and hi.profile_id is null
      and hi.item_type in ('live_category', 'vod_category', 'series_category')
  ), '[]'::jsonb);
end
$$;

revoke all on function public.global_hidden_categories(uuid) from public, anon;
grant execute on function public.global_hidden_categories(uuid) to authenticated;
