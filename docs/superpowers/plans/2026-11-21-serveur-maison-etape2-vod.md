# Serveur maison, étape 2 (Films, Séries, Versions, Fiches, Publication iPhone) — plan de construction

> **For agentic workers:** Suivre le plan tâche par tâche (TDD : tests rouges puis verts, relecture indépendante après chaque tâche, commit + push fréquents sur la branche `serveur-maison-etape2-vod`, journal dans `docs/rapports/serveur-maison/etape2-progress.md`).

**Goal :** déplacer sur le serveur maison la mise à jour des films et des séries (tous les 3 jours), le calcul lourd de l'index des versions (qui prenait 13,4 s + 4,5 s au démarrage sur la TCL), le cache partagé des fiches (`get_vod_info` / `get_series_info` + « nouveaux épisodes »), la publication pour l'iPhone (`publish_catalog_titles`, `publish_movie_categories`, `publish_series_categories`) et la vérification quotidienne des comptes supplémentaires (`ExtraAccountsCheck`). La télé garde sa copie complète sur son disque (mise à jour par différences catégorie par catégorie depuis le serveur), demande les versions et fiches au serveur en mode « comptoir », et bascule en secours si le serveur est absent.

**Architecture :** extension directe du module `apps/server` et du client `apps/tv/.../home/HomeServerClient.kt` de l'étape 1. On reste en `HOME_PROTOCOL = 1` en ajoutant de nouvelles routes `/v1/movies/*`, `/v1/series/*`, `/v1/versions/*` (100 % rétrocompatible avec les télés en 0.43.0 pendant le déploiement).

**Spec :** `docs/superpowers/specs/2026-11-20-serveur-maison-catalogue-design.md` (§6.3, §6.4, §6.5, §6.7, §6.9).

**Branche :** `serveur-maison-etape2-vod` (issue de `main` @ `aae08c2`).

---

## Global Constraints

