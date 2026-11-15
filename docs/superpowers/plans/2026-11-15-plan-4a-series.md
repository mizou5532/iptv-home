# Étape 4A — Les séries — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Une section Séries sur la télé (accueil « Reprendre », fiche, lecture avec reprise, épisode suivant automatique, audio/sous-titres) et l'organisation des catégories de séries depuis l'iPhone.

**Architecture:** Le client Xtream gagne les appels séries ; un cache SQLDelight par catégorie (avec fraîcheur) évite de tout télécharger. Une migration 0011 étend le backend : catégories de séries publiées (noms seulement), `org_*` et `profile_prefs` pour la section séries, Ma liste des séries, progression enrichie, `set_watched`. La télé ajoute une barre de sections (Direct / Séries), l'accueil et la fiche, et un lecteur d'épisodes qui enregistre la progression via une file locale.

**Tech Stack:** Kotlin Multiplatform (`:shared`), Compose for TV + Media3 (`:tv`), SQLDelight, supabase-kt 3.6.0, Coil 3, Postgres 16 / Supabase (tests Vitest + pg), TypeScript/Vite (`web/config`).

**Spec:** `docs/superpowers/specs/2026-11-15-etape4a-series-design.md` (lire la section 9 « Précisions »). Contexte : `PASSATION.md`, maquettes `docs/maquettes/project/Accueil-Series.dc.html` et `Fiche-Serie.dc.html`, contrat `backend/README.md`.

## Global Constraints

- Branche `plan-4-series`. **Sauvegarde fréquente** : `git pull --rebase origin plan-4-series` puis commit + push après chaque sous-étape verte ; jamais plus de 30 min sans push. Journal : `docs/rapports/journaux/plan-4-journal.md`.
- Aucune migration en production, aucune publication, aucune fusion dans main sans accord explicite du propriétaire (tâche 8).
- Nouvelle migration **`backend/supabase/migrations/20261114000011_series.sql`** ; ne jamais modifier 0001–0009 (déjà en production) ; 0010 reste locale et non appliquée — 0011 ne doit pas en dépendre.
- Jamais d'identifiant IPTV ni d'adresse de flux dans le code journalisé, les messages, la copie publiée, les tests ou les captures ; jamais la clé `service_role` ; jamais régénérer la clé de signature.
- Une catégorie (live, séries) masquée pour tout le monde n'est **jamais demandée** au fournisseur. « Masqué gagne » partout (accueil, Reprendre, Continuer).
- Supabase coupe à 1 000 lignes : réponses en un seul bloc jsonb/tableau ou paginées sous 1 000.
- Une seule connexion au fournisseur : la lecture d'un épisode ferme d'abord tout lecteur actif (direct ou aperçu) via le `PlayerController` existant.
- Fraîcheur du cache : catégories de séries 1 jour, séries d'une catégorie 1 jour, fiche d'une série 6 h.
- Lecture : position enregistrée toutes les 30 s, à la pause, à l'arrêt, à la sortie et en `onStop` ; « vu » à 90 % (règle backend) ; épisode suivant après **10 s** de décompte, annulable, y compris d'une saison à l'autre.
- Langue audio et sous-titres retenues **sur la télé, par profil, par code de langue** (local, pas de synchro) ; dernier onglet (Direct/Séries) retenu par profil en local.
- Textes visibles en français ; thème sombre via les jetons `IptvTheme` (aucune couleur codée en dur dans les nouveaux écrans) ; tests Kotlin en noms ASCII ; Gradle `--max-workers=2`.

## Review Focus

1. Coupure réseau ou mise en veille pendant la lecture : la dernière position est gardée en local et envoyée au retour ; elle ne recule jamais une position plus récente d'un autre appareil (« le plus récent gagne »). — tâches 3 et 6.
2. Série dont la catégorie devient masquée : elle disparaît de Reprendre / Continuer / Ma liste et ses épisodes ne se lisent plus. — tâches 4 et 5.
3. Fiche fournisseur incomplète (saison sans épisodes, durée absente, épisode sans extension, affiche manquante) : l'écran reste utilisable, rien ne plante. — tâches 2 et 5.
4. Dernier épisode de la dernière saison : pas de décompte « épisode suivant » ; la série passe en « terminée » et quitte Continuer. — tâches 4 et 6.
5. Changement de profil ou retour pendant une lecture : la progression est enregistrée pour le bon profil, aucune lecture ne reprend en arrière-plan. — tâche 6.

