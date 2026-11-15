# Essai télé 0.4.0 — guide « Programme indisponible » (5) et « Mise à jour » permanent (6)

Agent B, branche `fix-epg-refresh` (rebasée sur `plan-4-series` après la fin de l'agent A). 15 novembre 2026.
Aucune version n'a été changée, rien n'a été publié, la production Supabase n'a pas été touchée.

## En bref (pour le propriétaire)

**« Mise à jour » qui ne s'efface jamais.** La télé relisait toute la liste des chaînes chez le fournisseur,
**une catégorie à la fois**. Avec des centaines de catégories, cela durait très longtemps. Pire : **changer de
profil arrêtait tout et recommençait depuis le début**. Avec deux ou trois changements de profil, la mise à jour ne
finissait donc jamais, et « Mise à jour… » restait affiché.

Ce qui change :
- la télé demande **3 catégories à la fois** au lieu d'une (environ 3 fois plus rapide) ;
- changer de profil **ne relance plus rien** : le nouveau profil suit la mise à jour déjà en cours ;
- une mise à jour **ne peut plus durer plus de 10 minutes** : au-delà, elle s'arrête et les chaînes déjà
  enregistrées restent affichées ;
- si la mise à jour échoue, s'arrête ou laisse des catégories de côté, un **court message** remplace
  « Mise à jour… » en haut de l'écran pendant 30 secondes (par exemple « Mise à jour impossible : chaînes déjà
  enregistrées »). Il n'empêche jamais de naviguer.

**Guide « Programme indisponible ».** Je n'ai pas accès à ton fournisseur (c'est voulu : aucun identifiant ici). Je
n'ai donc pas pu voir sa vraie réponse. J'ai trouvé plusieurs raisons possibles, toutes réelles chez certains
fournisseurs, et la télé sait maintenant gérer chacune :
- certains fournisseurs donnent les heures des émissions **sans l'heure « universelle »**, seulement l'heure de
  leur pays : la télé jetait alors **tout** le guide. Elle sait maintenant convertir ;
- certains renvoient les **émissions du matin** au lieu de celles en cours : la télé demande alors **le guide complet**
  de la chaîne et y trouve l'émission en cours ;
- pour une chaîne en plusieurs copies (HD, FHD, 4K…), le fournisseur ne relie souvent le guide qu'**à une seule
  copie** : la télé essaie maintenant **les autres copies** de la même chaîne ;
