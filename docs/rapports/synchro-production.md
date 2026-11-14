# Synchro : mise en production

- Date : 2026-11-14
- Projet Supabase : `iptv-home`, référence `gisjeuukbrgjgkrmlyvs`, région `ca-central-1`, offre gratuite
- URL : https://gisjeuukbrgjgkrmlyvs.supabase.co
- Clé publique (anon, publique par conception, la RLS protège les données) : `sb_publishable_Rfqg2UOqhABAF4ugGAfzHQ_84UIpABt` (la clé anon héritée existe aussi ; la clé `service_role` n'est jamais consignée)

## Migrations appliquées (7, dans l'ordre)

1. `foyer_profils`
2. `identifiants_xtream`
3. `appairage`
4. `preferences`
5. `progression`
6. `qui_regarde`
7. `correctifs`

Le registre `supabase_migrations.schema_migrations` confirme les sept entrées.

## Vérifications

- `list_tables` (public) : 12 tables, RLS activée sur les 12.
- `has_table_privilege('authenticated','public.profiles','select')` : true
- `has_table_privilege('anon','public.xtream_credentials','select')` : **true** (privilège de table hérité des droits par défaut de Supabase pour `anon` ; la RLS sans politique pour `anon` ne renvoie aucune ligne, mais la migration 7 ne retire pas ce droit)
- `has_table_privilege('authenticated','public.xtream_credentials','truncate')` : false
- `active_streams` dans la publication `supabase_realtime` : oui (1 ligne)
- Colonne `active_streams.device_key` présente.

## Avis de sécurité (`get_advisors`)

- INFO `rls_enabled_no_policy` : `public.pairing_codes` a la RLS sans politique. Voulu : accès uniquement par les fonctions. Rien à faire.
- WARN `authenticated_security_definer_function_executable` (17 fonctions) : `approve_pairing`, `can_use_profile`, `claim_stream`, `continue_watching`, `create_household`, `create_pairing_code`, `heartbeat_stream`, `hidden_for`, `is_member`, `is_owner`, `my_households`, `playlist_profile`, `profile_household`, `record_progress`, `release_stream`, `revoke_device`, `set_xtream_credentials`. Voulu pour les fonctions d'API (contrôles internes `is_owner`/`is_member`, `anon` exclu). Pour les aides (`is_member`, `is_owner`, `can_use_profile`, `profile_household`, `playlist_profile`), la remédiation serait de les sortir du schéma exposé ; elles ne divulguent qu'une appartenance au foyer de l'appelant ou des identifiants de profil. Remédiation : https://supabase.com/docs/guides/database/database-linter?lint=0029_authenticated_security_definer_function_executable

Aucune alerte de niveau ERROR.

## Avis de performance

- INFO `unindexed_foreign_keys` (3) : `active_streams.profile_id`, `households.owner_id`, `pairing_codes.household_id`.
- INFO `no_primary_key` (1) : `category_order` (unicité portée par la contrainte `category_order_unique`).
- INFO `unused_index` (9) : index sans usage pour l'instant (base vide).
- WARN `multiple_permissive_policies` (2) : `category_order` et `hidden_items` (politiques `*_select` et `*_write` toutes deux permissives en SELECT pour `authenticated`).

## Action en attente pour le propriétaire

Dans le tableau de bord Supabase : Authentication, Sign In / Providers, activer « Allow anonymous sign-ins » (nécessaire pour la télé) et garder la connexion par e-mail. Puis créer le foyer sans attendre (règle du foyer unique).
