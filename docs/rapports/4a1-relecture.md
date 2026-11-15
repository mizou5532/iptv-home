# Relecture indépendante 4a.1 (correctifs après l'essai télé 0.4.0)

*15 novembre 2026 · branche `plan-4-series` · périmètre : `git diff 91618a4..HEAD -- apps` et les rapports
`tele-4a-enquete.md`, `tele-4a-guide-maj.md`, `tele-4a-series-rangees.md`. Lecture du code seulement : aucun
Gradle lancé, aucun code modifié.*

## Verdict

Les trois correctifs principaux visent la bonne cause et sont corrects dans leur principe :

- **Focus** : `focusBlockedWhen` (groupe de focus + `onEnter { cancelFocusChange() }`) corrige bien le piège de
  `focusProperties { canFocus = … }` posé sur un conteneur, qui rendait les `LazyColumn` elles-mêmes focalisables.
- **Épisode** : attendre `Idle` sur le flux après `begin()` est juste, car `begin()` passe l'état à `Loading` ou
  `Failed` avant de rendre la main.
- **Écran noir** : TextureView est une hypothèse raisonnable. L'ordre « rattacher la nouvelle vue puis détacher
  l'ancienne » tient, et `detach` ne touche jamais la vue courante.
- **Le reste** : le rafraîchissement partagé, borné à 10 minutes, est sain. Le chargement en parallèle garde l'ordre
  du fournisseur, ne demande jamais une catégorie masquée et protège ses écritures. Aucun journal et aucun écran
  ajoutés ne montrent d'adresse ni d'identifiant.

**Rien de critique.** Il reste cinq points importants, dont trois à corriger avant le prochain essai télé (1, 2, 3) :

1. le panneau « Sources » s'ouvre sans focus (piège à la télécommande) ;
2. une réponse `{}` du fournisseur peut maintenant vider tout le cache du direct ;
3. le guide complet est lu et analysé sur le fil principal, et l'heure du serveur est redemandée à chaque guide.

