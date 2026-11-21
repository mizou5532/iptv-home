# Journal de construction — Serveur maison, étape 2 (Films, Séries, Versions, Fiches, Publication iPhone)

Plan : `docs/superpowers/plans/2026-11-21-serveur-maison-etape2-vod.md`
Spec : `docs/superpowers/specs/2026-11-20-serveur-maison-catalogue-design.md`
Branche : `serveur-maison-etape2-vod` (base `main` @ `aae08c2`).

## Décisions d'architecture (Rulings)
- Ruling: garder `HOME_PROTOCOL = 1` en ajoutant de nouvelles routes sous `/v1/movies/*`, `/v1/series/*`, `/v1/versions/*` — 100 % rétrocompatible avec les télés en 0.43.0 pendant le déploiement du serveur — coût si faux : aucun.
- Ruling: `VersionIndexes` sur le serveur regroupe par titre + année + `version_splits` (sans le cache local `media-meta-2.json` propre à chaque télé) — évite toute dépendance à des fichiers locaux de la télé ; la télé conserve son secours `VersionIndexes` complet si le serveur est absent — coût si faux : les rares titres traduits sans mot commun ne sont fusionnés que si la télé les fusionne ou en secours.
- Ruling: `FileVersionIndexDisk` passe à `sha256Hex` (code commun) au lieu de SHA-1 JVM dans `VersionIndexes.fingerprint()` — permet de garder `VersionIndexes` 100 % dans `commonMain` — coût si faux : l'ancien fichier disque local sera simplement régénéré une fois si le secours local tourne.

## Avancement
- Plan écrit (`docs/superpowers/plans/2026-11-21-serveur-maison-etape2-vod.md`) et branche `serveur-maison-etape2-vod` poussée.
- Tâche 1 : terminée (`49d146a` — `ExtraAccountsCheck`, `CatalogTitlesPublisher`, `VersionIndexes` déplacés dans `apps/shared`, `:shared:jvmTest` + `:tv:testDebugUnitTest` verts).
- Tâche 2 : terminée (`99862ee` — `HomeProtocol.kt` étendu avec `VodManifest`, `SnapMovie`, `SnapSeries`, `SnapMovieInfo`, `SnapSeriesInfo`, `VersionsRequest`/`VersionsReply` et empreintes `rev` ; `HomeProtocolVodTest` vert).
- Tâche 3 : terminée (`2501e93` — `Movies.sq`, `Series.sq`, `MovieRepository.kt`, `SeriesRepository.kt` : remplacement atomique, `wipeMovies`/`wipeSeries`, `saveInfo`/`cachedInfo`, `markFresh`, garde-fou liste vide ; `ReplaceMoviesTest` + `ReplaceSeriesTest` verts).
- Tâche 4 : terminée (`e1f2111` — `InfoCache.kt` : cache partagé 6 h fiches films/séries, `Semaphore(3)`, `beforeFetch`, refus des catégories masquées globalement ; `InfoCacheTest` vert).
- Tâche 5 : terminée (`5229356` — `VodJob.kt` : mise à jour 72 h films/séries vitrine d'abord, respect strict `globalHiddenCategories`, persistance `vod-manifests.json` + `titles-published.json`, `VersionIndexes` + `version_splits`, publication iPhone catégories + titres, `ExtraAccountsCheck` ; `VodJobTest` 6 tests verts).
- Tâche 6 : terminée (`59a3e4d` — `Routes.kt` + `Main.kt` : `/v1/movies/*`, `/v1/series/*`, `/v1/versions/*`, `SERVER_VERSION = "0.2.0"` ; `VodRoutesTest` 6 tests verts + `:server:installDist` OK).
- Tâche 7 : terminée (`HomeServerClient.kt` + `HomeVodClient`, `LocalStore.kt` révisions VOD/Séries, `PerfData.kt` classification `/v1/*` ; `HomeServerClientVodTest` + `PerfDataTest` verts).
