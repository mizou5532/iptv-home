# Recherche (étape 5) — plan de réalisation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal :** une recherche à la télécommande et à la voix, dans la section ouverte (Direct, Séries ou Films), sur toutes les catégories non masquées, tolérante aux accents, mots partiels, fautes et surnoms.

**Architecture :** moteur de recherche pur dans `apps/shared` (index en mémoire, aucune dépendance Android). Les séries et les films sont complétés en arrière-plan dans le cache SQLDelight existant par un « remplisseur » qui réutilise `SeriesRepository.seriesOf` / `MovieRepository.moviesOf`. Côté télé, un écran de recherche par-dessus l'accueil de la section ; pour le Direct, OK ramène dans la liste sur la chaîne avec l'aperçu (choix A du propriétaire).

**Tech Stack :** Kotlin Multiplatform 2.3.21, SQLDelight (cache existant), Compose for TV, Robolectric pour les tests de télécommande, kotlinx-coroutines-test.

**Spec :** `docs/superpowers/specs/2026-11-16-etape5-recherche-design.md` (validée par le propriétaire le 16 nov. 2026, choix A pour le Direct). Lire aussi `PASSATION.md` section 2 (règles absolues).

## Global Constraints

- Version livrée : **0.6.0 / versionCode 7** (`apps/tv/build.gradle.kts`, `web/config/public/version.json`).
- Ne jamais demander au fournisseur une catégorie masquée (profil actif ou « tout le monde ») ; préférences inconnues = aucune demande, aucun résultat.
- « Masqué gagne » : un titre est écarté si **une** de ses catégories connues est masquée.
- Au plus **60** résultats affichés ; < **100 ms** par requête pour **50 000** titres (index déjà construit).
- Fautes : aucune sous 4 lettres, **1** de 4 à 7 lettres, **2** à partir de 8 ; le dernier mot tapé est comparé au **début** des mots du titre, avec la même tolérance.
- Remplissage : **une demande fournisseur à la fois**, pause d'environ **500 ms** entre deux, les demandes de l'écran passent devant, arrêt à `onStop`, reprise au retour.
- Aucun identifiant IPTV ni adresse de flux dans les journaux, rapports ou messages d'erreur. Jamais la clé `service_role`. Aucune migration, aucun changement backend ni page iPhone.
- Textes visibles en français. Noms de tests Kotlin en ASCII. Jamais régénérer la clé de signature.
- Commandes : `cd apps && ./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest :tv:assembleDebug` (relancer une fois si Maven Central renvoie 429) ; `npm test -w backend` ; `npm test -w web/config`.
- Branche `plan-5-recherche` partant de `main`. Commit et push après chaque tâche verte (`git pull --rebase` avant). Tenir `PASSATION.md` section 0, le journal `docs/rapports/journaux/plan-5-journal.md` et ce plan à jour.

## Review Focus

