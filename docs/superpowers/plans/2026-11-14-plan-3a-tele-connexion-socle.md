# Plan 3a — App télé : connexion et socle — plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Une première app sur la télé du salon : au premier lancement elle affiche un QR code ; le propriétaire le scanne avec son iPhone, crée le compte, saisit ses identifiants IPTV et nomme les profils sur une page web ; la télé démarre, affiche « Qui regarde ? », charge les chaînes en direct et en lit une en plein écran.

**Architecture:** Un projet Gradle Kotlin Multiplatform dans `apps/` : le module `:shared` (cœur commun : URLs et API Xtream, synchro Supabase, cache du catalogue) avec des tests JVM rapides, et le module `:tv` (app Android TV, Compose for TV, Media3). Une page web de configuration dans `web/config/` (Vite + TypeScript + supabase-js), hébergée gratuitement, qui sert aussi le fichier d'installation de l'app. Le plan 3b ajoutera ensuite le vrai écran Direct (copies regroupées, bascule automatique, guide, favoris, « qui regarde »).

**Tech Stack:** Kotlin (Multiplatform), Gradle 8.14 + Android Gradle Plugin, Compose for TV (`androidx.tv:tv-material`), Media3 ExoPlayer, Ktor client + kotlinx.serialization, supabase-kt (Auth, Postgrest), SQLDelight, ZXing core (QR), kotlinx-coroutines-test ; TypeScript, Vite, Vitest, `@supabase/supabase-js`.

**Spec:** `docs/superpowers/specs/2026-11-14-player-iptv-design.md` (§4, §6.1 — décision QR du 2026-11-14, §6.2, §6.3 pour la lecture simple, §7, §8). Contrat de la base : `backend/README.md` et l'addendum du plan 2 (`docs/superpowers/plans/2026-11-14-plan-2-synchro.md`). Projet Supabase : `docs/rapports/synchro-production.md`.

## Global Constraints

- **Télé cible** : TCL QM85 (65QM851G), Google TV. `minSdk 28`, `targetSdk` = dernière stable. Navigation **à la télécommande seulement** (D-pad, OK, retour) : chaque écran a un élément sélectionné visible.
- **Une seule connexion à la fois** chez le fournisseur : jamais deux lecteurs ouverts ; libérer le lecteur avant d'en ouvrir un autre.
- Les identifiants Xtream ne passent **jamais** par un commit, un journal (`Log`), un rapport ou la conversation. La télé les lit dans Supabase au démarrage et les garde dans son stockage privé pour démarrer hors ligne.
- La télé est un **utilisateur anonyme** Supabase ; elle ne connaît jamais le mot de passe du compte. Seul le propriétaire valide un appareil (`approve_pairing`, propriétaire seulement).
- Contrat de la base (backend/README.md) : un seul foyer (`foyer-existe-deja`), au plus 10 profils, nom d'appareil 1 à 40 caractères (`nom-appareil-invalide`), code d'appairage 6 caractères `ABCDEFGHJKMNPQRSTUVWXYZ23456789` valable 10 minutes, `my_households()` pour savoir si la télé est rattachée.
- Supabase : URL et clé publique dans `docs/rapports/synchro-production.md` (publiques par conception) ; **jamais** la clé `service_role`.
- Une catégorie masquée **pour tout le monde** n'est **jamais téléchargée** (spec §6.2). Le masquage par profil est un filtre d'affichage (plan 7).
- Interface en **français** ; thème **sombre** des maquettes (fond `#0E1116`, surfaces `#171C24`, texte `#F2F4F8`, secondaire `#A3ADBD`, accent `#2C64F0`, élément sélectionné entouré de blanc).
- Performance (spec §7) : ouverture jusqu'à « Qui regarde ? » < 2 s quand la télé est déjà rattachée et le catalogue en cache.
- Versions : dernières **stables** au moment de l'exécution, publiées depuis au moins deux semaines, consignées dans `apps/gradle/libs.versions.toml`.
- **Signature de l'app** — décision : une clé de signature dédiée est générée une fois et **versionnée dans le dépôt privé** (`apps/tv/signature/iptv-home.jks`), avec son mot de passe dans `apps/tv/signature/README.md`. Raison : les mises à jour installées à la main doivent être signées par la même clé et les sessions de travail ne gardent rien ; risque : quiconque a accès au dépôt privé pourrait signer une mise à jour, que le propriétaire devrait de toute façon installer lui-même.
- Toute publication ou création de service en ligne (page de configuration) se fait **après accord explicite du propriétaire**.

