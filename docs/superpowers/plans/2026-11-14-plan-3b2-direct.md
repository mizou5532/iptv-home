# B2 — Le direct complet — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Regrouper les copies avec corrections sur iPhone, ajouter aperçu sur OK, guide maintenant/ensuite et prise de main sûre sur la télé.

**Architecture:** Conserver les identifiants concrets et les préférences B1. Un moteur déterministe Kotlin/TypeScript présente les groupes ; Supabase conserve uniquement les corrections, réglages et réservations de lecture. Un coordinateur unique pilote Media3, la présence et les transitions aperçu/plein écran.

**Tech Stack:** Kotlin Multiplatform, SQLDelight, Compose for TV, Media3, TypeScript/Vite/Vitest, Supabase/Postgres 16, tests Kotlin/JUnit.

**Spec:** `docs/superpowers/specs/2026-11-14-b2-direct-complet-design.md` — validée par le propriétaire dans la conversation le 14 novembre 2026.

## Global Constraints

- Sauvegarde prioritaire : commit/push après chaque sous-étape verte ou correction, avant commande longue/sous-agent, jamais plus de 30 min sans push. `git pull --rebase origin plan-3b2-direct` avant chaque push pour intégrer le travail de Claude ; WIP explicite autorisé.

- « L'aperçu démarre uniquement sur OK, jamais au simple passage sur une chaîne. »
- « Choix de source manuel ; la bascule automatique reste à la toute fin du projet. »
- « Masqué gagne » avant tout affichage et toute lecture ; une catégorie masquée pour le foyer n'est jamais téléchargée.
- Budget B1 : 100 000 chaînes et 2 000 catégories ; pas de regroupement quadratique ni de téléchargement du guide complet.
- Les corrections sont communes au foyer et modifiables uniquement par le propriétaire ; le réglage de regroupement est par profil, activé par défaut.
- « Même signature Android, jamais régénérée. Aucun usage de service_role. Aucun compte ni foyer créé dans Supabase de production par des tests. »
- Aucun identifiant IPTV, aucune adresse de flux dans journaux, fixtures, messages, copies de catalogue ou captures ; tests avec données fictives.
- Textes visibles en français ; conserver les jetons du thème sombre et la navigation télécommande.
- Un seul flux du fournisseur : fermeture avant remplacement ; déplacement aperçu/plein écran sans reconnexion.
- Chaque tâche : tests en échec démontré, implémentation, tests passants, relecture indépendante et correction avant la suivante ; relecture finale de branche.
- Avant le premier cycle de tests concerné, résoudre ses prérequis d'environnement listés en tâche 9.1 ; une panne d'initdb/SDK ne constitue pas le test rouge d'une fonctionnalité. Relancer d'abord les suites B1 disponibles pour établir la référence.
- Checkout cloud déjà isolé : conserver `plan-3b2-direct`, aucun worktree supplémentaire. Conserver les changements utilisateur. Mettre à jour passation, feuille de route et journal à chaque étape, committer et pousser sur cette branche.
- Accords explicites distincts avant migration de production, mise en ligne et fusion dans main. Le présent plan ne les autorise pas.

## Review Focus

1. Un favori masqué ne doit pas réapparaître grâce à une autre copie visible — assertions tâche 4.
2. Une réponse réseau ou une première image ancienne ne doit pas relancer une chaîne après changement de profil/arrêt — tâches 6 et 7, jeton de génération.
3. Deux iPhone corrigeant le même groupe ne doivent pas perdre une modification — tâche 2, révision attendue et transaction atomique.
4. Un arrêt brutal ou une révocation avec appareil hors ligne ne prouve pas l'arrêt vidéo — tâches 5 et 6, confirmation physique explicite et aucune libération sur expiration seule.
5. Une migration locale ne démontre pas la compatibilité PostgREST, la fluidité TCL ni le nombre réel de connexions — tâche 9 : contrat paginé, essais distincts, résultats non exécutés identifiés.

## Carte des fichiers et décisions communes