Pas de conflit entre les agents : aucun correctif n'a été défait par un autre. On trouve seulement deux délais
de 10 minutes empilés (c'est voulu) et un reste de code mort (voir « Mineur »).

---

## Critique

Aucun.

---

## Important

### I1. Le panneau « Sources » ne reçoit jamais le focus à son ouverture (piège à la télécommande)

- **Où** : `apps/tv/src/main/kotlin/home/iptv/tv/live/SourcesPanel.kt:61` ; `live/LiveRoute.kt:187-195` ;
  `live/LiveFocus.kt:67-68` ; `player/PlayerScreen.kt:104-111`.
- **Problème** :
  - `LiveRoute` fournit les copies du groupe par `produceState(null) { vm.groupOf(…) }`, et `groupOf` passe par
    `withContext(io)`. À la première composition du panneau, la liste est donc **toujours vide**.
  - `LaunchedEffect(Unit) { first.requestFocus() }` échoue alors, car aucun élément ne porte `first`. Rien ne
    redemande le focus quand les copies arrivent.
  - `focusBlockedWhen` empêche seulement d'**entrer** dans le fond. Il ne chasse pas un focus déjà présent dedans.
- **Effet sur la télé** :
  - Depuis l'aperçu : le focus reste sur le bouton « Sources » du fond. Haut, Bas et Gauche naviguent derrière le
    panneau, et OK sur une autre chaîne lance une lecture pendant que le panneau est ouvert.
  - En plein écran : les commandes restent affichées sous le panneau, le focus reste sur leurs boutons, et
    `PlayerScreen.onKeyEvent` ne regarde pas `panelOpen`.
  - Seul Retour en sort. Aucun test ne couvre ce chemin : `LiveDpadTest` n'ouvre jamais le panneau.
- **Correctif** :
  1. Dans `SourcesPanel` : `LaunchedEffect(items.isNotEmpty()) { withFrameNanos {}; runCatching { (if (items.isEmpty()) close else first).requestFocus() } }`,
     avec un `FocusRequester` sur « Fermer », qui existe dès la première image.
  2. Dans `PlayerScreen.onKeyEvent` : `if (controlsVisible || panelOpen) return false`. Dans `LivePlaybackState`,
     `OpenSources` devrait aussi masquer les commandes (`controlsVisible = false`).
  3. Test Robolectric : aperçu, Droite vers « Sources », OK. Le focus doit être sur une copie (`"source"`) et
     Haut ou Bas ne doivent pas changer de chaîne. Faire la même chose en plein écran.

### I2. `TrackPanel` : même risque (focus demandé avant que la liste ne soit composée)

- **Où** : `apps/tv/src/main/kotlin/home/iptv/tv/series/TrackPanel.kt:53`.
- **Problème** :
  - `LaunchedEffect(Unit) { first.requestFocus() }` vise un élément de `LazyColumn`. Les éléments d'une liste
    paresseuse sont composés pendant la mesure, souvent après l'exécution de l'effet.
  - C'est pour cela que le reste de l'app attend une image (`withFrameNanos {}`) avant de demander le focus.
  - Si la demande échoue, le focus reste sur « Audio et sous-titres », dans les commandes sous le panneau.
- **Correctif** : `LaunchedEffect(Unit) { withFrameNanos {}; runCatching { first.requestFocus() } }`, plus un test
  (lecture, Haut, Droite vers « Audio et sous-titres », OK : le focus doit être sur une piste).

### I3. Une réponse `{}` à `get_live_categories` vide maintenant tout le cache du direct

- **Où** : `apps/shared/src/commonMain/kotlin/home/iptv/shared/xtream/XtreamApi.kt:298-300` (agent C) et
  `apps/shared/src/commonMain/kotlin/home/iptv/shared/catalog/CatalogRepository.kt:62-74`.
- **Problème** :
  - L'agent C a ajouté `root is JsonObject && root.isEmpty() -> emptyList()` dans `items()`, pour les séries.
    Ce code est commun au direct.
  - Avant, `{}` levait `XtreamError.Unexpected` : la mise à jour échouait et le cache restait intact.
  - Maintenant, `refreshLive` reçoit 0 catégorie. `stale` contient alors **toutes** les catégories en cache, qui
    sont supprimées avec leurs chaînes. Les séries sont protégées par `refreshIfStale(isEmpty = …)`, pas le direct.
  - Même effet pour `get_live_streams` : `{}` vide la catégorie au lieu de la compter « sans réponse ».
  - Un panneau surchargé (3 demandes à la fois maintenant) ou un compte suspendu peut répondre `{}`.
- **Correctif** :
  1. `items(root, emptyObjectIsEmpty = false)`, à `true` seulement pour `seriesCategories` et `series`.
  2. Dans `refreshLive` : si `remote` est vide alors que le cache contient des catégories, lever une erreur (cache
     gardé, message « Mise à jour impossible »). C'est la même règle que « une réponse vide n'écrase jamais le
     cache » côté séries.
  3. Ajouter un test dans `CatalogRepositoryTest` (`{}` puis `[]` : le cache reste).

### I4. Le guide complet est analysé sur le fil principal, et l'heure du serveur est redemandée à chaque guide

- **Où** :
  - `apps/tv/src/main/kotlin/home/iptv/tv/live/epg/EpgRepository.kt:61` (portée = portée de la session,
    `Dispatchers.Main.immediate`, `MainActivity.kt:37`) et `:146-190` ;
  - `apps/shared/src/commonMain/kotlin/home/iptv/shared/xtream/XtreamApi.kt:207, 216-224, 259-273`.
- **Problème** :
  1. `load()` appelle `api.shortEpg` / `api.fullEpg` sans changer de fil. Ktor reprend sur le fil de l'appelant.
     `bodyAsText()`, `Json.parseToJsonElement` et `parseEpg` (Base64 de chaque titre) tournent donc sur le fil
     principal.
     - C'était négligeable pour le guide court, mais `get_simple_data_table` renvoie tout le guide de la chaîne
       (souvent plusieurs jours, avec les descriptions) : des centaines de Ko analysés sur le fil principal de la
       TCL, à chaque chaîne sans guide court utile.
     - Cela peut aller jusqu'à **6 demandes par chaîne** (3 copies × court + complet), même pour une chaîne qui
       n'a aucun guide (fréquent).
  2. `serverOffsetSeconds()` ne garde en mémoire qu'un décalage trouvé. Si le panneau ne donne pas `server_info`,
     ou si l'appel échoue, **chaque** guide au format « dates seulement » refait un appel à `player_api.php`. Cela
     double les demandes, et en cas de lenteur chaque appel peut attendre jusqu'à 15 s avec un des 2 jetons du
     guide pris.
- **Correctif** :
  - Injecter `io` dans `EpgRepository` et faire `request { withContext(io) { block() } }` (ou analyser dans
    `XtreamApi.fetch` via `withContext(Dispatchers.Default)`).
  - Garder aussi le résultat négatif, par exemple `serverOffsets[server] = 0L` avec une date, et un nouvel essai
    au plus toutes les 30 min.
  - Garder en mémoire « pas de guide complet » par source : après un `fullEpg` vide, ne plus le demander pendant
    1 h.

### I5. Séries : boucle sans fin de relectures quand les rangées à l'écran pèsent plus de 40 000 affiches

- **Où** : `apps/tv/src/main/kotlin/home/iptv/tv/series/SeriesHomeViewModel.kt:164-173` (`nextRow`), `:176-197`
  (`fetchRow`), `:204-214` (`trimRows`).
- **Problème** :
  - `trimRows` ne retire jamais une rangée **à l'écran**, mais peut retirer une rangée **préchargée** (sous
    l'écran), y compris celle qui vient d'arriver.
  - Si les rangées à l'écran plus celles préchargées dépassent `MAX_CARDS_IN_MEMORY` (une grosse catégorie
    « Toutes les séries » de 30 000 titres et une voisine de 15 000), la rangée préchargée est retirée aussitôt.
  - `nextRow` la redonne alors (ni en mémoire ni en échec) : `fetchRow` la relit, la retire, et ainsi de suite.
  - Chaque tour lit SQLite, convertit des dizaines de milliers de cartes et reconstruit l'accueil : la boucle
    occupe le processeur sans fin tant que l'écran reste là.
