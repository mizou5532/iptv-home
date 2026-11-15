# Enquête sur l'essai télé 0.4.0 (TCL QM85)

Rapport de l'agent A (télécommande, écran noir, épisode, ouverture du direct, lenteur, diagnostic), branche
`plan-4-series`. Rien n'a été publié : pas de nouvelle version, pas d'APK, aucun service en ligne touché.

## En bref, pour le propriétaire

On n'avait aucun moyen de « jouer » l'app à la télécommande sans la télé. C'est corrigé : l'ordinateur simule
maintenant la télé et appuie sur les flèches et OK comme toi, sur les vrais écrans (Direct, Séries, fiche, lecture).
Chaque problème ci-dessous a d'abord été reproduit ainsi (le test échouait), puis corrigé (le test passe).

| Ce que tu as vu | Cause trouvée | État |
|---|---|---|
| Direct : les flèches gauche/droite font « disparaître » le curseur | Une règle mal posée rendait les **listes elles-mêmes** sélectionnables : le curseur allait sur la liste entière, qui n'a aucun dessin de sélection | **Confirmé et corrigé** |
| Séries : OK sur un épisode, écran noir une fraction de seconde, puis retour à la fiche | L'écran de lecture lisait un état « arrêté » **d'avant** le démarrage et se refermait aussitôt | **Confirmé et corrigé** |
| Plein écran du direct noir (son présent) jusqu'à un nouvel appui | Type de surface vidéo (« SurfaceView ») mal pris en charge quand elle passe de l'aperçu au plein écran | **Hypothèse forte, non vérifiée** (impossible sans la télé) ; corrigé par une autre surface (« TextureView ») |
| Direct à l'ouverture : seulement « Favoris », vide | Les réglages du profil arrivaient avant la lecture du catalogue et l'écran affichait ce calcul incomplet | **Confirmé et corrigé** |
| Chaque catégorie lente à s'afficher | Pendant la mise à jour, tout le catalogue était recalculé chaque seconde et bloquait l'écran ; chaque passage du curseur relisait le disque | **Confirmé et corrigé** (en partie : voir plus bas) |
| Après Direct → Séries, plus rien ne bouge | Le curseur disparaissait avec l'ancien écran | **Trouvé par les nouveaux tests et corrigé** |
| Séries : seulement les premières rangées | Une rangée pas encore chargée n'avait rien de sélectionnable ; Bas échouait et annulait son chargement | **Confirmé et corrigé** (avant le partage du travail ; l'agent C reprend ce sujet) |
| Guide « Programme indisponible » | Un seul titre mal encodé (accents en Latin-1) faisait échouer tout le guide d'une chaîne | **Une cause confirmée et corrigée** (avant le partage du travail ; l'agent B reprend le guide) |

### Le calque de diagnostic caché (pour les photos)

Pour l'ouvrir ou le fermer, n'importe où dans l'app :

- appuie **3 fois de suite, en moins de 2 secondes**, sur la touche **Info**, **Menu** ou **0** de la télécommande ;
- si ta télécommande n'a aucune de ces touches : fais **Haut, Haut, Bas, Bas, Gauche, Droite, Gauche, Droite** en
  moins de 6 secondes (les flèches gardent leur effet normal pendant ce temps).

Un petit cadre jaune apparaît en haut à droite. Il montre : la version, l'écran (« Direct · plein écran »…), le
dernier élément sélectionné visible, l'état du lecteur (inactif, tampon, prêt, fin), si la vue vidéo est
rattachée, **si la première image est arrivée sur cette vue**, la taille de la vidéo et le dernier code d'erreur.
Il n'affiche **jamais** d'adresse, de nom d'utilisateur ni de mot de passe.