- `fixtures/live-groups-v1.json` : contrat de noms et résultats commun aux deux langages, aucune donnée du propriétaire.
- `apps/shared/src/commonMain/kotlin/home/iptv/shared/groups/` : modèles et moteur purs ; équivalent `web/config/src/org/groups.ts`.
- `backend/supabase/migrations/20261114000009_groupes_direct.sql` : groupes corrigés, réglages et RPC. `20261114000010_lecture_confirmee.sql` : protocole de lecture B2.
- `web/config/src/org/groups-view.ts` : écran Copies séparé de `view.ts`, déjà volumineux ; `playback-admin.ts` : confirmations propriétaire.
- `apps/tv/src/main/kotlin/home/iptv/tv/live/PlaybackCoordinator.kt` : autorisations et cycle de lecture ; `LivePlaybackState.kt` : état pur de navigation. `live/epg/` : guide borné.
- Conserver `PlayerController` comme propriétaire du lecteur ; ni les panneaux Compose ni le guide ne créent de lecteur.
- Ajouter des contrats dédiés `GroupsClient` et `PlaybackClient` à côté de `SyncClient`, pour ne pas gonfler tous les faux clients B1. `ProfilePrefs` gagne seulement `groupCopies: Boolean = true` (JSON `group_copies`).
- Le moteur automatique v1 utilise exclusivement les noms : les identifiants EPG ne sont pas publiés dans la copie B1. Ainsi téléphone et télé calculent le même résultat sans exposer de données supplémentaires.
- Délais choisis pour le constructeur : guide après 300 ms de sélection stable, cache LRU de 100 sources, fraîcheur maximale 5 min et expiration plus tôt à la fin du programme actuel ; 2 requêtes simultanées ; 8 entrées au plus par guide court.
- Présence : contrôle toutes les 2 s, battement toutes les 10 s, présence périmée après 45 s ; attente de prise de main 30 s. Ces délais n'autorisent jamais une deuxième lecture automatiquement.

## Tâche 1 — Moteur de regroupement déterministe

**Fichiers :** créer `fixtures/live-groups-v1.json`, `apps/shared/src/commonMain/kotlin/home/iptv/shared/groups/LiveGroups.kt`, `web/config/src/org/groups.ts`, `apps/shared/src/jvmTest/kotlin/home/iptv/shared/groups/LiveGroupsTest.kt`, `web/config/test/org-groups.test.ts`.

**Interfaces :** dans les deux langages, `GroupSource(id, categoryId, name, position)` ; `ManualGroup(id, title, sourceIds, blockedAutoKeys)` ; `GroupSnapshot(rev, rulesVersion, groups)` ; `LiveGroup(id, title, sources)` ; Kotlin `fun autoKey(name: String): String?` et `fun buildGroups(sources: List<GroupSource>, snapshot: GroupSnapshot): List<LiveGroup>` ; TypeScript mêmes noms en camelCase, tableaux en paramètres. Identifiants automatiques `auto:v1:<clé normalisée>`, isolés `source:<id>`, manuels `manual:<uuid>`.

- [x] **1. Tests rouges :** chaque runner lit exactement la fixture racine (Kotlin via chemin résolu depuis `apps`, pas une copie). Vecteur : `RDS HD` et `RDS FHD` de catégories différentes donnent un groupe ; `RDS 2 HD` reste distinct ; `RDS +1`, `RDS Québec`, `RDS France`, `RDS EN` restent distincts. Entrée vide/ambiguë reste isolée. Résultat indépendant de l'ordre d'arrivée, ordre des sources stable `(position, id)`.
- [x] **2. Vérifier l'échec :** `npm test -w web/config -- test/org-groups.test.ts` ; depuis `apps`, `./gradlew --max-workers=2 :shared:jvmTest --tests '*LiveGroupsTest'`. Échec attendu sur moteur absent ou assertions des règles.
- [x] **3. Implémenter** `autoKey` et `buildGroups` : normalisation latine identique à `searchNorm`, retrait uniquement des suffixes techniques terminaux HD/FHD/UHD/4K/SD et séparateurs connus ; conserver chiffres distinctifs et autres mots. Utiliser des maps O(n). Groupes manuels prioritaires ; tout `blockedAutoKey` empêche de regrouper automatiquement les sources restantes de cette famille. Une exclusion individuelle est un groupe manuel singleton.
- [x] **4. Ajouter et passer** les cas séparation persistante, nouvelle source laissée seule après correction, rétablissement automatique, groupe manuel intercatégories, 100 000 sources/2 000 catégories. Compter les opérations du moteur pour exclure les comparaisons par paires, sans seuil de temps CI fragile. Les deux runners doivent produire tous les mêmes résultats.
- [x] **5. Relecture puis commit :** `feat(shared): regrouper prudemment les copies du direct` ; documents d'avancement et push de branche.

## Tâche 2 — Corrections et réglages persistants, protégés et paginés

**Fichiers :** créer migration `20261114000009_groupes_direct.sql`, `backend/test/groupes-direct.test.ts` ; modifier `backend/README.md`.