- quelques formats de réponse inhabituels (heures en millisecondes, liste rangée autrement, titres en clair) ;
- un titre avec des accents mal encodés vidait tout le guide (déjà corrigé par l'agent A, conservé).

**À vérifier sur la télé** (voir la fin du rapport) : regarder le guide de quelques grandes chaînes, puis ouvrir le
calque de diagnostic (Info, Menu ou 0 trois fois de suite) et me dire ce qu'affichent les lignes **« Guide : … »** et
**« Mise à jour : … »**. Elles disent quelle méthode a trouvé le programme, ou pourquoi il n'y en a pas.

---

## Détails techniques

### Bogue 6 : `refreshing` ne repasse pas à faux

#### Chemin tracé

`LiveRoute` → `LaunchedEffect(Unit) { vm.start() }` → `LiveBrowseViewModel.start()` :
`reload()` (cache) → `_refreshing = true` → `withContext(io) { refresh(::onProgress) }` → `finally { _refreshing = false }`
→ `reload()` → `publishCatalog()`.

`refresh` = `LiveSessions.refresh` = `LiveRefresher.run` :
1. `sync.globalHiddenCategories` : RPC Supabase. supabase-kt 3.6.0 a un `requestTimeout` de 10 s par défaut
   (lu dans le bytecode de `SupabaseClientBuilder`) : pas d'attente sans fin ici.
2. `userInfo()` : `XtreamApi.fetch`, `withTimeout(15 s)`.
3. `CatalogRepository.refreshLive` : `get_live_categories`, puis **`get_live_streams` catégorie par catégorie, en
   séquence**, 15 s de délai maximal chacune ; après chaque catégorie, `onProgress` attendu dans la boucle.

Hors indicateur (vérifié) : la publication (`publish_live_categories`, `publish_live_channels` par morceaux de 2 000)
tourne **après** le `finally` ; la publication des groupes B2 (`GroupsWatcher`) et `publish_series_categories`
(migration 0011, accueil Séries) ne touchent jamais `refreshing`. Aucun compteur partagé, aucune boucle de
nouvel essai, aucun flux attendu qui n'émet jamais. Le `finally` était présent : le drapeau ne restait vrai que
**tant que `refresh` ne finissait pas**.

#### Causes (avec preuves)

1. **Durée proportionnelle au nombre de catégories** : une requête à la fois (le commentaire de `refreshLive` disait
   « une catégorie après l'autre »). Test rouge : 30 catégories de 1 s → **32 s** ; il en faudrait autant de fois plus
   pour les centaines d'un vrai fournisseur. En 0.4.0, en plus, chaque catégorie déclenchait une relecture complète du
   catalogue (`reload()` toutes les 1 s ou 25 catégories, attendue dans la boucle) ; le propriétaire mesure ~5 s pour
   une relecture sur la TCL (« les chaînes apparaissent après ~5 s »). L'agent A a déjà espacé ces relectures
   (15 s, `430c396`) : conservé.
2. **Rafraîchissement lié au profil** : il tournait dans la portée de la session (`LiveSessions.open`). Changer de
   profil annule cette portée ; `catalogDone` restant faux, la session suivante **recommençait à la première
   catégorie** (symptôme « revient après un changement de profil »). Tests rouges :
   `changer_de_profil_pendant_le_rafraichissement_ne_le_relance_pas` (2 rafraîchissements au lieu de 1).
3. **Catalogue « fait » seulement après la publication** vers le téléphone (`catalogDone = true` après `publish`) :
   changer de profil pendant la publication relançait aussi tout. Test rouge :
   `changer_de_profil_pendant_la_publication_ne_relance_pas_le_rafraichissement`.
4. **Aucune borne** : rien n'arrêtait un rafraîchissement très lent. Tests rouges :
   `un_rafraichissement_qui_ne_finit_jamais_ne_laisse_pas_mise_a_jour_affiche` (indicateur vrai après 11 min),
   `un_rafraichissement_bloque_est_abandonne_et_la_session_suivante_reessaie`.
5. **Échec invisible** : l'exception était avalée sans rien afficher.

#### Correctifs

- `CatalogRepository.refreshLive` : au plus `LiveSource.parallelism` demandes à la fois (`XtreamLiveSource` : 3 ;
  1 par défaut). Écritures SQLite et `onProgress` sérialisés (`Mutex`), place du fournisseur conservée, catégories
  masquées jamais demandées, annulation qui arrête tout avant la catégorie suivante. 3 et pas plus : OkHttp mène
  5 requêtes par serveur, il en reste 2 pour le guide ; rythme modéré pour le fournisseur. La limite d'une connexion
  du fournisseur concerne les flux vidéo, pas les appels d'API (à confirmer sur la télé : voir plus bas).
- `LiveSessions` : un rafraîchissement par processus dans la portée parente (`joinRefresh`/`runRefresh`) ; une
  session ouverte pendant le suit (avancement compris), une session ouverte après un succès reçoit le résultat sans
  relance ; `withTimeout(10 min)` → `RefreshTooLong` ; `catalogDone` dès le succès ; publication lancée une seule fois
  par le rafraîchissement partagé (survit aux changements de profil).
- `LiveBrowseViewModel` : filet `withTimeout(10 min 30 s)` en plus (au cas où), `refreshing` remis à faux dans tous
  les cas ; `refreshNotice` (impossible / trop longue / incomplète), effacé après 30 s ; affiché par `LiveTopBar`
  (paramètre `notice`, deux lignes dans `LiveBrowseScreen`).
- Calque de diagnostic (agent A) : ligne « Mise à jour : en cours : 120 / 640 catégories », « terminée… »,
  « arrêtée après 10 min », « échec (NomDeLErreur) » (jamais de message, qui pourrait contenir une adresse).

#### Tests

- shared : `CatalogRepositoryTest.plusieurs_categories_a_la_fois_borne_ordre_et_masquees_respectes`
  (rouge 32 s → vert ≤ 12 s, 3 en vol au plus).
- tv : `LiveBrowseViewModelTest.un_rafraichissement_qui_ne_finit_jamais_ne_laisse_pas_mise_a_jour_affiche`,
  `un_echec_du_rafraichissement_est_signale_sans_bloquer_puis_s_efface`, `une_mise_a_jour_incomplete_est_signalee` ;
  `LiveSessionsTest.changer_de_profil_pendant_le_rafraichissement_ne_le_relance_pas`,
  `changer_de_profil_pendant_la_publication_ne_relance_pas_le_rafraichissement`,
  `un_rafraichissement_bloque_est_abandonne_et_la_session_suivante_reessaie`. Tous rouges avant, verts après.
- Modifié : `LiveSessionsTest.ancienne_session_annulee` affirmait que le rafraîchissement de A était annulé ; c'est
  désormais voulu qu'il continue (B le suit). Le reste du test (collecteur de A arrêté) est inchangé.

### Bogue 5 : « Programme indisponible » pour maintenant et ensuite

#### Chemin tracé

- Demande : `LiveRoute` (lecture seule pour moi) `guideSource = state.playingSourceId ?: anchorSourceId` de la ligne
  sélectionnée → `epg?.observe(guideSource)`. Le guide est bien demandé, en navigation comme en lecture. L'identifiant
  passé est un vrai `stream_id` Xtream (celui de la copie en lecture, sinon la copie « ancre » du groupe), pas un
  identifiant de groupe.
- `EpgRepository.follow` : 300 ms de pause, `load` → `EpgSource.shortEpg(id, 8)` → `XtreamApi.shortEpg` →
  `get_short_epg&stream_id=…&limit=8`. `get_simple_data_table` n'était jamais utilisé.
- Analyse : `epg_listings` exigé en tableau ; `start_timestamp` + `stop_timestamp` exigés (secondes) ; titre Base64.
  Toute entrée sans horodatage était jetée. Erreur → `null` → « indisponible » et nouvel essai dans 60 s ;
  réponse vide → « indisponible » gardé 5 min. Aucune trace nulle part.
- `nowNext` : actuel `start <= now < end`, suivant après l'actuel. Horloge `Clock.System`, affichage en
  `ZoneId.systemDefault()` : correct si les instants sont justes.
- Affichage (`LivePreview.GuideLine`) : « Programme indisponible » quand l'entrée est nulle — y compris pendant le
  chargement (pas d'état « chargement » distinct ; non modifié, écran de l'agent A).

#### Causes possibles retenues (non vérifiables sans le vrai fournisseur)

Le comportement « indisponible pour maintenant ET ensuite sur presque toutes les chaînes » est systématique : il
vient d'un format ou d'une méthode, pas d'une chaîne. Les candidats, tous reproduits par un test rouge :
1. Titre Latin-1 qui levait une exception et vidait tout le guide (agent A, `e080595`, conservé).
2. Panneau sans `start_timestamp`/`stop_timestamp`, seulement `start`/`end` à l'heure **locale du serveur** :
   tout était jeté. → `shortEpgWithoutTimestampsUsesServerLocalDates`.
3. `get_short_epg` qui renvoie les premiers programmes de la journée (tous finis) au lieu des prochains : ni actuel
   ni suivant. → `guide_court_sans_actuel_ni_suivant_passe_au_guide_complet`.
4. Guide relié par le fournisseur à une seule copie d'un groupe (les autres ont `epg_channel_id` vide) : la copie
   ancre ou en lecture n'a rien. → `chaine_sans_guide_prend_celui_d_une_copie_du_meme_groupe`,
   `le_guide_d_une_copie_sans_guide_est_cherche_dans_les_autres_copies`.
5. Formats variés : `epg_listings` en objet `{"0":…}`, racine en tableau, millisecondes, titres en clair, champs
   manquants. → `shortEpgKeyedObjectMillisecondsAndPlainTitle`, `epgMissingFieldsAndOddShapesNeverThrow`.
6. Heures décalées mais drapeau `now_playing` juste (`get_simple_data_table`). →
   `le_drapeau_en_cours_du_panneau_sert_quand_les_heures_ne_couvrent_pas_maintenant`.

#### Correctifs

- `XtreamApi.parseEpg` commun à `shortEpg` et au nouveau `fullEpg` (`get_simple_data_table`) : horodatages
  `start_timestamp`/`stop_timestamp`/`end_timestamp` (nombre ou texte, millisecondes ramenées en secondes) ; sinon
  dates `start`/`end` « AAAA-MM-JJ HH:MM:SS » converties avec le décalage du serveur, lu **une fois par serveur** dans
  `server_info` (`time_now` − `timestamp_now`, arrondi au quart d'heure ; UTC si absent) ; `epg_listings` en tableau
  ou objet à clés numériques, ou racine en tableau ; titre Base64 (UTF-8 puis Latin-1) ou en clair ; `now_playing`.
  Jamais d'exception pour un format inattendu. `EpgEntry.nowPlaying` (défaut faux).
- `EpgModel.nowNext` : si les heures ne donnent ni actuel ni suivant, l'entrée marquée `now_playing` devient l'actuel
  (sans barre d'avancement).
- `EpgRepository.load` : pour chaque source candidate (`guideIds`, 3 au plus) : guide court, puis guide complet
  (réduit à 8 entrées à partir de l'actuel) ; s'arrête au premier qui donne un actuel ou un suivant. Durée de cache :
  prochain changement **à venir** (sinon 5 min), ce qui évite de redemander chaque seconde sur des heures périmées.
  Un guide complet vide ne masque pas l'erreur du guide court (nouvel essai dans 1 min).
- `LiveBrowseViewModel.guideCandidates` : la copie elle-même si elle a un `epg_channel_id`, puis les autres copies
  **visibles** du groupe qui en ont un (une par guide distinct). Branché dans `LiveSessions`.
- `MainActivity` : `EpgSource.of(::api)` (guide court et complet ; identifiants toujours lus au moment de l'appel).
- Diagnostic : ligne « Guide : guide court », « guide complet, copie 2 », « …, drapeau « en cours » »,
  « aucun programme (3 source(s)) » ou « erreur, nouvel essai dans 1 min ».

#### Tests

shared : `XtreamApiTest.shortEpgTypicalXuiPanel` (garde), `shortEpgWithoutTimestampsUsesServerLocalDates`,
`shortEpgKeyedObjectMillisecondsAndPlainTitle`, `fullEpgReadsSimpleDataTableWithNowPlaying`,
`epgMissingFieldsAndOddShapesNeverThrow` (agent A : `shortEpgKeepsTheGuideWhenOneTitleIsNotUtf8`,
`shortEpgAcceptsEndTimestampAndPlainTitles`). Fixtures inventées, aucune vraie adresse :
`apps/shared/src/jvmTest/resources/xtream/short_epg_xui.json`, `short_epg_dates_only.json`,
`short_epg_keyed_ms.json`, `simple_data_table.json`, `user_info_server_time.json`.
tv : `EpgModelTest.le_drapeau_en_cours_du_panneau_sert_quand_les_heures_ne_couvrent_pas_maintenant`,
`les_heures_priment_sur_le_drapeau_quand_elles_sont_coherentes` ; `EpgRepositoryTest.guide_court_sans_actuel_ni_suivant_passe_au_guide_complet`,
`guide_court_utile_ne_demande_pas_le_guide_complet`, `chaine_sans_guide_prend_celui_d_une_copie_du_meme_groupe`,
`programme_en_cours_aux_heures_perimees_ne_relance_pas_chaque_seconde` ; `LiveBrowseViewModelTest.le_guide_d_une_copie_sans_guide_est_cherche_dans_les_autres_copies`.
Rouges vérifiés : les quatre tests `XtreamApiTest` nouveaux (avec `fullEpg` vide), les deux tests du drapeau (avec
l'ancien `EpgModel.kt`) ; les autres tests `EpgRepository` ne compilaient pas sans la nouvelle interface.

### Suites lancées (après rebase sur `plan-4-series`, `28e52e6`)

- `./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest --rerun-tasks -q` : **180 partagés, 271 télé**,
  0 échec. Maven Central renvoyait 429 : script Gradle d'initialisation **hors dépôt** (miroir Google de Maven Central
  en premier), non versionné.
- `npm test -w backend` : **167** ; `npm test -w web/config` : **124**.

### À confirmer sur la télé

1. Guide de 3 ou 4 grandes chaînes, en navigation et en lecture ; calque de diagnostic, ligne « Guide ». Si elle dit
   « aucun programme » partout, le fournisseur ne renvoie rien d'exploitable par `get_short_epg` ni
   `get_simple_data_table` : il faudrait lire le guide XMLTV (`xmltv.php`), gros fichier, travail plus long.
2. Heures affichées justes (fuseau) pour une émission connue.
3. Premier démarrage : durée de « Mise à jour… » (ligne « Mise à jour : en cours : n / total ») ; changer de profil
   pendant : l'avancement continue au lieu de repartir de 0 ; plus de « Mise à jour » au retour.
4. Que le fournisseur accepte 3 demandes à la fois (aucune catégorie « sans réponse » de plus qu'avant ; sinon
   repasser `XtreamLiveSource.parallelism` à 2 ou 1).

### Points laissés (hors de mon périmètre)

- Accueil Séries : la barre du haut affiche aussi « Mise à jour… » via `SeriesHomeUi.loading`, qui reste vrai tant que
  les préférences du profil sont inconnues (`SeriesHomeViewModel`, ligne `loading = true` quand `prefs.value == null`) :
  hors ligne, cet indicateur peut rester. Domaine de l'agent C.
- Pas d'état « chargement du guide » distinct de « indisponible » dans `LivePreview` (écran de l'agent A).
- Le rafraîchissement ne démarre qu'à l'ouverture de l'onglet Direct (inchangé).
