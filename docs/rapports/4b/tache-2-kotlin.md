# Tâche 2 Kotlin — reprise du 16 novembre 2026

Base effective : `db55352` sur `plan-4-films`. PASSATION, bloc « ÉTAT EXACT AU 16 NOVEMBRE », et `docs/rapports/4b/prompt-reprise.md` relus. Ils remplacent la première tentative locale (base ea15551), archivée par le contrôleur dans stash@{0} et `/workspace/iptv-work/recovery-4b/untracked-before-sync.tar.gz`. Aucun stash réappliqué. Seuls deux fichiers de tests manquants ont été extraits sélectivement : MovieClientTest et SeriesTypedQueueTest.

## État de départ vérifié

- Catalogue Xtream `0d57f54` et cache SQLDelight `efaec8f` sont déjà distants ; aucun de leurs fichiers n'est modifié par cette reprise.
- `edad69f` contient ProgressEntry/ProgressRow enrichis et tests rouges ; la file et le routage RPC restent spécialisés épisodes.
- Organisation iPhone terminée dans `2a24547` ; hors périmètre de cette tâche.
- Le contrôleur lance d'abord `:shared:compileTestKotlinJvm` (journal `recovery-4b/remote-compile.log`). Pas de Gradle concurrent.

## Travail restant pris en charge

1. Observer RED tests progression existants ; ajouter tests MovieClient, préférences et suppression typée séries.
2. Identité file profil/type/id, métadonnées propres, routage movie dédié conservant exactement les 12 clés séries.
3. MovieClient/SupabaseMovieClient pour publication catégories, continuation, fiche progression (vus compris), Ma liste et marquage.
4. ProfilePrefs.movieOrder/movieList avec anciens JSON compatibles.
5. Filtres épisodes de MainActivity, queuedAsRows et regroupements séries.
6. Suites partagées/TV, compilation APK debug, auto-revue et commit ciblé. Le contrôleur gère revue indépendante, plan, passation, pushes.

## Décisions

- Checkout isolé existant conservé, sans worktree ni changement de branche, conformément au brief.
- Contrat backend/README.md + migration 0012 autoritaires pour les RPC. Aucune production ni publication.
- Le masquage tardif et le cycle de lecteur Films relèvent de la tâche 3 ; ne pas réimplémenter le catalogue déjà livré.

## Résultats et preuves

À compléter avec commandes exactes, sorties et compteurs réellement observés.

### Checkpoint partagé vert (16 novembre)

- RED distant observé par le contrôleur : `source /workspace/iptv-env/activate.sh; cd apps; ./gradlew --max-workers=2 :shared:jvmTest` → `256 tests completed, 8 failed`, BUILD FAILED. Journal `/workspace/iptv-work/recovery-4b/remote-compile.log`. Échecs attendus : recordRoutesMovieToRecordMovieProgressWithAllEightKeys, unknownItemTypeIsRefusedWithoutAnyCall, recordSendsCleanedMovieMetadataAndNeverSeriesFields, replacementNeverMixesMovieAndEpisodeMetadata, replacementTimestampAdvancesPerTypeOnly, movieCoverPersistsOnlySchemeHostAndPath, movieAndEpisodeWithSameIdStayIndependent, sendingOrRefusingOneTypeLeavesTheOtherQueued.
- RED nouveaux contrats : même commande → `:shared:compileTestKotlinJvm FAILED`, références MovieClient/movieOrder/movieList absentes. Journal `task-2-resume-red.log`. Ce RED de compilation correspond aux API neuves manquantes, distinct du RED comportemental des huit régressions existantes.
- Implémentation : identité profil/type/id appliquée à la recherche/remplacement ; horodatage et métadonnées hérités seulement du même type ; affiches films nettoyées. Flush et suppression restent fondés sur l'entrée complète et n'effacent pas une autre position en vol. Type RPC inconnu refusé avant envoi ; épisode conserve exactement son ancien corps de 12 clés ; film utilise record_movie_progress et ses 8 clés explicites.
- `MovieClient` expose publication catégories, continuation dédiée, état fiche (`ProgressRow?`), liste (`List<MovieListItem>`), ajout/retrait, vu/non vu. `MovieListItem` correspond aux quatre colonnes JSON du backend ; les mutations prennent `MovieSummary`, métadonnées nettoyées et catégorie vide envoyée comme null. Les six opérations exigent une session authentifiée.
- `ProfilePrefs` conserve les clés anciennes et ajoute movieOrder null/movieList vide par défaut ; tests sur ancien JSON et préférences mixtes films/séries.
- GREEN : `source /workspace/iptv-env/activate.sh; cd apps; ./gradlew --max-workers=2 :shared:jvmTest` → `BUILD SUCCESSFUL in 24s`, **260 tests, 0 échec, 21 classes** (compteurs XML). Journal `task-2-resume-green.log`. Seul avertissement : dépréciation préexistante RefreshFailureCause dans SyncClient.kt.
- RED TV : `./gradlew --max-workers=2 :tv:compileDebugUnitTestKotlin` → `:tv:compileDebugUnitTestKotlin FAILED`, dropQueuedEpisodes absent. Journal `task-2-tv-red.log`. La correction TV est en cours dans des fichiers exclus du checkpoint partagé.