**Interfaces SQL :** `live_group_revision(household_id,rev)` ; `live_group_override(household_id,id,title,blocked_auto_keys text[])` ; `live_group_source(household_id,source_id,group_id)` avec unicité `(household_id,source_id)` et FK composée vers le groupe ; `profiles.group_copies boolean not null default true`.

RPC `live_group_page(h uuid, after_id uuid, lim int default 500) returns jsonb` → `{rev,rules_version:1,groups,next_id}` ; `live_group_edit(h uuid, expected_rev bigint, replacement jsonb) returns jsonb` → nouvelle révision ; `replacement={remove_ids:[uuid],groups:[{id,title,source_ids,blocked_auto_keys}]}` ; `live_catalog_page(h uuid, after_position int, after_id text, lim int default 500) returns jsonb` → `{catalog_rev,channels,next_cursor}` avec noms, catégories, positions, aucune URL ; `set_group_copies(p uuid, enabled boolean) returns void`. Ajouter un compteur `live_catalog_revision` par foyer, incrémenté par les transactions de publication qui changent la copie. Maximum page 1 000 ; refuser révision incohérente entre pages et recommencer le snapshot. Après trois tentatives pendant une publication en cours, conserver le dernier cache complet et proposer Réessayer, sans boucle infinie.

- [x] **1. Tests rouges :** `conflit_revision_ne_perd_aucune_correction` : deux éditions avec `expected_rev=0`, première réussit, deuxième renvoie `conflit-revision`, état égal à la première ; `source_unique_dans_un_foyer` : fusion atomique, aucune duplication ; tests membre lecture/propriétaire écriture/étranger et anon refusés ; profil d'autre foyer refusé.
- [x] **2. Exécuter** `npm test -w backend -- test/groupes-direct.test.ts` sur PostgreSQL 16 local. Attendre FAIL sur RPC/tables manquantes, pas sur initdb manquant.
- [x] **3. Implémenter** tables RLS et RPC security definer, `search_path` borné et droits exécutables explicites. Verrouiller le foyer pour éditer ; révision incrémentée dans la même transaction ; vérifier les sources connues dans la copie publiée. Autoriser de conserver une ancienne référence disparue, mais pas d'en inventer une nouvelle. `blocked_auto_keys` garde la famille gelée tant qu'une correction la référence. Une restauration retire ces références avec confirmation si d'autres corrections sont touchées.
- [x] **4. Passer** tests de pagination au-delà de 1 000 groupes et chaînes, révision modifiée entre pages, coupure réseau sans remplacer le cache complet par une moitié, groupe vide interdit, conservation des sources disparues, absence de modification des favoris et réglage indépendant par profil. Étendre `profile_prefs` avec `group_copies` sans changer les clés B1 ; incrémenter `prefs_revision` après édition/réglage pour réveiller les télés en ~5 s.
- [x] **5. Relecture puis commit :** `feat(backend): enregistrer les corrections de groupes du foyer` ; contrat backend et documents mis à jour, push.

## Tâche 3 — Corrections sur iPhone et démonstration

**Fichiers :** créer `web/config/src/org/groups-api.ts`, `groups-view.ts`, `web/config/test/org-groups-api.test.ts`, `org-groups-view.test.ts` ; modifier `web/config/src/org/view.ts`, `demo.ts`, `web/config/src/ui.css`, `web/config/package.json` seulement si un outil DOM est réellement nécessaire (avec lockfile).

**Interfaces :** `loadGroupSnapshot(db: Db,h: string): Promise<GroupSnapshot>` ; `loadGroupSources(db: Db,h: string): Promise<GroupSource[]>` ; `saveGroups(db,h,expectedRev,replacement): Promise<number>` ; `mountGroups(ctx: OrgCtx): Promise<void>` ; `setGroupCopies(db,p,enabled): Promise<void>`.

- [x] **1. Tests rouges :** `editer_puis_recharger_conserve_le_groupe` ; `separer_garde_une_exclusion` ; `annuler_necrit_rien` ; `conflit_affiche_recharger_sans_ecraser` ; `echec_reseau_ne_marque_pas_enregistre` ; texte de nom contenant `<img onerror=...>` affiché comme texte, jamais exécuté.
- [x] **2. Exécuter** `npm test -w web/config -- test/org-groups-api.test.ts test/org-groups-view.test.ts` — FAIL attendu sur nouvelles fonctions.
- [x] **3. Implémenter** Copies avec recherche locale indexée sur la copie paginée, rendu fenêtré, nom original/catégorie/état, sélection multi-source, aperçu avant Enregistrer, séparer/réunir/restaurer. Groupe corrigé = UUID stable ; gel des familles automatiques affectées. Indiquer copie ancienne/incomplète, ne pas enregistrer sur un snapshot incomplet. Réglage par profil dans Organiser ; corrections présentées comme communes au foyer. Utiliser `textContent` ou échappement existant pour tous les noms.
- [x] **4. Vérifier** tests + `npm run build -w web/config` ; le faux `demoDb` simule toutes les nouvelles RPC, conflits et erreurs. Parcours navigateur en `?demo`, format iPhone : rechercher, réunir deux catégories, séparer, recharger, restaurer ; ancien onglet Favoris toujours fonctionnel, aucun appel au vrai Supabase. Liste de 100 000 chaînes fenêtrée.
- [x] **5. Relecture puis commit :** `feat(web): corriger les copies des chaines depuis le telephone` ; documents et push.

