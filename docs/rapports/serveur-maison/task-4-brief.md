### Task 4 : Code commun, protocole et appels Supabase du serveur maison

**Files :**
- Create : `apps/shared/src/commonMain/kotlin/home/iptv/shared/home/HomeProtocol.kt`
- Modify : `apps/shared/src/commonMain/kotlin/home/iptv/shared/sync/SyncClient.kt`
- Test : `apps/shared/src/jvmTest/kotlin/home/iptv/shared/home/HomeProtocolTest.kt`, `.../sync/HomeServerSyncTest.kt`

**Interfaces :**
- `@Serializable data class SnapStream(val id: String, val categoryId: String, val name: String, val icon: String?, val epgChannelId: String?, val number: Int?, val position: Int, val autoKey: String?, val title: String)`
- `@Serializable data class SnapCategory(val id: String, val name: String, val position: Int)`
- `@Serializable data class LiveSnapshot(val rev: String, val account: String, val builtAt: Long, val categories: List<SnapCategory>, val streams: List<SnapStream>, val hidden: List<String>)`
  : `rev` est une empreinte (SHA-256 en hexadécimal) du contenu ; `account` est l'empreinte du compte IPTV calculée
  comme `IptvApp.accountFingerprint()` le fait aujourd'hui (même fonction, déplacée dans `shared` si besoin) ;
  `hidden` est la liste des catégories masquées pour tout le monde au moment de la mise à jour.
- `@Serializable data class SnapEpg(val title: String, val startMs: Long, val endMs: Long, val nowPlaying: Boolean)` ; `@Serializable data class EpgReply(val entries: List<SnapEpg>)`
- `const val HOME_PROTOCOL = 1` (envoyé dans l'en-tête `X-Home-Protocol`, refus net si différent).
- Dans `SyncClient` (avec implémentations par défaut pour les faux clients des tests) :
  - `suspend fun registerHomeServer(householdId: String, url: String, pin: String, version: String) {}`
  - `suspend fun homeServer(householdId: String): HomeServerInfo? = null`, avec
    `data class HomeServerInfo(val url: String, val pin: String, val version: String?, val lastSeenMs: Long)`
  - `suspend fun issueHomeServerToken(householdId: String): String = throw IOException("non pris en charge")`
  - `suspend fun checkHomeServerToken(householdId: String, token: String): String? = null`
  - `SupabaseSyncClient` les implémente avec `rpc(...)` et une lecture de `home_server`.
- `HomeServerInfo.toString()` ne doit pas afficher le jeton (il n'y en a pas) ; **le jeton n'apparaît dans aucun
  `toString`** (classe `HomeToken(value)` avec `toString() = "HomeToken(***)"`).

- [ ] **Step 1 :** `HomeProtocolTest.snapshotRoundTrip` (encode puis décode à l'identique) et
  `snapshotRevIsStableForSameContent` (même contenu → même `rev` ; une chaîne renommée → autre `rev`), avec la
  fonction `fun liveSnapshotRev(categories: List<SnapCategory>, streams: List<SnapStream>, hidden: List<String>): String`.
- [ ] **Step 2 :** `HomeServerSyncTest` avec `ktor-client-mock` : chaque fonction appelle la bonne RPC avec les bons
  paramètres, et `homeServer` rend `null` quand la table est vide.
- [ ] **Step 3 :** les lancer (ÉCHEC), implémenter, relancer (PASS).
- [ ] **Step 4 :** commit `feat(shared): protocole et appels du serveur maison`.