1. **Catégorie masquée pendant le remplissage** : masquée depuis l'iPhone alors que le remplisseur l'a dans sa file → elle n'est jamais demandée, et ses titres déjà en cache disparaissent des résultats aussitôt (test en tâche 3 et en tâche 4).
2. **Frappe rapide** : « c », « ca », « can »… en moins d'une seconde → seul le résultat de la dernière requête est affiché, jamais un plus ancien arrivé en retard (test en tâche 4).
3. **Résultats qui changent sous le focus** (remplissage qui ajoute des titres pendant qu'on est sur une affiche) → le focus reste sur la même affiche si elle existe toujours, sinon sur la première ; jamais perdu (test en tâche 5).
4. **Changement de profil pendant le remplissage** → aucune catégorie masquée du nouveau profil n'est demandée ; l'avancement affiché est recalculé pour le nouveau profil (test en tâche 3).
5. **Chaîne trouvée dont la catégorie a été masquée entre-temps** (OK sur un résultat devenu invisible) → rien ne se lance, message « Chaîne indisponible », focus conservé (test en tâche 5).

---

### Task 1 : Moteur de recherche

**Files :**
- Create : `apps/shared/src/commonMain/kotlin/home/iptv/shared/search/SearchEngine.kt`
- Create : `apps/shared/src/commonMain/kotlin/home/iptv/shared/search/Nicknames.kt`
- Test : `apps/shared/src/jvmTest/kotlin/home/iptv/shared/search/SearchEngineTest.kt`

**Interfaces :**
- Consumes : `searchNorm(s: String): String` (`shared/text/SearchNorm.kt`).
- Produces :
  - `data class SearchItem(val id: String, val title: String, val categoryIds: Set<String>)`
  - `class SearchIndex(items: List<SearchItem>)` avec `fun search(query: String, limit: Int = SEARCH_LIMIT): List<SearchItem>` et `val size: Int`
  - `const val SEARCH_LIMIT = 60`
  - `fun searchWords(text: String): List<String>` (normalise par `searchNorm`, coupe sur espaces et `| : - . / ( ) [ ] ,`, retire les mots vides)
  - `val NICKNAMES: Map<String, List<String>>` dans `Nicknames.kt`

- [x] **Step 1 : écrire les tests rouges** (`SearchEngineTest`), avec ces cas exacts :
  - `accents_et_casse` : index `["Les Canadiens de Montréal"]`, requête `"MONTREAL"` → 1 résultat.
  - `mot_partiel` : `"canadi"` trouve `"Canadiens"`.
  - `ordre_des_mots` : `"montreal canadiens"` trouve `"Les Canadiens de Montréal"`.
  - `une_faute_sur_mot_moyen` : `"canadeins"` trouve `"Canadiens"` ; `"barcelonr"` trouve `"FC Barcelona"`.
  - `pas_de_faute_sous_quatre_lettres` : `"rbs"` ne trouve pas `"RDS"`.
  - `deux_fautes_sur_mot_long` : `"montrael"` (8 lettres) trouve `"Montreal"`.
  - `dernier_mot_en_cours_avec_faute` : `"canade"` trouve `"Canadiens"`.
  - `surnoms` : `"habs"` et `"ch"` trouvent `"Canadiens"` ; `"barca"` trouve `"FC Barcelona"` et `"Barcelone"`.
  - `classement` : pour `"rds"` sur `["RDS 2", "RDS", "Super RDS Info"]` → ordre `RDS`, `RDS 2`, `Super RDS Info` (égal > début > contenu ; à égalité titre le plus court puis alphabétique).
  - `separateurs` : `"FR| SPORT HD"` est trouvé par `"sport"`.
  - `doublons_par_id` : deux `SearchItem` de même `id` → un seul résultat.
  - `limite` : 200 titres correspondants → 60 résultats.
  - `requete_vide` : `""` et `"   "` → liste vide.
  - `stable` : même requête deux fois → même liste dans le même ordre.
  - `performance_50000_titres` : index de 50 000 titres inventés (graine fixe) ; 20 requêtes variées ; chacune < 100 ms après construction (mesure `measureTime`, échauffement de 3 requêtes non mesurées).

- [x] **Step 2 : lancer et voir échouer** — `cd apps && ./gradlew --max-workers=2 :shared:jvmTest --tests '*SearchEngineTest*'` → échec de compilation (classes absentes).

- [x] **Step 3 : implémenter.** Index : mots distincts → liste d'indices d'éléments ; pour chaque mot tapé, trouver les mots de l'index qui correspondent (égal, préfixe, contient, distance d'édition de Damerau bornée ; pour le dernier mot, distance sur préfixe) en parcourant les **mots distincts** regroupés par première lettre et longueur, jamais chaque titre ; intersection des ensembles d'éléments ; score par élément (meilleur type de correspondance de chaque mot). Un mot tapé présent dans `NICKNAMES` cherche aussi ses équivalents (union). Table `NICKNAMES` de départ : `habs, ch, canadien → canadiens` ; `barca, fcb, barça → barcelona, barcelone` ; `psg → paris` ; `mtl → montreal` ; `qc → quebec` ; `nhl → lnh` ; `lnh → nhl`.

- [x] **Step 4 : relancer** la même commande → tous verts.

- [x] **Step 5 : commit** `feat(shared): moteur de recherche tolérant (accents, partiels, fautes, surnoms)`.


Résultat :57385b1 puis correctifdc7f948 relu ;19tests moteur/283shared verts,20requêtes50k sous100ms (max89,659ms). Rapport `docs/rapports/5/tache-1-moteur.md`.

### Task 2 : Sources de recherche (ce qui est cherché, filtré par les masquages)