## Review Focus

1. **Télé éteinte ou app relancée pendant ou après l'appairage** → la télé reprend où elle en était : déjà rattachée → « Qui regarde ? » directement ; code expiré → nouveau code, sans écran bloqué. Test dans la tâche 5.
2. **Identifiants IPTV modifiés sur la page de configuration** → la télé utilise les nouveaux au prochain démarrage (le cache ne sert que hors ligne). Test dans la tâche 5.
3. **Pas d'internet ou Supabase injoignable au démarrage** → l'app s'ouvre quand même sur les profils et le catalogue en cache, avec un message discret ; jamais un chargement sans fin. Test dans la tâche 5.
4. **Le rafraîchissement du catalogue échoue au milieu** (une catégorie expire chez le fournisseur) → les chaînes déjà en cache de cette catégorie restent ; aucune liste à moitié vide. Test dans la tâche 6.
5. **Code tapé à la main sur le téléphone en minuscules ou avec un espace** (`ab3 k7m`), ou lien QR ouvert deux fois → accepté une fois, puis message clair « déjà utilisé ». Test dans la tâche 4.

---

## Fichiers

```
package.json                          workspaces : ajouter "web/*"
apps/                                 projet Gradle (Kotlin Multiplatform + Android)
  settings.gradle.kts  build.gradle.kts  gradle.properties  gradle/libs.versions.toml  gradlew…
  shared/                             cœur commun (commonMain, jvmTest, androidMain)
    src/commonMain/kotlin/home/iptv/shared/
      xtream/XtreamUrls.kt            serveur, URL d'API, URL de flux
      xtream/XtreamModels.kt          modèles tolérants (nombres en chaîne, nuls)
      xtream/XtreamApi.kt             appels player_api.php
      sync/SyncClient.kt              session anonyme, appairage, foyer, identifiants, profils, masquages
      catalog/Catalog.sq (SQLDelight) + catalog/CatalogRepository.kt
    src/jvmTest/…                     tests (MockEngine, SQLite en mémoire)
  tv/                                 app Android TV
    src/main/kotlin/home/iptv/tv/
      App.kt  MainActivity.kt
      startup/StartupViewModel.kt  startup/PairingScreen.kt  startup/WhoIsWatchingScreen.kt
      live/LiveBrowseScreen.kt  player/PlayerScreen.kt
      theme/Theme.kt
    src/test/…                        tests JVM du ViewModel
    signature/iptv-home.jks  signature/README.md
web/config/                           page de configuration (téléphone)
  package.json  index.html  vite.config.ts  vercel.json
  src/flow.ts  src/main.ts  src/ui.css
  test/flow.test.ts
tools/android/installer-sdk.sh        installe le SDK Android dans $HOME/android-sdk
docs/installer-sur-la-tele.md         guide d'installation pour le propriétaire
```

---

### Task 1: Outillage Android, projet Gradle et URLs Xtream

**Files:**
- Create: `tools/android/installer-sdk.sh`, `apps/settings.gradle.kts`, `apps/build.gradle.kts`, `apps/gradle.properties`, `apps/gradle/libs.versions.toml`, wrapper Gradle (`gradle wrapper` avec Gradle 8.14.3), `apps/shared/build.gradle.kts`, `apps/tv/build.gradle.kts`, `apps/tv/src/main/AndroidManifest.xml`, `apps/tv/src/main/kotlin/home/iptv/tv/MainActivity.kt` (écran vide « iptv-home »)
- Create: `apps/shared/src/commonMain/kotlin/home/iptv/shared/xtream/XtreamUrls.kt`
- Test: `apps/shared/src/jvmTest/kotlin/home/iptv/shared/xtream/XtreamUrlsTest.kt`
- Modify: `.gitignore` (`apps/**/build/`, `apps/.gradle/`, `apps/local.properties`)

**Interfaces:**
- Produces:
  - `data class XtreamCreds(val server: String, val username: String, val password: String)` — `toString()` masque `username` et `password` (`***`)
  - `fun normalizeServer(input: String): String` — même règle que `tools/test-lecture/src/xtream.ts` : ajoute `http://` sans protocole, ne garde que `protocole://hôte[:port]` en minuscules, sans chemin ni requête
  - `fun apiUrl(creds: XtreamCreds, action: String? = null, params: Map<String, String> = emptyMap()): String` — `{server}/player_api.php?username=…&password=…[&action=…&…]`, valeurs encodées en pourcentage (espace → `%20`, jamais `+`)
  - `enum class StreamKind(val path: String) { LIVE("live"), MOVIE("movie"), SERIES("series") }`
  - `fun streamUrl(creds: XtreamCreds, kind: StreamKind, id: String, ext: String): String`