- **Correctif** : ne retirer, dans `trimRows`, que les rangées hors de l'ensemble voulu (`shown + below` calculé
  par `nextRow`). Ou alors noter « retirée faute de place » (comme `retryLater`) et ne pas la redemander avant son
  retour à l'écran. Ajouter un test : 2 rangées de 30 000 séries, `seriesOf` appelé au plus une fois par rangée.

---

## Mineur

| # | Où | Problème | Correctif |
|---|---|---|---|
| M1 | `shared/.../xtream/XtreamApi.kt:276-280` | `decodeTitle` : un titre en clair d'un seul mot dont la longueur est un multiple de 4 (« Football », « Live », « Cine ») est valide en Base64. Il est décodé en caractères illisibles (secours Latin-1). | Décider pour toute la réponse : si un titre contient un caractère hors Base64, la réponse est « en clair », et on garde aussi le texte brut quand le décodage donne des caractères de contrôle. |
| M2 | `shared/.../xtream/XtreamApi.kt:331` | `catch (TimeoutCancellationException)` attrape aussi le délai **extérieur** (les 10 min de `LiveSessions`) quand l'appel est directement dans son bloc (`userInfo()`). Il le transforme en `Unreachable` : le calque affiche « échec (Unreachable) » au lieu de « trop longue ». | Dans ce `catch`, appeler `currentCoroutineContext().ensureActive()` avant de lever `Unreachable`. |
| M3 | `shared/.../catalog/CatalogRepository.kt:84-113` | Le jeton du `Semaphore` est rendu **avant** l'écriture. Pendant un `onProgress` long (relecture du catalogue, ~5 s sur la TCL), les demandes continuent et leurs listes s'accumulent en mémoire en attendant le verrou. Une coroutine est aussi lancée par catégorie dès le départ. | Écrire à l'intérieur de `withPermit`, ou appeler `onProgress` hors du verrou d'écriture (signal regroupé). Au plus `parallelism` listes en attente. |
| M4 | `tv/.../live/LiveBrowseViewModel.kt:43, 164, 178` | Le paramètre `publish` et `publishCatalog` sont morts depuis que `LiveSessions` publie lui-même (valeur par défaut `{}`). Un futur appelant croirait publier deux fois. | Supprimer `publish` et `publishCatalog` du ViewModel. |
| M5 | `tv/.../live/LiveBrowseViewModel.kt:107` | Une section réellement vide est relue depuis le disque à chaque passage du focus, car la condition de sortie exige des chaînes. | Tester `_loaded.value.key == key` au lieu de `_streams.value.isNotEmpty()`. |
| M6 | `tv/.../live/LiveBrowseViewModel.kt:241` | Pendant une reconstruction, chaque changement de section relance **tout** le calcul (index des groupes compris), alors que seule la liste de la section dépend de la sélection. Coûteux en défilant vite sur un gros catalogue, comme le signale l'agent A. | Si seule la sélection a changé, garder `index` et recalculer seulement `sectionStreams`. |
| M7 | `tv/.../live/LiveBrowseViewModel.kt` (`indexInputs`) | `indexInputs` est écrit sous `viewLock` sur IO et lu sur le fil principal dans `cachedStreams`, sans `@Volatile`. | `@Volatile`, ou lecture sous `cacheLock`. |
| M8 | `tv/.../live/LiveBrowseScreen.kt` + `focusSection` | Bas puis Droite très vite (dans les 150 ms, plus la lecture disque) : la colonne des chaînes est vide, donc Droite ne fait rien et il faut appuyer de nouveau. | Retenir un « Droite en attente » et déplacer le focus à l'arrivée des chaînes, ou garder la liste précédente jusqu'à la nouvelle. |
| M9 | `tv/.../series/SeriesHomeScreen.kt:162` | Commentaire périmé : « s'annule si la rangée quitte l'écran ». Une demande partie va maintenant au bout. | Mettre le commentaire à jour. |
| M10 | `tv/.../series/SeriesHomeScreen.kt` (`PlaceholderCard`) | Sur « Indisponible pour l'instant », OK ne fait rien : on ne peut réessayer qu'en quittant la rangée puis en y revenant. | `onClick` sur la carte : `vm.loadCategory(id)` (qui vide `retryLater`). |
| M11 | `tv/.../series/SeriesHomeScreen.kt:261-265` (`CardFocus`) | `requesters` garde un `FocusRequester` par affiche composée, sans limite. Les rangées montrent maintenant des catégories entières. | Retirer l'entrée dans un `DisposableEffect` de la carte, ou borner la table (LRU). |
| M12 | `tv/.../player/ExoPlayerPlayer.kt:91, 145` | `currentView` garde une référence forte à une vue (contexte de l'activité) si `release()` arrive sans `detach`. | `release()` : `currentView = null; viewAttached = false`. |
| M13 | `tv/.../live/LiveRoute.kt:127` | Le guide continue d'être suivi (nouvel essai toutes les ≤ 5 min, jusqu'à 6 demandes) après le passage à Séries, car rien n'appelle `observe(null)` en quittant le direct. | `DisposableEffect(epg) { onDispose { epg?.observe(null) } }`. |
| M14 | `IptvApp.kt:47` + budgets | Mise à jour (3) + guide (2) = 5 demandes, soit la limite par défaut d'OkHttp par serveur. Une fiche ou une rangée de séries attend dans la file d'OkHttp et son délai Ktor (15 ou 45 s) court pendant ce temps : on obtient « Indisponible » à tort pendant le premier remplissage. | Régler `maxRequestsPerHost` explicitement (6 à 8) ou réduire le guide à 1 jeton pendant la mise à jour. Écrire le budget total en un seul endroit. |
| M15 | `tv/.../series/SeriesHomeScreen.kt:127` | Le message bref d'échec de mise à jour (`refreshNotice`) ne s'affiche que dans la barre du Direct. Sur Séries, « Mise à jour… » vient d'un autre indicateur (`ui.loading`). Deux mécanismes, deux comportements. | Acceptable. À noter dans PASSATION, ou exposer le même message aux deux barres. |
| M16 | `tv/build.gradle.kts:82` | `debugImplementation(ui-test-manifest)` ajoute une `ComponentActivity` exportée au manifeste **de débogage**. | Ne jamais installer l'APK de débogage chez le propriétaire (le rappeler dans PASSATION §6). |
| M17 | Tests : `tv/src/test/.../ui/SeriesDpadTest.kt:55` | La dernière assertion (`focusedTexts()?.any { "Episode" } != true`) passe aussi quand **rien** n'a le focus, c'est-à-dire justement un piège à la télécommande. | Mettre un `testTag` sur la zone des touches d'`EpisodePlayerScreen` et vérifier qu'elle a le focus ; ensuite Retour doit rendre le focus à « Episode 1 ». |
| M18 | Tests : `LiveDpadTest`, `HomeHarness` | `FakePlayer` n'est pas un `ExoPlayerPlayer` : `PlayerSurface` n'est jamais composée. Le passage TextureView aperçu → plein écran (rattacher puis détacher) n'est pas exercé. Les tests vérifient seulement le type de vue (`PlayerViewTest`). Le plein écran ne vérifie pas non plus que Haut et Bas changent de chaîne. | Test Robolectric qui compose aperçu puis plein écran avec un `ExoPlayerPlayer` (sans `play`), et vérifie `viewAttached` et que l'ancienne vue n'a plus le lecteur. Ajouter Haut et Bas en plein écran (log du faux lecteur). |
| M19 | Tests : écrans non couverts | Aucun test pour : panneau Sources (I1), TrackPanel (I2), cartes « Épisode suivant », « Erreur » et « Terminé » d'`EpisodePlayerScreen`, pastille « Changer de profil » puis Qui regarde, Réessayer et Retour de la fiche indisponible. `WhoIsWatching` et `PairingScreen` sont sains à la lecture du code : focus posé sur une rangée non paresseuse, et aucun élément focalisable à l'appairage, où Retour quitte. | Ajouter ces parcours au banc d'essai, I1 et I2 en premier. |
| M20 | Tests : horloge réelle | Les tests d'écran utilisent `Dispatchers.IO` et des attentes de 3 à 15 s en temps réel : ils peuvent devenir instables sur une machine chargée. | Acceptable. Si un test devient instable, injecter un répartiteur de test dans `HomeRig`. |

