# Task 10 : client de la télé vers le serveur maison

## Implémentation
- `tv/home/PinnedTls.kt` : `PinnedTrustManager` (compare `CertificatePinner.pin(leaf)` à l'empreinte, lève `PinMismatchException`, aucun repli sur le système, `acceptedIssuers` vide), `pinnedClient(base, pin)` : hostnameVerifier remplacé par l'empreinte, TLS seul (pas de http clair), pas de redirection (le jeton ne part que vers le serveur épinglé).
- `tv/home/HomeServerClient.kt` : `HomeState`, `LiveSnapshotResult`, `HomeServerClient` selon l'interface du brief. `homeServer(h)` gardé 10 min ; jeton lu dans `LocalStore` sinon émis ; 401 -> un seul renouvellement (sous `Mutex`, car un nouveau jeton invalide l'ancien : une demande concurrente reprend celui déjà renouvelé) puis Absent ; erreur d'empreinte -> une relecture de `homeServer(h)` (nouvel essai seulement si adresse/empreinte ont changé) puis Absent ; échec/délai/503/426/JSON illisible -> Absent 60 s sans aucune demande (horloge injectée). Délai = `callTimeout` OkHttp (1 500 ms). Aucun journal ; exceptions avalées (jamais relayées) ; le jeton est tenu dans `HomeToken`. Le jeton Supabase n'est jamais manipulé.
- `LocalStore` : `homeToken(h)` / `saveHomeToken(h, token)` (défauts null/no-op, donc les 4 faux existants compilent), stockés dans les SharedPreferences privées comme les identifiants (le code actuel ne les chiffre pas autrement que par le stockage privé de l'app). `lastSnapshotRev`/`lastServerContactMs` laissés à la Task 11.
- Catalogue : `okhttp = "5.3.2"` (version déjà résolue par ktor/coil), `okhttp-mockwebserver`, `okhttp-tls` en `testImplementation` du tv.

## Tests (HomeServerClientTest, 8)
freshThenUnchangedWith304, epgMapsEntries, wrongPinRefused (aucune requête n'atteint le serveur, une seule relecture), pinMismatchRereadsHomeServerOnce, expiredTokenRenewedOnce (+ double 401 -> Absent, un seul renouvellement), slowServerMarkedAbsentForOneMinute (réponse à 2 s -> Unavailable en 1,4-1,95 s réel ; horloge fictive : rien à +59 s, nouvel essai à +60 s), noHomeServerRowMeansAbsent, supabaseSessionTokenNeverSent. TLS réel via `HeldCertificate`/`HandshakeCertificates`.

## TDD
Honnête : implémentation écrite avant la première exécution (pas de ROUGE préalable sur code absent). Premier passage : 6/8 verts, 2 échecs réels (pin mismatch non détecté) : OkHttp 5 essaie plusieurs routes (IPv6/IPv4) et garde l'erreur TLS dans `suppressed`, la `ConnectException` finale étant en tête ; corrigé en parcourant causes + suppressed. Puis 8/8.
Suite complète `:tv:testDebugUnitTest` : 593 tests, 0 échec (585 + 8).

## Auto-revue / réserves
- Le « chiffrement » des identifiants dans `LocalStore` n'existe pas dans le code (SharedPreferences privées en clair) ; le jeton suit le même régime.
- 503 sur `/v1/live` (instantané pas encore prêt) est traité comme un échec : Absent 60 s.
- Le délai de 1 500 ms ne borne que la requête serveur maison, pas les appels Supabase (`homeServer`, `issueHomeServerToken`) ; une erreur de leur part -> Absent.
- `state` reste Absent jusqu'à la prochaine réussite (pas de retour à Unknown après 60 s).

## Fix round 1
- I1 : tout s'exécute sur `Dispatchers.IO` (délais en temps réel). `epg` : appel entier (lectures Supabase + HTTP + corps) borné à 1 500 ms (`withTimeoutOrNull`), délai -> null + Absent. `liveSnapshot` : lectures Supabase + connexion + en-têtes bornés à 1 500 ms, corps jusqu'à `SNAPSHOT_BODY_TIMEOUT_MS = 20_000` (`callTimeout` par demande). L'émission d'un jeton et son enregistrement dans `LocalStore` passent par `NonCancellable` (`issueAndSave`).
- I2 : `epg` 400/404/502 -> null sans `failed()` ; autres non-200, 401 répété, 426, transport, délai -> Absent.
- M2 : `resume(response) { _, _, _ -> response.close() }` dans `await`.
- Bug trouvé par les tests : un appel `epg` pendant la fenêtre Absent rouvrait la fenêtre de 60 s (failed() sur null d'absence) ; maintenant `failed()` seulement si la fenêtre n'est pas active.
- Tests ajoutés : slowSupabaseFallsBackWithinBudget, largeSnapshotBodyAllowedBeyondHeaderBudget, epgProviderErrorDoesNotMarkServerAbsent.
- Commande : `tester.sh 'cd apps && ./gradlew --max-workers=2 :tv:testDebugUnitTest --tests "*HomeServerClientTest*"'` -> 11 tests verts ; suite complète `:tv:testDebugUnitTest` -> BUILD SUCCESSFUL, 596 tests, 0 échec.
