### Task 6 : Module serveur, démarrage et appairage

**Files :**
- Modify : `apps/settings.gradle.kts` (`include(":shared", ":tv", ":server")`), `apps/gradle/libs.versions.toml`
  (`ktor-server-core`, `ktor-server-netty`, `ktor-server-content-negotiation`, `ktor-serialization-kotlinx-json`,
  `ktor-server-compression`, `ktor-server-auth`, `ktor-server-test-host`, `ktor-network-tls-certificates`, tous en
  `ktor` 3.4.3)
- Create : `apps/server/build.gradle.kts` (plugins `kotlin-jvm`, `application`, `kotlin-serialization` ;
  `mainClass = "home.iptv.server.MainKt"` ; dépend de `project(":shared")`, `ktor-client-okhttp`,
  `sqldelight-sqlite-driver`)
- Create : `apps/server/src/main/kotlin/home/iptv/server/{Main.kt, Config.kt, FileSessionManager.kt, Pairing.kt}`
- Test : `apps/server/src/test/kotlin/home/iptv/server/PairingTest.kt`, `ConfigTest.kt`

**Interfaces :**
- `data class Config(val supabaseUrl: String, val supabaseKey: String, val dataDir: java.io.File, val port: Int, val lanUrl: String)`,
  `fun Config.Companion.fromEnv(env: Map<String, String>): Config` : variables `SUPABASE_URL`, `SUPABASE_KEY`
  (clé publishable), `DATA_DIR` (défaut `/data`), `PORT` (défaut `8443`), `LAN_URL` (ex.
  `https://192.168.2.81:8443`). Erreur claire en français si une variable manque.
- `class FileSessionManager(file: java.io.File) : SessionManager` : session Supabase dans `DATA_DIR/session.json`,
  fichier créé avec les droits `600`.
- `class Pairing(sync: SyncClient, deviceName: String = "Serveur maison", pollMs: Long = 5_000)` avec
  `suspend fun awaitHousehold(onCode: (String) -> Unit): String` : `ensureSignedIn()`, puis `currentHousehold()` ; si
  nul, `createPairingCode(deviceName)`, appelle `onCode(code)` et réinterroge toutes les `pollMs` jusqu'à ce que le
  foyer existe. Rend l'identifiant du foyer.
- Pendant l'attente, `Main.kt` écrit le code dans le journal (`Code d'appairage : XXXXXX`) et sert une page HTTP
  simple sur le port, qui l'affiche en grand avec la marche à suivre en français (« Ouvrez la page de configuration
  sur votre iPhone, Ajouter un appareil, tapez ce code »).

- [ ] **Step 1 :** `PairingTest.waitsUntilApprovedThenReturnsHousehold` (faux `SyncClient` : nul deux fois puis un
  foyer ; `onCode` appelé une seule fois avec le code), `PairingTest.alreadyPairedSkipsCode`,
  `ConfigTest.missingVariableExplained`.
- [ ] **Step 2 :** ÉCHEC, implémenter, PASS : `./gradlew --max-workers=2 :server:test`.
- [ ] **Step 3 :** `./gradlew :server:installDist`. Attendu : `apps/server/build/install/server/bin/server` existe.
- [ ] **Step 4 :** commit `feat(server): module serveur maison, démarrage et appairage`.

