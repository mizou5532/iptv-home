# Serveur maison, étape 1 (socle, Direct, guide) — plan de construction

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal :** un serveur à la maison qui met à jour le Direct une fois pour toute la maison, calcule les clés de
regroupement et sert la liste et le guide à la télé ; la télé s'en sert quand il répond et retombe sur son
fonctionnement actuel sinon.

**Architecture :** nouveau module Gradle `apps/server` (Kotlin JVM, Ktor serveur en TLS) qui réutilise `apps/shared`.
Il est appairé au foyer comme une télé, publie son adresse et l'empreinte de son certificat dans Supabase, et
n'accepte que des jetons « serveur maison » délivrés par Supabase aux membres du foyer. La télé garde son cache
SQLDelight, qu'elle remplit à partir du serveur (avec les clés de regroupement déjà calculées) au lieu du
fournisseur.

**Tech stack :** Kotlin 2.3.21, Ktor 3.4.3 (serveur Netty + `ktor-network-tls-certificates`), SQLDelight 2.4.1
(pilote `sqlite-driver` côté serveur), supabase-kt 3.6.0, OkHttp (télé), Postgres 16 + Vitest (backend), Docker
(`eclipse-temurin:21-jre`).

**Spec :** `docs/superpowers/specs/2026-11-20-serveur-maison-catalogue-design.md` (à lire avec ce plan, en entier).

## Global Constraints

- Règles absolues de `PASSATION.md` §2, toutes. En particulier : **aucun identifiant IPTV** dans le code, les tests,
  les journaux, les réponses du serveur ni les commits ; **jamais la clé `service_role`** ; pas de foyer créé sur la
  production depuis des tests.
- Catégorie masquée « pour tout le monde » : jamais demandée au fournisseur, ni par la télé ni par le serveur.
- **Le jeton de session Supabase de la télé n'est jamais envoyé au serveur maison.** Seul le jeton « serveur maison »
  y circule, et seulement en TLS avec l'empreinte vérifiée.
- Le serveur ne renvoie jamais d'adresse de flux ni d'identifiant : seulement des identifiants de chaînes, des noms,
  des logos et des clés.
- Délai d'une demande de la télé au serveur : **1 500 ms**, puis secours. Mises à jour refaites par la télé elle-même
  quand le serveur est absent depuis **plus de 24 h**, ou si aucune liste n'a encore été reçue.
- Rythme du Direct sur le serveur : **6 h** (comme aujourd'hui), plus une mise à jour quand les catégories masquées
  pour tout le monde changent.
- Guide sur le serveur : mêmes bornes que `EpgRepository` (fraîcheur 5 min au plus, 8 entrées par demande, guide
  complet en secours).
- Mémoire Java du conteneur : `-Xmx2g`. Port **8443** (TLS), à l'écoute sur toutes les interfaces du serveur (réseau
  de la maison + Tailscale). Le serveur n'est pas ouvert sur Internet.
- Noms de tests Kotlin en **ASCII**. Textes visibles en **français**. Commentaires en français, comme le reste du dépôt.
- Toute publication (APK, page, migration de production) : **accord du propriétaire d'abord** ; la migration est
  approuvée par lui dans l'invite Supabase.
- Version télé de cette étape : **0.43.0, versionCode 72**.

## Review Focus

1. **Serveur qui répond mais avec une liste vide ou partielle** (fournisseur en panne pendant sa mise à jour) : la
   télé ne doit jamais remplacer une liste remplie par une liste vide. → test `replaceLiveKeepsCacheWhenSnapshotEmpty`
   (tâche 5).
2. **Appareil retiré du foyer sur l'iPhone** : son jeton « serveur maison » doit cesser de marcher dans les 5 min. →
   test backend « jeton refusé après revoke_device » (tâche 3) et test serveur `revokedTokenRejectedAfterCacheExpiry`
   (tâche 7).
3. **Serveur réinstallé** (nouveau certificat) : la télé doit relire l'empreinte dans Supabase et se reconnecter sans
   être réappairée. → test `pinMismatchRereadsHomeServerOnce` (tâche 10).