- `installer-sdk.sh` : télécharge les command-line tools depuis `dl.google.com`, accepte les licences, installe `platform-tools`, la plate-forme et les build-tools ciblés, écrit `apps/local.properties` (`sdk.dir`) ; idempotent.
- Modules : `:shared` cibles `jvm()` et `androidTarget()` ; `:tv` application `home.iptv.tv`, `minSdk 28`, catégorie `LEANBACK_LAUNCHER`, `uses-feature android.software.leanback required=false`, `android.hardware.touchscreen required=false`, permission `INTERNET`, `usesCleartextTraffic=true` (le fournisseur est en `http`).

- [ ] **Step 1: Installer le SDK** — Run: `bash tools/android/installer-sdk.sh` — Expected : `apps/local.properties` contient `sdk.dir=…` et `$HOME/android-sdk/platform-tools/adb` existe.
- [ ] **Step 2: Écrire les tests** — reprendre les cas de `tools/test-lecture/test/xtream.test.ts` en Kotlin :

```kotlin
class XtreamUrlsTest {
    private val c = XtreamCreds("http://tv.exemple.com:8080", "jean@x", "a&b#c/d e")
    @Test fun normalise() {
        mapOf(
            "tv.exemple.com:8080" to "http://tv.exemple.com:8080",
            "http://tv.exemple.com:8080/" to "http://tv.exemple.com:8080",
            "  http://tv.exemple.com:8080/player_api.php " to "http://tv.exemple.com:8080",
            "http://tv.exemple.com:8080/get.php?username=a&password=b&type=m3u_plus" to "http://tv.exemple.com:8080",
            "HTTP://TV.Exemple.com:8080" to "http://tv.exemple.com:8080",
        ).forEach { (entree, attendu) -> assertEquals(attendu, normalizeServer(entree)) }
    }
    @Test fun api() = assertEquals(
        "http://tv.exemple.com:8080/player_api.php?username=jean%40x&password=a%26b%23c%2Fd%20e&action=get_live_streams&category_id=12",
        apiUrl(c, "get_live_streams", mapOf("category_id" to "12")))
    @Test fun flux() = assertEquals("http://tv.exemple.com:8080/live/jean%40x/a%26b%23c%2Fd%20e/7.ts", streamUrl(c, StreamKind.LIVE, "7", "ts"))
    @Test fun identifiantsMasques() { assertFalse(c.toString().contains("jean")); assertFalse(c.toString().contains("a&b")) }
}
```

- [ ] **Step 3: Vérifier l'échec** — Run: `cd apps && ./gradlew :shared:jvmTest` — Expected: FAIL (références non résolues).
- [ ] **Step 4: Implémenter `XtreamUrls.kt`** — `normalizeServer` avec `io.ktor.http.Url` (ou analyse manuelle) ; encodage par `encodeURLParameter(spaceToPlus = false)`.
- [ ] **Step 5: Vérifier** — Run: `cd apps && ./gradlew :shared:jvmTest :tv:assembleDebug` — Expected : tests PASS et `apps/tv/build/outputs/apk/debug/tv-debug.apk` existe.
- [ ] **Step 6: Commit** — `git add tools/android apps .gitignore && git commit -m "feat(tv): projet Kotlin Multiplatform, app Android TV vide et URLs Xtream"`

---

### Task 2: API Xtream du direct

**Files:**
- Create: `apps/shared/src/commonMain/kotlin/home/iptv/shared/xtream/XtreamModels.kt`, `…/xtream/XtreamApi.kt`
- Test: `apps/shared/src/jvmTest/kotlin/home/iptv/shared/xtream/XtreamApiTest.kt`, `apps/shared/src/jvmTest/resources/xtream/*.json`

**Interfaces:**
- Consumes: `XtreamCreds`, `apiUrl` (tâche 1)
- Produces:
  - `data class UserInfo(val auth: Boolean, val maxConnections: Int?, val allowedOutputFormats: List<String>)`
  - `data class LiveCategory(val id: String, val name: String)`
  - `data class LiveStream(val id: String, val name: String, val categoryId: String, val icon: String?, val epgChannelId: String?, val number: Int?)`
  - `data class EpgEntry(val title: String, val start: Instant, val end: Instant)`
  - `sealed class XtreamError : Exception() { object Unreachable; object Refused; data class Unexpected(val status: Int) }`
  - `class XtreamApi(private val http: HttpClient, private val creds: XtreamCreds) { suspend fun userInfo(): UserInfo; suspend fun liveCategories(): List<LiveCategory>; suspend fun liveStreams(categoryId: String): List<LiveStream>; suspend fun shortEpg(streamId: String, limit: Int = 2): List<EpgEntry> }`