## Tâche 4 — Groupes sur la télé sans perdre l'organisation B1

**Fichiers :** créer `apps/shared/src/commonMain/kotlin/home/iptv/shared/groups/GroupsClient.kt`, `apps/tv/src/main/kotlin/home/iptv/tv/live/GroupsWatcher.kt` et leurs tests JVM ; modifier `SyncModels.kt`, `Catalog.sq`, `CatalogRepository.kt`, `LocalStore.kt`, `PrefsWatcher.kt`, `LiveSessions.kt`, `Sections.kt`, `LiveBrowseViewModel.kt`, `LiveBrowseScreen.kt` et tests correspondants.

**Interfaces :** `GroupsClient.snapshot(householdId: String): GroupSnapshot` suspend ; `CatalogRepository.allGroupSources(): List<GroupSource>` indexable à travers le cache ; `LocalStore.groupSnapshot(h: String): GroupSnapshot?`, `saveGroupSnapshot(h,snapshot)`, `lastGoodSource(profileId,groupId): String?`, `saveLastGoodSource(profileId,groupId,sourceId)` ; `GroupRow(group: LiveGroup, anchorSourceId: String)` ; `buildGroupRows(sectionSources,visibleSources,snapshot,enabled): List<GroupRow>` ; `GroupsWatcher.snapshot: StateFlow<GroupSnapshot?>`.

- [x] **1. Tests rouges :** pour favoris `[sourceMasquee]` et autre copie visible, résultat `[]` ; pour favoris `[b,a]` du même groupe et `[c]` autre groupe, lignes ancrées `[b,c]` ; groupe en catégorie A propose source visible de B ; source masquée de B exclue ; `groupCopies=false` rend toutes les entrées séparées.
- [x] **2. Exécuter** depuis `apps` `./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest` — FAIL attendu sur assertions B2.
- [x] **3. Implémenter** récupération paginée avec révision cohérente et cache atomique. Lire d'abord le snapshot local, relire après `prefs_revision` modifiée ; conserver l'ancien en cas d'erreur. Construire l'index global en IO après changement de catalogue ou snapshot, jamais à chaque focus. Filtrer les sources avant de construire les lignes ; conserver positions B1 et identifiants concrets. JSON ancien sans `group_copies` → true. Mettre à jour les faux `LocalStore`.
- [x] **4. Vérifier** tests de rafraîchissement <5 s en temps virtuel, profil changé pendant réponse, source disparue, mémoire libérée au changement de foyer, cache hors ligne, nouvelle catégorie masquée jamais téléchargée ; commande précédente PASS et `:tv:assembleDebug` PASS. La source mémorisée n'est lue que si toujours visible.
- [x] **5. Relecture puis commit :** `feat(tv): afficher les groupes sans modifier les favoris existants` ; documents et push.

## Tâche 5 — Réservation B2 avec arrêt confirmé

**Fichiers :** créer migration `20261114000010_lecture_confirmee.sql`, `backend/test/lecture-confirmee.test.ts` ; modifier `backend/README.md` ; créer `web/config/src/org/playback-admin.ts` et `web/config/test/playback-admin.test.ts`, brancher depuis `view.ts` et `demo.ts`.

**Choix de protocole :** table dédiée `playback_slot` (une ligne persistante par foyer) avec `generation uuid`, `state` (`empty/reserved/playing/stopping`), utilisateur/appareil/profil/source/label, battement. Table `playback_request` avec UUID, génération de place ciblée, demandeur, expiration 30 s, état `waiting/ready/cancelled`. Ne pas réutiliser le nettoyage automatique de `active_streams` v1 : une expiration réseau ne prouve pas un arrêt.