4. **Changement de compte IPTV** (autre fournisseur saisi sur l'iPhone) : le serveur vide son cache et repart de zéro,
   et la télé ne mélange pas les deux catalogues. → test `accountChangeWipesServerCache` (tâche 8), la télé compare
   l'empreinte du compte dans la liste reçue (tâche 11, `snapshotFromOtherAccountIgnored`).
5. **Serveur lent mais pas éteint** (Wi-Fi faible) : une demande au-delà de 1 500 ms ne doit pas bloquer l'écran, et
   la télé ne doit pas basculer sans cesse entre serveur et secours. → test `slowServerMarkedAbsentForOneMinute`
   (tâche 10).

---

## Structure des fichiers

| Fichier | Rôle |
|---|---|
| `apps/shared/.../groups/GroupIndex.kt` (créé) | `categoryCountries`, `cachedAutoKey`, `cachedTitle`, `groupSourceIndex`, déplacés de `apps/tv/.../live/Sections.kt` |
| `apps/shared/.../catalog/LiveRefresher.kt` (déplacé) | `LiveRefresher` + `chooseFormat`, déplacés de `apps/tv/.../live/LiveRefresher.kt` |
| `apps/shared/.../home/HomeProtocol.kt` (créé) | Modèles échangés entre serveur et télé (`LiveSnapshot`, `SnapStream`, `EpgReply`) |
| `apps/shared/.../sync/SyncClient.kt` (modifié) | 4 appels Supabase du serveur maison |
| `apps/shared/src/commonMain/sqldelight/.../catalog/5.sqm` + `Catalog.sq` (modifiés) | Colonnes `auto_key`, `base_title` sur `live_stream` |
| `apps/shared/.../catalog/CatalogRepository.kt` (modifié) | `replaceLive`, `liveKeys` |
| `backend/supabase/migrations/20261121000035_serveur_maison.sql` (créé) | Tables `home_server`, `home_server_tokens` et leurs fonctions |
| `apps/server/` (créé) | `Main.kt`, `Config.kt`, `FileSessionManager.kt`, `Pairing.kt`, `Tls.kt`, `Auth.kt`, `LiveJob.kt`, `EpgCache.kt`, `Routes.kt` |
| `apps/tv/.../home/HomeServerClient.kt`, `PinnedTls.kt` (créés) | Client de la télé vers le serveur maison |
| `apps/tv/.../live/LiveSessions.kt`, `MainActivity.kt`, `live/epg/*`, `health/*`, `diag/PerfData.kt` (modifiés) | Utilisation du serveur et secours |
| `~/serveur-maison/iptv-catalogue/` (Mac) → `/home/volcan/services/iptv-catalogue/` (serveur) | `Dockerfile`, `docker-compose.yml` |

---

### Task 1 : Déplacer le regroupement du Direct vers `apps/shared`

**Files :**
- Create : `apps/shared/src/commonMain/kotlin/home/iptv/shared/groups/GroupIndex.kt`
- Modify : `apps/tv/src/main/kotlin/home/iptv/tv/live/Sections.kt` (retirer `categoryCountries`, `autoKeys`,
  `cachedTitle`, `cachedAutoKey`, `groupSourceIndex` et importer ceux de `shared`)
- Test : `apps/shared/src/jvmTest/kotlin/home/iptv/shared/groups/GroupIndexTest.kt`

**Interfaces :**
- Produces (paquet `home.iptv.shared.groups`) :
  - `fun categoryCountries(categories: List<LiveCategory>): Map<String, String>`
  - `fun cachedTitle(name: String): String`
  - `fun cachedAutoKey(name: String, country: String?): String?`
  - `data class PrecomputedKey(val autoKey: String?, val title: String)`
  - `fun groupSourceIndex(visibleSources: List<GroupSource>, snapshot: GroupSnapshot, enabled: Boolean, countries: Map<String, String> = emptyMap(), precomputed: (sourceId: String) -> PrecomputedKey? = { null }): Map<String, LiveGroup>`
    : quand `precomputed(id)` n'est pas nul, ses valeurs remplacent `cachedAutoKey` et `cachedTitle` pour cette
    source.
- Le cache mémoire de `commonMain` ne peut pas utiliser `java.util.concurrent` : un `HashMap` protégé par un verrou
  `kotlinx.atomicfu` ou `synchronized` d'un `expect/actual` minimal. *Au choix du constructeur*, à condition de garder
  la limite `AUTO_KEYS_MAX = 200_000`.

- [ ] **Step 1 : écrire le test qui échoue**, `GroupIndexTest.sameGroupsAsBeforeOnFixture` : sur
  `fixtures/live-groups-v2.json` (déjà utilisé par les tests de `LiveGroups`), `groupSourceIndex(...)` donne
  exactement les mêmes groupes (identifiants, titres, sources dans l'ordre) que
  `buildGroups(sources, snapshot, keyOf = { autoKey(it.name, countries[it.categoryId]) }, titleOf = ::baseTitle)`.
- [ ] **Step 2 :** écrire `precomputedKeysWinOverParsing` : une source dont `precomputed` rend
  `PrecomputedKey("cle-forcee", "Titre forcé")` est regroupée avec une autre source ayant la même clé forcée, même si
  leurs noms ne se ressemblent pas.
- [ ] **Step 3 :** lancer `cd apps && ./gradlew --max-workers=2 :shared:jvmTest --tests '*GroupIndexTest*'`. Attendu :
  ÉCHEC (fonctions inconnues).
- [ ] **Step 4 :** créer `GroupIndex.kt` en déplaçant le code de `Sections.kt` tel quel, puis ajouter `precomputed`.
- [ ] **Step 5 :** relancer le test, puis `:tv:testDebugUnitTest`. Attendu : tout passe (les tests télé du Direct,
  du Sport et de la recherche qui utilisent ces fonctions restent verts sans modification de comportement).
- [ ] **Step 6 :** commit `refactor(shared): regroupement du Direct déplacé dans le code commun`.

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

### Task 3 : Backend, tables et fonctions du serveur maison

**Files :**
- Create : `backend/supabase/migrations/20261121000035_serveur_maison.sql`
- Create : `backend/test/serveur-maison.test.ts`
- Modify : `backend/README.md` (contrat : nouvelles tables et fonctions)

**Interfaces (SQL, toutes `security definer`, `search_path = public`, `revoke ... from public, anon`, `grant ... to authenticated`) :**
- Table `home_server(household_id uuid primary key references households on delete cascade, server_user_id uuid not null, url text not null, pin text not null, version text, last_seen timestamptz not null default now())`.
  RLS activée. Lecture : `is_member(household_id)`. Aucune écriture directe.
- Table `home_server_tokens(household_id uuid references households on delete cascade, device_user_id uuid not null, token_hash bytea not null, created_at timestamptz default now(), primary key (household_id, device_user_id))`.
  RLS activée sans politique : accès seulement par les fonctions.
- `register_home_server(h uuid, url text, pin text, version text) returns void` : l'appelant doit être membre `device`
  du foyer. Insère ou remplace la ligne, avec `server_user_id = auth.uid()` et `last_seen = now()`. `url` doit
  commencer par `https://` ; `pin` doit commencer par `sha256/`. Erreurs : `acces-refuse`, `adresse-invalide`,
  `empreinte-invalide`.
- `issue_home_server_token(h uuid) returns text` : l'appelant doit être membre. Crée un jeton de 32 octets aléatoires
  (`gen_random_bytes`), en hexadécimal ; garde seulement son `sha256`. Un nouvel appel remplace l'ancien jeton du même
  appareil.
- `check_home_server_token(h uuid, token text) returns uuid` : l'appelant doit être le `server_user_id` de
  `home_server` pour `h`. Rend le `device_user_id` si le hachage correspond **et** si cet appareil est encore dans
  `household_members` ; sinon `null`.

- [ ] **Step 1 : écrire les tests qui échouent** dans `serveur-maison.test.ts` (même structure que
  `rapports-lenteur.test.ts`, helper `maison()` avec une télé **et** un serveur, tous deux membres `device`) :
  - « le serveur s'enregistre ; un membre lit l'adresse et l'empreinte ; un étranger ne lit rien » ;
  - « refus : non membre, propriétaire anonyme, adresse http, empreinte sans sha256/ » ;
  - « la télé obtient un jeton ; le serveur le vérifie et reçoit l'identifiant de la télé ; un faux jeton rend null » ;
  - « un nouveau jeton remplace l'ancien » ;
  - « jeton refusé après revoke_device » (Review Focus 2) ;
  - « seul le serveur enregistré peut vérifier un jeton (une autre télé du foyer reçoit acces-refuse) ».
- [ ] **Step 2 :** `npm test -w backend -- serveur-maison`. Attendu : ÉCHEC (fonctions absentes).
- [ ] **Step 3 :** écrire la migration (commentaire d'en-tête en français, comme les autres).
- [ ] **Step 4 :** `npm test -w backend`. Attendu : tous les tests passent, nouveaux compris.
- [ ] **Step 5 :** mettre à jour `backend/README.md`, commit `feat(backend): serveur maison (adresse, empreinte, jetons)`.
  **Ne pas appliquer en production** : ce sera fait à la tâche 12, avec l'accord du propriétaire.

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

### Task 5 : Cache de la télé, colonnes de clés et remplacement depuis le serveur

**Files :**
- Create : `apps/shared/src/commonMain/sqldelight/home/iptv/shared/catalog/5.sqm`
- Modify : `Catalog.sq` (colonnes, requêtes), `CatalogRepository.kt`
- Test : `apps/shared/src/jvmTest/kotlin/home/iptv/shared/catalog/ReplaceLiveTest.kt`

**Interfaces :**
- `live_stream` gagne `auto_key TEXT` et `base_title TEXT` (nuls pour les chaînes téléchargées par la télé elle-même).
- `fun CatalogRepository.replaceLive(snapshot: LiveSnapshot): RefreshReport` : en **une transaction**, remplace
  catégories et chaînes par celles de la liste reçue, clés comprises. **Si la liste reçue n'a aucune catégorie alors
  que le cache en a, ne touche à rien** et lève `XtreamError.Unexpected(200)` (même règle que `refreshLive`). Rend un
  `RefreshReport` avec `providerCategories = snapshot.categories` (pour l'écran et la publication).
- `fun CatalogRepository.liveKeys(): Map<String, PrecomputedKey>` : les clés gardées (chaînes dont `base_title` n'est
  pas nul).
- `refreshLive` (chemin de secours) met `auto_key` et `base_title` à nul pour les chaînes qu'il écrit.

- [ ] **Step 1 :** tests `replaceLiveWritesStreamsAndKeys`, `replaceLiveKeepsCacheWhenSnapshotEmpty` (Review Focus
  1), `refreshLiveClearsKeys`, `migration5KeepsExistingRows` (une base au schéma 4 avec des chaînes passe au schéma 5
  sans rien perdre).
- [ ] **Step 2 :** ÉCHEC, implémenter, PASS (`:shared:jvmTest`), puis `:tv:testDebugUnitTest` (PASS).
- [ ] **Step 3 :** commit `feat(shared): cache du Direct rempli depuis le serveur maison`.

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

### Task 7 : TLS, enregistrement dans Supabase et contrôle des jetons

**Files :**
- Create : `apps/server/src/main/kotlin/home/iptv/server/{Tls.kt, Auth.kt}`
- Test : `apps/server/src/test/kotlin/home/iptv/server/{TlsTest.kt, AuthTest.kt}`

**Interfaces :**
- `fun loadOrCreateKeyStore(dir: java.io.File): java.security.KeyStore` : `DATA_DIR/tls.p12`, créé une fois avec
  `buildKeyStore` (`ktor-network-tls-certificates`), alias `home`, validité 10 ans, mot de passe aléatoire gardé dans
  `DATA_DIR/tls.pass` (droits `600`).
- `fun spkiPin(cert: java.security.cert.X509Certificate): String` → `"sha256/" + base64(sha256(publicKey.encoded))`
  (même format qu'OkHttp `CertificatePinner`).
- Au démarrage puis toutes les **10 min** : `registerHomeServer(h, config.lanUrl, pin, version)`.
- `class TokenChecker(check: suspend (token: String) -> String?, ttlMs: Long = 300_000, clockMs: () -> Long)` avec
  `suspend fun deviceFor(token: String): String?` : met en cache les réponses (bonnes **et** mauvaises) pendant
  `ttlMs` ; au plus 64 jetons en cache.
- Plugin Ktor : toute route `/v1/*` exige `Authorization: Bearer <jeton>` et `X-Home-Protocol: 1`, sinon `401` ou
  `426`, sans détail. `/` (page d'appairage) et `/health` (rend `{"ok":true,"version":"…"}`) sont publics et ne
  révèlent rien du foyer.

- [ ] **Step 1 :** `TlsTest.keyStoreReusedAcrossStarts` (même empreinte au second chargement),
  `TlsTest.pinFormatMatchesOkHttp` (comparer avec `okhttp3.CertificatePinner.pin(cert)`).
- [ ] **Step 2 :** `AuthTest` (Ktor `testApplication`) : `missingTokenIs401`, `wrongProtocolIs426`,
  `validTokenReachesRoute`, `revokedTokenRejectedAfterCacheExpiry` (Review Focus 2 : valide, puis le faux `check` rend
  nul ; encore accepté avant 5 min, refusé après).
- [ ] **Step 3 :** ÉCHEC, implémenter, PASS. Commit `feat(server): TLS, enregistrement et jetons`.

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

### Task 12 : Installation sur le serveur, mise en production et essai

*Tâche guidée : plusieurs gestes du propriétaire. Lui expliquer chaque geste simplement, l'un après l'autre. Pour sudo,
ouvrir un onglet du Terminal avec `ssh -t volcan@serveur 'sudo …'` déjà lancé ; il n'a qu'à taper son mot de passe.*

**Files :**
- Create (Mac) : `~/serveur-maison/iptv-catalogue/Dockerfile` (`FROM eclipse-temurin:21-jre`, copie de
  `apps/server/build/install/server`, `ENV JAVA_OPTS=-Xmx2g`, utilisateur non root, `EXPOSE 8443`),
  `~/serveur-maison/iptv-catalogue/docker-compose.yml` (service `iptv-catalogue`, `restart: unless-stopped`, port
  `8443:8443`, volume `./data:/data`, variables de la tâche 6 dont `LAN_URL=https://192.168.2.81:8443`)
- Modify : `PASSATION.md` (état, services, serveur maison), `~/services/CLAUDE.md` sur le serveur et sa copie Mac
  `~/serveur-maison/services/CLAUDE.md`

- [ ] **Step 1 :** **migration de production** `20261121000035_serveur_maison`, approuvée par le propriétaire dans
  l'invite Supabase (le serveur en a besoin dès son démarrage). Vérifier ensuite que les avertissements de sécurité
  Supabase sont à zéro.
- [ ] **Step 2 :** **adresse fixe du serveur** : le propriétaire réserve `192.168.2.81` pour le MacBook dans les
  réglages de sa box (réservation DHCP), guidé pas à pas. Sinon `LAN_URL` deviendrait faux après un changement
  d'adresse. La découverte automatique sur le réseau (mDNS), prévue en secours par la conception, est reportée à une
  étape suivante.
- [ ] **Step 3 :** construire (`./gradlew :server:installDist`), copier par `scp` dans
  `/home/volcan/services/iptv-catalogue/`, puis `docker compose up -d --build`. Vérifier :
  `docker logs iptv-catalogue` montre `Code d'appairage : …`. **Le propriétaire** valide ce code sur la page iPhone
  (« Ajouter un appareil »). Vérifier dans les journaux : foyer trouvé, enregistrement fait, première mise à jour du
  Direct terminée.
- [ ] **Step 4 :** pare-feu (geste du propriétaire, mot de passe sudo) : `ufw allow OpenSSH`,
  `ufw allow from 192.168.2.0/24 to any port 8443 proto tcp`, `ufw allow in on tailscale0`, `ufw --force enable`.
  Vérifier depuis le Mac : `curl -sk https://192.168.2.81:8443/health` renvoie `{"ok":true,…}`.
- [ ] **Step 5 :** publier l'APK 0.43.0 (procédure `PASSATION.md` §6), **avec l'accord du propriétaire**.
- [ ] **Step 6 : essai TCL**, guidé pas à pas : forcer l'arrêt de l'app, la rouvrir. Le Direct s'affiche en moins de
  3 s ; le panneau de diagnostic indique « Serveur maison : présent ». Puis arrêter le conteneur
  (`docker compose stop`) et rouvrir l'app : le Direct s'affiche quand même (dernière copie), avec « absent ».
  Relancer le conteneur. Envoyer un rapport de lenteur depuis la télé et comparer le « ménage de mémoire » avec les
  rapports du 19 nov.
- [ ] **Step 7 :** mettre à jour `PASSATION.md`, `~/services/CLAUDE.md` (service, port, volume, appairage, commande de
  mise à jour) et sa copie Mac. Commit `docs: serveur maison étape 1 en service`.
