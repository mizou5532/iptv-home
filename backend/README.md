# Backend de synchronisation (Supabase)

Backend du lecteur IPTV familial : un seul foyer par projet Supabase, des profils, des identifiants Xtream partagés, des préférences synchronisées, la progression de lecture et la règle « qui regarde ». Tout le schéma vit dans `backend/supabase/migrations/` (huit migrations, 0001 à 0008) ; la sécurité repose sur la RLS et sur des fonctions `security definer`.

## Tables (schéma `public`, RLS activée partout)

- `households` : le foyer (un seul par projet), son nom (1 à 40 caractères) et son propriétaire.
- `household_members` : les membres du foyer, avec le rôle `owner` (ordinateur du propriétaire) ou `device` (télé appairée).
- `profiles` : les profils du foyer (nom 1 à 30 caractères, couleur, accueil, thème, réglages, position), 10 au plus.
- `xtream_credentials` : serveur, identifiant et mot de passe Xtream du foyer, lisibles par tous les membres, écrits par le propriétaire seulement.
- `pairing_codes` : codes d'appairage de télé à 6 caractères valables 10 minutes, accessibles uniquement par les fonctions (RLS sans politique).
- `favorites` : favoris d'un profil (chaîne, film ou série).
- `playlists` : listes nommées d'un profil (nom 1 à 40 caractères).
- `playlist_items` : éléments ordonnés d'une liste.
- `hidden_items` : éléments ou catégories masqués, pour tout le foyer (profil nul, propriétaire seulement) ou pour un profil.
- `category_order` : ordre des catégories par section (`live`, `vod`, `series`), pour tout le foyer ou pour un profil.
- `watch_progress` : position de lecture par profil et par film ou épisode, en lecture seule en direct.
- `active_streams` : les lectures en cours sur le compte IPTV (publiée dans Realtime).
- `live_catalog_category` : copie des catégories du direct publiée par la télé (clé `(household_id, id)`, nom, position, empreinte), lisible par les membres, sans écriture directe.
- `live_catalog_channel` : copie des chaînes du direct (clé `(household_id, id)`, catégorie, nom, nom normalisé, numéro, position), supprimées avec leur catégorie, lisible par les membres, sans écriture directe.
- `prefs_revision` : une ligne par foyer, `rev` augmente de 1 à chaque changement de `favorites`, `playlists`, `playlist_items`, `hidden_items` ou `category_order` (déclencheurs). Lisible par les membres, sans écriture directe.
- `favorites.position` (nouvelle colonne) : ordre des favoris d'un profil, ajout en fin de liste.

## Fonctions (appelables par `authenticated` uniquement, via `rpc`)

Toutes lèvent `acces-refuse` (errcode `42501`) si l'appelant n'a pas le droit. Les autres erreurs ont l'errcode `P0001` ; les clients les distinguent par le message.

