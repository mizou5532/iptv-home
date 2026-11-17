-- Autocollant par profil (17 nov. 2026) : comme la photo, posé depuis la page iPhone dans le compartiment public
-- « web », sous stickers/<id du profil>.webp (adresse lue par la télé, coin des accueils Séries et Films).
-- Même règle étroite que la photo : ce nom exact seulement, et seulement pour un profil de son propre foyer.
-- Les 4 règles avatars_* existantes s'appliquent donc aussi aux autocollants (aucune nouvelle règle).

create or replace function public.can_write_avatar(object_name text) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from public.profiles p
    where object_name in ('avatars/' || p.id::text || '.webp', 'stickers/' || p.id::text || '.webp')
      and public.is_member(p.household_id)
  )
$$;
revoke all on function public.can_write_avatar(text) from public, anon;
grant execute on function public.can_write_avatar(text) to authenticated;

-- Un autocollant garde sa transparence : Safari (iPhone) ne sait pas toujours produire du WebP, la page envoie alors
-- du PNG (même nom de fichier).
update storage.buckets
set allowed_mime_types = array(select distinct unnest(coalesce(allowed_mime_types, '{}') || array['image/png']))
where id = 'web';
