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