**RPC, tous authentifiés et limités au foyer :**
- `playback_claim(h uuid,p uuid,device_key text,device_name text,source_id text,label text) returns jsonb` → `{status:ok,generation}` ou `{status:occupied,occupant,stale}` ou `{status:setup_required}`.
- `playback_control(h uuid,generation uuid,device_key text,beat boolean default false) returns jsonb` → `{status:keep|stop,request_id?}`. Sans droit d'accès : refus, que le client traite comme arrêt.
- `playback_request_stop(h uuid,generation uuid,p uuid,device_key text,device_name text,source_id text,label text) returns uuid` ; `playback_poll_request(request_id uuid,device_key text) returns jsonb` ; `playback_cancel_request(request_id uuid,device_key text) returns void`.
- `playback_stopped(h uuid,generation uuid,device_key text,request_id uuid default null) returns void` : appel uniquement APRÈS arrêt/libération physique du lecteur ; seule l'installation titulaire peut accuser son propre arrêt. Autoriser cet accusé restreint même après révocation, sans autre droit de lecture/écriture.
- `playback_promote(request_id uuid,device_key text) returns jsonb` : après accusé seulement ; contrôler cible, demandeur, expiration et génération.
- `playback_started(h uuid,generation uuid,device_key text) returns void` : installation titulaire seulement, transition reserved → playing après première image. `playback_update(h uuid,generation uuid,device_key text,source_id text,label text) returns void` actualise la chaîne de la même installation sans créer une nouvelle place.
- `playback_enable(h uuid) returns void` : propriétaire seulement, après confirmation toutes installations mises à jour et lectures anciennes arrêtées.
- `playback_confirm_physical_stop(request_id uuid) returns void` : propriétaire seulement depuis iPhone, après vérification physique explicite. Rejeter génération changée et demande expirée.
- `playback_admin_state(h uuid) returns jsonb` : propriétaire seulement, état de mise en service et demandes de récupération sans clé d'installation.

Précisions de contrat décidées pendant la construction : une prise de main transporte la lecture souhaitée pour que la promotion sache quoi autoriser ; chaque opération de titulaire/demandeur vérifie aussi l'installation, car deux appareils peuvent partager auth.uid. Conserver seulement l'empreinte de la clé aléatoire dans les tables (SHA-256 natif PostgreSQL), jamais la clé lisible dans les réponses ou événements. Ces précisions n'ajoutent pas de parcours utilisateur. Le contrat final README devra rester la référence des clients 6/8.

- [ ] **1. Tests rouges :** deux claims concurrents → une seule autorisation ; takeover sans accusé → aucune autorisation nouvelle même après 45 s ; arrêt confirmé puis promote → nouvel appareil autorisé ; accusé ancien après nouvelle génération → sans effet ; propriétaire différent → refus. Claim/update avec profil hors foyer, source inconnue ou masquée globalement/pour ce profil → refus ; revalider les droits au promote, pas seulement à la demande initiale.
- [ ] **2. Exécuter** `npm test -w backend -- test/lecture-confirmee.test.ts` — FAIL attendu sur protocole absent, PostgreSQL prêt.
- [ ] **3. Implémenter** transitions atomiques sous verrou du foyer, aucun secret ni URL, état périmé conservé jusqu'à preuve ou confirmation physique. Pendant une demande ready valide, réserver la place au demandeur jusqu'à promotion ou annulation/expiration : un troisième claim ne doit pas lui passer devant. Une réservation expirée sans preuve d'arrêt reste bloquante ; une demande expirée ne détruit pas la lecture titulaire. Après activation v2, les appels legacy `claim_stream` de ce foyer doivent refuser les nouvelles lectures v1 plutôt que contourner le protocole. Garder les tests v1 pour les foyers non activés. Révocation membre/suppression profil : garder un marqueur de place bloquante et déclencher stop, jamais vider sur simple suppression en cascade. Ne pas publier `playback_slot` et ses battements en Realtime ; publier les demandes et une table `playback_signal(household_id,generation,kind)` limitée aux transitions stop/revoked, RLS membre en lecture et aucune écriture directe. Le contrôle toutes les 2 s reste le secours si Realtime est absent.
- [ ] **4. Vérifier** crash, réseau coupé, demande annulée/expirée, arrêt volontaire, révocation hors ligne, profil supprimé, même compte sur deux `device_key`, ancien accusé, ancienne récupération physique et paramètres invalides (device_key 1–64). Tester `playback_started` d'une ancienne génération refusé, `playback_update` par un autre titulaire refusé et troisième claim entre accusé/promotion bloqué. Test de heartbeat périmé sans libération. Sur iPhone : mise en service et récupération uniquement via bouton explicite + confirmation, jamais au chargement ; fake demo et droits owner testés. `npm test -w backend` et tests/build web PASS.
- [ ] **5. Relecture puis commit :** `feat(backend): reserver la lecture apres arret confirme` ; contrat, documents et push.

