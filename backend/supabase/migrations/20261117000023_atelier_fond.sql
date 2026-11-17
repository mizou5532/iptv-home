-- Atelier de fond d'écran (17 nov. 2026) : la création se rouvre pour être modifiée. Ses images (photo du fond,
-- images ajoutées) sont gardées dans le compartiment public « web » sous studio/<id du profil>/<clé>.webp.
-- Même règle étroite que la photo : seulement un profil de son propre foyer, clé de 6 à 40 lettres/chiffres
-- minuscules (aucun autre dossier, aucun « .. »), toujours les 4 règles avatars_*.

create or replace function public.can_write_avatar(object_name text) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from public.profiles p
    where (object_name in ('avatars/' || p.id::text || '.webp', 'stickers/' || p.id::text || '.webp',
                           'wallpapers/' || p.id::text || '.webp')
           or object_name ~ ('^studio/' || p.id::text || '/[a-z0-9]{6,40}\.webp$'))
      and public.is_member(p.household_id)
  )
$$;
revoke all on function public.can_write_avatar(text) from public, anon;
grant execute on function public.can_write_avatar(text) to authenticated;
