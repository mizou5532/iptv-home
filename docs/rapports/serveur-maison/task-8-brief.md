### Task 8 : Mise à jour du Direct sur le serveur et route `/v1/live`

**Files :**
- Create : `apps/server/src/main/kotlin/home/iptv/server/{LiveJob.kt, Routes.kt}`
- Test : `apps/server/src/test/kotlin/home/iptv/server/{LiveJobTest.kt, LiveRouteTest.kt}`

**Interfaces :**
- `class LiveJob(refresher: LiveRefresher, repo: CatalogRepository, sync: SyncClient, householdId: String, account: () -> String?, clockMs: () -> Long, periodMs: Long = 6 * 3_600_000L)` avec :
  - `val snapshot: StateFlow<LiveSnapshot?>`
  - `suspend fun runOnce()` : `refresher.run()`, puis construit la liste à partir du cache (`repo.categories()`,
    `repo.streams(id)`), avec `autoKey = cachedAutoKey(name, countries[categoryId])` et `title = cachedTitle(name)`,
    `countries = categoryCountries(categories)`. Puis `refresher.publish(report)` (la publication pour l'iPhone se
    fait désormais ici). Puis enregistre la liste dans `DATA_DIR/live-snapshot.json` (relue au démarrage : pas
    d'attente après un redémarrage du conteneur).
  - `suspend fun loop()` : `runOnce()` au démarrage si la liste gardée a plus de 6 h ; ensuite toutes les `periodMs` ;
    et aussitôt si les catégories masquées pour tout le monde changent (surveillance de `prefsRevision` toutes les
    **30 s**, puis relecture de `globalHiddenCategories`).
  - Changement de compte IPTV (`account()` différent de celui de la liste gardée) : vider le cache (requêtes
    `wipe*` existantes), puis `runOnce()`.
- Identifiants IPTV : lus par `sync.credentials(h)` à chaque mise à jour, jamais écrits sur le disque ni dans le
  journal. Le client `XtreamApi` du serveur utilise OkHttp.
- Route `GET /v1/live?since=<rev>` : `304` si `since` est égal au `rev` actuel ; `503` si aucune liste n'existe
  encore ; sinon `200`, JSON de `LiveSnapshot`, compressé gzip (plugin `Compression`).

- [ ] **Step 1 :** `LiveJobTest` avec une fausse `LiveSource` et un faux `SyncClient` :
  `hiddenCategoryNeverRequested` (règle n° 7), `snapshotCarriesSameKeysAsTv` (clés égales à
  `cachedAutoKey`/`cachedTitle`), `publishesForIphoneAfterRefresh`, `hiddenChangeTriggersRefresh`,
  `accountChangeWipesServerCache` (Review Focus 4), `providerFailureKeepsLastSnapshot`, `snapshotReloadedFromDisk`.
- [ ] **Step 2 :** `LiveRouteTest` : `sameRevIs304`, `noSnapshotIs503`, `responseIsGzipped`, `noCredentialInResponse`
  (le corps ne contient ni `username`, ni `password`, ni l'adresse du fournisseur de la fausse source).
- [ ] **Step 3 :** ÉCHEC, implémenter, PASS. Commit `feat(server): Direct mis à jour par le serveur`.

