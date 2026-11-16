-- Onglet d'ouverture par profil (16 nov. 2026) : profiles.home choisit l'onglet où la TV arrive quand on
-- choisit ce profil. Valeurs : direct, series, films, sport. « mixte » (ancienne valeur par défaut) devient direct.
alter table public.profiles drop constraint if exists profiles_home_check;
update public.profiles set home = 'direct' where home is null or home = 'mixte';
alter table public.profiles alter column home set default 'direct';
alter table public.profiles alter column home set not null;
alter table public.profiles add constraint profiles_home_check check (home in ('direct', 'series', 'films', 'sport'));
