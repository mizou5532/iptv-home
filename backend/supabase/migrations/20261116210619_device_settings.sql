-- Noms personnalisés et pictogrammes ; compatibilité des appareils déjà associés.
alter table public.household_members
 add column device_icon text not null default 'tv' check (device_icon in ('tv','sofa','bed','desk','other')),
 add column device_default_name text,
 add column device_name_customized boolean not null default false;
-- Seul l'ancien libellé générique peut être remplacé automatiquement.
update public.household_members set device_name_customized=true
 where role='device' and nullif(btrim(device_name),'') is not null and device_name <> 'Télé du salon';

create function public.set_device_settings(h uuid, device uuid, name text, icon text) returns void
language plpgsql security definer set search_path = '' as $$
begin
 if not public.is_owner(h) then raise exception 'acces-refuse' using errcode='42501'; end if;
 if name is null or char_length(btrim(name)) not between 1 and 40 then raise exception 'nom-appareil-invalide'; end if;
 if icon is null or icon not in ('tv','sofa','bed','desk','other') then raise exception 'icone-appareil-invalide'; end if;
 update public.household_members m set device_name=btrim(name),device_icon=icon,device_name_customized=true
 where m.household_id=h and m.user_id=device and m.role='device';
 if not found then raise exception 'appareil-introuvable'; end if;
end $$;

-- L'appareil ne peut déclarer que sa propre identité. Aucun numéro de série matériel.
create function public.report_device_identity(h uuid, default_name text) returns text
language plpgsql security definer set search_path = '' as $$
declare result text; model text; suffix text;
begin
 if default_name is null or char_length(btrim(default_name)) not between 1 and 40 then raise exception 'nom-appareil-invalide'; end if;
 model:=btrim(default_name);
 suffix:=upper(right(replace(auth.uid()::text,'-',''),6));
 update public.household_members m set device_default_name=model,
 device_name=case when m.device_name_customized then m.device_name else left(model,31)||' · '||suffix end
 where m.household_id=h and m.user_id=auth.uid() and m.role='device'
 returning m.device_name into result;
 if not found then raise exception 'acces-refuse' using errcode='42501'; end if;
 return result;
end $$;
revoke all on function public.set_device_settings(uuid,uuid,text,text) from public,anon;
revoke all on function public.report_device_identity(uuid,text) from public,anon;
grant execute on function public.set_device_settings(uuid,uuid,text,text) to authenticated;
grant execute on function public.report_device_identity(uuid,text) to authenticated;
