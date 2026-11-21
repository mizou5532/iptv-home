# Task 11 : la télé se sert du serveur, avec secours

## Implémentation
- `tv/home/HomeLiveRefresh.kt` : `RefreshOutcome(report, fromServer)`, `HomeLiveRefresh(home, repo, local, store, account, clockMs)` et `homeEpgSource(home, fallback)`.
  - `run(onProgress)` : demande `home.liveSnapshot(store.lastSnapshotRev())`.
  - `Fresh(snapshot)` : si `snapshot.account != account()`, ignore la liste du serveur maison et applique la règle de secours (`fallbackOrKeepCache`). Sinon `repo.replaceLive(snapshot)`, enregistre `lastSnapshotRev(snapshot.rev)`, `lastServerContactMs(clockMs())`, retient `lastHidden = snapshot.hidden.toSet()`, et renvoie `fromServer = true`.
  - `Unchanged` (304) : enregistre `lastServerContactMs(clockMs())` et garde le cache avec `fromServer = true` (si le cache local était vide par incohérence, remet `lastSnapshotRev(null)` et bascule sur le secours).
  - `Unavailable` : si le cache est vide (`repo.categories().isEmpty()`) ou si `lastServerContactMs` est absent ou vieux de plus de 24 h (`> 24 * 3_600_000L`), appelle `local.run(onProgress)`, remet `lastSnapshotRev(null)` (pour redemander une liste complète au retour du serveur maison), retient `lastHidden = local.lastHidden` et renvoie `fromServer = false`. Sinon, garde le cache actuel sans interroger le fournisseur (`fromServer = true`).
  - `publish(report)` : n'appelle `local.publish(report)` que si `fromServer == false` (le serveur maison publie lui-même pour l'iPhone).
  - `homeEpgSource` : interroge d'abord `home.epg(streamId, limit, full = false/true)` et retombe sur le fournisseur si le résultat est `null`.
- `tv/data/LocalStore.kt` : `lastSnapshotRev()` / `saveLastSnapshotRev(rev)` (`home_snapshot_rev`) et `lastServerContactMs()` / `saveLastServerContactMs(ms)` (`home_contact_ms`).
- `tv/IptvApp.kt` : instance unique `homeServerClient` par processus (avec `PerfInterceptor` sur son client `OkHttpClient`), propriété `homeServerState` (lecture sans initialiser Supabase si le client n'a pas encore servi), ajout de `"serveur_maison"` dans `reportState()`, et remise à `null` de `lastSnapshotRev` dans `wipeCatalog()`.
- `tv/MainActivity.kt` : branche `HomeLiveRefresh` et `homeEpgSource` sur `LiveSessions`, conserve `keptHidden` dans `onDone` lors d'un `304 Unchanged`.
- Clés précalculées (`precomputed = repo.liveKeys()::get`) passées aux 3 appelants de `groupSourceIndex` : `LiveBrowseViewModel.kt`, `SportData.kt` (`visibleSportChannels`) et `SearchSourcesTv.kt` (`liveItems`).
- `tv/health/Health.kt` & `HealthScreen.kt` & `Diagnostics.kt` : ligne « Serveur maison : présent (version) / absent » dans le bilan de santé et le calque de diagnostic.
- `tv/diag/PerfData.kt` : demandes `/v1/live` et `/v1/epg/*` classées sous le type `"maison"` (`"/v1/live"`, `"/v1/epg/:id"`), sans jamais exposer l'hôte ni l'adresse IP.
- `tv/build.gradle.kts` : version **0.43.0 (`versionCode = 72`)**.

## Tests (HomeLiveRefreshTest : 8, HealthCheckTest : +1, PerfDataTest : +1)
- `HomeLiveRefreshTest` : `serverSnapshotFillsCache` (+ second passage 304 `Unchanged`), `snapshotFromOtherAccountIgnored` (Review Focus 4), `serverAbsentRecentlyKeepsCache`, `serverAbsentOver24hRefreshesLocally`, `emptyCacheRefreshesLocallyAtOnce`, `noPublishWhenFromServer`, `epgFallsBackToProvider`, `liveBrowseUsesPrecomputedKeysFromServerSnapshot`.
- `HealthCheckTest` : `serveur_maison_present_ou_absent`.
- `PerfDataTest` : classification `"maison"` pour `/v1/live` et `/v1/epg/:id`.

## TDD & Vérification
- Étape ROUGE vérifiée sur l'atelier (`compileDebugUnitTestKotlin FAILED` : `HomeLiveRefresh` et `homeEpgSource` absents).
- Implémentation puis étape VERTE sur les 3 classes de tests.
- Suite complète dans l'atelier :
  - un premier passage a révélé que `reportState()` dans `IptvApp` accédait à `homeServerClient.state` (qui initialisait `supabase` hors du fil principal pendant `ReportButtonDpadTest`) ; corrigé avec `homeServerState` (`homeServerClientReady`).
  - `tester.sh 'cd apps && ./gradlew --max-workers=2 :shared:jvmTest :server:test :tv:testDebugUnitTest :tv:assembleRelease'` : **BUILD SUCCESSFUL** (shared 419, server 45, tv 605 tests verts, APK release 0.43.0 assemblé et vérifié par R8 + lintVital).

## Relecture indépendante
- Aucune remarque critique ou importante.
- Micro-amélioration appliquée sur `onDone` dans `MainActivity.kt` pour préserver `liveCatalogDone.hidden` lors d'un `304 Unchanged`.
- Commit : `944f780`.