- Toutes les règles absolues de `PASSATION.md` §2 : **aucun identifiant IPTV** ni URL de flux dans le code, les tests, les journaux, les réponses du serveur ni les commits ; jamais de clé `service_role` ; aucun foyer créé sur la production depuis les tests.
- **Règle absolue n° 7** : une catégorie masquée « pour tout le monde » (`vod_category` ou `series_category` dans `globalHiddenCategories`) n'est **jamais téléchargée** chez le fournisseur, ni par le serveur maison ni par la télé. Les fiches d'un film ou d'une série appartenant à une catégorie masquée pour tout le monde ne sont pas servies non plus.
- **Sécurité Wi-Fi** : les nouvelles routes vivent sous `/v1/*` (jeton « serveur maison » + `X-Home-Protocol: 1` + TLS épinglé). Le jeton Supabase de la télé ne quitte jamais la télé.
- **Délais côté télé** :
  - Appels « comptoir » (versions, fiches `info`) : **1 500 ms** strict, puis secours local/fournisseur sans bloquer l'écran.
  - Appels « plateau » (manifeste : 1 500 ms ; catégorie films/séries : en-têtes 1 500 ms, corps jusqu'à **20 s**).
  - Secours catalogue : la télé ne relance le téléchargement Xtream direct et la publication iPhone que si son cache est vide ou si le serveur maison n'a pas répondu depuis **plus de 24 h**.
- **Aucun changement de schéma Supabase** nécessaire pour l'étape 2 (toutes les RPC `publish_catalog_titles`, `publish_movie_categories`, `publish_series_categories`, `global_hidden_categories`, `household_settings`, `report_extra_account` existent déjà).
- **Mémoire de la télé** : supprimer le pré-calcul de `VersionIndexes` dans `MainActivity.onCreate()` quand le serveur maison est présent ; l'index local n'est construit qu'à la demande en mode secours.
- **Noms de tests Kotlin en ASCII**. Textes visibles en **français**. Commentaires en français.
- **Toute publication** (mise à jour du conteneur en production ou APK **0.44.0 / versionCode 73**) se fait avec l'accord du propriétaire (tâche 10).

---

## Review Focus

1. **Catégorie masquée « pour tout le monde » jamais demandée au fournisseur** : ni pour les films (`vod_category`), ni pour les séries (`series_category`), ni par `VodJob` ni par `CatalogFiller`. → test `hiddenVodAndSeriesCategoriesNeverRequested` (tâche 5).
2. **Changement de compte IPTV** : si l'empreinte `account` change, le serveur vide son cache films/séries et repart à zéro ; la télé ignore tout manifeste ou catégorie d'un autre compte (`account != currentAccount`) et vide ses révisions locales dans `wipeCatalog()`. → test `vodManifestFromOtherAccountIgnored` (tâche 8).
3. **Liste vide du fournisseur ne détruit jamais un cache rempli** : même garde-fou que le Direct (`replaceMovieCategories` / `replaceSeriesCategories` / `VodJob`). → test `emptyVodResponseKeepsExistingCache` (tâche 3).
4. **Suppression du pic CPU/GC au démarrage (13,4 s + 4,5 s)** : quand le serveur maison répond, `MainActivity` ne lance plus `VersionIndexes.index(MOVIE/SERIES)` au démarrage ; les versions d'une rangée ou d'une fiche arrivent par `/v1/versions/...` (et basculent sur `VersionIndexes` local seulement si le serveur est absent). → test `versionsServedFromHomeWithoutBuildingLocalIndex` (tâche 9).
5. **Séparation manuelle (`version_splits`)** : quand l'utilisateur clique « Ce n'est pas le même titre » sur la télé, la séparation est enregistrée dans Supabase et immédiatement prise en compte par le serveur et la télé (plus regroupés au rechargement). → test `splitInvalidatesServerAndTvVersions` (tâche 6 & 9).
6. **Aucune double publication iPhone** : quand le serveur maison est actif, il publie les catégories films/séries et les titres (`CatalogTitlesPublisher`) ; la télé ne publie pas (elle ne reprend qu'en secours). → test `tvSkipsIphonePublicationWhenHomeServerActive` (tâche 9).

---

## Structure des fichiers

| Fichier | Rôle |
|---|---|
| `apps/shared/.../account/ExtraAccountsCheck.kt` (déplacé de `apps/tv/.../player/`) | Vérification quotidienne des comptes IPTV supplémentaires (partagée serveur + télé) |
| `apps/shared/.../catalog/CatalogTitlesPublisher.kt` (déplacé de `apps/tv/.../search/`) | Publication des titres films/séries pour l'iPhone (partagée serveur + télé) |
| `apps/shared/.../versions/VersionIndexes.kt` (déplacé de `apps/tv/.../feature/`) | Construction et cache d'index des versions (partie pure dans `shared`, `LocalVersionIndexes` reste dans `tv`) |
| `apps/shared/.../home/HomeProtocol.kt` (modifié) | Modèles JSON : `VodManifest`, `VodCategoryItems`, `SeriesCategoryItems`, `SnapMovie`, `SnapSeries`, `SnapMovieInfo`, `SnapSeriesInfo`, `VersionsRequest`, `VersionsReply`, `SnapVersion` |
| `apps/shared/.../movies/MovieRepository.kt` + `Movies.sq` (modifiés) | `replaceCategories`, `replaceCategoryMovies`, `wipeMovies`, `cachedCategoryIds` |
| `apps/shared/.../series/SeriesRepository.kt` + `Series.sq` (modifiés) | `replaceCategories`, `replaceCategorySeries`, `wipeSeries`, `cachedCategoryIds` |
| `apps/server/.../VodJob.kt` (créé) | Mise à jour 72 h films/séries, respect masqué global, révisions par catégorie, publication iPhone (catégories + titres), `VersionIndex` films/séries en mémoire + `version_splits`, `ExtraAccountsCheck` |
| `apps/server/.../InfoCache.kt` (créé) | Cache partagé 6 h pour `get_vod_info` et `get_series_info` (`Semaphore(3)`, `ensureCreds`) |
| `apps/server/.../Routes.kt` & `Main.kt` (modifiés) | Routes `/v1/movies/*`, `/v1/series/*`, `/v1/versions/*` + démarrage de `VodJob` et `InfoCache` |
| `apps/tv/.../home/HomeServerClient.kt` (modifié) | Appels manifestes, catégories films/séries, fiches `movieInfo`/`seriesInfo`, versions `versions(kind, ids)` |
| `apps/tv/.../home/HomeVodSync.kt` (créé) | Synchronisation différentielle films/séries (vitrine d'abord, priorité basse, secours > 24 h / cache vide), sources `homeMovieSource`/`homeSeriesSource`, `HomeVersionLookup` |
| `apps/tv/.../MainActivity.kt`, `IptvApp.kt`, `LocalStore.kt`, `PerfData.kt`, `SeriesHomeViewModel.kt`, `MovieHomeViewModel.kt`, `App.kt` (modifiés) | Branchement films/séries/versions/fiches sur le serveur, arrêt du calcul au démarrage, arrêt de la publication iPhone et d'`ExtraAccountsCheck` quand le serveur est actif |

---

## Découpage en 10 tâches

### Task 1 : Déplacer `ExtraAccountsCheck`, `CatalogTitlesPublisher` et la logique pure de `VersionIndexes` vers `apps/shared`
- **Files** :
  - Move : `apps/tv/.../player/ExtraAccountsCheck.kt` → `apps/shared/src/commonMain/kotlin/home/iptv/shared/account/ExtraAccountsCheck.kt` (+ test vers `apps/shared/src/jvmTest/...`)
  - Move : `apps/tv/.../search/CatalogTitlesPublisher.kt` → `apps/shared/src/commonMain/kotlin/home/iptv/shared/catalog/CatalogTitlesPublisher.kt` (`AndroidCatalogTitlesState` reste dans `apps/tv/.../search/AndroidCatalogTitlesState.kt` ; test pur déplacé dans `shared`)
  - Move : `VersionIndexes` + `VersionIndexDisk` vers `apps/shared/src/commonMain/kotlin/home/iptv/shared/versions/VersionIndexes.kt` (injecter `measure: (MediaKind, () -> VersionIndex) -> VersionIndex`, utiliser `sha256Hex` commun ; garder `LocalVersionIndexes` et `FileVersionIndexDisk` dans `apps/tv/.../feature/VersionIndexesTv.kt`)
  - Modify : imports dans `apps/tv`
- **Verification** : `./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest` (tous les tests existants verts sans régression).
- **Commit** : `refactor(shared): déplacer ExtraAccountsCheck, CatalogTitlesPublisher et VersionIndexes dans le code commun`.

### Task 2 : Étendre le protocole (`HomeProtocol.kt`) pour Films, Séries, Fiches et Versions
- **Files** :
  - Modify : `apps/shared/src/commonMain/kotlin/home/iptv/shared/home/HomeProtocol.kt`
  - Test : `apps/shared/src/jvmTest/kotlin/home/iptv/shared/home/HomeProtocolVodTest.kt`
- **Interfaces** (paquet `home.iptv.shared.home`, tous `@Serializable`) :
  - `SnapVodCategory(id, name, position, rev)`
  - `VodManifest(rev, account, builtAt, categories: List<SnapVodCategory>, hidden: List<String>)`
  - `SnapMovie(id, categoryId, name, cover, year, genre, plot, rating, addedAt, extension, durationS, position, backdrop)` + `MovieCategoryReply(categoryId, rev, account, items: List<SnapMovie>)`
  - `SnapSeries(id, categoryId, name, cover, year, genre, plot, rating, position, backdrop)` + `SeriesCategoryReply(categoryId, rev, account, items: List<SnapSeries>)`
  - `SnapMediaTech(...)`, `SnapMovieInfo(...)` (conversion 1:1 avec `MovieInfo`)
  - `SnapSeason(...)`, `SnapEpisode(...)`, `SnapSeriesInfo(...)` (conversion 1:1 avec `SeriesInfo`)
  - `SnapVersionTag(lang: String?, uhd: Boolean, hdr: Boolean, source: String?)`, `SnapVersion(id, categoryId, title, tag, cover, year)`
  - `VersionsRequest(ids: List<String>)`, `VersionsReply(rev: String, groups: Map<String, List<SnapVersion>>)` (chaque clé = `groupKey` ; la télé reconstruit un `VersionIndex` léger pour les IDs demandés ; les titres sans doublon n'ont pas besoin de grossir la réponse si l'appelant le gère, ou chaque ID trouvé renvoie son groupe).
  - Fonctions d'empreinte : `movieCategoryRev(items)`, `seriesCategoryRev(items)`, `vodManifestRev(categories, hidden)`.
- **Tests** : aller-retour JSON exact pour chaque modèle + stabilité des empreintes `rev`.
- **Commit** : `feat(shared): protocole films, séries, fiches et versions du serveur maison`.

### Task 3 : Méthodes de mise à jour serveur dans `MovieRepository` et `SeriesRepository`
- **Files** :
  - Modify : `Movies.sq`, `Series.sq`, `MovieRepository.kt`, `SeriesRepository.kt`
  - Test : `apps/shared/src/jvmTest/kotlin/home/iptv/shared/movies/ReplaceMoviesTest.kt`, `.../series/ReplaceSeriesTest.kt`
- **Interfaces** :
  - `MovieRepository` :
    - `replaceCategories(categories: List<LiveCategory>, fetchedAtMs: Long)` (refuse liste vide si cache non vide → `XtreamError.Unexpected(200)`, retire les catégories disparues et leurs films en une transaction, met à jour `movie_fetch('cats')`)
    - `replaceCategoryMovies(categoryId: String, items: List<Movie>, fetchedAtMs: Long)` (remplace les films de cette catégorie et met à jour `movie_fetch('cat:<id>')` en une transaction)
    - `wipeMovies()` (vide toutes les tables `movie_*` en une transaction)
    - `saveInfo(info: MovieInfo, fetchedAtMs: Long)` / `cachedInfo(movieId: String): MovieInfo?`
  - `SeriesRepository` : méthodes strictement symétriques (`replaceCategories`, `replaceCategorySeries`, `wipeSeries`, `saveInfo`).
- **Tests** : `emptyVodResponseKeepsExistingCache` (Review Focus 3), remplacement atomique, suppression des catégories disparues/masquées, `wipeMovies`/`wipeSeries`.
- **Commit** : `feat(shared): remplacement atomique films et séries depuis le serveur maison`.

### Task 4 : Cache partagé des fiches sur le serveur (`InfoCache.kt`)
- **Files** :
  - Create : `apps/server/src/main/kotlin/home/iptv/server/InfoCache.kt`
  - Test : `apps/server/src/test/kotlin/home/iptv/server/InfoCacheTest.kt`
- **Interfaces** :
  - `class InfoCache(movies: MovieRepository, series: SeriesRepository, hiddenMovieCats: () -> Set<String>, hiddenSeriesCats: () -> Set<String>, beforeFetch: suspend () -> Unit)` :
    - `suspend fun movieInfo(movieId: String): MovieInfo` (vérifie `cachedCategory(movieId)` n'est pas masquée globalement, sinon lève `HiddenCategoryException` → 404 ; réutilise le cache SQLite 6 h de `MovieRepository`, `Semaphore(3)` au plus vers Xtream, `beforeFetch()` avant tout appel réseau)
    - `suspend fun seriesInfo(seriesId: String): SeriesInfo` (idem pour séries).
- **Tests** : second appel servi depuis le cache SQLite sans appeler le fournisseur, `beforeFetch` appelé au redémarrage, catégorie masquée refusée, au plus 3 appels simultanés.
- **Commit** : `feat(server): cache partagé des fiches films et séries`.

### Task 5 : Travail de fond Films, Séries, Versions, Publication iPhone et Comptes supplémentaires (`VodJob.kt`)
- **Files** :
  - Create : `apps/server/src/main/kotlin/home/iptv/server/VodJob.kt`
  - Test : `apps/server/src/test/kotlin/home/iptv/server/VodJobTest.kt`
- **Interfaces** :
  - `class VodJob(...)` :
    - Met à jour les catégories et contenus Films & Séries (rythme 72 h, vitrine d'abord, 100 % des catégories visibles ensuite ; respecte strictement `globalHiddenCategories` : **jamais** de catégorie `vod_category` ou `series_category` masquée demandée à Xtream).
    - Calcule les `rev` par catégorie et le `VodManifest` (relus depuis le disque `DATA_DIR/vod-manifests.json` au démarrage pour répondre instantanément après redémarrage).
    - Changement de compte IPTV (`account()`) : vide `movies` + `series`, réinitialise les manifestes et relance.
    - Construit et maintient en mémoire `VersionIndex` films et `VersionIndex` séries (en lisant `householdSettings(h).versionSplits`, reconstruit si catalogue ou `prefs_rev`/`versionSplits` changent).
    - Publie pour l'iPhone : `publishSeriesCategories`, `publishMovieCategories` (via `MovieClient` / `SyncClient`), et `CatalogTitlesPublisher` (état gardé dans `DATA_DIR/titles-published.json`, au plus une fois par 20 h une fois le remplissage complet).
    - Exécute `ExtraAccountsCheck.runPending()` au démarrage, toutes les 6 h et à chaque nouvelle `prefs_rev`.
- **Tests** : `hiddenVodAndSeriesCategoriesNeverRequested` (Review Focus 1), `accountChangeWipesVodCache` (Review Focus 2), `publishesCategoriesAndTitlesForIphone`, `versionsBuiltAndUpdatedOnSplitChange`, `extraAccountsCheckedByServer`.
- **Commit** : `feat(server): mise à jour films/séries, versions, publication iPhone et comptes supplémentaires`.

### Task 6 : Routes serveur `/v1/movies/*`, `/v1/series/*`, `/v1/versions/*` et branchement `Main.kt`
- **Files** :
  - Modify : `apps/server/src/main/kotlin/home/iptv/server/Routes.kt`, `Main.kt`, `apps/shared/.../sync/SyncClient.kt` (si `publishMovieCategories` doit être exposé sur `SyncClient` ou réutilisation de `SupabaseMovieClient`)
  - Test : `apps/server/src/test/kotlin/home/iptv/server/VodRoutesTest.kt`
- **Routes (toutes dans `/v1`, protégées par `TokenChecker` + `X-Home-Protocol: 1` + gzip)** :
  - `GET /v1/movies/manifest?since=<rev>` → `304` / `503` / `200 VodManifest`
  - `GET /v1/movies/category/{id}?since=<rev>` → `304` / `404` / `200 MovieCategoryReply`
  - `GET /v1/movies/info/{id}` → `200 SnapMovieInfo` / `400` / `404` / `502`
  - `GET /v1/series/manifest?since=<rev>` → `304` / `503` / `200 VodManifest`
  - `GET /v1/series/category/{id}?since=<rev>` → `304` / `404` / `200 SeriesCategoryReply`
  - `GET /v1/series/info/{id}` → `200 SnapSeriesInfo` / `400` / `404` / `502`
  - `POST /v1/versions/movies` et `POST /v1/versions/series` (corps `VersionsRequest(ids)` borné à 2 000 IDs) → `200 VersionsReply` (groupes de versions pour les IDs demandés)
  - `POST /v1/versions/invalidate` → recharge `versionSplits` et reconstruit l'index des versions aussitôt (Review Focus 5).
- **Tests** : 304/503/200/404, compression gzip, aucun identifiant ni URL de flux dans les réponses, lookup versions, invalidation après split.
- **Commit** : `feat(server): routes films, séries, fiches et versions`.

### Task 7 : Client télé étendu (`HomeServerClient.kt` + `PerfData.kt` + `LocalStore.kt`)
- **Files** :
  - Modify : `apps/tv/src/main/kotlin/home/iptv/tv/home/HomeServerClient.kt`
  - Modify : `apps/tv/src/main/kotlin/home/iptv/tv/data/LocalStore.kt` (révisions locales des manifestes et catégories films/séries, remise à zéro dans `wipeCatalog()`)
  - Modify : `apps/tv/src/main/kotlin/home/iptv/tv/diag/PerfData.kt` (toute route `/v1/*` → type `"maison"` avec normalisation `:id`, jamais d'IP)
  - Test : `apps/tv/src/test/kotlin/home/iptv/tv/home/HomeServerClientVodTest.kt`
- **Interfaces ajoutées sur `HomeServerClient`** :
  - `suspend fun movieManifest(since: String?): VodManifestResult` / `seriesManifest(since: String?): VodManifestResult`
  - `suspend fun movieCategory(id: String, since: String?): MovieCategoryResult` / `seriesCategory(id: String, since: String?): SeriesCategoryResult`
  - `suspend fun movieInfo(id: String): MovieInfo?` / `seriesInfo(id: String): SeriesInfo?` (délai strict 1 500 ms ; 400/404/502 → `null` sans marquer le serveur absent)
  - `suspend fun versions(kind: MediaKind, ids: List<String>): VersionsReply?` (délai strict 1 500 ms)
  - `suspend fun invalidateVersions()` (best-effort, sans erreur).
- **Commit** : `feat(tv): client serveur maison pour films, séries, fiches et versions`.

### Task 8 : Synchronisation différentielle et sources avec secours (`HomeVodSync.kt`)
- **Files** :
  - Create : `apps/tv/src/main/kotlin/home/iptv/tv/home/HomeVodSync.kt`
  - Test : `apps/tv/src/test/kotlin/home/iptv/tv/home/HomeVodSyncTest.kt`
- **Interfaces** :
  - `homeMovieSource(home, fallback)` & `homeSeriesSource(home, fallback)` :
    - `movieInfo(id)` / `seriesInfo(id)` interrogent d'abord le serveur maison (1 500 ms), puis le fournisseur si `null`.
    - `movieCategories()` / `movies(catId)` et `seriesCategories()` / `series(catId)` : quand le serveur maison est présent et que `account` correspond, servent les données du serveur maison (avec révision par catégorie pour ne rien retélécharger si `304`), sinon basculent sur le fournisseur selon la règle (cache vide ou serveur absent > 24 h).
  - `HomeVersionStore(home, fallbackLocal, maxGroups = 2_000)` :
    - Interroge `/v1/versions/{kind}` pour les IDs demandés (rangée, Reprendre, Ma liste, fiche), garde un petit cache LRU en mémoire (zéro index géant de 47k films au démarrage !).
    - Si le serveur est absent ou répond `null`, bascule automatiquement sur `fallbackLocal` (`VersionIndexes` local).
    - Sur `split(kind, id)` : enregistre dans Supabase, vide le cache LRU, appelle `home.invalidateVersions()`.
- **Tests** : `vodManifestFromOtherAccountIgnored` (Review Focus 2), `onlyChangedCategoriesDownloaded`, `infoFallsBackToProvider`, `versionsServedFromHomeWithoutBuildingLocalIndex` (Review Focus 4), `versionsFallBackToLocalWhenServerAbsent`, `splitInvalidatesServerAndTvVersions` (Review Focus 5).
- **Commit** : `feat(tv): synchronisation différentielle films/séries et versions depuis le serveur maison`.

### Task 9 : Branchement dans l'app télé (`MainActivity`, `IptvApp`, `SeriesHomeViewModel`, `MovieHomeViewModel`, `App`) + version 0.44.0
- **Files** :
  - Modify : `apps/tv/src/main/kotlin/home/iptv/tv/IptvApp.kt`, `MainActivity.kt`, `App.kt`, `series/SeriesHomeViewModel.kt`, `movies/MovieHomeViewModel.kt`, `apps/tv/build.gradle.kts` (`versionName = "0.44.0"`, `versionCode = 73`)
  - Test : tests d'intégration dans `HomeVodSyncTest.kt` + suite complète `:shared:jvmTest :server:test :tv:testDebugUnitTest :tv:assembleRelease`
- **Changements clés** :
  - `MainActivity.onCreate()` : ne lance plus le pré-calcul lourd `v.index(SERIES)` / `v.index(MOVIE)` au démarrage (débloque immédiatement `StartupOrder.Step.SERIES` et `MOVIES` ; le démarrage passe de ~18 s de calcul à < 1 s).
  - `SeriesHomeViewModel` / `MovieHomeViewModel` / `MovieDetailViewModel` / `SeriesDetailViewModel` / `App.kt` : utilisent `HomeVersionStore` (comptoir serveur avec petit cache LRU + secours local transparent).
  - `CatalogTitlesPublisher`, `publishSeriesCategories`, `movies.publishCategories` et `ExtraAccountsCheck` sur la télé : ne tournent que si le serveur maison est absent depuis > 24 h (sinon le serveur maison s'en charge déjà — Review Focus 6).
  - `ProgressRemapper` reste déclenché correctement via `runtime.filler.progress`.
- **Commit** : `feat(tv): films, séries, versions et fiches servis par le serveur maison (0.44.0)`.

### Task 10 : Déploiement serveur, publication 0.44.0 et essai sur la télé TCL (avec le propriétaire)
- Mettre à jour le conteneur `iptv-catalogue` (`:server:installDist` + `docker compose up -d --build`, `SERVER_VERSION = "0.2.0"`).
- Vérifier dans les journaux que le serveur termine sa première passe films/séries, publie les titres iPhone et construit l'index des versions sans erreur.
- Publier l'APK **0.44.0 (code 73)** avec l'accord du propriétaire.
- Essai guidé sur **TV Salon** + rapport `!` : vérifier que `Index des versions (films)` et `Index des versions (séries)` ont **disparu** des travaux de démarrage, que les fiches et le bouton « Version » fonctionnent, et que le secours marche serveur éteint.
- Mettre à jour `PASSATION.md`, `docs/rapports/serveur-maison/etape2-progress.md` et `~/services/CLAUDE.md`.