## Tâche 6 — Coordinateur unique de lecture et prise de main

**Fichiers :** créer `apps/shared/src/commonMain/kotlin/home/iptv/shared/sync/PlaybackClient.kt`, test JVM `sync/PlaybackClientTest.kt` ; créer `apps/tv/src/main/kotlin/home/iptv/tv/live/PlaybackCoordinator.kt`, `LivePlaybackState.kt` et tests dans `apps/tv/src/test/kotlin/home/iptv/tv/live/` ; modifier `PlayerController.kt`, `ExoPlayerPlayer.kt`, `LocalStore.kt`, `MainActivity.kt`, `LiveSessions.kt` ; ajouter le module Realtime de la version supabase existante dans le catalogue Gradle si nécessaire.

**Interfaces :** `PlaybackClient` expose des fonctions suspend correspondant exactement aux RPC de tâche 5 et `stopEvents(h): Flow<Unit>` via Realtime, plus modèles typés. `LocalStore.deviceKey(): String` crée une UUID une seule fois, distincte par installation. `Player` ajoute `events: Flow<PlayerEvent>` avec `FirstFrame`, `Failed`, `Stopped`, sans URL. `PlaybackCoordinator.start(source: LiveStream,groupId: String)`, `selectSource(source)`, `requestTakeover()`, `cancelTakeover()`, `stop()` ; `state: StateFlow<PlaybackState>`, états `Idle/Claiming/Occupied/AwaitingStop/Playing/Failed`. Le coordinateur reçoit profil/foyer, clients, lecteur, cache, horloge et portée de session ; il ne les récupère pas dans Compose.

- [ ] **1. Tests rouges :** `claim_refuse_nouvre_pas_le_lecteur` ; `prise_de_main_attend_arret` ; `arret_precede_ouverture_source_suivante` ; `premiere_image_ancienne_ne_memorise_pas` ; `annulation_profil_invalide_claim_tardif`. Assert ordre exact des appels faux client/faux lecteur.
- [ ] **2. Exécuter** les tests JVM shared/tv — FAIL attendu sur états absents.
- [ ] **3. Implémenter** traitement sérialisé et génération locale : toute réponse tardive d'une ancienne opération libère sa réservation si nécessaire sans ouvrir de lecteur. Pour changer de source, conserver la place, fermer le lecteur, actualiser source/label via `playback_update` sous la même génération, puis ouvrir. Si cette mise à jour échoue, rester arrêté et accuser l'arrêt dès que possible ; ne pas ouvrir sans autorisation vérifiée. Première image de la bonne génération mémorise la source locale et appelle `playback_started` (idempotent pour une place déjà playing). Contrôle toutes les 2 s + signal Realtime ; battement toutes les 10 s. Échec réseau après autorisation conserve la lecture ; refus explicite arrête immédiatement. Nouveau claim réseau impossible = message, aucune vidéo.
- [ ] **4. Vérifier** panne réseau puis réservation perdue au retour ; révocation ; onStop durant claim/lecture/prise de main ; événement Realtime manqué rattrapé en 2 s ; reprise app sans lancement automatique ; nouvelle génération concurrente ; aucune URL dans erreur ni événement. Chaque arrêt libère d'abord le lecteur puis accuse l'arrêt côté serveur. Jouer les scénarios à deux faux appareils avec faux client partagé.
- [ ] **5. Relecture puis commit :** `feat(tv): coordonner une seule lecture entre les appareils` ; documents et push.

## Tâche 7 — Guide court borné et indépendant du lecteur

**Fichiers :** créer `apps/tv/src/main/kotlin/home/iptv/tv/live/epg/EpgRepository.kt`, `EpgModel.kt` et tests homonymes ; modifier `apps/shared/src/commonMain/kotlin/home/iptv/shared/xtream/XtreamApi.kt` et `XtreamApiTest.kt` si parsing/horaires invalides nécessitent correction.

**Interfaces :** `NowNext(current: EpgEntry?,next: EpgEntry?,progress: Float?)` ; `fun nowNext(entries: List<EpgEntry>,now: Instant): NowNext` ; `EpgRepository(api,scope,clock)` avec `observe(sourceId: String?): StateFlow<NowNext>` et `close()`. Implémentation bornée : 300 ms avant sélection sans lecture, LRU 100, fraîcheur <=5 min et fin d'émission, 2 requêtes simultanées, `shortEpg(streamId,limit=8)`.

