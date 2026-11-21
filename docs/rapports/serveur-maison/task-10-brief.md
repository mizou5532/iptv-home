### Task 10 : Client de la télé vers le serveur maison

**Files :**
- Create : `apps/tv/src/main/kotlin/home/iptv/tv/home/{HomeServerClient.kt, PinnedTls.kt}`
- Modify : `apps/tv/src/main/kotlin/home/iptv/tv/data/LocalStore.kt` (jeton serveur gardé chiffré comme les
  identifiants)
- Test : `apps/tv/src/test/kotlin/home/iptv/tv/home/HomeServerClientTest.kt` (OkHttp `MockWebServer` en TLS avec un
  certificat de test ; ajouter `mockwebserver` et `okhttp-tls` aux dépendances de test)

**Interfaces :**
- `fun pinnedClient(base: OkHttpClient, pin: String): OkHttpClient` : un `X509TrustManager` qui n'accepte **que** le
  certificat dont `spkiPin` vaut `pin`, et une vérification du nom d'hôte remplacée par cette empreinte (le certificat
  est auto-signé, pour une adresse IP).
- `sealed interface HomeState { data object Unknown; data object Absent; data class Present(val version: String?) }`
- `class HomeServerClient(sync: SyncClient, store: LocalStore, householdId: () -> String?, base: OkHttpClient, clockMs: () -> Long, timeoutMs: Long = 1_500)` avec :
  - `val state: StateFlow<HomeState>`
  - `suspend fun liveSnapshot(since: String?): LiveSnapshotResult` où
    `sealed interface LiveSnapshotResult { data class Fresh(val snapshot: LiveSnapshot); data object Unchanged; data object Unavailable }`
  - `suspend fun epg(streamId: String, limit: Int, full: Boolean): List<EpgEntry>?` (nul = indisponible)
  - Ordre : lire `homeServer(h)` (gardé 10 min), jeton depuis `LocalStore` ou `issueHomeServerToken(h)`, puis la
    demande. `401` → nouveau jeton une fois, puis `Absent`. Erreur TLS d'empreinte → relire `homeServer(h)` une fois
    (serveur réinstallé), puis `Absent`.
  - Après un échec ou un délai dépassé, `Absent` pendant **60 s** (aucune demande au serveur pendant ce temps), puis
    nouvel essai.
- Le jeton n'apparaît dans aucun journal ni rapport de lenteur.

- [ ] **Step 1 :** tests `freshThenUnchangedWith304`, `wrongPinRefused`, `pinMismatchRereadsHomeServerOnce` (Review
  Focus 3), `expiredTokenRenewedOnce`, `slowServerMarkedAbsentForOneMinute` (Review Focus 5 : réponse retardée de 2 s
  → `Unavailable` en 1,5 s environ, puis aucune demande pendant 60 s), `noHomeServerRowMeansAbsent`,
  `supabaseSessionTokenNeverSent` (aucune requête reçue par le faux serveur ne porte le jeton Supabase).
- [ ] **Step 2 :** ÉCHEC, implémenter, PASS (`:tv:testDebugUnitTest --tests '*HomeServerClientTest*'`). Commit
  `feat(tv): client du serveur maison`.

