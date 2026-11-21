# Task 7 - rapport
Implémenté : Tls.kt (loadOrCreateKeyStore, keyStorePassword, spkiPin, fichiers 600 via création atomique), Auth.kt (TokenChecker avec cache TTL 5 min, 64 entrées max, positifs et négatifs ; plugin de route HomeAuth : 401 / 426 / 503 sans corps ; homeModule : "/" et /health publics, /v1 protégé), Main.kt (pairing HTTP -> arrêt -> keystore -> HTTPS sslConnector sur config.port -> registerHomeServer au démarrage puis toutes les 10 min, erreurs loguées par nom de classe seulement).
Tests : TlsTest (2), AuthTest (6) ; :server:test = 18 verts (10 + 8) ; installDist OK. okhttp : déjà disponible transitivement via ktor-client-okhttp, aucune dépendance ajoutée.
TDD : tests écrits avant le code, mais RED non exécuté séparément (code et tests lancés ensemble ; premier build en erreur de syntaxe KDoc, puis vert).
Choix : jeton vérifié avant la version de protocole (401 prioritaire sur 426) ; erreur de check Supabase -> 503 non mis en cache ; SERVER_VERSION = "0.1.0" en constante.
Concerns : démarrage HTTPS réel (Main) non exécuté de bout en bout (pas de Supabase réel) ; certificat sans SAN LAN (le client épingle la clé, pas le nom).

## Fix round 1
- TokenChecker : cache LRU (accessOrder), clé = SHA-256 du jeton ; à saturation les refus (null) sont évincés avant tout bon jeton, et un refus n'est pas mis en cache si le cache ne contient que de bons jetons. 64 entrées / TTL 300 000 ms inchangés.
- Tests ajoutés : badTokenFloodKeepsValidTokenCached, refusedCallsNeverReachHandler (401, 426, 401 jeton faux, 503 : compteur handler = 0 ; Ktor n'exécute donc pas le handler, plugin inchangé).
- KDoc de homeModule : routes protégées uniquement dans le bloc /v1.
- Commande : tester.sh 'cd apps && ./gradlew --max-workers=2 :server:test :server:installDist' -> BUILD SUCCESSFUL, 20 tests, 0 échec.