---

## Reporté (connu, non bloquant)

- **Écran noir en plein écran** : la TextureView n'est pas vérifiable sans la télé. Il faut la confirmer sur la
  TCL avec le calque (ligne « 1re image sur cette vue »). Surveiller la fluidité du sport. Plan B prévu par
  l'agent A : SurfaceView et redessin forcé à la première image.
- **Guide** : si le calque dit « aucun programme » partout, il faudra lire le guide XMLTV (`xmltv.php`), un gros
  fichier à analyser hors du fil principal et à garder sur disque.
- **Pas d'état « guide en chargement »** distinct de « Programme indisponible » (`LivePreview.GuideLine`).
- La mise à jour du direct ne démarre qu'à l'ouverture de l'onglet Direct. Un profil qui arrive directement sur
  Séries ne rafraîchit jamais le direct.
- Après un échec, la mise à jour n'est réessayée qu'au prochain changement de profil ou au prochain lancement.
- **Gros catalogues** : la reconstruction complète des groupes reste coûteuse (relecture au plus toutes les 15 s).
  À mesurer sur la télé (voir M6).
- **Nombre de demandes simultanées accepté par le fournisseur** : `XtreamLiveSource.parallelism = 3` est à
  confirmer (rapport B). Repasser à 2 ou 1 si des catégories « sans réponse » apparaissent.

