-- Fond d'écran par profil (17 nov. 2026) : comme la photo et l'autocollant, posé depuis la page iPhone dans le
-- compartiment public « web », sous wallpapers/<id du profil>.webp (lu par la télé derrière les accueils Séries et
-- Films). Même règle étroite : ce nom exact seulement, pour un profil de son propre foyer ; aucune nouvelle règle.

create or replace function public.can_write_avatar(object_name text) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from public.profiles p
    where object_name in ('avatars/' || p.id::text || '.webp', 'stickers/' || p.id::text || '.webp',
                          'wallpapers/' || p.id::text || '.webp')
      and public.is_member(p.household_id)
  )
$$;
revoke all on function public.can_write_avatar(text) from public, anon;
grant execute on function public.can_write_avatar(text) to authenticated;
