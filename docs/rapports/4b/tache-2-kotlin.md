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

### Checkpoint télé : identité épisode conservée

- `queuedAsRows` exclut explicitement les films même si une ancienne métadonnée seriesId traîne. `dropQueuedEpisodes(queue,profileId,ids)` retire seulement les épisodes du profil demandé ; MainActivity utilise ce même helper dans la transaction du stockage.
- Les regroupements home/detail filtrent le type AVANT de grouper : une ligne film plus récente de même id/seriesId ne peut remplacer un épisode, ni le marquer vu.
- RED comportemental des deux nouveaux tests : `./gradlew --max-workers=2 -I /workspace/iptv-work/4b/task-2-plain-tests.gradle :tv:task2PlainTests` → `Tests run: 52, Failures: 3`. newerMovieCannotDisplaceEpisodeInSeriesMerge attendait `a`, obtenait `null` ; movieWithSameIdCannotMarkEpisodeWatched attendait `Upcoming`, obtenait `Watched`. Troisième échec du harnais seul : fiche_en_cache_perime_servie_quand_le_fournisseur_est_coupe ne trouvait pas le pilote JDBC SQLite dans le classpath de compilation. Ajout du JAR SQLite déjà présent dans le cache au harnais externe, sans modifier le produit. Journal `task-2-plain-red.log`.
- GREEN : `./gradlew --max-workers=2 -I /workspace/iptv-work/4b/task-2-plain-tests.gradle :tv:task2PlainTests :tv:assembleDebug` → **OK (52 tests)** et **BUILD SUCCESSFUL in 32s**. Trois classes : SeriesTypedQueueTest, SeriesHomeViewModelTest, SeriesDetailViewModelTest. APK debug construit ; aucune publication. Journal `task-2-plain-green.log`.
- La commande officielle ciblée `:tv:testDebugUnitTest --tests '*SeriesTypedQueueTest' --tests '*SeriesHomeViewModelTest.newerMovieCannotDisplaceEpisodeInSeriesMerge' --tests '*SeriesDetailViewModelTest.movieWithSameIdCannotMarkEpisodeWatched'` compile les tests puis échoue AVANT exécution : le JAR Robolectric instrumenté est téléchargé depuis repo1.maven.org, redirigé vers gcp.mirrors.maven.org, refusé HTTP403. Journal `task-2-tv-merge-red.log`. Ce n'est pas un résultat rouge du produit ni une suite TV complète validée.

### Harnais externe et limites exactes

Le harnais hors dépôt enregistre `:tv:task2PlainTests` comme `JavaExec`, main `org.junit.runner.JUnitCore`, dépendance `compileDebugUnitTestKotlin`, classpath `compileDebugUnitTestKotlin.libraries + testDebugUnitTest.testClassesDirs + JAR sqlite-jdbc existant`. Cela évite la configuration `robolectricSdk` du task Test officiel et ne forge aucun SDK. Un premier essai utilisant le classpath runtime officiel a aussi rencontré HTTP403 sur nativeruntime-dist-compat ; le classpath de compilation suffit aux tests sans Android runtime.

Élargissement aux 23 classes sans annotation Robolectric : **274 tests, 6 échecs de harnais** dans ProgressPersistenceTest uniquement (`android.content.Context` du SDK de compilation lève `RuntimeException: Stub!`) : queueRoundTripsThroughPreferencesAcrossStoreInstances, routineQueueWritesUseApply, unsupportedSavedTabIsIgnored, unreadableJsonIsPreservedBeforeQueueReplacement, explicitPersistenceIsExposedForOnStop, tabConstantsRoundTripPerProfile. Journal `task-2-plain-all.log`. Cette classe nécessite le mock Android du task officiel ; aucune correction produit hors périmètre. Elle est retirée du dernier passage externe, comme les 5 classes Robolectric (SeriesDpadTest, SectionBarDpadTest, LiveDpadTest, HarnessTest, PlayerViewTest).

Auto-revue : contrats et clés RPC comparés au backend/README.md ; signatures épisodes et anciens JSON conservés ; file profil/type/id vérifiée y compris copie metadata/horodatages/flush ; usages séries inspectés (MainActivity, queuedAsRows, home/detail merges) ; `git diff --check` sans erreur. Aucune modification Xtream/cache déjà distante, aucune clé/URL de flux ajoutée. Relecture indépendante confiée au contrôleur après commit.