---

## Vérifications faites (rien à signaler)

- **Fermeture de l'épisode** (`EpisodePlayerScreen.kt:76-82`) :
  - `begin()` est synchrone jusqu'à `Loading` ou `Failed`, donc `first { Idle }` ne peut plus voir l'`Idle`
    d'avant le démarrage ;
  - à la sortie, `onDispose → stop()` met `Idle`, mais la reprise de l'effet est postée et l'annulation passe
    avant : au pire, `close()` remet `null` à une valeur déjà nulle ;
  - le `stop()` d'`onStop` ferme bien la lecture, ce qui est voulu.
- **Vue vidéo** : la nouvelle `AndroidView` est attachée (et `update`, donc `attach`, exécuté) pendant
  l'application des changements, et l'`onRelease` de l'ancienne arrive ensuite. `clearVideoTextureView` ne vide
  que la vue courante. `firstFrameOnView` est remis à non à chaque nouvelle vue.
- **Rafraîchissement partagé** (`LiveSessions.kt:75-116`) :
  - un seul `Deferred`, protégé par un verrou ;
  - l'auditeur de chaque session est retiré dans `finally` ;
  - un échec est rendu comme une valeur (`Result`), donc il n'annule jamais la portée parente ;
  - `catalogDone` est mis dès le succès ;
  - la publication est lancée une seule fois, à part ;
  - le délai du ViewModel (10 min 30 s) sert de filet sous celui de la session (10 min). C'est cohérent, pas en
    double.