---

### Task 1: Backend — section séries, progression enrichie, marquer vu

**Files:** Create `backend/supabase/migrations/20261114000011_series.sql`, `backend/test/series.test.ts` ; Modify `backend/README.md`.

**Interfaces (SQL) :**
- `series_catalog_category(household_id uuid, id text, name text, position int, updated_at timestamptz)`, PK `(household_id,id)`, RLS lecture membre, aucune écriture directe ; `publish_series_categories(h uuid, cats jsonb) returns void` (liste vide = rien ; catégories absentes supprimées) — membre.
- `org_categories(h,p)` existante reste live ; nouvelle `org_series_categories(h uuid, p uuid) returns jsonb` (même forme que `org_categories` : `[{id,name,provider_position,hidden_global,hidden_profile}]`, ordre effectif de la section `series`) ; `org_set_hidden` accepte aussi `item_type = 'series_category'` (remplacer la fonction par `create or replace` dans 0011 sans changer sa signature) ; nouvelles `org_series_hide_all_except(h,p,keep text[]) returns int` et `org_set_series_category_order(h,p,ids text[]) returns void` (section `series`).
- Ma liste : `org_set_series_list(p uuid, series_id text, on_ boolean) returns void` (favorites `item_type='series'`, ajout en fin) ; `org_reorder_series_list(p uuid, ids text[])`.
- `profile_prefs(p)` (`create or replace`, mêmes clés B1/B2) gagne `"series_order": [ids]|null` et `"series_list": [ids]` ; `hidden` inclut déjà les `series_category`.
- `watch_progress` gagne `series_name text`, `series_cover text`, `series_category text` (nullables). `record_progress` : **nouvelle surcharge** avec 3 paramètres facultatifs supplémentaires `series_name text default null, series_cover text default null, series_category text default null` (garder l'ancienne signature pour la télé 0.3.0) ; **refuser `client_updated_at` NULL** (`horodatage-invalide`) dans les deux. Une valeur NULL de nom/affiche/catégorie conserve l'existante.
- `continue_watching(p, max_rows)` : renvoie aussi les trois colonnes et **exclut** les séries dont `series_category` est masquée (globale ou profil).
- `set_watched(p uuid, items jsonb, watched boolean) returns int` : `items = [{item_id, series_id, season, episode, duration_s}]` ; marque/démarque sans modifier `client_updated_at` d'une ligne existante ; une ligne nouvelle prend `client_updated_at = '2000-01-01'` et `position_s = 0` ; `duration_s` absent ou ≤ 0 → 1. Démarquer remet `watched=false` (seule exception à « vu ne redevient jamais faux », documentée).

- [ ] **1. Tests rouges** (`series.test.ts`) : `publication_categories_series_et_lecture_membre` ; `masquer_et_ordre_section_series` (profil et tout le monde, propriétaire seulement pour tout le monde) ; `ma_liste_series_ordre` ; `profile_prefs_contient_series_order_et_series_list` ; `record_progress_refuse_horodatage_nul` ; `record_progress_enrichi_et_ancienne_signature` ; `continue_watching_exclut_categorie_masquee` ; `set_watched_marque_et_demarque_sans_remonter_la_serie` ; `etranger_refuse`.
- [ ] **2. Lancer** `npm test -w backend -- series` — FAIL attendu.
- [ ] **3. Implémenter** (security definer, `search_path` borné, `org_assert` existant, grants explicites, révision `prefs_revision` déclenchée par les tables touchées).
- [ ] **4. Lancer** `npm test -w backend` — toute la suite PASS. README à jour (tables, fonctions, règle « démarquer »).
- [ ] **5. Commit + push** — `feat(backend): section séries, progression enrichie et marquer vu`.

### Task 2: Client Xtream séries et cache local

**Files:** Modify `apps/shared/src/commonMain/kotlin/home/iptv/shared/xtream/XtreamApi.kt`, `XtreamModels.kt`, `XtreamUrls.kt` ; Create `apps/shared/src/commonMain/kotlin/home/iptv/shared/series/SeriesRepository.kt`, `apps/shared/src/commonMain/sqldelight/home/iptv/shared/catalog/Series.sq` (même base `CatalogDatabase`, migration SQLDelight si nécessaire) ; Tests `apps/shared/src/jvmTest/.../xtream/XtreamSeriesTest.kt`, `.../series/SeriesRepositoryTest.kt`.

**Interfaces :**
- Modèles : `SeriesCategory(id, name)`, `SeriesSummary(id, categoryId, name, cover: String?, year: String?, genre: String?, plot: String?, rating: String?)`, `SeriesInfo(summary: SeriesSummary, seasons: List<Season>)`, `Season(number: Int, name: String, episodes: List<Episode>)`, `Episode(id, season: Int, number: Int, title: String, durationS: Int?, extension: String, cover: String?)`.
- `XtreamApi.seriesCategories()`, `series(categoryId)`, `seriesInfo(seriesId)`. `StreamKind.SERIES` et `streamUrl(creds, StreamKind.SERIES, episode.id, episode.extension)`.
- `SeriesRepository(db, api: SeriesSource, clock)` : `suspend fun categories(hidden: Set<String>): List<SeriesCategory>` ; `suspend fun seriesOf(categoryId: String): List<SeriesSummary>` ; `suspend fun info(seriesId: String): SeriesInfo?` ; chaque fonction lit le cache s'il est frais, sinon demande le fournisseur et met à jour ; en cas d'erreur réseau, renvoie le cache même périmé ; ne demande jamais une catégorie de `hidden`.

- [ ] **1. Tests rouges** : parsing de vraies formes Xtream fictives (épisodes en objet indexé par saison, durée en `duration_secs` ou `HH:MM:SS`, extension absente → `mkv`, saison sans épisodes conservée vide, affiche absente) ; repository : fraîcheur 1 j / 6 h en temps virtuel, cache périmé servi hors ligne, catégorie masquée jamais demandée, `CancellationException` relancée.
- [ ] **2. Lancer** `cd apps && ./gradlew --max-workers=2 :shared:jvmTest` — FAIL attendu.
- [ ] **3. Implémenter.**
- [ ] **4. Lancer** la même commande — PASS.
- [ ] **5. Commit + push** — `feat(shared): séries Xtream et cache local par catégorie`.

### Task 3: Progression et préférences séries côté télé

**Files:** Create `apps/shared/src/commonMain/kotlin/home/iptv/shared/progress/ProgressClient.kt`, `ProgressQueue.kt` ; Modify `sync/SyncModels.kt` (`ProfilePrefs` gagne `seriesOrder: List<String>? = null`, `seriesList: List<String> = emptyList()`), `sync/SyncClient.kt` (publication des catégories de séries), `apps/tv/.../data/LocalStore.kt` (file de progression, réglages audio/sous-titres et dernier onglet par profil) ; Tests correspondants.

**Interfaces :**
- `ProgressClient` : `suspend fun record(p: String, entry: ProgressEntry): Boolean`, `suspend fun continueWatching(p: String, max: Int = 20): List<ProgressRow>`, `suspend fun setWatched(p: String, items: List<WatchedItem>, watched: Boolean)`, `suspend fun setSeriesList(p: String, seriesId: String, on: Boolean)`.
- `ProgressEntry(itemId, seriesId, season, episode, positionS, durationS, clientUpdatedAt: Instant, seriesName, seriesCover, seriesCategory)`.
- `ProgressQueue(client, store, clock)` : `fun enqueue(p, entry)` (garde en local la dernière entrée par épisode), `suspend fun flush()` (envoie dans l'ordre, retire seulement après succès), appelé après chaque enqueue, au retour réseau et au démarrage.
- `SyncClient.publishSeriesCategories(householdId: String, cats: List<PublishedCategory>)` (RPC `publish_series_categories`), appelée par la tâche 4 après chaque lecture fraîche des catégories du fournisseur (toutes, masquées comprises, noms seulement).
- `LocalStore` : `audioLang(profileId)`, `subtitleLang(profileId)`, `saveTrackPrefs(...)`, `lastTab(profileId)`, `saveLastTab(...)`.

- [ ] **1. Tests rouges** : file conservée en cas d'erreur réseau puis vidée au retour ; deux entrées du même épisode → la plus récente seule envoyée ; JSON ancien de `profile_prefs` sans clés séries → valeurs par défaut ; `CancellationException` relancée ; aucune URL dans `ProgressEntry`.
- [ ] **2–4.** `./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest` FAIL puis PASS.
- [ ] **5. Commit + push** — `feat(shared): progression des séries avec file locale`.

### Task 4: Sections et accueil Séries sur la télé

**Files:** Create `apps/tv/src/main/kotlin/home/iptv/tv/series/SeriesHomeViewModel.kt`, `SeriesHomeScreen.kt`, `SeriesRows.kt` (fonctions pures), `apps/tv/src/main/kotlin/home/iptv/tv/nav/SectionBar.kt` ; Modify `App.kt` (barre Direct / Séries pour le profil choisi, onglet retenu), `IptvApp.kt`, `MainActivity.kt` ; Tests `series/SeriesRowsTest.kt`, `series/SeriesHomeViewModelTest.kt`.

**Interfaces :**
- `fun buildSeriesHome(progress: List<ProgressRow>, list: List<String>, categories: List<SeriesCategory>, prefs: ProfilePrefs?, infoOf: (String) -> SeriesInfo?): SeriesHome` avec `SeriesHome(resume: ResumeItem?, continueRow: List<ResumeItem>, myList: List<SeriesCard>, newEpisodes: List<SeriesCard>, categoryRows: List<CategoryRow>)`.
- Règles : `resume` = premier de `continueRow` ; un épisode vu → proposer l'épisode suivant si la fiche le connaît, sinon la série reste avec l'état « à jour » ; série entièrement vue → retirée ; masqué gagne (catégorie de la série masquée → absente partout) ; ordre des catégories = `seriesOrder` sinon fournisseur, nouvelles à la fin ; « Nouveaux épisodes » = séries de Continuer/Ma liste dont la fiche a un épisode après le dernier vu, vérifiées au plus une fois par jour, 20 max.
- Écran d'après la maquette 5 : bloc Reprendre (Reprendre · Recommencer l'épisode · Tous les épisodes, « Ensuite : … »), rangées horizontales `LazyRow` d'affiches (Coil, ~240 px, repli titre sur fond), focus visible ; le bloc Reprendre s'affiche dès que la progression locale/serveur est connue, les rangées se complètent ensuite.

- [ ] **1. Tests rouges** (`SeriesRowsTest`) : `reprendre_est_la_serie_la_plus_recente` ; `episode_vu_propose_le_suivant` ; `serie_terminee_disparait` ; `categorie_masquee_retire_la_serie_partout` ; `ordre_categories_profil_puis_fournisseur` ; `nouveaux_episodes_limites_a_20`. ViewModel : ouverture sans réseau → cache local, jamais d'écran vide sans message.
- [ ] **2–4.** `./gradlew --max-workers=2 :tv:testDebugUnitTest :tv:assembleDebug` FAIL puis PASS.
- [ ] **5. Commit + push** — `feat(tv): sections et accueil Séries`.

### Task 5: Fiche d'une série

**Files:** Create `apps/tv/src/main/kotlin/home/iptv/tv/series/SeriesDetailViewModel.kt`, `SeriesDetailScreen.kt` ; Tests `series/SeriesDetailViewModelTest.kt`.

**Interfaces :** `SeriesDetailViewModel(seriesId, repo, progress, queue, prefs)` : `state: StateFlow<SeriesDetailState>` (résumé, saisons avec `watchedCount/total`, épisodes avec `Watched | InProgress(posS, durS) | Upcoming`), `selectSeason(n)`, `toggleMyList()`, `markSeasonWatched(watched: Boolean)`, `markEpisodeWatched(id, watched)`, `play(episodeId, fromStart: Boolean)` (émet une demande de lecture vers la tâche 6).

- [ ] **1. Tests rouges** : saison initiale = celle de l'épisode en cours, sinon la première non vue ; « 4 / 8 vus » ; marquer la saison vue puis la démarquer ; durée absente acceptée ; fiche absente (réseau) → cache, sinon message « Fiche indisponible » sans planter ; série d'une catégorie masquée → lecture refusée.
- [ ] **2–4.** FAIL puis PASS (même commande).
- [ ] **5. Commit + push** — `feat(tv): fiche d'une série`.

### Task 6: Lecture d'un épisode

**Files:** Create `apps/tv/src/main/kotlin/home/iptv/tv/series/EpisodePlayback.kt` (logique pure : position, épisode suivant, décompte), `EpisodePlayerScreen.kt`, `TrackPanel.kt` ; Modify `player/PlayerController.kt`, `ExoPlayerPlayer.kt` (exposer position/durée/fin et pistes audio/sous-titres, sélection par code de langue) ; Tests `series/EpisodePlaybackTest.kt`.

**Interfaces :** `EpisodePlayback(controller, queue, store, clock, scope)` : `start(series: SeriesInfo, episode: Episode, resumeAtS: Int?)`, `onTick(posS, durS)`, `onEnded()`, `cancelNext()`, `stop()` ; `state: StateFlow<EpisodePlaybackState>` (`Playing`, `NextCountdown(secondsLeft, next: Episode)`, `Finished`, `Failed`). `fun nextEpisode(info: SeriesInfo, current: Episode): Episode?` (pure, passe à la saison suivante).

- [ ] **1. Tests rouges** : reprise à la position enregistrée ; progression mise en file toutes les 30 s et à l'arrêt ; fin → décompte 10 s puis épisode suivant (fermeture avant ouverture) ; Annuler → pas d'enchaînement ; dernier épisode → `Finished` sans décompte ; arrêt/onStop/changement de profil → position enregistrée pour le bon profil, rien ne reprend ; choix de langue mémorisé et réappliqué.
- [ ] **2–4.** FAIL puis PASS ; `:tv:assembleDebug` OK.
- [ ] **5. Commit + push** — `feat(tv): lecture des épisodes, reprise et épisode suivant`.

### Task 7: Organiser les catégories de séries sur l'iPhone

**Files:** Modify `web/config/src/org/view.ts`, `web/config/src/org/api.ts`, `web/config/src/org/demo.ts` (ou un nouveau `series-view.ts` si `view.ts` devient trop gros) ; Tests `web/config/test/org-series.test.ts`.

**Interfaces :** sélecteur « Direct / Séries » dans l'onglet Catégories ; en Séries : `orgSeriesCategories`, `orgSetHidden(..., 'series_category', ...)`, `orgSeriesHideAllExcept`, `orgSetSeriesCategoryOrder` ; même confirmation « tout le monde » qu'en B1 (adaptée : « catégories de séries »). Si aucune catégorie publiée : « Ouvre la section Séries sur la télé une fois pour envoyer la liste. »

- [ ] **1–4.** Tests rouges (arguments des appels, confirmation, liste vide) puis verts ; `npm test -w web/config && npm run build -w web/config` ; capture Playwright iPhone `?demo` sans erreur.
- [ ] **5. Commit + push** — `feat(web): organiser les catégories de séries`.

### Task 8: Relecture d'ensemble, version 0.4.0, mise en ligne et essai

- [ ] **1.** Relecture indépendante de toute la branche (modèle le plus capable), une vague de corrections, toutes les suites vertes.
- [ ] **2.** `versionCode = 4`, `versionName = "0.4.0"` ; `version.json` ; `:tv:assembleRelease` et certificat SHA-256 `5d8877ce9c75eebe8d4a0cfb20427cf13dbf443402fc5c055608358d76e615d4`.
- [ ] **3. Demander l'accord du propriétaire**, puis : migration 0011 en production (contrôle advisors), page (`tools/web/construire-page.sh`, procédure `docs/rapports/deploiement-page.md`), APK ; règles d'écriture temporaires supprimées.
- [ ] **4.** Essai du propriétaire (et de sa sœur) : reprise, épisode suivant, audio/sous-titres, organisation des catégories, rapidité ; rapport `docs/rapports/tele-4a-series.md` ; fusion dans main avec accord.
