-- Photos de profil (16 nov. 2026) : depuis la page iPhone, un membre du foyer enregistre la photo d'un profil de son
-- foyer dans le compartiment public « web », sous avatars/<id du profil>.webp (adresse lue par la télé).
-- Règles permanentes et étroites : rien d'autre que ce nom exact, et seulement pour un profil de son propre foyer.

create or replace function public.can_write_avatar(object_name text) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from public.profiles p
    where object_name = 'avatars/' || p.id::text || '.webp' and public.is_member(p.household_id)
  )
$$;
revoke all on function public.can_write_avatar(text) from public, anon;
grant execute on function public.can_write_avatar(text) to authenticated;

create policy avatars_select on storage.objects for select to authenticated
  using (bucket_id = 'web' and public.can_write_avatar(name));
create policy avatars_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'web' and public.can_write_avatar(name));
create policy avatars_update on storage.objects for update to authenticated
  using (bucket_id = 'web' and public.can_write_avatar(name))
  with check (bucket_id = 'web' and public.can_write_avatar(name));
create policy avatars_delete on storage.objects for delete to authenticated
  using (bucket_id = 'web' and public.can_write_avatar(name));

-- Safari (iPhone) ne sait pas toujours produire du WebP : la page envoie alors du JPEG (même nom de fichier).
update storage.buckets
set allowed_mime_types = array(select distinct unnest(coalesce(allowed_mime_types, '{}') || array['image/webp', 'image/jpeg']))
where id = 'web';
