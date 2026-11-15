# Films 4B Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Livrer Films sur la télé avec reprise, Ma liste et organisation iPhone.
**Architecture:** Cache à la demande sur le modèle des séries. Métadonnées films distinctes, progression typée et RPC additives compatibles 0.4.1. Media3 partagé ; jamais deux lecteurs simultanés.
**Tech Stack:** Kotlin Multiplatform, SQLDelight, Compose TV, Media3, PostgreSQL16, TypeScript/Vite.
**Spec:** `docs/superpowers/specs/2026-11-15-etape4b-films-design.md`, validée par le propriétaire le 15 novembre.

## Global Constraints

- Interface française ; aucun identifiant IPTV, adresse de flux ou service_role dans les sorties.
- Pas de production : PostgreSQL jetable, fixtures fictives. Aucune publication cloud ou application sans nouvel accord.
- Cache catégories/listes 24 h, fiches 6 h ; jamais de liste globale ni catégorie masquée demandée.
- Progression toutes les 30 s et pause/sortie/veille ; vu à 90 %, reprise zéro si 90 % ou moins de 30 s restantes.
- Compatibilité des files/cache et appels RPC de 0.4.1 ; clé de progression profil + type + identifiant.
- Pas de worktree : checkout cloud isolé existant, branche plan-4-films. Tests rouges puis verts, revue par tâche, commits/push fréquents autorisés par PASSATION.

## Review Focus

- Film et épisode de même identifiant : file, suppression et retour backend distincts (tâches 1–2).
- Catégorie masquée pendant une requête : ne pas afficher ni jouer la réponse tardive (tâche 3).
- Mise à jour depuis 0.4.1 : cache et file conservés, anciennes RPC valides (tâches 1–2).
- Plusieurs centaines de milliers de titres : aucune requête globale, rangées bornées et date récente valide (tâches 2–3).
- Arrêt/reprise/changement de profil rapide : aucun flux concurrent, aucune position écrite sur le mauvais profil (tâche 3).

## Task 1: Backend films

**Files:** créer `backend/supabase/migrations/20261115000012_movies.sql`, `backend/test/films.test.ts`; modifier `backend/README.md`.
**Interfaces:** publier `publish_movie_categories(h,cats)` et `org_movie_categories(h,p)` sur le modèle séries. `org_movie_hide_all_except(h,p,keep)`, `org_set_movie_category_order(h,p,ids)`, étendre `org_set_hidden` pour vod_category. `org_set_movie_list(p,movie_id,on_,movie_name,movie_cover,movie_category)` et `org_reorder_movie_list(p,ids)` ; `profile_prefs` ajoute movie_order et movie_list (ids), les métadonnées de liste se lisent par `movie_list(p)` -> tableau JSON d'objets item_id/movie_name/movie_cover/movie_category. `record_movie_progress(p,item_id,position_s,duration_s,client_updated_at,movie_name,movie_cover,movie_category)` ; `continue_movies(p,max_rows=20)` -> lignes watch_progress ; `movie_progress(p,item_id)` -> tableau 0/1 ligne ; `set_movie_watched(p,item_id,watched,movie_name,movie_cover,movie_category,duration_s)` -> void. Champs movie_* ajoutés à watch_progress, ne pas détourner series_*. Préserver les anciens RPC, adapter leurs projections si nécessaire et corriger leur identité de regroupement type+id.
- [x] Écrire tests rouges : autorisations/foyers, masquage, ordre, liste/métadonnées, progression collision movie/episode, anciennes RPC 0.4.1 et vu/non vu sans remontée temporelle.
- [x] Exécuter Vitest films sur PostgreSQL local et observer les erreurs fonctionnelles attendues.
- [x] Implémenter migration additive et contrat README ; 0010 reste locale seulement.
- [x] Relancer films puis suite backend entière ; toutes les assertions passent.
- [x] Auto-revue, commit, rapport avec preuves rouge/vert ; revue indépendante.

## Task 2: Catalogue et progression Kotlin