- **Chargement en parallèle** (`CatalogRepository.kt`) :
  - l'ordre du fournisseur est gardé par l'index ;
  - les catégories masquées pour tout le monde ne sont jamais demandées (règle 7) ;
  - `done`, `failed` et `streamCount` ne sont modifiés que sous `writes` ;
  - une annulation arrête tout ;
  - un délai d'une catégorie est converti en `Unreachable` par `fetch`, donc il ne fait pas tomber les autres.
- **Analyse du guide** : aucune exception ne s'échappe (formats inattendus, Base64, dates, millisecondes). La
  durée du cache est bornée entre 1 s et 5 min, dans une table LRU de 100 sources. Le drapeau `now_playing` ne
  sert que si les heures ne donnent rien.
- **Mémoire des sections du direct** : 24 entrées, invalidées dès que le catalogue, les masques ou les groupes
  changent. Rien n'est publié avant la première lecture du cache (`cacheRead`).
- **Boucle des rangées de séries** : une seule boucle à la fois (`pumpRunning` est réglé sous le même verrou que
  le choix de la rangée suivante). Le travail est borné à l'écran plus 2 rangées, jamais une rangée masquée. Une
  demande partie va au bout. La boucle est annulée avec la portée de l'onglet.
- **Sécurité** :
  - le calque n'affiche que des états, des étiquettes de focus fixes ou des libellés de boutons, `errorCodeName`,
    la taille de la vidéo, la version et des compteurs ;
  - les messages de la barre du haut sont des constantes ;
  - `Diagnostics.refresh` ne donne que le nom de la classe d'erreur ;
  - les journaux Media3 sont coupés, et aucun `Log`, `println` ou `printStackTrace` n'a été ajouté ;
  - le décalage horaire du serveur est gardé en mémoire seulement (clé = adresse du serveur, jamais écrite) ;
  - les fixtures de test sont inventées (`panel.test`, `img.example.test`) ;
  - la réponse de `player_api.php` lue pour l'heure du serveur contient le nom d'utilisateur, mais seul
    `server_info` est lu.