Règles de lecture (le format Xtream varie selon les panneaux) : identifiants et nombres peuvent arriver en nombre **ou** en chaîne ; champs absents ou `null` tolérés ; `user_info.auth` = `1`/`"1"`/`true` ; `max_connections` chaîne numérique ; un élément illisible est **ignoré**, pas toute la liste ; `get_short_epg` renvoie `{"epg_listings":[{"title": <base64>, "start_timestamp": "…", "stop_timestamp": "…"}]}` — titres **décodés du base64** ; `auth != 1` → `XtreamError.Refused` ; erreur réseau ou délai (15 s) → `Unreachable`. Les URL complètes ne sont jamais écrites dans un message d'erreur.

- [ ] **Step 1: Écrire les tests** avec Ktor `MockEngine` et des fixtures écrites à la main au format Xtream :
  - `userInfo` : `{"user_info":{"auth":1,"max_connections":"1","allowed_output_formats":["m3u8","ts"]}}` → `UserInfo(true, 1, ["m3u8","ts"])` ; `{"user_info":{"auth":0}}` → lève `Refused`
  - `liveCategories` : `category_id` en chaîne et en nombre → deux catégories
  - `liveStreams("12")` : la requête contient `action=get_live_streams&category_id=12` ; un élément sans `stream_id` est ignoré, les autres gardés ; `num` absent → `number = null`
  - `shortEpg` : titre `"SG9ja2V5IExOSA=="` → `"Hockey LNH"`, horaires convertis
  - délai dépassé (MockEngine qui attend plus que le délai) → `Unreachable`, et le message de l'exception ne contient ni `username` ni `password` de test
- [ ] **Step 2: Vérifier l'échec** — Run: `cd apps && ./gradlew :shared:jvmTest` — Expected: FAIL.
- [ ] **Step 3: Implémenter** — `kotlinx.serialization` avec `JsonElement` et des lecteurs tolérants (`jsonPrimitive.contentOrNull`).
- [ ] **Step 4: Vérifier le succès** — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(tv): API Xtream du direct"`

---

### Task 3: Synchro : session de la télé, appairage, foyer, identifiants, profils

**Files:**
- Create: `apps/shared/src/commonMain/kotlin/home/iptv/shared/sync/SyncClient.kt`, `…/sync/SyncModels.kt`
- Test: `apps/shared/src/jvmTest/kotlin/home/iptv/shared/sync/SyncClientTest.kt`

**Interfaces:**
- Consumes: `XtreamCreds` (tâche 1) ; contrat de la base (backend/README.md)
- Produces:
  - `data class Profile(val id: String, val name: String, val color: String, val home: String, val theme: String, val position: Int)`
  - `data class HiddenItem(val itemType: String, val itemId: String)`
  - `sealed class PairingState { data class Waiting(val code: String, val expiresAt: Instant) ; data class Paired(val householdId: String) }`
  - `interface SyncClient { suspend fun ensureSignedIn(); suspend fun currentHousehold(): String?; suspend fun createPairingCode(deviceName: String): PairingState.Waiting; suspend fun credentials(householdId: String): XtreamCreds?; suspend fun profiles(householdId: String): List<Profile>; suspend fun globalHiddenCategories(householdId: String): List<HiddenItem> }`
  - `class SupabaseSyncClient(supabase: SupabaseClient) : SyncClient` — `ensureSignedIn()` : session existante gardée, sinon `signInAnonymously()` ; `currentHousehold()` : premier `id` de `rpc("my_households")` ou `null` ; `createPairingCode` : `rpc("create_pairing_code", {device_name})`, expiration = maintenant + 10 min ; `credentials` : `xtream_credentials` filtré sur `household_id` ; `profiles` triés par `position` ; `globalHiddenCategories` : `hidden_items` avec `profile_id is null` et `item_type = 'live_category'`
  - `fun supabaseClient(url: String, publishableKey: String, sessionStore: SessionManager? = null, engine: HttpClientEngine? = null): SupabaseClient` — fabrique partagée (Auth + Postgrest)
  - `object SupabaseConfig { const val URL: String; const val PUBLISHABLE_KEY: String }` — valeurs de `docs/rapports/synchro-production.md`