**Files:** `apps/shared/src/commonMain/kotlin/home/iptv/shared/xtream/{XtreamApi,XtreamModels}.kt`, nouveau `movies/MovieRepository.kt`, `catalog/Movies.sq` et migration `2.sqm` ; `progress/{ProgressClient,ProgressQueue,ProgressQueueCodec}.kt`, nouveau `progress/MovieClient.kt`, `sync/SyncClient.kt`; tests JVM homologues ; adapter uniquement les usages séries nécessaires à la distinction de type.
**Interfaces:** `MovieCategory(id,name)`, `MovieSummary(id,categoryId,name,cover,year,genre,plot,rating,addedAt:Long?,extension:String,durationS:Int?)`, `MovieInfo(summary:MovieSummary)` ; XtreamApi `movieCategories()`, `movies(categoryId)`, `movieInfo(movieId)` ; MovieSource.of(api), MovieRepository(db,source,clock) `categories(hidden)`, `moviesOf(categoryId,hidden)`, `info(movieId,categoryId?)`, `cachedCategory(movieId)`, `cachedMovies():List<MovieSummary>`. ProgressEntry ajoute itemType="episode", movieName/movieCover/movieCategory facultatifs ; ProgressRow idem métadonnées. MovieClient interface et SupabaseMovieClient : `publishCategories(h,cats)`, `continueWatching(p,max=20)`, `progress(p,id)`, `list(p)`, `setList(p,movie,on)`, `setWatched(p,movie,watched)` ; mapping vers RPC tâche 1, SupabaseProgressClient.record route itemType=movie vers record_movie_progress. ProfilePrefs ajoute movieOrder/movieList par défaut vides/null comme séries.
- [ ] Préparer JDK21 complet, SDK36 et Gradle en conservant TLS/empreintes ; tests avant implémentation.
- [ ] Tests rouges parsing tolérant, refus catégorie vide, payload invalide conserve cache, fraîcheur 24h/6h et migration 0.4.1 ; collisions de file et anciens JSON lus comme épisodes ; corps RPC exacts.
- [ ] Implémenter signatures et stockage selon les patterns du dépôt ; données d'affiches nettoyées.
- [ ] Exécuter :shared:jvmTest, puis compilation/tests télé pour compatibilité ; résultat vert.
- [ ] Auto-revue, commit et rapport ; revue indépendante.

## Task 3: Parcours Films télé complet

**Files:** nouveaux `apps/tv/src/main/kotlin/home/iptv/tv/movies/{MovieHomeData,MovieHomeViewModel,MovieHomeScreen,MovieDetailViewModel,MovieDetailScreen,MoviePlayback,MoviePlayerScreen,MovieRows}.kt` (regrouper seulement si responsabilité cohérente) ; modifier `App.kt`, `MainActivity.kt`, `IptvApp.kt`, `data/LocalStore.kt` ; tests `movies/` et `ui/MovieDpadTest.kt`.
**Interfaces:** `MovieCatalog` adapte MovieRepository ; MovieHomeState persiste données films par profil ; `PlayMovieRequest(movieId,categoryId,restart)` ; MoviePlayback ferme le lecteur existant et sauvegarde la file avant arrêt. Réutiliser PlayerController, ProgressFlusher et panneau de pistes ; pas de refonte épisode. Factories/bindings dans MainActivity selon modèles séries. HomeTab.MOVIES("movies").
- [ ] Tests rouges accueil/reprise/liste/récents limités au cache et dates valides ; préférences inconnues, masquage pendant requête, ancienne référence sans catégorie ; fixture grand catalogue avec toutes rangées atteignables.
- [ ] Implémenter accueil, fiche et cache d'état, publication des catégories et préférences ; états de chargement/erreur sélectionnables, Retour restitue focus.
- [ ] Tests rouges lecture/reprise/30s/pause/veille/profil/fin/single lecteur, vu/non vu et état local en attente ; implémenter coordinateur/écran de lecture et actions fiche, avec mise à jour immédiate après retour.
- [ ] Tests Robolectric flèches/OK/Retour sur onglets, rangées, fiche, commandes et panneau audio ; implémenter intégration Films dans application.
- [ ] Suites shared + TV puis assembleDebug ; auto-revue, commit, rapport, revue indépendante.

## Task 4: Organisation Films iPhone

**Files:** `web/config/src/org/{api,view}.ts` et fichiers de types/démo existants, tests `web/config/test/org-movies.test.ts` et tests onglets.
**Interfaces:** RPC de tâche 1 ; troisième section Films avec catégories vod_category, ordre et masquage tous/profil, mêmes interactions que Séries. Démo purement locale, Ma liste gérée télé uniquement.
- [x] Tests rouges sélecteur, RPC, masquage/ordre, erreurs et démo.
- [x] Implémenter Films sans dupliquer le moteur organisation ; conserver Direct et Séries.
- [x] npm test -w web/config et npm run build -w web/config ; auto-revue, commit, rapport (`docs/rapports/4b/tache-4-iphone.md`). Revue indépendante : à faire par le coordinateur.

## Task 5: Validation intégrée et livraison préparée

**Files:** versions `apps/tv/build.gradle.kts`, `web/config/public/version.json`, `PASSATION.md`, feuille de route, journal et rapport `docs/rapports/4b-validation.md`.
- [ ] Revue globale fraîche de la branche ; corriger défauts importants avec tests de régression.
- [ ] Exécuter backend/web/shared/TV et compilation web ; distinguer tests réellement exécutés et limites.
- [ ] Vérifier la version courante puis préparer 0.5.0/code6 ; assembleRelease et certificat attendu, jamais régénérer la clé.
- [ ] Documenter commandes d'installation Android et résultats, migrations à publier (0012 seule), essai TCL à effectuer et limites ; sauvegarder les commits.
- [ ] Demander uniquement l'accord de mise en ligne quand les artefacts sont prêts. Pas de fusion avant essai TCL réussi, pas de republication de l'environnement.
