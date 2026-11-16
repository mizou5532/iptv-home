-- GIF animés comme photo de profil (16 nov. 2026) : le compartiment « web » accepte aussi image/gif
-- (même nom avatars/<id>.webp ; la télé reconnaît le format au contenu). Taille bornée par la page (5 Mo).
update storage.buckets
set allowed_mime_types = array(select distinct unnest(coalesce(allowed_mime_types, '{}') || array['image/gif']))
where id = 'web';