- [ ] **Step 1: Écrire les tests** avec un `MockEngine` passé à la fabrique : `ensureSignedIn` sans session appelle `POST /auth/v1/signup` (corps vide, inscription anonyme) une seule fois, puis ne rappelle pas ; `currentHousehold` → `POST /rest/v1/rpc/my_households` → `[]` donne `null`, `[{"id":"h1",…}]` donne `"h1"` ; `createPairingCode("Télé du salon")` envoie `{"device_name":"Télé du salon"}` à `/rest/v1/rpc/create_pairing_code` et renvoie le code ; erreur `nom-appareil-invalide` remontée telle quelle ; `credentials` lit `/rest/v1/xtream_credentials?…household_id=eq.h1` et renvoie `XtreamCreds` (ou `null` si aucune ligne) ; `globalHiddenCategories` filtre `profile_id=is.null` et `item_type=eq.live_category`.
- [ ] **Step 2: Vérifier l'échec** — Expected: FAIL.
- [ ] **Step 3: Implémenter** avec supabase-kt (modules `auth-kt`, `postgrest-kt`) ; aucune journalisation des réponses.
- [ ] **Step 4: Vérifier le succès** — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(tv): synchro — session anonyme, appairage, foyer, identifiants, profils"`

---

### Task 4: Page de configuration sur téléphone

**Files:**
- Create: `web/config/package.json`, `web/config/index.html`, `web/config/vite.config.ts`, `web/config/vercel.json`, `web/config/src/flow.ts`, `web/config/src/main.ts`, `web/config/src/ui.css`
- Test: `web/config/test/flow.test.ts`
- Modify: `package.json` (racine : workspaces `"web/*"`)

**Interfaces:**
- Produces (`src/flow.ts`, logique pure, client Supabase injecté) :
  - `normalizePairingCode(input: string): string` — majuscules, espaces et tirets retirés
  - `interface Db { rpc(fn: string, args?: object): Promise<{ data: unknown; error: { message: string } | null }>; from(table: string): … }` — le sous-ensemble de supabase-js utilisé, pour tester avec un faux
  - `async function loadState(db: Db): Promise<{ householdId: string | null; hasCredentials: boolean; devices: { userId: string; name: string; role: string }[] }>`
  - `async function setupHousehold(db: Db, input: { name: string; profiles: string[]; server: string; username: string; password: string }): Promise<string>` — `create_household` puis `set_xtream_credentials` ; profils vides retirés ; au plus 10
  - `async function approveCode(db: Db, householdId: string, rawCode: string): Promise<{ ok: true; deviceName: string } | { ok: false; message: string }>` — messages en français : `code-invalide` → « Ce code n'existe pas ou a déjà été utilisé. », `code-expire` → « Ce code a expiré. Sur la télé, un nouveau code va s'afficher. », `acces-refuse` → « Seul le compte du propriétaire peut ajouter un appareil. »
  - `async function updateCredentials(db, householdId, creds)` et `async function revokeDevice(db, householdId, userId)`