- [ ] **1. Tests rouges :** `maintenant_a_borne_fin_exclue` ; `episodes_perimes_ignores` ; `intervalle_negatif_ignore` ; `reponse_ancienne_n_ecrase_pas_la_source_courante` ; `horaires_changement_heure` avec Instant fixé et zone America/Toronto. Pas d'hypothèse sur le fuseau de la machine CI.
- [ ] **2. Exécuter** shared/tv JVM tests ciblés EPG — FAIL attendu sur projection/cache absents.
- [ ] **3. Implémenter** tri/filtrage des intervalles `start <= now < end`, programme suivant après le courant ou après maintenant ; progression bornée 0..1 ; données invalides écartées. Un guide ne migre pas entre copies. Séparer horloge réelle et temps monotone pour cache/délais. Réseau/format absent : résultat partiel ou indisponible sans bloquer lecture.
- [ ] **4. Vérifier** cache à la fin du programme, changements rapides 100 sources, jamais plus de 2 appels simultanés, LRU <=100, stop/profile annule collecteurs, huit entrées dont anciennes, dates invalides et erreur de décodage. Commandes JVM PASS.
- [ ] **5. Relecture puis commit :** `feat(tv): afficher le guide maintenant et ensuite` ; documents et push.

## Tâche 8 — Aperçu sur OK, sources, plein écran et télécommande

**Fichiers :** créer `apps/tv/src/main/kotlin/home/iptv/tv/live/LivePreview.kt`, `SourcesPanel.kt`, `TakeoverDialog.kt`, tests `LivePlaybackStateTest.kt`, `LiveNavigationTest.kt` ; modifier `LiveRoute.kt`, `LiveBrowseScreen.kt`, `LiveFocus.kt`, `LiveDims.kt`, `PlayerScreen.kt`, `ExoPlayerPlayer.kt`, `App.kt`, thème `IptvTheme.kt` si jetons nouveaux.

**Interfaces :** `LivePlaybackState` tâche 6 complété avec mode `Browse/Fullscreen`, `selectedRowId`, `playingGroupId`, `originSectionKey`, `panel` (`None/Sources/Takeover`) ; reducer `reduce(state,event): LivePlaybackState` pur et effets adressés au coordinateur. `LivePreview` et `PlayerScreen` attachent la même instance Media3 à une seule `PlayerView` active à la fois ; le détachement ne stoppe pas le lecteur.

- [ ] **1. Tests rouges :** focus seul → zéro appel play ; premier OK → aperçu ; deuxième OK même groupe → plein écran sans nouveau play ; Retour → aperçu sans nouveau play ; OK autre groupe → remplacement ; Retour panneau ferme panneau avant de quitter ; changer catégorie conserve lecture. Haut/bas suit les groupes de la section d'origine, sans boucler, et reste en plein écran.
- [ ] **2. Exécuter** `./gradlew --max-workers=2 :tv:testDebugUnitTest` depuis `apps` — FAIL attendu sur transitions B2.
- [ ] **3. Implémenter** trois zones (sections 24 %, chaînes 38 %, aperçu 38 %, adapter aux marges/jetons) : lecture marquée distinctement du focus, Sources avec noms originaux/catégories et source active, Arrêter accessible, guide de la lecture ou de la sélection avant lecture. Dialogues occupé avec Annuler par défaut, attente, échec et explication récupération via iPhone. Si un groupe change pendant lecture, retrouver la source concrète plutôt que redémarrer. Masquage/retrait de droit arrête sans changer de source. Section disparue : favoris sinon première catégorie.
- [ ] **4. Vérifier** reducer et coordinateur ensemble ; `:tv:assembleDebug` PASS. Vérifier sur émulateur disponible ou TCL ensuite : une seule surface vidéo, pas d'écran noir durable au déplacement, aucun reconnect réseau, `keepScreenOn`, focus après panneaux, erreurs de source et arrêt, textes à 3 m. Une compilation seule ne valide pas ces comportements visuels ; consigner ceux non exécutés.
- [ ] **5. Relecture puis commit :** `feat(tv): parcourir le direct avec apercu et choix des sources` ; documents et push.

## Tâche 9 — Validation complète, version et livraison sous accords

**Fichiers :** créer `docs/rapports/tele-b2-direct.md` ; modifier `docs/installer-sur-la-tele.md`, `apps/tv/build.gradle.kts`, `web/config/public/version.json` lors de préparation de livraison ; passation, feuille de route et journal.

