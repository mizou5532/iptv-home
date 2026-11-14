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
| `publish_live_categories` | `h`, `cats` (jsonb `[{id,name,position}]`) | un objet jsonb `{"<id>": "<empreinte ou null>"}` des catégories connues | membre seulement ; met à jour toutes les catégories envoyées et supprime les absentes avec leurs chaînes |
| `publish_live_channels` | `h`, `category`, `fingerprint`, `chunk`, `is_last`, `channels` (jsonb `[{id,name,norm,number,position}]`) | void | `categorie-inconnue` (catégorie non publiée) ; membre seulement |
| `prefs_rev` | `h` | bigint (0 si aucune préférence) | membre seulement |
| `global_hidden_live_categories` | `h` | `text[]` : ids des catégories du direct masquées pour tout le monde (tableau vide si aucune) | membre seulement ; la télé l'appelle au lieu de lire `hidden_items` |
| `org_categories` | `h`, `p` | un tableau jsonb `[{id, name, provider_position, hidden_global, hidden_profile, channel_count}]` dans l'ordre effectif de la portée | membre ; `p` doit appartenir à `h` |
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
- **Révision** : `prefs_revision.rev` ne bouge que si une ligne change réellement (un masquage déjà fait ou un réordonnancement identique de favoris ne compte pas). Exception : `org_set_category_order` augmente toujours la révision, même pour un ordre identique. Supprimer un profil ne la fait pas bouger.
- **Publication interrompue** : le morceau 0 de `publish_live_channels` remet aussi l'empreinte de la catégorie à `null`. `publish_live_categories` avec une liste vide ne supprime rien et renvoie les empreintes connues (protège la copie d'une réponse vide transitoire du fournisseur).
- **Suppression d'un profil** : propriétaire seulement (efface favoris, listes et progression).

- **Limite « Max rows » de PostgREST** : Supabase coupe à 1 000 lignes (par défaut) les `GET` de table et les fonctions qui renvoient un jeu de lignes (`returns table`). Les tests tournent sur Postgres seul et ne le voient pas. Un résultat qui peut dépasser 1 000 doit donc être UNE valeur (jsonb ou tableau) ou être paginé sous 1 000 : c'est le cas de `org_categories`, `publish_live_categories` et `global_hidden_live_categories`. Les autres fonctions `org_*` sont bornées : `org_channels` (`lim` ≤ 1 000, paginée par `off`), `org_search` (≤ 200), `org_playlists` (une ligne par liste) ; `org_favorites` renvoie une ligne par favori d'un profil (très en dessous de 1 000 en pratique).

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

## B2 — Corrections des copies du direct (migration 0009)

Les corrections sont communes au foyer ; tout membre peut les lire, seul le propriétaire
peut les modifier. Les quatre nouvelles tables ont RLS et n'autorisent aucune écriture
client directe : `live_group_revision(household_id,rev)`,
`live_group_override(household_id,id,title,blocked_auto_keys text[])`,
`live_group_source(household_id,source_id,group_id)` et
`live_catalog_revision(household_id,rev)`. Une source appartient à un seul groupe par foyer ;
la FK composée interdit les références entre foyers. Les sources ne référencent pas le
catalogue par FK : une correction survit à la disparition d'une chaîne.

