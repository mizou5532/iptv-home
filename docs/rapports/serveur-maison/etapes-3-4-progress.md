# Journal d'avancement — Serveur maison Étapes 3 (Recherche) & 4 (Sport + mémoire partagée)

*21 nov. 2026 — Branches : `serveur-maison-etape3-recherche` (`5e9575f`), `serveur-maison-etape4-sport` (base : `serveur-maison-etape2-vod` `fc98445`). `main` laissé volontairement à `aae08c2` en attendant l'essai sur TV Salon.*

---

## Résumé exécutif

- **Étape 3 (Recherche sur le serveur maison)** : codée, testée et relue. La télé n'a plus besoin de construire d'index de recherche en mémoire (0 Mo sur la télé quand le serveur répond ; l'index local n'est plus construit sur requête vide) tout en gardant le secours local automatique et le filtrage défensif « masqué gagne ».
- **Étape 4 (Sport sur le serveur maison + mémoire Sport partagée)** : codée, testée, relue par une IA indépendante (**8 bloquants + 3 mineurs trouvés et corrigés** → verdict final **PRÊT**).
  - Serveur maison `iptv-catalogue` **v0.4.0** **déployé et vérifié en ligne** sur `192.168.2.81:8443` (`/health` → `{"ok":true,"version":"0.4.0"}`, 0 erreur dans les logs).
  - APK télé **0.45.0 (code 74)** **compilé, optimisé R8 et signé** avec le certificat officiel (`388d4fa307b468acb88639f2932d4b1b12edc919f464bf52d5dce126cf38ae07`, 5 502 121 octets, cert `5d8877ce…615d4`).
- **Déploiement complet effectué le 21 nov. 2026** :
  - ✅ Migration `sport_memoire_partagee` (`20261121171800`) appliquée sur Supabase prod (`gisjeuukbrgjgkrmlyvs`), **0 erreur** `get_advisors(security)`.
  - ✅ Serveur maison `iptv-catalogue` **v0.4.0** en ligne sur `192.168.2.81:8443` (`/health` → `{"ok":true,"version":"0.4.0"}`, 0 erreur).
  - ✅ APK télé **0.45.0 (code 74)** publié sur Supabase Storage (`apk/iptv-home.apk`, `deploy_temp_*` refermées à `false`) et Vercel production (`dpl_Fc64pjPAeEs7SEbiJuseSEs41dM2`, empreinte SHA-256 `388d4fa307b468acb88639f2932d4b1b12edc919f464bf52d5dce126cf38ae07` vérifiée identique via Supabase et Vercel).
  - ⏳ En attente d'essai sur **TV Salon** avant fusion dans `main` (`aae08c2`).

---

## Étape 3 — Recherche sur le serveur maison

- **Partagé (`apps/shared`)** :
  - `SearchGrouping.kt` : `SearchResult` et `groupSearchItems` déplacés dans `shared` (partagés entre serveur et télé).
  - `HomeProtocol.kt` : `SearchRequest`, `SnapSearchResult`, `SearchReply` + tests `HomeProtocolSearchTest.kt`.
- **Serveur (`apps/server`)** :
  - `SearchService.kt` + `ServerGroupsCache` (regroupements manuels relus toutes les 30 s) : recherche Direct, Films et Séries avec caches LRU bornés (max 6 entrées), vitrine d'abord + hors vitrine dès 2 caractères, union stricte masquage global + profil, `version_splits`, `LanguagePrefs.onlyMine`, et recherche par acteur/réalisateur (`personCredits`). Sortie immédiate sur requête vide (aucun index construit inutilement).
  - `Routes.kt` : `POST /v1/search` + tests `SearchServiceTest.kt`, `SearchRouteTest.kt`.
- **Télé (`apps/tv`)** :
  - `HomeServerClient.kt` : `HomeSearchClient.search()` (comptoir 1 500 ms ; `400`/`404`/`503` ne marquent pas le serveur absent).
  - `SearchViewModel.kt` + `SearchSourcesTv.kt` : chemin serveur maison + ne construit plus l'index local sur requête vide + filtrage défensif « masqué gagne » + prise en compte de `remoteResults` dans `currentResult` + vérification de l'empreinte du compte IPTV + tests `HomeSearchTest.kt`.

---

## Étape 4 — Sport sur le serveur maison + mémoire Sport partagée