| Fonction | Paramètres | Retour | Erreurs propres |
|---|---|---|---|
| `create_household` | `name`, `profile_names text[]` | uuid du foyer | `foyer-existe-deja` (42501, un foyer existe déjà), `trop-de-profils` (plus de 10), `acces-refuse` aussi pour les comptes anonymes |
| `set_xtream_credentials` | `h`, `server`, `username`, `password` | void | `identifiants-incomplets` (champ vide), propriétaire seulement |
| `create_pairing_code` | `device_name` (1 à 40 caractères) | code de 6 caractères | `nom-appareil-invalide` (remplace `nom-appareil-vide`) ; efface les codes non utilisés de l'appelant |
| `approve_pairing` | `h`, `code` | nom de l'appareil | `code-invalide`, `code-expire` ; propriétaire seulement |
| `my_households` | aucun | foyers de l'appelant | |
| `revoke_device` | `h`, `device` | void | `acces-refuse` si on vise le propriétaire ; propriétaire seulement ; prend le verrou du foyer |
| `hidden_for` | `p` (profil) | `(item_type, item_id)` masqués globalement ou pour ce profil | |
| `record_progress` | `p`, `item_type`, `item_id`, `position_s`, `duration_s`, `client_updated_at`, `series_id`, `season`, `episode` | booléen : `true` si la ligne a été écrite | |
| `continue_watching` | `p`, `max_rows` (20 par défaut) | lignes `watch_progress` | |
| `claim_stream` | `h`, `p`, `device_name`, `label`, `max_connections`, `device_key`, `takeover` (faux par défaut) | jsonb `{status:'ok', stream_id}` ou `{status:'occupe', occupants:[{device_name, profile_name, label}]}` | |
| `heartbeat_stream` | `id` | booléen : `false` si la lecture n'existe plus | |
| `release_stream` | `id` | void | |
| `publish_live_categories` | `h`, `cats` (jsonb `[{id,name,position}]`) | `(id, fingerprint)` des catégories connues | membre seulement ; met à jour toutes les catégories envoyées et supprime les absentes avec leurs chaînes |
| `publish_live_channels` | `h`, `category`, `fingerprint`, `chunk`, `is_last`, `channels` (jsonb `[{id,name,norm,number,position}]`) | void | `categorie-inconnue` (catégorie non publiée) ; membre seulement |
| `prefs_rev` | `h` | bigint (0 si aucune préférence) | membre seulement |
| `org_categories` | `h`, `p` | `(id, name, provider_position, hidden_global, hidden_profile, channel_count)` dans l'ordre effectif de la portée | membre ; `p` doit appartenir à `h` |
| `org_channels` | `h`, `category`, `p`, `off`, `lim` (plafonné à 1 000) | `(id, name, number, favorite, hidden_global, hidden_profile)` | idem |
| `org_search` | `h`, `q` (déjà normalisé), `p`, `lim` (plafonné à 200) | `(id, name, number, category_id, category_name, favorite, hidden_global, hidden_profile)` ; `%`, `_` et `\` de `q` sont cherchés littéralement ; `q` vide : aucun résultat | idem |
| `org_set_hidden` | `h`, `p`, `item_type` (`live_category` ou `channel`), `ids`, `hidden` | nombre de lignes changées | `type-invalide` ; écriture, voir plus bas |
| `org_hide_all_except` | `h`, `p`, `keep` | nombre de catégories publiées hors `keep` (masquées dans la portée) ; celles de `keep` sont réaffichées | écriture |
| `org_set_category_order` | `h`, `p`, `ids` | void ; ordre de la section `live`, doublons retirés | écriture |
| `org_set_favorite` | `p`, `channel`, `on_` | void ; ajoute en fin de liste ou retire | écriture sur le profil |
| `org_reorder_favorites` | `p`, `ids` | void ; les favoris de `ids` passent en tête, les autres suivent | écriture sur le profil |
| `org_favorites` | `p` | `(id, name, number, category_id, state)` ; `state` : `ok`, `categorie-masquee`, `masquee`, `n-existe-plus` (chaîne absente de la copie) | membre |
| `org_playlist_create` / `org_playlist_rename` / `org_playlist_delete` | `p`+`name` / `id`+`name` / `id` | uuid / void / void ; nom de 1 à 40 caractères sinon erreur de contrainte (23514) | écriture sur le profil de la liste |
| `org_playlists_reorder` | `p`, `ids` | void | écriture sur le profil |
| `org_playlist_set_items` | `id`, `channel_ids` | void ; remplace la liste ordonnée | écriture sur le profil |
| `org_playlists` | `p` | `(id, name, position, channel_ids, items)` ; `items` = `[{id, name, number, category_id, state}]` dans l'ordre, mêmes états que `org_favorites` | membre |
| `profile_prefs` | `p` | jsonb `{rev, hidden:[{item_type,item_id}], favorites:[ids de chaînes], playlists:[{id,name,items}], order:[ids]\|null}` | membre seulement (la télé l'appelle) |

`is_member`, `is_owner`, `can_use_profile`, `profile_household` et `playlist_profile` sont des aides de RLS ; `is_member` et `is_owner` ne sont pas exécutables par `anon`.

## Règles métier

- **90 %** : `record_progress` marque `watched = true` dès que `position_s >= 0,9 * duration_s` ; « vu » ne redevient jamais faux.
- **45 s** : une lecture sans battement depuis plus de 45 secondes est périmée et supprimée au prochain `claim_stream`. Les apps envoient `heartbeat_stream` régulièrement.
- **Plafond de 5 minutes** : l'heure envoyée par l'appareil est plafonnée à `now() + 5 minutes`, pour qu'une horloge déréglée ne bloque pas la progression. Le plus récent gagne.
- **Un seul foyer** : `create_household` refuse (`foyer-existe-deja`) dès qu'un foyer existe. Personne d'autre ne peut devenir propriétaire en s'inscrivant. Créer le foyer juste après la mise en place.
- **Copie figée du catalogue** : republier les catégories ne touche pas aux chaînes. Une catégorie dont la télé ne republie pas les chaînes garde celles déjà publiées ; seule la disparition de la catégorie les supprime.
- **Empreinte au dernier morceau** : les chaînes d'une catégorie arrivent par morceaux (`chunk` 0, 1, ...). Le morceau 0 efface d'abord les chaînes de la catégorie ; l'empreinte est remise à `null` avec lui et n'est écrite qu'avec `is_last`, donc une publication interrompue ne passe jamais pour terminée et sera refaite. Une chaîne déjà publiée sous une autre catégorie est déplacée (le dernier envoi gagne).
- **Portée des fonctions `org_*`** : `p` nul = « tout le monde ». Lecture ouverte à tout membre ; écriture « tout le monde » réservée au propriétaire (`is_owner`) ; écriture d'un profil = `can_use_profile(p)`. Quand une fonction prend `h` et `p`, `p` doit appartenir à `h` (sinon `acces-refuse`). Les fonctions de lecture renvoient un instantané cohérent.
- **Ordre et masquage** : « masqué gagne » ; ordre = profil, sinon tout le monde, sinon fournisseur ; les catégories absentes de l'ordre suivent dans l'ordre du fournisseur et les identifiants inconnus sont ignorés. Un ordre vide pour un profil l'emporte sur l'ordre du foyer (retour à l'ordre du fournisseur).
- **Révision** : `prefs_revision.rev` ne bouge que si une ligne change réellement (un masquage déjà fait ou un réordonnancement identique ne compte pas). Supprimer un profil ne la fait pas bouger.
- **Publication interrompue** : le morceau 0 de `publish_live_channels` remet aussi l'empreinte de la catégorie à `null`. `publish_live_categories` avec une liste vide ne supprime rien et renvoie les empreintes connues (protège la copie d'une réponse vide transitoire du fournisseur).
- **Suppression d'un profil** : propriétaire seulement (efface favoris, listes et progression).

## Contrat pour les clients (apps des plans 3 et 8)

- **`device_key`** : clé stable propre à chaque installation de l'app (1 à 64 caractères). Tous les ordinateurs partagent le compte du propriétaire ; sans elle, ils se chasseraient. `claim_stream` remplace seulement la lecture du même utilisateur ET de la même `device_key`. L'ancienne signature sans `device_key` n'existe plus.
- **Realtime** : un événement DELETE ne transporte que l'`id` de la ligne. L'app s'abonne aux suppressions de `active_streams` seulement (quota gratuit) et compare `old.id` à son propre `stream_id` pour s'arrêter. `heartbeat_stream` qui renvoie `false` reste le filet de sécurité.
- **`continue_watching`** : renvoie la dernière ligne par série, même si l'épisode est vu ; l'app propose l'épisode suivant ou retire la série. Un film n'apparaît que s'il n'est pas vu et a une position positive.
- **`record_progress`** : un appel sans série, saison ou épisode garde les valeurs déjà connues.
- **Upserts** : pour le masquage et l'ordre « pour tout le monde », l'unicité est `nulls not distinct`, donc `upsert` avec `on_conflict=household_id,profile_id,item_type,item_id` (`hidden_items`) et `on_conflict=household_id,profile_id,section` (`category_order`).
- **Écritures directes** : `watch_progress`, `active_streams`, `xtream_credentials` et `pairing_codes` n'ont pas d'écriture directe ; passer par les fonctions.
- **Appairage** : la télé se connecte en anonyme, appelle `create_pairing_code`, affiche le code ; le propriétaire appelle `approve_pairing`.

## Lancer les tests

```
npm test -w backend
```

## Mettre en production

1. Appliquer les migrations dans l'ordre (0001 à 0007) avec l'outil Supabase `apply_migration` (nom = nom du fichier sans l'horodatage) ou avec le SQL editor. Ne jamais modifier une migration déjà appliquée : ajouter une nouvelle migration.
2. Vérifier : 12 tables dans `public`, RLS activée, `get_advisors` (sécurité et performance).
3. Dans le tableau de bord Supabase : Authentication, Sign In / Providers, activer « Allow anonymous sign-ins » (nécessaire pour la télé) et garder la connexion par e-mail.
4. Créer le foyer tout de suite avec le compte du propriétaire.

Les adresses et la clé publique du projet sont dans `docs/rapports/synchro-production.md`. Ne jamais copier la clé `service_role` dans le dépôt.

## Notes d'exploitation

- **Télé perdue** : la révoquer (`revoke_device`) ET changer le mot de passe Xtream chez le fournisseur si possible ; une télé révoquée garde ce qu'elle a déjà en cache.
- **Ordinateur perdu** : c'est le compte du propriétaire. Changer le mot de passe du foyer et déconnecter toutes les sessions.
- **Utilisateurs anonymes** : ne jamais purger les utilisateurs anonymes qui sont des télés appairées.
- **`foyer-existe-deja` inattendu** : la seule remise à zéro est, dans le SQL editor, `delete from public.households` (cascade sur tout). Créer le foyer juste après.
