-- Stabilisation zone 8 (18 nov. 2026) : réglages conseillés par Supabase. Aucun changement de comportement.

-- Index sur des clés étrangères (suppressions en cascade et jointures sans lecture complète).
create index if not exists active_streams_profile_idx on public.active_streams (profile_id);
create index if not exists households_owner_idx on public.households (owner_id);
create index if not exists pairing_codes_household_idx on public.pairing_codes (household_id);

-- media_meta : l'identité de l'appelant est lue une fois par demande, pas une fois par ligne.
drop policy media_meta_select on public.media_meta;
create policy media_meta_select on public.media_meta for select to authenticated
  using (exists (select 1 from public.household_members m where m.user_id = (select auth.uid())));

-- category_order et hidden_items : une seule règle de lecture (la règle « ALL » d'écriture s'ajoutait aux lectures).
-- Mêmes conditions qu'avant, découpées en insertion / modification / suppression.
drop policy category_order_write on public.category_order;
create policy category_order_insert on public.category_order for insert to authenticated
  with check (case when profile_id is null then public.is_owner(household_id)
                   else public.can_use_profile(profile_id) and public.profile_household(profile_id) = household_id end);
create policy category_order_update on public.category_order for update to authenticated
  using (case when profile_id is null then public.is_owner(household_id) else public.can_use_profile(profile_id) end)
  with check (case when profile_id is null then public.is_owner(household_id)
                   else public.can_use_profile(profile_id) and public.profile_household(profile_id) = household_id end);
create policy category_order_delete on public.category_order for delete to authenticated
  using (case when profile_id is null then public.is_owner(household_id) else public.can_use_profile(profile_id) end);

drop policy hidden_items_write on public.hidden_items;
create policy hidden_items_insert on public.hidden_items for insert to authenticated
  with check (case when profile_id is null then public.is_owner(household_id)
                   else public.can_use_profile(profile_id) and public.profile_household(profile_id) = household_id end);
create policy hidden_items_update on public.hidden_items for update to authenticated
  using (case when profile_id is null then public.is_owner(household_id) else public.can_use_profile(profile_id) end)
  with check (case when profile_id is null then public.is_owner(household_id)
                   else public.can_use_profile(profile_id) and public.profile_household(profile_id) = household_id end);
create policy hidden_items_delete on public.hidden_items for delete to authenticated
  using (case when profile_id is null then public.is_owner(household_id) else public.can_use_profile(profile_id) end);
