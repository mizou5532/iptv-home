### Task 2 : Déplacer `LiveRefresher` vers `apps/shared`

**Files :**
- Move : `apps/tv/src/main/kotlin/home/iptv/tv/live/LiveRefresher.kt` → `apps/shared/src/commonMain/kotlin/home/iptv/shared/catalog/LiveRefresher.kt` (paquet `home.iptv.shared.catalog`)
- Move : ses tests de `apps/tv/src/test/kotlin/home/iptv/tv/live/` vers `apps/shared/src/jvmTest/kotlin/home/iptv/shared/catalog/` (s'ils n'utilisent pas Android)
- Modify : `apps/tv/src/main/kotlin/home/iptv/tv/MainActivity.kt` (import)

**Interfaces :**
- Produces : `class LiveRefresher(sync: SyncClient, repo: CatalogRepository, householdId: () -> String?, userInfo: suspend () -> UserInfo, source: () -> LiveSource)`,
  avec `run(onProgress)`, `publish(report)`, `format`, `lastHidden`, sans changement. `fun chooseFormat(allowed: List<String>): String`.

- [ ] **Step 1 :** déplacer les fichiers et corriger les imports.
- [ ] **Step 2 :** lancer `./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest`. Attendu : tout passe,
  même nombre de tests qu'avant le déplacement.
- [ ] **Step 3 :** commit `refactor(shared): mise à jour du Direct déplacée dans le code commun`.