**Files :**
- Modify : `apps/shared/src/commonMain/sqldelight/home/iptv/shared/catalog/Series.sq` (ajouter `selectAllSeriesTitles`)
- Modify : `apps/shared/src/commonMain/sqldelight/home/iptv/shared/catalog/Movies.sq` (ajouter `selectAllMovieTitles`)
- Modify : `apps/shared/src/commonMain/kotlin/home/iptv/shared/series/SeriesRepository.kt`, `.../movies/MovieRepository.kt`
- Create : `apps/shared/src/commonMain/kotlin/home/iptv/shared/search/SearchSources.kt`
- Test : `apps/shared/src/jvmTest/kotlin/home/iptv/shared/search/SearchSourcesTest.kt`

**Interfaces :**
- Consumes : `SearchItem` (tâche 1), `ProfilePrefs`, `GroupSource`, `LiveGroup`, `buildGroups` (`shared/groups/LiveGroups.kt`).
- Produces :
  - `fun SeriesRepository.searchItems(): List<SearchItem>` et `fun MovieRepository.searchItems(): List<SearchItem>` : tous les titres en cache, un `SearchItem` par id avec **toutes** ses catégories connues (liste et fiche), lecture seule, aucune demande réseau.
  - `fun visibleSearchItems(items: List<SearchItem>, hidden: Set<String>): List<SearchItem>` : écarte un élément dès qu'une de ses catégories est dans `hidden`, ou s'il n'a aucune catégorie.
  - `fun liveSearchItems(sources: List<GroupSource>, groups: Map<String, LiveGroup>): List<SearchItem>` : un `SearchItem` par **groupe** de copies (id = id du groupe, titre = titre du groupe, catégories = celles de ses copies), les copies étant déjà filtrées par `visibleStreams`.

- [x] **Step 1 : tests rouges** : `serie_dans_deux_categories_dont_une_masquee_est_ecartee`, `film_sans_categorie_connue_est_ecarte`, `masquage_profil_et_tout_le_monde` (prefs avec `HiddenItem("series_category","a")` et `HiddenItem("vod_category","b")`), `prefs_inconnues_aucun_element` (l'appelant passe `null` → la tâche 4 renvoie vide ; ici vérifier `visibleSearchItems` avec l'ensemble vide garde tout), `copies_regroupees_un_seul_resultat` (« RDS HD » et « RDS FHD » → 1 élément), `searchItems_lit_le_cache_sans_reseau` (dépôt construit sur une source qui échoue si elle est appelée).
- [x] **Step 2 : lancer et voir échouer** (`--tests '*SearchSourcesTest*'`).
- [x] **Step 3 : implémenter** les deux requêtes SQL (id, nom, catégorie ; jointure avec la catégorie de la fiche si elle diffère) et les fonctions ci-dessus.
- [x] **Step 4 : relancer**, puis toute la suite `:shared:jvmTest` → verte.
- [x] **Step 5 : commit** `feat(shared): sources de recherche filtrées par les masquages`.


Résultat :86cc57e relu/approuvé,7tests sources/290shared verts, rapport `docs/rapports/5/tache-2-sources.md`.

### Task 3 : Remplissage du catalogue en arrière-plan

**Files :**
- Create : `apps/shared/src/commonMain/kotlin/home/iptv/shared/search/CatalogFiller.kt`
- Test : `apps/shared/src/jvmTest/kotlin/home/iptv/shared/search/CatalogFillerTest.kt`

**Interfaces :**
- Consumes : `SeriesRepository.categories(hidden)`, `seriesOf(categoryId, hidden)`, `MovieRepository.categories(hidden)`, `moviesOf(categoryId, hidden)`, leurs règles de fraîcheur existantes.
- Produces :
  - `enum class SearchSection { LIVE, SERIES, MOVIES }`
  - `data class FillProgress(val done: Int, val total: Int) { val complete: Boolean get() = done >= total }`
  - `class CatalogFiller(series: SeriesRepository, movies: MovieRepository, scope: CoroutineScope, pause: Duration = 500.milliseconds)` avec :
    - `val progress: StateFlow<Map<SearchSection, FillProgress>>` (SERIES et MOVIES seulement)
    - `val changed: SharedFlow<SearchSection>` (émis après chaque catégorie ajoutée au cache)
    - `fun start(seriesHidden: () -> Set<String>?, movieHidden: () -> Set<String>?, priority: () -> SearchSection)` ; `fun stop()` ; `suspend fun <T> screenFirst(block: suspend () -> T): T` (une demande de l'écran passe devant : le remplisseur attend qu'elle finisse avant sa prochaine demande).
  - `seriesHidden()` / `movieHidden()` renvoient les catégories masquées **du profil actif** (les siennes et celles de « tout le monde », types `series_category` et `vod_category`) ; `null` = préférences inconnues → rien n'est demandé dans cette section.

