### Task 9 : Guide sur le serveur et route `/v1/epg`

**Files :**
- Create : `apps/server/src/main/kotlin/home/iptv/server/EpgCache.kt` ; modifier `Routes.kt`
- Move : `EpgSource` de `apps/tv/.../live/epg/EpgRepository.kt` vers
  `apps/shared/src/commonMain/kotlin/home/iptv/shared/xtream/EpgSource.kt` ; corriger les imports de la télé
- Test : `apps/server/src/test/kotlin/home/iptv/server/EpgCacheTest.kt`

**Interfaces :**
- `class EpgCache(api: EpgSource, clockMs: () -> Long, maxEntries: Int = 2_000)` avec
  `suspend fun entries(streamId: String, limit: Int, full: Boolean): List<EpgEntry>` : cache partagé par toute la
  maison, fraîcheur 5 min au plus et fin anticipée au terme du programme en cours (mêmes règles que
  `EpgRepository`), 2 demandes au fournisseur à la fois au plus, `limit` ramené à 8 au plus.
  `EpgSource` (interface de `apps/tv/.../live/epg/EpgRepository.kt`) **est déplacée dans `apps/shared`**
  (`home.iptv.shared.xtream.EpgSource`), avec sa fabrique `of(api)`.
- Route `GET /v1/epg/{streamId}?limit=8&full=0|1` → `EpgReply`.

- [ ] **Step 1 :** `EpgCacheTest` : `secondCallServedFromCache`, `expiresAtEndOfCurrentProgram`,
  `atMostTwoProviderCallsAtOnce`, `limitCappedAt8`.
- [ ] **Step 2 :** ÉCHEC, implémenter, PASS. Commit `feat(server): guide partagé par la maison`.