Photo utile : en plein écran du direct, si l'écran est noir, le cadre dit si la première image est arrivée
(« oui » = l'image existe mais ne se voit pas ; « non » = l'image n'arrive pas).

### Ce qu'il reste à vérifier sur la télé

1. Le plein écran du direct n'est plus noir (une chaîne, puis plusieurs, puis Haut/Bas).
2. Flèches dans le direct : catégorie → chaînes → boutons de l'aperçu et retour, sans jamais perdre le curseur.
3. OK sur un épisode lance bien la lecture, et Retour revient à la fiche.
4. Ouverture du direct : catégories et favoris tout de suite ; passer d'une catégorie à l'autre est rapide.
5. Le calque de diagnostic s'ouvre avec ta télécommande (dire quelle touche a marché).
6. Fluidité de l'image avec la nouvelle surface vidéo, surtout le sport (voir « Risque » plus bas).

---

## Détails techniques

### Banc d'essai (A)

- `apps/tv/build.gradle.kts`, `apps/gradle/libs.versions.toml` : `ui-test-junit4` et `ui-test-manifest` (BOM Compose
  2026.06.01, soit 1.11.x), Robolectric 4.18, `isIncludeAndroidResources = true`. L'image Android 35 de Robolectric
  (`android-all-instrumented:15-robolectric-14663667-2-i7`) est une dépendance Gradle (configuration
  `robolectricSdk`) passée en mode hors ligne : Robolectric ne télécharge rien au lancement.
- `apps/tv/src/test/resources/robolectric.properties` : SDK 35, écran télé `w960dp-h540dp-land-television-xhdpi`
  (1920×1080 px). Avec l'écran par défaut (320×470 px), la géométrie du focus ne ressemblait pas à la télé.
- `apps/tv/src/test/kotlin/home/iptv/tv/ui/TvUi.kt` : touches envoyées par la fenêtre de l'activité (vrai chemin
  Android), mode clavier forcé (`InputModeManager`), `focusedTexts()`, `until()` avec diagnostic, `testMain()`.
- `HomeHarness.kt` : `HomeForProfile` (rendu `internal`) avec la barre des sections, une vraie `LiveSessions`, un vrai
  `EpisodePlayback`, `ProgressQueue`, `ProgressFlusher`, et des faux : synchro, magasin local, catalogue de séries,
  progression, lecteur (`FakePlayer`, jamais Media3).
- `HarnessTest` : témoin (le banc déplace bien le focus entre éléments simples).
- Piège rencontré : sous Robolectric, le fil principal ne tourne que quand le test le fait tourner. Une attente dont
  la condition ne lit pas l'arbre Compose (par exemple « le faux lecteur a reçu play ») laissait une reprise de
  coroutine postée sur le fil principal sans jamais l'exécuter : le test de l'épisode restait en « Loading »
  1 fois sur 3. `until()` appelle `waitForIdle()` à chaque tour. Artefact de test uniquement.
- Environnement : Maven Central répondait 404/429 par intermittence. Les commandes ont été lancées avec un script
  d'initialisation hors dépôt qui met le miroir Google de Maven Central en tête
  (`maven-central.storage-download.googleapis.com/maven2`). Rien de cela n'est dans le dépôt.

### Symptôme 1 — focus perdu à gauche/droite (direct) — confirmé

- Preuve : `LiveDpadTest.droite_depuis_une_categorie_va_sur_une_chaine` (et 2 autres) : après Droite, aucun nœud
  focalisé (`null`). Bissection (`MiniRoute`, hors dépôt) : la seule différence nécessaire était
  `Modifier.focusProperties { canFocus = true }` sur le conteneur de `LiveRoute`.
- Cause : `focusProperties { canFocus = … }` posé sur un conteneur sans cible de focus s'applique aux premières cibles
  rencontrées dessous, c'est-à-dire aux groupes de focus des `LazyColumn`. Avec `canFocus = true`, la liste des
  chaînes devient elle-même focalisable : Droite y pose le focus, sans dessin. Ajouté en B2 (panneau Sources),
  d'où « 0.2.0 marchait ». Même motif dans `App.kt` (accueil Séries sous une fiche, fiche sous la lecture).
- Correctif : `Modifier.focusBlockedWhen(blocked)` (`live/LiveFocus.kt`) = groupe de focus qui annule l'entrée
  (`onEnter { cancelFocusChange() }`) quand le fond est recouvert. Utilisé aux 3 endroits.
- Tests verts : `LiveDpadTest` (5 tests, dont aperçu → boutons Sources/Arrêter → plein écran sans 2e flux → Retour
  qui rend le focus à la chaîne).

### Symptôme 2 — plein écran noir — hypothèse forte, non vérifiée