Dernier passage externe après exclusion de ProgressPersistenceTest : **OK (267 tests)**, **BUILD SUCCESSFUL in 6s**, **22 classes**. Commande : `source /workspace/iptv-env/activate.sh; cd apps; ./gradlew --max-workers=2 -I /workspace/iptv-work/4b/task-2-plain-tests.gradle :tv:task2PlainTests`. Journal `task-2-plain-final.log`. Classes : SecretComboTest, BackActionTest, GroupRowsTest, GroupsWatcherTest, LiveBrowseViewModelTest, LiveNavigationTest, LivePlaybackStateTest, LiveRefresherTest, LiveScreenModelTest, LiveSessionsTest, PrefsWatcherTest, SectionsTest, PlayerControllerTest, EpisodePlaybackTest, SeriesDetailViewModelTest, SeriesHomeViewModelTest, SeriesRowsTest, SeriesTypedQueueTest, StartupViewModelTest, IptvThemeTest, EpgModelTest, EpgRepositoryTest. L'élargissement compte 7 tests dans ProgressPersistenceTest, dont 6 échouent uniquement avec le SDK de compilation ; ils ne sont pas revendiqués verts.

## Livraison de cette reprise

- Checkpoint partagé : `8c1a005` (260/260 tests partagés).
- Checkpoint TV : commit accompagnant cette mise à jour (267 tests TV sans runtime Android verts, APK debug construit).
- Limite restante explicite : relancer la suite TV officielle avec Robolectric et ProgressPersistenceTest lorsque le téléchargement réel est autorisé. Aucun test d'écran Films ajouté ici : la tâche 3 n'est pas encore implémentée.
- Catalogue déjà livré conservé : tests Xtream/cache/migration inclus dans les 260 tests shared verts ; aucun fichier catalogue réécrit depuis la reprise distante.

## Correction après relecture indépendante — ronde 1

Risque important confirmé : une réponse imbriquée comme `{"info":{"error":"temporarily unavailable"}}` ou `{"movie_data":{"unknown":"value"}}` était considérée comme une fiche. Le client fabriquait un titre de remplacement et le repository écrasait la fiche valide en renouvelant sa fraîcheur de 6 h.

Deux tests écrits avant le correctif : `nestedErrorsAndUnknownOnlyDetailsAreRejectedButSparseMetadataRemainsValid` vérifie le refus du parser et préserve les formats légitimes résumé seul/stream_id seul ; `nestedErrorDetailsPreserveWarmCacheAndDoNotRenewFreshness` utilise le vrai client et une base SQLite, réchauffe une fiche valide, avance de 7 h puis reçoit les deux formes invalides. Il vérifie la fiche inchangée, l'estampille inchangée et trois requêtes au total (chaque erreur périmée reste réessayable).

- RED : `source /workspace/iptv-env/activate.sh; cd apps; ./gradlew --max-workers=2 :shared:jvmTest --tests '*XtreamMoviesTest' --tests '*MovieRepositoryTest'` → **53 tests completed, 2 failed**, BUILD FAILED in 13s. Les deux échecs portent exactement les nouveaux tests. Journal `/workspace/iptv-work/4b/task-2-review-red.log`.
- Correction minimale dans `XtreamApi.movieInfo` : une section non vide doit contenir au moins un champ film reconnu avec valeur exploitable. Les champs reconnus correspondent aux métadonnées déjà parsées et stream_id. Une erreur/objet inconnu seul lève `XtreamError.Unexpected`, ce qui réutilise le fallback cache existant sans écriture ni nouveau timestamp. Les réponses vides continuent de retourner null. Aucun autre parser ni stockage modifié.
- GREEN ciblé : même commande → **53 tests verts**, BUILD SUCCESSFUL in 16s. Journal `task-2-review-focused-green.log`.
- GREEN suite finale, une exécution après le dernier code : `./gradlew --max-workers=2 :shared:jvmTest` → **262 tests, 0 échec, 0 erreur, 21 classes**, BUILD SUCCESSFUL in 10s. Compteurs vérifiés dans les XML ; journal `task-2-review-suite-green.log`.
- Auto-revue : diff limité à movieInfo, ses deux classes de tests et ce rapport ; formats pauvres existants conservés par les tests ; `git diff --check` sans erreur. Les limites du runtime Robolectric signalées précédemment restent inchangées. Le catalogue a été modifié uniquement pour corriger ce risque identifié par la revue, après l'accord du contrôleur.