- [x] **Step 1 : tests rouges** avec une source fournisseur factice qui compte les appels et détecte la concurrence :
  - `ne_demande_jamais_une_categorie_masquee` (y compris masquée **après** le démarrage, avant son tour) ;
  - `une_seule_demande_a_la_fois` (compteur d'appels simultanés ≤ 1) ;
  - `priorite_a_la_section_affichee` (priority = MOVIES → la première demande est une catégorie de films) ;
  - `demande_de_l_ecran_passe_devant` (`screenFirst` lancé pendant le remplissage : aucune demande du remplisseur ne démarre avant sa fin) ;
  - `echec_reessaye_au_prochain_passage_pas_en_boucle` (une catégorie qui échoue n'est redemandée qu'après `start` suivant) ;
  - `stop_puis_start_reprend_sans_redemander_le_frais` ;
  - `prefs_inconnues_aucune_demande` ;
  - `changement_de_profil_recalcule_l_avancement` (hidden change → total recalculé, aucune catégorie masquée du nouveau profil demandée) ;
  - `avancement_et_changed_emis`.
- [x] **Step 2 : lancer et voir échouer** (`--tests '*CatalogFillerTest*'`).
- [x] **Step 3 : implémenter** : boucle unique ; à chaque tour relire `hidden()` et la liste des catégories, choisir la prochaine catégorie non masquée et non fraîche de la section prioritaire puis de l'autre ; `seriesOf`/`moviesOf` (qui refusent déjà une catégorie masquée) ; pause ; mutex partagé avec `screenFirst`.
- [x] **Step 4 : relancer**, puis `:shared:jvmTest` complet.
- [x] **Step 5 : commit** `feat(shared): remplissage du catalogue en arrière-plan pour la recherche`.


Résultat :e8aeaaf puis f7f0d6b relus/approuvés,15tests remplissage/305shared verts, rapport `docs/rapports/5/tache-3-remplissage.md`.

### Task 4 : Modèle de l'écran de recherche

**Files :**
- Create : `apps/tv/src/main/kotlin/home/iptv/tv/search/SearchViewModel.kt`
- Modify : `apps/tv/src/main/kotlin/home/iptv/tv/series/SeriesRows.kt` (+ ses trois autres usages du filtre `series_category`)
- Test : `apps/tv/src/test/kotlin/home/iptv/tv/search/SearchViewModelTest.kt`

**Interfaces :**
- Consumes : tâches 1 à 3 ; `StateFlow<ProfilePrefs?>` du profil ; `movieHidden()` (`tv/movies/MovieRows.kt`) ; pour le Direct, la liste complète des chaînes du cache et `groupSourceIndex` (`tv/live/Sections.kt`).
- Produces :
  - `fun ProfilePrefs.seriesHidden(): Set<String>` dans `tv/series/SeriesRows.kt` (même forme que `movieHidden()`), et remplacer les quatre copies existantes du filtre `series_category` par cet appel.
  - `data class SearchResult(val id: String, val title: String, val cover: String?, val categoryId: String, val anchorSourceId: String? = null)` (`anchorSourceId` pour le Direct)
  - `data class SearchUi(val section: SearchSection, val query: String = "", val results: List<SearchResult> = emptyList(), val progress: FillProgress? = null, val message: String? = null)`
  - `class SearchViewModel(section: SearchSection, items: suspend () -> List<SearchItem>, details: (String) -> SearchResult?, prefs: StateFlow<ProfilePrefs?>, hiddenFor: (ProfilePrefs) -> Set<String>, changed: Flow<SearchSection>, progress: StateFlow<Map<SearchSection, FillProgress>>, scope: CoroutineScope, io: CoroutineDispatcher)` avec `val ui: StateFlow<SearchUi>` et `fun setQuery(text: String)`.
  - Messages exacts : champ vide → `"Tape quelques lettres ou appuie sur Parler."` ; aucun résultat → `"Rien trouvé pour « <texte> »."` ; préférences inconnues → `"Les réglages du profil ne sont pas encore chargés."` ; remplissage incomplet → ligne séparée `"Catalogue en préparation : <done> catégories sur <total>. D'autres résultats peuvent apparaître."`

- [ ] **Step 1 : tests rouges** : `frappe_rapide_seul_le_dernier_resultat` (requêtes « c », « ca », « can » dont la première est lente → `ui.results` correspond à « can »), `index_reconstruit_quand_le_cache_change` (émission sur `changed` → nouveau titre trouvé sans retaper), `masquage_pendant_la_recherche_retire_le_resultat`, `prefs_inconnues_aucun_resultat_et_message`, `messages_exacts`, `avancement_affiche_tant_que_incomplet`.
- [ ] **Step 2 : lancer et voir échouer** (`:tv:testDebugUnitTest --tests '*SearchViewModelTest*'`).
- [ ] **Step 3 : implémenter** : index construit en `io`, reconstruit sur `changed`/préférences ; anti-rebond court (≈ 120 ms) ; chaque nouvelle requête annule la précédente.
- [ ] **Step 4 : relancer** → vert.
- [ ] **Step 5 : commit** `feat(tv): modèle de l'écran de recherche`.

### Task 5 : Écran de recherche, bouton, voix, Direct (choix A) et intégration

**Files :**
- Create : `apps/tv/src/main/kotlin/home/iptv/tv/search/SearchScreen.kt`
- Modify : `apps/tv/src/main/kotlin/home/iptv/tv/nav/SectionBar.kt` (bouton « Rechercher » après les pastilles)
- Modify : `apps/tv/src/main/kotlin/home/iptv/tv/App.kt` (état « recherche ouverte » par onglet, création du `SearchViewModel`, du `CatalogFiller` unique du processus)
- Modify : `apps/tv/src/main/kotlin/home/iptv/tv/live/LiveBrowseViewModel.kt` et `live/LiveRoute.kt` (révéler une chaîne)
- Modify : `apps/tv/src/main/kotlin/home/iptv/tv/MainActivity.kt` (lanceur de reconnaissance vocale ; `CatalogFiller.stop()` à `onStop`, `start` au retour)
- Modify : `apps/tv/src/main/kotlin/home/iptv/tv/series/SeriesHomeViewModel.kt`, `movies/MovieHomeViewModel.kt` (leurs demandes fournisseur passent par `CatalogFiller.screenFirst`)
- Test : `apps/tv/src/test/kotlin/home/iptv/tv/ui/SearchDpadTest.kt` (harnais `HomeHarness`/`TvUi` existant)

**Interfaces :**
- Consumes : tâche 4.
- Produces :
  - `@Composable fun SearchScreen(vm: SearchViewModel, onOpen: (SearchResult) -> Unit, onBack: () -> Unit, voice: (() -> Unit)?, restoreId: String?)` (`voice == null` → pas de bouton Parler).
  - `fun LiveBrowseViewModel.reveal(categoryId: String, sourceId: String)` : sélectionne la section `"cat:$categoryId"` et retient la chaîne à révéler. Dans `LiveRoute`, quand `loaded.key == "cat:$categoryId"` contient une rangée dont le groupe inclut `sourceId` : focus sur cette rangée puis `host.ok(row, key)` (aperçu). Rangée absente ou catégorie masquée → message `"Chaîne indisponible."`, rien ne se lance.
  - `SectionBar(..., onSearch: () -> Unit)` : bouton texte « Rechercher », focalisable, à droite des pastilles.
  - Voix : `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`, `EXTRA_LANGUAGE = "fr-CA"`, `EXTRA_LANGUAGE_MODEL = LANGUAGE_MODEL_FREE_FORM` ; disponible si `packageManager.queryIntentActivities(...)` n'est pas vide ; le premier texte reconnu remplit le champ.
  - `CatalogFiller.start(seriesHidden = { prefs.value?.seriesHidden() }, movieHidden = { prefs.value?.movieHidden() }, priority = { onglet affiché })`, relancé au changement de profil.

- [ ] **Step 1 : tests rouges (télécommande, Robolectric)** :
  - `recherche_s_ouvre_depuis_chaque_section` (OK sur « Rechercher » depuis Direct, Séries, Films ; le titre indique la section) ;
  - `bas_va_aux_resultats_haut_revient_au_champ` ;
  - `ok_sur_film_ouvre_la_fiche_retour_rend_le_focus_au_meme_resultat_et_garde_le_texte` (même chose pour une série) ;
  - `ok_sur_chaine_revele_la_chaine_dans_le_direct_avec_apercu` (la section de la chaîne est sélectionnée, la rangée a le focus, l'aperçu est demandé ; la recherche est fermée) ;
  - `chaine_devenue_masquee_message_et_rien_ne_se_lance` (Review Focus 5) ;
  - `resultats_qui_changent_sous_le_focus_gardent_le_focus` (Review Focus 3) ;
  - `retour_ferme_la_recherche_focus_sur_le_bouton_rechercher` ;
  - `bouton_parler_absent_sans_service_vocal` ;
  - `etats_vide_et_rien_trouve_focalisables`.
- [ ] **Step 2 : lancer et voir échouer** (`--tests '*SearchDpadTest*'`).
- [ ] **Step 3 : implémenter** l'écran (champ, bouton Parler, liste de chaînes pour le Direct, grille d'affiches réutilisant `Poster` pour Séries/Films, ligne d'avancement), le bouton dans la barre, `reveal`, l'intégration dans `App.kt` (la recherche se dessine par-dessus l'accueil comme une fiche ; ouvrir la recherche depuis Séries/Films ne ferme pas le lecteur du Direct inutilement : rien ne joue dans ces onglets), le remplisseur unique, `screenFirst` dans les deux accueils.
- [ ] **Step 4 : relancer** ces tests, puis **toutes** les suites : `cd apps && ./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest :tv:assembleDebug` → tout vert, dont `LiveDpadTest`, `SeriesDpadTest`, `MovieDpadTest`, `SectionBarDpadTest` inchangés.
- [ ] **Step 5 : commit** `feat(tv): écran de recherche, voix et retour sur la chaîne dans le direct`.

### Task 6 : Validation, version 0.6.0, mise en ligne avec accord, essai télé

**Files :**
- Modify : `apps/tv/build.gradle.kts` (`versionCode = 7`, `versionName = "0.6.0"`), `web/config/public/version.json`
- Create : `docs/rapports/5/validation.md`
- Modify : `PASSATION.md`, `docs/superpowers/plans/2026-11-14-feuille-de-route.md`, ce plan

- [ ] **Step 1 : relecture indépendante de toute la branche** (sous-agent, lecture seule) ; corriger chaque défaut important avec un test rouge puis vert.
- [ ] **Step 2 : suites complètes** : partagé, télé, backend, web, build web → toutes vertes ; noter les nombres exacts dans le rapport.
- [ ] **Step 3 : version 0.6.0/7**, `:tv:assembleRelease`, `apksigner verify --print-certs` → SHA-256 `5d8877ce9c75eebe8d4a0cfb20427cf13dbf443402fc5c055608358d76e615d4`. Noter le SHA-256 du fichier APK.
- [ ] **Step 4 : demander l'accord du propriétaire.** Ensuite seulement : APK dans le compartiment `apk` (règle temporaire limitée au nom exact, supprimée aussitôt, vérifier **0 règle** sur `storage.objects`), `version.json` 0.6.0 sur Vercel avec `index.html` et `vercel.json` inchangés (`projectSettings` `{framework:null, buildCommand:"", installCommand:"", outputDirectory:"."}`). Vérifier l'APK téléchargé par `https://iptv-home-config.vercel.app/iptv-home.apk` identique au fichier signé. Pas de migration.
- [ ] **Step 5 : essai TCL** par le propriétaire : recherche dans chaque section, clavier Google TV, bouton Parler, « habs », « canadi », « barca », OK sur une chaîne (retour dans la liste avec aperçu), fiche film/série puis Retour, avancement du remplissage, fluidité pendant le remplissage. Si réussi : fusion dans `main` (accord permanent). Sinon : cause, test rouge, correction, 0.6.1.