- Écrans (`main.ts`, une colonne lisible sur iPhone, champs ≥ 16 px, thème sombre des maquettes) : **1. Connexion** (e-mail + mot de passe ; « Créer le compte » / « Me connecter » ; après inscription, si Supabase demande une confirmation par e-mail : « Ouvre le lien reçu par e-mail, puis reviens ici ») ; **2. Première configuration** si aucun foyer (nom du foyer, profils pré-remplis « Toi », « Sœur », « Frère » modifiables, ajout jusqu'à 10, adresse du serveur, nom d'utilisateur, mot de passe IPTV) ; **3. Valider la télé** (code pré-rempli depuis `?code=` de l'URL du QR, sinon saisie) ; **4. Mes appareils** (liste, « Retirer »), « Modifier mes identifiants IPTV ». Les identifiants ne sont jamais réaffichés ni journalisés ; après envoi, les champs sont vidés.
- `vercel.json` : sert aussi `/iptv-home.apk` et `/version.json` (déposés à la tâche 7).

- [ ] **Step 1: Écrire les tests** (faux `Db` qui enregistre les appels) : `normalizePairingCode(' ab3 k7m ')` → `'AB3K7M'`, `'ab3-k7m'` → `'AB3K7M'` ; `setupHousehold` appelle `create_household` avec les profils non vides puis `set_xtream_credentials` avec les identifiants tels quels ; 11 profils → erreur avant tout appel ; `approveCode` code déjà utilisé (`code-invalide`) → message « déjà été utilisé » ; `code-expire` → message d'expiration ; succès → `deviceName` ; `loadState` sans foyer → `householdId: null`.
- [ ] **Step 2: Vérifier l'échec** — Run: `npm test -w web/config` — Expected: FAIL.
- [ ] **Step 3: Implémenter `flow.ts` puis `main.ts`**
- [ ] **Step 4: Vérifier le succès** — `npm test -w web/config` PASS et `npm run build -w web/config` sans erreur.
- [ ] **Step 5: Vérifier dans un navigateur** contre le vrai projet Supabase avec **un compte de test jetable** ? **Non** : le projet n'accepte qu'un foyer, le premier foyer créé doit être celui du propriétaire. Vérifier seulement l'affichage et la navigation de l'écran de connexion à la largeur d'un iPhone (Playwright, une capture), sans s'inscrire.
- [ ] **Step 6: Commit** — `git commit -m "feat(config): page de configuration sur téléphone"`

---

### Task 5: Démarrage de la télé : appairage par QR et « Qui regarde ? »

**Files:**
- Create: `apps/tv/src/main/kotlin/home/iptv/tv/App.kt`, `…/startup/StartupViewModel.kt`, `…/startup/PairingScreen.kt`, `…/startup/WhoIsWatchingScreen.kt`, `…/theme/Theme.kt`, `…/data/LocalStore.kt`
- Modify: `MainActivity.kt`
- Test: `apps/tv/src/test/kotlin/home/iptv/tv/startup/StartupViewModelTest.kt`

**Interfaces:**
- Consumes: `SyncClient`, `PairingState`, `Profile`, `XtreamCreds` (tâche 3)
- Produces:
  - `interface LocalStore { fun householdId(): String?; fun saveHouseholdId(id: String); fun creds(): XtreamCreds?; fun saveCreds(c: XtreamCreds); fun profiles(): List<Profile>; fun saveProfiles(p: List<Profile>) }` — implémentation Android dans le stockage privé de l'app
  - `sealed interface StartupUi { object Loading; data class Pairing(val code: String, val qrUrl: String); data class Ready(val profiles: List<Profile>, val offline: Boolean); data class Failed(val message: String) }`
  - `class StartupViewModel(sync: SyncClient, store: LocalStore, configPageUrl: String, deviceName: String = "Télé du salon", pollEvery: Duration = 3.seconds, clock: Clock = Clock.System) { val ui: StateFlow<StartupUi>; fun start() }`
  - URL du QR : `"$configPageUrl/?code=$code"`

Comportement de `start()` : `ensureSignedIn` ; si `currentHousehold()` est non nul → rafraîchir identifiants et profils depuis Supabase (les sauver en local) → `Ready(offline=false)` ; sinon → `createPairingCode` → `Pairing` → interroger `currentHousehold()` toutes les 3 s ; à l'expiration du code → nouveau code ; une fois rattachée → comme ci-dessus. **Hors ligne** (erreur réseau) avec un foyer en local → `Ready(profiles locaux, offline=true)` ; hors ligne sans rien en local → `Failed("Pas de connexion internet. Vérifie le réseau de la télé.")` avec un bouton « Réessayer ». Rattachée mais sans identifiants IPTV → `Failed("Ajoute tes identifiants IPTV sur la page de configuration.")`.

Écrans : `PairingScreen` — grand QR (ZXing core → `ImageBitmap`), le code en gros caractères espacés, « Scanne ce code avec ton téléphone » et l'adresse de la page en petit ; `WhoIsWatchingScreen` — reprend la maquette 1 (`Main.dc.html` du canevas de maquettes) : tuiles de profil 184 px, couleur du profil, nom, tuile sélectionnée entourée de blanc ; OK ouvre la tâche 7.

- [ ] **Step 1: Écrire les tests** (faux `SyncClient` et `LocalStore` en mémoire, `kotlinx-coroutines-test`, temps virtuel) :
  - déjà rattachée → `Ready(offline=false)` sans créer de code ; identifiants **modifiés côté Supabase** → les nouveaux sont sauvés en local (Review Focus 2)
  - non rattachée → `Pairing(code, "https://config.exemple/?code=ABC234")` ; après 2 interrogations le foyer apparaît → `Ready`
  - code expiré (temps virtuel + 10 min) → un nouveau code est créé et affiché (Review Focus 1)
  - relance de l'app après rattachement (nouveau ViewModel, même `LocalStore`, même session) → `Ready` directement (Review Focus 1)
  - réseau coupé avec foyer en local → `Ready(offline=true)` en moins de 5 s de temps virtuel ; sans rien en local → `Failed` avec le message réseau (Review Focus 3)
  - rattachée sans identifiants → `Failed` avec le message des identifiants
- [ ] **Step 2: Vérifier l'échec** — Run: `cd apps && ./gradlew :tv:testDebugUnitTest` — Expected: FAIL.
- [ ] **Step 3: Implémenter** ViewModel, `LocalStore` Android, écrans, thème ; la session Supabase de la télé est persistée (gestionnaire de session de supabase-kt sur stockage Android).
- [ ] **Step 4: Vérifier** — `./gradlew :tv:testDebugUnitTest :tv:assembleDebug` — Expected: PASS et APK construit.
- [ ] **Step 5: Commit** — `git commit -m "feat(tv): appairage par QR code et écran Qui regarde"`

---

### Task 6: Cache du catalogue du direct

**Files:**
- Create: `apps/shared/src/commonMain/sqldelight/home/iptv/shared/catalog/Catalog.sq`, `apps/shared/src/commonMain/kotlin/home/iptv/shared/catalog/CatalogRepository.kt`
- Test: `apps/shared/src/jvmTest/kotlin/home/iptv/shared/catalog/CatalogRepositoryTest.kt`

**Interfaces:**
- Consumes: `XtreamApi`, `LiveCategory`, `LiveStream` (tâche 2) ; `HiddenItem` (tâche 3)
- Produces:
  - tables `live_category(id TEXT PRIMARY KEY, name TEXT NOT NULL, position INTEGER NOT NULL)` et `live_stream(id TEXT PRIMARY KEY, category_id TEXT NOT NULL, name TEXT NOT NULL, icon TEXT, epg_channel_id TEXT, number INTEGER, position INTEGER NOT NULL)` avec index sur `category_id`
  - `interface LiveSource { suspend fun liveCategories(): List<LiveCategory>; suspend fun liveStreams(categoryId: String): List<LiveStream> }` et l'adaptateur `class XtreamLiveSource(private val api: XtreamApi) : LiveSource`
  - `data class RefreshReport(val categories: Int, val streams: Int, val failedCategories: List<String>)`
  - `class CatalogRepository(db: CatalogDatabase) { suspend fun refreshLive(source: LiveSource, hiddenCategoryIds: Set<String>): RefreshReport; fun categories(): List<LiveCategory>; fun streams(categoryId: String): List<LiveStream> }`

Règles : les catégories masquées pour tout le monde ne sont **jamais demandées** au fournisseur et sont **retirées** du cache ; chaque catégorie est remplacée **dans sa propre transaction** (supprimer ses chaînes puis insérer les nouvelles) ; une catégorie en erreur **garde ses chaînes en cache** et figure dans `failedCategories` ; une catégorie disparue chez le fournisseur est retirée avec ses chaînes ; les catégories sont demandées une par une (une seule connexion).

- [ ] **Step 1: Écrire les tests** (SQLite JVM en mémoire, faux `LiveSource` qui compte les appels) :
  - premier rafraîchissement : 3 catégories dont 1 masquée → `liveStreams` appelé 2 fois, jamais pour la masquée ; `categories()` en renvoie 2
  - deuxième rafraîchissement où une catégorie lève une erreur → ses chaînes d'avant sont toujours là (Review Focus 4) et elle est dans `failedCategories`
  - une catégorie disparue → retirée avec ses chaînes ; une catégorie nouvellement masquée → retirée du cache
  - 20 000 chaînes dans une catégorie → toutes présentes après rafraîchissement, ordre (`position`) conservé
- [ ] **Step 2: Vérifier l'échec** — Expected: FAIL.
- [ ] **Step 3: Implémenter** (pilote SQLDelight JVM pour les tests, Android dans `:tv`).
- [ ] **Step 4: Vérifier le succès** — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(tv): cache du catalogue du direct par catégorie"`

---

### Task 7: Chaînes en direct et lecture simple

**Files:**
- Create: `apps/tv/src/main/kotlin/home/iptv/tv/live/LiveBrowseScreen.kt`, `…/live/LiveBrowseViewModel.kt`, `…/player/PlayerScreen.kt`, `…/player/PlayerController.kt`
- Test: `apps/tv/src/test/kotlin/home/iptv/tv/live/LiveBrowseViewModelTest.kt`, `…/player/PlayerControllerTest.kt`

**Interfaces:**
- Consumes: `CatalogRepository`, `XtreamApi`, `streamUrl`, `StreamKind` ; `SyncClient.globalHiddenCategories` ; `LocalStore.creds()`
- Produces:
  - `class LiveBrowseViewModel(repo: CatalogRepository, refresh: suspend () -> RefreshReport) { val categories: StateFlow<List<LiveCategory>>; val streams: StateFlow<List<LiveStream>>; val refreshing: StateFlow<Boolean>; fun select(categoryId: String); fun start() }` — affiche le cache **tout de suite**, rafraîchit en arrière-plan, puis met à jour
  - `interface Player { fun play(url: String); fun stop(); fun release() }` et `class PlayerController(newPlayer: () -> Player) { fun open(url: String); fun close() }` — `open` arrête et libère le lecteur précédent **avant** d'en créer un autre (une seule connexion)
- Écran : deux colonnes (catégories / chaînes) dans l'esprit de la maquette 3 (sans aperçu ni regroupement : plan 3b) ; OK sur une chaîne → plein écran Media3 (`ts` d'abord, `m3u8` si `allowed_output_formats` ne contient pas `ts`) ; Retour → revient à la liste en arrêtant le lecteur.