- **Base de données (`backend/supabase/migrations/20261121000036_sport_memoire_partagee.sql`)** :
  - Tables `sport_profile_preferred` (étoile par équipe avec `group_id` nullable = pierre tombale pour ne jamais ressusciter une étoile retirée) et `sport_profile_learned` (chaînes apprises par ligue, plafonnées à 6), `on delete cascade`, RLS stricte via `can_use_profile(profile_id)`, `revoke all`.
  - 5 RPC `security definer set search_path = public` : `sport_memory_get`, `sport_set_preferred`, `sport_learn_channel`, `sport_forget_learned`, `sport_merge_memory` (fusion sans perte au premier lancement, boucles bornées à 200 équipes / 50 ligues / 6 chaînes).
  - Testée sur vrai PostgreSQL 16 dans `backend/test/sport-memory.test.ts` (isolation entre foyers/profils, fusion sans perte, pierres tombales, plafond 6).
- **Partagé (`apps/shared`)** :
  - `SyncModels.kt` / `SyncClient.kt` : `SportMemory` + les 5 appels RPC.
  - `SportBoardHelpers.kt` : helpers purs multiplateformes (`scheduleSources`, `fullWeekFor`, `sportLeagues`, `visibleSportChannels`, `guideProbes`, `confirmedBy`).
  - `HomeProtocol.kt` : `SnapMatch`, `SnapSportChannel`, `SnapSportCandidates`, `SportBoardRequest`, `SportBoardReply` + tests `HomeProtocolSportTest.kt`.
- **Serveur (`apps/server`, v0.4.0)** :
  - `SportService.kt` : cache partagé LNH/ESPN borné (250 URLs / 24 h) avec vérification HTTP `200..299`, classements, chaînes Sport dédiées (`sport_categories` : distinction `null` automatique vs `[]` vidé, pas de mise en cache sur erreur Supabase, isolation par compte IPTV), sonde EPG en tâche de fond (purgée des matchs passés), boucle adaptative (3 min si match en direct ou < 15 min, sinon 15 min), parallélisation des lectures dans `board()` sans jamais bloquer sur Xtream.
  - `Routes.kt` : `POST /v1/sport/board` et `POST /v1/sport/invalidate?profileId=...` + tests `SportServiceTest.kt`, `SportRouteTest.kt`.
- **Télé (`apps/tv`, 0.45.0 / code 74)** :
  - `HomeServerClient.kt` : `HomeSportClient.sportBoard()` et `invalidateSport(profileId)` (`400`/`404`/`502`/`503` ne marquent pas le serveur absent ; `connectTimeout` borné à 1 500 ms).
  - `SportData.kt` : `AndroidSportStore` étendu (fusion unique atomique vers Supabase sans perte hors-ligne), `SportPrep.prewarm` désactivé quand le serveur maison est actif, `SportViewModel` serveur d'abord + `hideScores`/`reveal(matchId)` locaux instantanés + protection « masqué gagne » quand `prefs == null` + `collectLatest` sur `Dispatchers.IO` + synchro Supabase en secours local et sur étoile / apprentissage / « Pas le bon match » + tests `HomeSportTest.kt`.

---

## Revue de code indépendante & validation

Une IA indépendante a audité l'intégralité du diff Étapes 3 + 4 et relevé **8 bloquants** (tous sur l'Étape 4) :
1. « Masqué gagne » et course sur `prefs == null` / changement de `prefs` dans `SportViewModel`.
2. `sport_categories = []` confondu avec `null`, cache 6 h sur erreur Supabase, et mélange de comptes IPTV dans `extraRef`.
3. Fuites mémoire `urlCache`/`confirmedByMatch` non bornées + `loop()` bloqué à 3 min.
4. Réponses HTTP 4xx/5xx LNH/ESPN non vérifiées avant mise en cache.
5. `prefs.drop(1).collect` et `learned()` sur le fil principal TV.
6. Incompatibilité `profileId` (corps JSON vs query param) sur `/v1/sport/invalidate`.
7. Data race sur `anyOffline` + blocage de `board()` sur `extraLock` risquant de marquer le serveur `Absent`.
8. Mémoire partagée non relue en secours local + écrasement hors-ligne dans `learned`/`wrongChannel`.

**Tous les 8 bloquants + 3 mineurs ont été corrigés**, couverts par des tests de régression, et la seconde passe de revue a rendu le verdict **PRÊT**.

### Résultats des batteries de tests (100 % verts)
- **Backend Postgres (`npm test -w backend`)** : **31 fichiers / 274 tests réussis** (dont `sport-memory.test.ts`).
- **Partagé (`:shared:jvmTest`)** : **100 % verts** (dont `HomeProtocolSearchTest` et `HomeProtocolSportTest`).
- **Serveur (`:server:test`)** : **72 tests réussis** (dont `SearchServiceTest`, `SearchRouteTest`, `SportServiceTest`, `SportRouteTest`).
- **Télé (`:tv:testDebugUnitTest`)** : **626 tests réussis** (dont `HomeSearchTest` et `HomeSportTest`).
- **Assemblages** : `:server:installDist` (v0.4.0) et `:tv:assembleRelease` (0.45.0 / code 74, R8 + lintVital) réussis.