- **Tests d'écran** :
  - les touches passent par le vrai chemin Android (`decorView.dispatchKeyEvent`) en mode clavier forcé, sur un
    écran télé de 960×540 dp : la recherche de focus est réelle ;
  - `LiveDpadTest` échoue bien quand une `LazyColumn` prend le focus, car un nœud sans texte donne une liste vide ;
  - `SeriesDpadTest.bas_parcourt…` exige 5 rangées distinctes atteintes, donc il ne passe pas par hasard.

---

## Correctifs de la relecture (15 novembre 2026, par Claude)

Chaque point a eu son test rouge avant la correction, puis vert.

| Point | Correction | Tests |
|---|---|---|
| I1 | `SourcesPanel` : focus d'abord sur « Fermer » (présent dès l'ouverture), puis sur la copie en lecture dès qu'elle arrive. `PlayerScreen` ignore les touches quand le panneau est ouvert. `OpenSources` referme les commandes. | `LiveDpadTest.panneau_sources_depuis_l_apercu…`, `…en_plein_ecran…` |
| I2 | `TrackPanel` : une image d'attente avant la demande de focus, repli sur « Fermer ». | `SeriesDpadTest.panneau_audio_et_sous_titres…` |
| **Nouveau piège** trouvé par ces tests | Dans les deux lecteurs, **Retour était avalé** : Compose le traitait comme « sortir du groupe de focus » et envoyait le focus sur le fond focalisable du lecteur. Il fallait appuyer deux fois pour fermer un panneau ou les commandes. Corrigé par `Modifier.backKey` (`live/LiveFocus.kt`), posé sur le fond de `PlayerScreen` et de `EpisodePlayerScreen`. | `LiveDpadTest.plein_ecran_un_seul_retour…`, `SeriesDpadTest.panneau_audio…` |
| I3 | `items()` : `{}` n'est une liste vide que pour les séries ; pour le direct c'est une réponse inattendue (cache gardé). `refreshLive` refuse une liste de catégories vide quand le cache est rempli. Une vraie liste vide `[]` pour une catégorie continue de faire foi (une chaîne retirée ne revient pas : test existant `source_disappeared…`). | `XtreamApiTest.emptyObjectOnLiveListsIsUnexpected`, `CatalogRepositoryTest.liste_des_categories_vide…`, `…categorie_que_le_fournisseur_dit_vide…`, `…categorie_vide_au_premier…` |
| I4 | `EpgRepository` : demandes et analyse sur `io`. Un guide complet vide n'est pas redemandé avant 1 h. `XtreamApi` : heure du serveur absente retenue 30 min. | `EpgRepositoryTest.les_demandes_du_guide_tournent_hors_du_fil…`, `…guide_complet_vide_n_est_pas_redemande…`, `XtreamApiTest.serverTimeMissingIsAskedOnlyOnce` |
| I5 | `trimRows` ne retire jamais une rangée voulue (à l'écran ou chargée d'avance). Sans la correction, le test ne se terminait jamais (boucle sans fin confirmée). | `SeriesHomeViewModelTest.de_tres_grosses_rangees…` |
| `SeriesDpadTest:55` | L'assertion échoue maintenant quand plus rien n'a le focus. | — |

Suites complètes après correctifs : **193 partagés, 285 télé, 167 backend, 124 web, 0 échec**, APK de débogage construit.

Reportés : les points mineurs M1 à M20 et la section « Reporté » ci-dessus.