- [ ] **Step 1: Écrire les tests** : le ViewModel publie le cache avant la fin du rafraîchissement (rafraîchissement suspendu dans le test) ; `refreshing` passe à faux après ; `PlayerController.open(a)` puis `open(b)` → le faux lecteur A reçoit `stop()` et `release()` **avant** que B soit créé (journal d'appels ordonné) ; `close()` libère.
- [ ] **Step 2: Vérifier l'échec** — Expected: FAIL.
- [ ] **Step 3: Implémenter** (Media3 ExoPlayer derrière `Player`).
- [ ] **Step 4: Vérifier** — `./gradlew :shared:jvmTest :tv:testDebugUnitTest :tv:assembleDebug` — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(tv): chaînes en direct depuis le cache et lecture plein écran"`

---

### Task 8: Version installable, mise en ligne de la page et test chez le propriétaire

**Files:**
- Create: `apps/tv/signature/iptv-home.jks`, `apps/tv/signature/README.md`, `docs/installer-sur-la-tele.md`, `docs/rapports/tele-premiere-version.md`
- Modify: `apps/tv/build.gradle.kts` (signature `release`, `versionCode`/`versionName`), `web/config/public/` (APK et `version.json` copiés au moment du déploiement, non versionnés)

- [ ] **Step 1: Clé de signature** — générer `iptv-home.jks` (`keytool`, RSA 4096, validité 30 ans) ; configurer la signature `release` ; `./gradlew :tv:assembleRelease` → `tv-release.apk` signé (`apksigner verify` OK).
- [ ] **Step 2: Demander l'accord du propriétaire** pour publier la page de configuration sur Vercel (projet `iptv-home-config`, gratuit, sur son compte, même procédure qu'au plan 1). Sans accord : s'arrêter ici.
- [ ] **Step 3: Déployer** la page avec `iptv-home.apk` et `version.json` (`{"versionCode":1,"versionName":"0.1.0"}`) ; vérifier que la page s'ouvre et que l'APK se télécharge (taille, en-têtes).
- [ ] **Step 4: Réglage Supabase par le propriétaire** — Authentication → URL Configuration : « Site URL » = adresse de la page ; donner le chemin exact.
- [ ] **Step 5: Guide `docs/installer-sur-la-tele.md`** (français, pas à pas, pour le propriétaire) : installer l'app « Downloader » depuis le Play Store de la télé ; autoriser Downloader à installer des apps (Paramètres → Système → Développeur ou Confidentialité → Sources inconnues, selon la version de Google TV) ; taper l'adresse courte de l'APK ; installer ; ouvrir « iptv-home » ; scanner le QR ; créer le compte et configurer sur le téléphone.
- [ ] **Step 6: Test chez le propriétaire** — lui envoyer le guide ; recueillir : installation OK ?, QR scanné ?, profils affichés ?, catégories et chaînes affichées ?, une chaîne qui lit ?, temps d'ouverture la 2ᵉ fois (Review Focus perf) ; consigner dans `docs/rapports/tele-premiere-version.md` avec les problèmes rencontrés.
- [ ] **Step 7: Commit** — `git commit -m "chore(tv): version installable 0.1.0 et guide d'installation"`
