-- Réglages du foyer (17 nov. 2026) : thème de l'écran « Qui regarde ? » (welcome_theme), choisi sur la page iPhone.
-- Lecture : tous les membres (télés comprises, règle households_select) ; écriture : le propriétaire seul (households_update).
alter table public.households add column settings jsonb not null default '{}'::jsonb;
alter table public.households add constraint households_settings_object check (jsonb_typeof(settings) = 'object');