RPC (rôle `authenticated` seulement ; `anon` sans droit d'exécution) :

- `live_group_page(h uuid, after_id uuid, lim int default 500) returns jsonb` :
  `{rev, rules_version:1, groups:[{id,title,source_ids:[text],blocked_auto_keys:[text]}], next_id:uuid|null}`.
  Première page : `after_id=null`. Ordre des groupes par UUID ; sources triées par identifiant.
- `live_group_edit(h uuid, expected_rev bigint, replacement jsonb) returns jsonb` : `{rev}`.
  `replacement={remove_ids:[uuid],groups:[{id:uuid,title:text,source_ids:[text],blocked_auto_keys:[text]}]}`.
  Les groupes de `remove_ids` sont supprimés ; ceux de `groups` remplacent intégralement les
  groupes de même identifiant, les autres restent intacts. Pour fusionner, retirer les anciens
  groupes dans le même appel. Un verrou du foyer sérialise les éditions ; `expected_rev`
  doit égaler la révision courante (initialement 0), sinon `conflit-revision` sans modification.
  Titres blancs, groupes vides, sources répétées ou groupes répétés sont refusés
  (`correction-invalide` ou contrainte d'unicité). Une source doit exister dans la copie publiée
  ou dans une correction actuelle de ce foyer, sinon `source-inconnue`. Les anciennes sources
  disparues peuvent ainsi être conservées ou déplacées atomiquement, sans en inventer.
  Chaque clé `blocked_auto_keys` reste portée par son groupe tant que celui-ci existe : retirer
  une correction laisse intactes les références des autres corrections. La confirmation d'une
  restauration touchant plusieurs corrections appartient à l'interface cliente.
- `live_catalog_page(h uuid, after_position int, after_id text, lim int default 500) returns jsonb` :
  `{catalog_rev,channels:[{id,name,category_id,category_name,position,number}],next_cursor:{position,id}|null}`.
  Première page : les deux paramètres `after_*` à `null`, ensuite reprendre les deux valeurs
  du curseur. Tri `(position,id)` et index correspondant ; aucune URL. Les préférences de
  visibilité B1 restent à appliquer avant tout affichage/lecture ; cette copie sert à organiser.
- `set_group_copies(p uuid, enabled boolean) returns void` : tout utilisateur autorisé à utiliser
  le profil peut changer ce seul profil ; profil étranger ou inconnu : `acces-refuse`.
  `profiles.group_copies boolean not null default true` ; `enabled=null` est refusé.
  `profile_prefs` ajoute `group_copies` et conserve les clés B1 `rev`, `hidden`, `favorites`,
  `playlists`, `order`. Les favoris restent inchangés. Chaque édition de corrections et chaque
  changement effectif du réglage incrémentent `prefs_revision` pour le polling existant (~5 s).

Les pages acceptent `1 <= lim <= 1000`, sinon `page-invalide` (y compris `null`) ; un
curseur catalogue à moitié renseigné est également refusé. `next_id`/`next_cursor=null`
signifie la fin, même si la dernière page contient exactement `lim` éléments. Une page vide
renvoie un tableau vide. Chaque appel lit un instantané SQL cohérent. Il n'y a pas de snapshot
retenu entre appels : le client **doit comparer `rev`/`catalog_rev` à chaque page**, rejeter
l'ensemble si une révision change et reprendre depuis la première page. Il conserve le dernier
cache complet jusqu'à une lecture entière réussie, y compris en cas de coupure réseau ; après
au plus trois tentatives, il propose « Réessayer ». Cette logique sera implémentée dans les clients.

`live_catalog_revision` augmente dans les mêmes transactions que les changements de copie,
par triggers d'instruction sur insert/update/delete des catégories et chaînes (cascades incluses).
Le compteur est monotone, pas un nombre de publications : un appel peut l'incrémenter plusieurs
fois ; une réécriture identique peut l'incrémenter aussi. Une transaction annulée annule son
incrément. Les triggers traitent les foyers distincts en lot, sans incrément par chaîne.
Les RPC utilisent `security definer`, un `search_path` borné et des droits exécutables explicites.

## B2 — Réservation avec arrêt confirmé (migration 0010, locale uniquement)

Le protocole ci-dessous remplace v1 **pour les foyers explicitement activés seulement**.
L’activation appartient au propriétaire, après mise à jour de toutes les installations et
vérification de l’arrêt physique des anciennes lectures. Elle est idempotente : la répéter
ne modifie jamais une place existante. Les appels `claim_stream` d’un foyer activé échouent
avec `protocole-obsolete`. Les foyers non activés gardent intégralement v1 et ses tests.

### Tables et confidentialité

- `playback_slot` : une ligne persistante par foyer ; existence = activation. Champs :
  `household_id`, `generation uuid`, `state` (`empty/reserved/playing/stopping`), `user_id`,
  `device_hash bytea`, `device_name`, `profile_id`, `source_id`, `source_category_id`, `label`, `last_heartbeat`.
  Aucune lecture/écriture directe client, aucune publication Realtime.
- `playback_request` : `id uuid`, `household_id`, génération **ciblée**, `user_id`,
  `device_hash`, `device_name`, `profile_id`, `source_id`, `label`, `expires_at` (30 secondes),
  `state` (`waiting/ready/cancelled`). Pas de FK vers profil/membre : leur suppression
  conserve la preuve de réservation et déclenche un arrêt au lieu de libérer la place.
  Lecture membre des colonnes publiques uniquement, aucune écriture directe.
- `playback_signal(household_id,generation,kind)` : dernier signal par foyer, `kind` vaut
  `stop` ou `revoked`. Lecture membre seulement, aucune écriture directe.

La clé d’installation aléatoire et privée (1–64 caractères) reste côté appareil. Seule son
empreinte SHA-256 native PostgreSQL est stockée ; aucune RPC ni publication ne la renvoie,
pas même l’empreinte. Les deux appareils d’un même `auth.uid` doivent avoir des clés distinctes.
Les champs `label` (1–200 caractères non blancs) et `device_name` (1–40) contiennent uniquement
un nom d’affichage, jamais identifiants fournisseur ou adresse de flux. `p` est obligatoire,
appartient au foyer ; `source_id` est l’identifiant du catalogue direct, pas une URL.

### Signatures RPC et JSON exacts (clients tâches 6/8)

Tous les paramètres sont nommés comme ci-dessous pour PostgREST. Tous les RPC sont
`SECURITY DEFINER`, `search_path=public`, exécutables par `authenticated` uniquement.
`playback_key`, `playback_lock`, `playback_allowed` et `playback_revoke` sont privés.

| RPC | Retour exact |
|---|---|
| `playback_claim(h uuid,p uuid,device_key text,device_name text,source_id text,label text)` | `{status:"ok",generation:uuid}` ; `{status:"setup_required"}` ; ou `{status:"occupied",generation:uuid,occupant:{device_name:text\|null,profile_id:uuid\|null,label:text\|null},stale:boolean}` |
| `playback_control(h uuid,generation uuid,device_key text,beat boolean default false)` | `{status:"keep"}` ou `{status:"stop"}` ou `{status:"stop",request_id:uuid}` |
| `playback_request_stop(h uuid,generation uuid,p uuid,device_key text,device_name text,source_id text,label text)` | UUID de la demande |
| `playback_poll_request(request_id uuid,device_key text)` | `{status:"waiting"\|"ready"\|"cancelled"}` |
| `playback_cancel_request(request_id uuid,device_key text)` | void |
| `playback_stopped(h uuid,generation uuid,device_key text,request_id uuid default null)` | void |
| `playback_promote(request_id uuid,device_key text)` | `{status:"ok",generation:uuid}` (nouvelle génération) |
| `playback_started(h uuid,generation uuid,device_key text)` | void |
| `playback_update(h uuid,generation uuid,device_key text,source_id text,label text)` | void |
| `playback_enable(h uuid)` | void, propriétaire seulement |
| `playback_confirm_physical_stop(request_id uuid)` | void, propriétaire seulement |
| `playback_admin_state(h uuid)` | `{enabled:boolean,slot:null\|{generation:uuid,state:"empty"\|"reserved"\|"playing"\|"stopping",device_name:text\|null,label:text\|null,stale:boolean},requests:[{id:uuid,generation:uuid,device_name:text,label:text,expires_at:ISO8601,state:"waiting"}]}` ; propriétaire seulement |

`generation` dans la réponse `occupied` sert à cibler `playback_request_stop`. `occupant`
ne fournit pas de nom de profil : utiliser `profile_id` avec les profils du foyer déjà lus.
Une place vide peut garder les anciens champs descriptifs ; le client affiche l’occupant
uniquement lorsque l’état est différent de `empty`. `requests` de l’admin contient seulement
les demandes waiting non expirées de la génération courante, triées par expiration.
`poll_request` présente une demande expirée ou d’ancienne génération comme `cancelled`
sans supprimer la ligne. Une promotion consommée passe également la demande à `cancelled`.

### Ordre des opérations côté lecteur

1. Un `claim` réussi réserve une génération. Un `claim` répété, même par le titulaire,
   ne remplace jamais cette place : utiliser `update` pour changer de chaîne, en fermant
   physiquement le flux précédent avant son remplacement. `started` passe reserved → playing
   après la première image (répétition en playing acceptée).
2. Une prise de main envoie la lecture souhaitée (profil/source/label) avec sa clé privée.
   `request_stop` marque stopping et crée une demande waiting, sans autorisation de lecture.
   Une seule demande non expirée waiting/ready est admise par foyer.
3. Le titulaire interroge `control` toutes les **2 secondes**, avec `beat=true` pour le
   battement. Tout refus ou impossibilité de confirmer son droit de lecture doit faire arrêter
   le lecteur ; ne jamais interpréter une erreur réseau comme une nouvelle autorisation.
   `stop` fait fermer/libérer physiquement le lecteur **avant** `stopped`. L’accusé peut être
   envoyé avec `request_id=null` pour un arrêt volontaire ou une demande devenue périmée.
4. Seuls l’utilisateur ET l’installation titulaires accusent l’arrêt. Exception limitée :
   cet accusé reste autorisé après révocation du membre. Un ancien accusé est sans effet.
   Un identifiant de demande fourni doit appartenir au foyer et à la génération accusée.
5. L’accusé rend la place empty et les demandes waiting valides ready. Jusqu’à promotion,
   annulation ou expiration, un autre claim reste bloqué par la priorité ready. `promote`
   vérifie de nouveau utilisateur, installation, expiration, génération, profil, catalogue
   et masques globaux/profil, puis attribue une nouvelle génération reserved.
6. Une demande annulée/expirée n’efface jamais la lecture titulaire et n’annule pas un stop
   déjà envoyé. Une réservation stale, même sans première image, reste bloquante sans preuve
   d’arrêt. **45 secondes signifie seulement `stale=true`, jamais libération.**
7. En récupération, le propriétaire utilise l’onglet Lecture du téléphone et confirme après
   vérification physique. `confirm_physical_stop` refuse une demande expirée/annulée/ready
   ou une génération changée. Il rend la demande ready ; seul le demandeur peut promouvoir.

Les transitions sont sérialisées par verrou du foyer. `started`/`update` refusent une ancienne
génération, une mauvaise installation et un état stopping/empty. `update`, `started` et
`control` revalident le profil et la visibilité ; `control` renvoie stop si ces droits ont
changé. Révocation ou suppression du profil conserve la place stopping et publie `revoked`.

Realtime publie uniquement les colonnes publiques des demandes (liste explicite PostgreSQL16)
et `playback_signal`, jamais `playback_slot` ni les battements. Écouter les INSERT/UPDATE
filtrés sur le foyer, puis vérifier génération/demande ; l’événement ne constitue jamais une
permission de démarrer. Le polling reste obligatoire en cas de perte d’événements. Aucune
clé n’est présente dans les réponses, les événements ou l’administration.

Erreurs métier : `cle-appareil-invalide`, `nom-appareil-invalide`, `libelle-invalide`,
`source-inconnue`, `source-masquee`, `generation-invalide`, `demande-en-cours`,
`demande-invalide`, `protocole-obsolete` (P0001) ; refus d’accès `acces-refuse` (42501).
Les clients affichent des textes français fixes, jamais les erreurs SQL brutes.


### Publication B1 par lots et lecture déjà autorisée

Une publication de catégorie B1 efface ses chaînes au lot 0 et garde `fingerprint=NULL`
jusqu'au dernier lot. Cette absence transitoire de la copie ne doit couper ni une lecture en
cours ni la première image arrivant après un claim réussi. `source_category_id` est une
preuve privée de la catégorie validée pour la source : claim/promote/update la mémorisent
avec leur contrôle strict, dans un même instantané. Aucune FK ne doit l'effacer par cascade.

Seuls `control` et `started` réutilisent cette preuve : si la source est absente mais sa
catégorie mémorisée existe encore avec empreinte NULL, ils continuent à vérifier profil,
installation, membre et masques chaîne/catégorie aux deux portées, puis acceptent la continuité.
Les masques se lisent directement, même en l'absence de ligne source. Si la source est présente,
sa catégorie courante est validée et mémorisée, y compris après déplacement dans le catalogue.
Une catégorie supprimée ou une publication terminée (empreinte non NULL) sans la source ne
permet pas cette tolérance : `control` demande l'arrêt et `started` échoue.

Les nouvelles autorisations (`claim`, `request_stop`, `promote`, `update`) restent strictes :
la source doit être présente et visible, même pendant une publication partielle. Il n'y a
aucune nouvelle signature ni clé JSON publique. Une publication interrompue qui laisse une
empreinte NULL conserve uniquement l'autorisation déjà accordée ; aucun délai réseau ne
supprime les contrôles de masques/révocation ni n'autorise un nouveau flux.