- Ne se reproduit pas sous Robolectric (pas de vraie composition graphique).
- Indices du propriétaire : son présent, image absente même après 15 s ; Haut/Bas (nouvelle chaîne) montre l'image ;
  OK (affiche les commandes) fait apparaître l'image. Lecture du code : en passant de l'aperçu au plein écran, le
  même lecteur (`started = true`) est rattaché à une nouvelle `PlayerView`, et rien ne se dessine par-dessus
  (« Connexion… » n'apparaît pas). Dès que Compose dessine quelque chose au-dessus (commandes, « Connexion… »), l'image
  apparaît. Cela désigne le « trou » de la SurfaceView (région transparente) non recalculé pour une SurfaceView
  nouvellement ajoutée dans un `AndroidView`, et non une panne du décodeur (qu'un calque ne réparerait pas).
- Correctif choisi : **TextureView**. `res/layout/player_view.xml` (`app:surface_type="texture_view"`, seule façon de
  la choisir), gonflée par `newPlayerView()` (`player/PlayerScreen.kt`). L'image est composée dans la hiérarchie des
  vues : pas de trou à percer, et le passage aperçu → plein écran n'est qu'un changement de vue cible. L'ordre
  rattacher-la-nouvelle puis détacher-l'ancienne (équivalent de `PlayerView.switchTargetView`) est conservé ;
  `ExoPlayer.clearVideoTextureView` n'efface que la vue courante.
- Pourquoi pas une seule vue persistante : il faudrait déplacer un `AndroidView` entre deux arbres (aperçu et plein
  écran), ce qui le détache de la fenêtre et recrée sa surface ; plus risqué et plus gros.
- Risque : la TextureView coûte un peu plus au processeur graphique et ne gère pas le HDR ; pour des flux IPTV 1080p
  SDR, c'est l'usage courant. À surveiller sur la télé : fluidité du sport. Si elle déçoit, on reviendra à la
  SurfaceView avec un redessin forcé à la première image (le calque dira si la première image arrive).
- Test : `PlayerViewTest` (rouge : `SurfaceView` ; vert : `TextureView`, sans commandes, écran gardé allumé).
- Diagnostic : `ExoPlayerPlayer` expose `diagState`, `viewAttached`, `firstFrameOnView` (remis à non à chaque
  rattachement, oui à `onRenderedFirstFrame`), `videoSize`, `lastErrorCode` (`errorCodeName` seulement).

### Symptôme 3 — l'épisode se referme aussitôt — confirmé

- Preuve : `SeriesDpadTest.ok_sur_un_episode_lance_la_lecture_et_la_garde` : rouge (aucune lecture ouverte, focus
  revenu sur l'épisode), vert après correctif ; rouge de nouveau en retirant le correctif.
- Cause (`series/EpisodePlayerScreen.kt`) : `LaunchedEffect(request) { begin(); begun = true }` puis
  `LaunchedEffect(state, begun) { if (begun && state is Idle) onClose() }`. `state` venait de `collectAsState` :
  la recomposition qui voit `begun = true` lit encore l'`Idle` d'avant `begin()`, et ferme. La fermeture appelle
  `playback.stop()`, qui annule le chargement de la fiche : rien ne joue.
- Correctif : un seul effet `begin(request)` puis `playback.state.first { it is Idle }` (lu sur le flux, après le
  démarrage) puis fermeture. Les autres appels à `stop()` (sortie de l'écran, onStop, changement de profil) sont
  légitimes et inchangés.
- `combinedClickable` (OK court / appui long) fonctionne à la télécommande : vérifié par le même test.

### Symptôme 8 — ouverture du direct : « Favoris » seul et vide — confirmé

- Preuve : `LiveBrowseViewModelTest.a_l_ouverture_jamais_de_favoris_seuls_et_vides_avant_la_lecture_du_cache` avec
  une lecture du cache plus lente que la première reconstruction (cas d'un gros catalogue) : rouge
  (`[fav]` publié, favoris vides car l'index des groupes est vide), vert après correctif.
- Cause : `start()` lance l'observation des préférences avant la lecture du cache ; la reconstruction déclenchée
  publiait des sections sans catégories et un index de groupes vide.
- Correctif : `rebuild()` ne publie rien avant la première lecture du cache (`cacheRead`).
- Le « OK nécessaire pour changer de catégorie » venait du symptôme 1 (focus sur la liste) : couvert par
  `LiveDpadTest.bas_puis_droite_va_sur_une_chaine_de_la_nouvelle_categorie`.

### Symptôme 9 — lenteur à chaque catégorie — confirmé, corrigé en partie

- Aucune lecture réseau au focus : seulement le cache SQLite, sur le fil d'entrée-sortie. Mais :
  - pendant le rafraîchissement, `onProgress` relisait tout le catalogue et reconstruisait tous les groupes toutes
    les secondes (ou toutes les 25 catégories), en tenant le verrou de l'écran ; le chargement d'une catégorie
    attendait ce verrou. Preuve : `pendant_un_long_rafraichissement_le_cache_n_est_pas_relu_a_chaque_categorie`
    (rouge : 202 relectures pour 200 catégories) ;
  - chaque passage du curseur relisait la section, sans délai ni mémoire. Preuves :
    `parcourir_les_sections_au_focus_ne_charge_que_celle_ou_l_on_s_arrete` (rouge : 5 lectures pour 3 passages),
    `revenir_sur_une_section_deja_vue_est_immediat` (rouge).
- Correctifs (`live/LiveBrowseViewModel.kt`) : relecture au plus toutes les 15 s pendant le rafraîchissement (la
  première tout de suite) ; `focusSection()` sélectionne tout de suite mais ne lit qu'après 150 ms sans mouvement
  (OK reste immédiat) ; mémoire des 24 dernières sections, valable pour un état donné (catalogue, masques, groupes).
- Reste : sur un catalogue énorme, la reconstruction complète (groupes) reste coûteuse ; à mesurer sur la télé.
  Note pour l'agent B : la relecture espacée touche `onProgress`, voisin de l'état « Mise à jour ».

### Autres problèmes de télécommande trouvés par les tests

- Direct → Séries (ou l'inverse) par la barre : la pastille focalisée disparaît avec l'ancien écran, plus rien n'a
  le focus, Bas ne fait rien. Correctif : `App.kt` redonne le focus à la pastille de la nouvelle section. Test :
  `SectionBarDpadTest` (rouge sans le correctif : focus `null`).
- Séries, rangées non chargées : voir le tableau (carte « Chargement… » focalisable, `SeriesHomeScreen.kt`).
  Test : `SeriesDpadTest.bas_parcourt_toutes_les_rangees_de_categories` (rouge : seules les rangées 1 à 4 atteintes).

### Travail fait avant le partage, dans les sujets confiés aux agents B et C

À prendre en compte à la fusion de `fix-epg-refresh` et `fix-series-rows` :

- `e080595` (shared, `XtreamApi.shortEpg`) : un titre Base64 non UTF-8 levait `MalformedInputException`, non
  attrapée, et vidait tout le guide ; décodage entrée par entrée, Latin-1 en secours, titre en clair accepté,
  `end_timestamp` accepté. Tests `XtreamApiTest.shortEpgKeepsTheGuideWhenOneTitleIsNotUtf8`,
  `shortEpgAcceptsEndTimestampAndPlainTitles`. Cause confirmée en test, **non vérifiée sur ce fournisseur**.
- `87e14a4` (`SeriesHomeScreen.kt`, `SeriesHomeViewModel.kt`) : carte d'attente focalisable et plus de plafond de
  20 séries par rangée (`SeriesHomeViewModelTest` adapté : 30).
- Mes essais sur « Mise à jour » (rafraîchissement partagé entre profils, délai de 30 s sur la liste des masquées)
  ont été retirés de `LiveSessions.kt` et `LiveRefresher.kt` (`430c396`) ; ils figurent dans l'historique
  (`0665f33`) si l'agent B veut s'en servir.

### Vérifications (15 nov. 2026, après `ccc8175`)

- `cd apps && ./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest :tv:assembleDebug --rerun-tasks -q`
  (avec le miroir Maven hors dépôt) : vert. 174 tests partagés, 258 tests télé (dont 13 tests d'écran à la
  télécommande), APK de débogage construit.
- `npm test -w backend` : 167 tests verts (Postgres 16 local). `npm test -w web/config` : 124 tests verts.
- Pas de changement de version, pas d'APK de publication, rien de publié.
