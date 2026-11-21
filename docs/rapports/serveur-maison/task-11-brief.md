### Task 11 : La télé se sert du serveur, avec secours

**Files :**
- Modify : `apps/tv/src/main/kotlin/home/iptv/tv/MainActivity.kt` (lambdas `refresh` et `publish` de `LiveSessions`,
  `epgSource`), `IptvApp.kt` (créer `HomeServerClient` une fois), `live/LiveBrowseViewModel.kt` et les autres
  appelants de `groupSourceIndex` (Sport, recherche : passer `precomputed = repo.liveKeys()::get`),
  `health/HealthScreen.kt` (ligne « Serveur maison : présent (version) / absent »), `diag/PerfData.kt` (type réseau
  `maison` pour les demandes au serveur, sans adresse)
- Create : `apps/tv/src/main/kotlin/home/iptv/tv/home/HomeLiveRefresh.kt`
- Test : `apps/tv/src/test/kotlin/home/iptv/tv/home/HomeLiveRefreshTest.kt`

**Interfaces :**
- `class HomeLiveRefresh(home: HomeServerClient, repo: CatalogRepository, local: LiveRefresher, store: LocalStore, account: () -> String?, clockMs: () -> Long)` avec
  `suspend fun run(onProgress: suspend (Int, Int) -> Unit): RefreshOutcome`, où
  `data class RefreshOutcome(val report: RefreshReport, val fromServer: Boolean)` :
  1. demande `liveSnapshot(store.lastSnapshotRev())` ;
  2. `Fresh` : si `snapshot.account` est différent de `account()`, ignorer la liste (Review Focus 4) ; sinon
     `repo.replaceLive(snapshot)`, enregistrer `rev`, l'heure (`store.lastServerContactMs`) et `snapshot.hidden`
     comme `lastHidden` ;
  3. `Unchanged` : enregistrer seulement l'heure ;
  4. `Unavailable` : si `lastServerContactMs` date de plus de **24 h**, ou si le cache est vide, alors
     `local.run(onProgress)` (chemin d'aujourd'hui) ; sinon ne rien faire et garder le cache.
- `publish` (dans `MainActivity`) : seulement si `fromServer == false` (le serveur publie lui-même).
- `epgSource` : `EpgSource` qui demande d'abord `home.epg(...)`, puis le fournisseur si le résultat est nul.

- [ ] **Step 1 :** tests `serverSnapshotFillsCache`, `snapshotFromOtherAccountIgnored`,
  `serverAbsentRecentlyKeepsCache`, `serverAbsentOver24hRefreshesLocally`, `emptyCacheRefreshesLocallyAtOnce`,
  `noPublishWhenFromServer`, `epgFallsBackToProvider`.
- [ ] **Step 2 :** ÉCHEC, implémenter, PASS, puis la suite complète :
  `./gradlew --max-workers=2 :shared:jvmTest :server:test :tv:testDebugUnitTest :tv:assembleRelease` (PASS, aucune
  erreur d'analyse Android).
- [ ] **Step 3 :** version télé **0.43.0 (code 72)** dans `apps/tv/build.gradle.kts`. Commit
  `feat(tv): Direct et guide depuis le serveur maison, avec secours`.