- [ ] **1. Vérifier les prérequis sans changer le code pour les contourner :** Node compatible Vite (22.12+ ou 24), npm ci, JDK 21, wrapper Gradle 8.14.3, SDK 36/build-tools 36.0.0, PostgreSQL 16. Session de préparation : dépendances npm installées, 149 tests outils/web passaient ; PostgreSQL natif absent et installation Android non terminée. Ces observations ne valent pas validation B2. Résoudre l'environnement avant tests concernés, sans désactiver TLS/signatures ni modifier les checksums. Variante PostgreSQL conteneur : utilisateur déclaré, checkout et binaire Node montés en lecture/écriture appropriée, binaires à `/usr/lib/postgresql/16/bin` ; ne pas réécrire le chemin codé dans les tests pour masquer un défaut de setup.
- [ ] **2. Exécuter les suites :** racine `npm ci`, `npm test`, `npm run build -w web/config`, `npm run build -w tools/test-lecture` ; depuis `apps`, `./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest :tv:assembleDebug`. Attendre toutes les suites réelles, sans tests ignorés par défaut d'environnement. Relever les nombres de tests et sorties ; corriger les échecs de B2, distinguer un défaut préexistant.
- [ ] **3. Vérifier l'intégration locale :** Postgres jetable + jeux fictifs ; cycles iPhone/télé, concurrent updates, takeover à deux clients, récupération physique simulée, snapshot paginé >1 000 ; navigateur iPhone démo et 100 000 chaînes. Captures et journaux sans données réelles. Vérifier que tous les nouveaux RPC JSON/paramètres correspondent aux clients ; test MockEngine sur tailles/curseurs/revisions. Relecture indépendante de toute la branche et correction de ses conclusions.
- [ ] **4. Préparer la version candidate** 0.3.0, versionCode 3 (si aucun incrément intervenu entre-temps, sinon prochain entier), documenter mise à jour de toutes installations et confirmation propriétaire. Construire `:tv:assembleRelease`, vérifier certificat avec `apksigner verify --print-certs` : SHA-256 `5d8877ce9c75eebe8d4a0cfb20427cf13dbf443402fc5c055608358d76e615d4`. Ne jamais lire/afficher les mots de passe de signature. Préparer ordre migration 0009 puis 0010, fichiers web/APK et procédure de restauration de l'application ; ne pas proposer de supprimer les nouvelles données pour revenir en arrière.
- [ ] **5. Demander les accords sur les résultats concrets :** migrations de production, publication web/APK, puis essai TCL. Arrêter avant chaque action non autorisée ; aucun accord du présent plan ne remplace ces autorisations. Après accord migration, contrôler RLS/advisors ; après accord publication, suivre la procédure existante et supprimer toute règle d'écriture temporaire, vérifier octets/certificat. Ne pas créer de service nouveau.
- [ ] **6. Faire essayer au propriétaire** ouverture/rapidité, copies intercatégories, correction sur iPhone suivie en ~5 s, masquage gagnant, source défaillante, OK/plein écran/Retour, guide, changement profil, mise en veille. Prise de main réelle seulement avec deux installations B2 ; sinon marquer essai réel non exécuté et conserver simulation identifiée. Vérifier l'appairage conservé après mise à jour et noter les limites du vrai fournisseur sans identifiants.
- [ ] **7. Mettre les documents à jour, committer et pousser** `chore(tv): preparer puis valider la version 0.3.0 du direct` ; ouvrir une PR de revue vers main si utile. Demander l'accord de fusion quand les preuves et limites sont présentées, puis fusionner seulement après cet accord. Ne marquer B2 terminé qu'après essai requis et état explicite des éventuelles limites.

## Autorelecture du plan

Couverture : regroupement et variantes §4 → tâches 1/4 ; corrections §5 → 2/3 ; aperçu/navigation §6 → 6/8 ; guide §7 → 7/8 ; prise de main §8 → 5/6 ; sécurité/compatibilité §9 → 2/5/9 ; validations §11 → tests dans chaque tâche et 9. Aucun code prévu pour les éléments hors périmètre §10. Les modèles `GroupSnapshot`, `GroupSource`, `LiveGroup`, la révision et les clés sont communs aux tâches 1–4 ; la génération de place et les RPC sont communs aux tâches 5–8.

Méthode d'exécution déjà fournie dans PASSATION.md : un sous-agent constructeur par tâche, une relecture indépendante par tâche, puis relecture finale de la branche. Plan validé et construction autorisée par le propriétaire dans la conversation ; tâches 1–4 terminées et relues, tâche 5 en cours. Reprise autorisée jusqu’à la tâche 6, puis 7 uniquement si quota disponible ; 8–9 hors de cette reprise.
