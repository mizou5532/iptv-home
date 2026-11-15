# Relecture finale de la branche `plan-4-series` (étape 4A, séries)

*15 novembre 2026. Périmètre : `git diff d92f874..HEAD` (78 fichiers, environ 8 400 lignes) : migration 0011, `apps/shared` (séries Xtream, cache SQLDelight, client et file de progression), `apps/tv` (sections, accueil Séries, fiche, lecture), `web/config` (organisation des catégories de séries). Relecture seule : aucun code modifié.*

## Verdict

**À corriger avant la tâche 8 (mise en ligne).** Aucun point critique. Deux points importants : une fiche de série masquée peut encore être demandée au fournisseur, et une position peut être perdue sans bruit quand la durée est inconnue. Tout le reste est mineur ou reporté. La migration 0011 est sûre pour la production : elle s'applique sans 0010 et reste compatible avec la télé 0.3.0.

## Tests (relancés pendant cette relecture)

| Suite | Résultat |
|---|---|
| `cd apps && ./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest :tv:assembleDebug --rerun-tasks -q` | Réussite (code 0). shared : **169** tests, 0 échec ; tv : **232** tests, 0 échec ; `tv-debug.apk` construit ; aucun avertissement dans la sortie. |
| `npm test -w backend` (Vitest sur un Postgres 16 local jetable, `test/db.ts`) | **165** tests réussis (11 fichiers). |
| `npm test -w web/config` | **124** tests réussis (10 fichiers). |
| `npm run build -w web/config` | Construction réussie. |
| Vérification manuelle de la migration (Postgres 16 jetable) | 0001 à 0009, puis **0011 sans 0010** : la migration s'applique. 0010 appliquée **après** 0011 : elle s'applique aussi. Les appels nommés à 6, 9 et 12 arguments de `record_progress` sont résolus sans ambiguïté (les appels à 6 et 9 arguments passent par l'enveloppe). `anon` n'a pas l'exécution sur la version à 12 arguments ni sur `set_watched`. `authenticated` n'a pas l'exécution sur `org_series_order`. |

## Vérifications transverses

- **Progression écrite par la lecture = progression lue par l'accueil et la fiche.**
  - `EpisodePlayback.save` (`EpisodePlayback.kt:318-335`) remplit `seriesName`, `seriesCover` et `seriesCategory`.
  - `recordBody` envoie les 12 clés, avec un `null` explicite pour une valeur absente.
  - `continue_watching` renvoie `series_name`, `series_cover` et `cat`. `cat` sort dans la 13ᵉ colonne du type de ligne, donc sous le nom `series_category`.
  - `ProgressRow` décode ces champs, et `queuedAsRows` reprend les mêmes champs depuis la file.
  - Exception : `series_progress` ne renvoie pas la catégorie (voir I1).
- **Règle des 90 %, identique partout.**
  - Backend : `position_s >= 0.9 * duration_s`.
  - Côté client, avec `pos*10 >= dur*9` : `queuedAsRows` (`SeriesHomeData.kt:49`) et `statusOf` (`SeriesDetailViewModel.kt:370`).
  - Côté client, avec `p*100 >= d*90` : `resumePosition` (`EpisodePlayback.kt:55`).
  - Une durée nulle compte partout comme « inconnue ».
- **« Masqué gagne ».**
  - Backend : masquage pour tout le monde ou pour le profil, et catégorie la plus récente connue de la série (`continue_watching`).
  - Client : `buildSeriesHome` (Reprendre, Continuer, Ma liste, Nouveaux épisodes, rangées), la fiche (`isMasked`), puis la lecture (`begin`, `start`, et `start` de nouveau lors de l'enchaînement).
  - L'affichage est cohérent. Les demandes au fournisseur ont deux fuites (I1).
- **Une seule connexion au fournisseur.**
  - Passer à Séries appelle `player.close()`, au clic et dans `LaunchedEffect(tab)` (`App.kt`).
  - Quitter Séries passe par `onDispose`, qui appelle `playback.stop()` puis `controller.close()`.
  - Lire un épisode : `controller.open`, qui fait `close()` avant `newPlayer()` (testé).
  - `onStop` : `playback.stop()` puis `player.close()`.
  - Les appels à l'API Xtream (catégories, fiches) ne sont pas des flux.
- **Sécurité.**
  - Chaque nouvelle fonction RPC appelle `org_assert` (publish, org_series_*, org_set_series_list, org_reorder_series_list, set_watched) ou `can_use_profile` (record_progress à 12 arguments, continue_watching, series_progress).
  - Les droits sont retirés à `public` et `anon`, puis accordés à `authenticated`.
  - Pas de clé `service_role` dans le diff.
  - Aucun journal, `println` ni message d'erreur ne contient d'adresse. L'URL de flux n'existe que dans `urlFor` et dans `controller.open`.
  - Les journaux de Media3 sont coupés.
  - La page web ne reçoit que des catégories (identifiant et nom).
- **Télé 0.3.0 déjà installée.**
  - Le client Supabase par défaut (`SupabaseClientBuilder`) décode avec `ignoreUnknownKeys = true`, vérifié dans le code compilé de supabase-kt 3.6.0. Les nouvelles clés `series_order` et `series_list` de `profile_prefs` sont donc ignorées.
  - La 0.3.0 n'appelle ni `record_progress` ni `continue_watching`.
  - `org_set_hidden` garde sa signature et accepte toujours `live_category` et `channel`.
- **Mémoire et performance.** L'accueil et la fiche affichent des listes paresseuses. Les rangées sont limitées à 20 affiches, chargées après 300 ms d'attente et annulées hors de l'écran. Les images sont demandées à taille réduite (`size(...)`). Au plus 300 fiches sont gardées en cache. Le plafond de 1 000 lignes est respecté : `continue_watching` est limitée à 200 lignes, et les autres réponses sont des blocs jsonb.

---

## Critical

Aucun.

## Important

### I1. Une série masquée peut encore être demandée au fournisseur (accueil et fiche)

**Où :**
- `apps/tv/src/main/kotlin/home/iptv/tv/series/SeriesHomeViewModel.kt:184-192`
- `backend/supabase/migrations/20261114000011_series.sql:360-372`
- `apps/tv/src/main/kotlin/home/iptv/tv/series/SeriesDetailViewModel.kt:112-114`
- `apps/tv/src/main/kotlin/home/iptv/tv/series/EpisodePlayback.kt:144-149` (point mineur reporté n° 2)

**Problème 1 : l'accueil.**
- `completeInfos` filtre `rows` en retirant les lignes dont la catégorie est masquée **avant** de calculer `category` pour Ma liste.
- Prenons une série de Ma liste dont la progression porte une catégorie masquée. Elle n'est pas dans `continuing`, puis elle est ajoutée par `p.seriesList`. Sa catégorie connue a disparu (`category = null`), et `catalog.info(id, null)` interroge le fournisseur.
- Le test `masque_gagne_aussi_pour_les_series_de_ma_liste_et_de_reprendre` (série « a », catégorie 9 masquée, dans Ma liste) passe à l'affichage, mais `infoCalls` contient « a ». Il ne vérifie pas ce point.

**Problème 2 : la fiche.**
- Sans `categoryId`, la fiche lit d'abord la progression (« la progression porte la catégorie »).
- Or `series_progress` ne renvoie pas `series_category`. La garde est donc sans effet, et la fiche est demandée avant de savoir si la série est masquée.
- `EpisodePlayback.begin` a le même défaut quand `categoryId` est null (reporté de la tâche 6).

C'est contraire à la contrainte « Masqué gagne partout », et à la promesse de `SeriesDetailViewModel` (« ni demandée ni lisible »).

**Correction :**
1. Dans `completeInfos`, calculer l'ensemble des séries dont une catégorie connue est masquée à partir de `merged()` **non filtré**. L'exclure de `wanted`, et prendre la catégorie dans `merged()` non filtré.
2. Ajouter `'series_category', w.series_category` à l'objet jsonb de `series_progress`. 0011 n'est pas en production, donc on la modifie sur place. Ajouter un test backend.
3. Ajouter à `SeriesCatalog` une méthode `cachedCategory(seriesId)`, sans réseau, qui lit `selectItemCategory` puis `selectDetailCategory`. La consulter avant tout `info(id, null)` dans `completeInfos`, `SeriesDetailViewModel.start` et `reload`, et `EpisodePlayback.begin`.
4. Tests :
   - accueil : `infoCalls` ne contient pas « a » ;
   - fiche ouverte sans catégorie, progression masquée : aucun appel à `info` ;
   - `begin` sans catégorie, catégorie masquée connue du cache : `Failed(MASKED)` sans `loadInfo`.

Seule la série inconnue partout (aucune progression, jamais listée) reste demandée une fois : c'est la seule façon d'apprendre sa catégorie. Le noter dans PASSATION, section 3 (limite connue).

### I2. Position perdue sans bruit quand la durée est inconnue

**Où :**
- `apps/tv/src/main/kotlin/home/iptv/tv/series/EpisodePlayback.kt:321-324`
- `apps/shared/src/commonMain/kotlin/home/iptv/shared/progress/ProgressQueue.kt:113-119`
- `backend/supabase/migrations/20261114000005_progression.sql:11` (`check (duration_s > 0)`)

**Problème :**
- Si la fiche n'a pas de durée (cas prévu, « durée absente ») et que Media3 ne la donne pas (`durationMs = -1`), `save()` met dans la file une entrée avec `durationS = 0` et une position supérieure à 0.
- Le serveur refuse cette entrée (violation de contrainte 23514, HTTP 400). Le client la classe comme refus permanent, puis la jette après 5 passages.
- En attendant, l'accueil et la fiche l'affichent « en cours », puis elle disparaît.
- Le spec, section 4, interdit justement de perdre une position de reprise. Le cas est rare, car le MKV donne presque toujours sa durée, mais la perte est silencieuse.

**Correction :**
- Choix recommandé, dans 0011 (non publiée) : remplacer la contrainte par `check (duration_s >= 0)`, et calculer `watched` avec `duration_s > 0 and position_s >= 0.9 * duration_s`, dans `record_progress` comme dans l'enveloppe. Le client traite déjà 0 comme « inconnue » (`statusOf`, `remainingLabel`, `resumePosition`).
- À défaut, côté client : ne pas mettre la position dans la file tant que la durée est inconnue, et garder la dernière position en mémoire jusqu'à la première durée connue.
- Ajouter un test : une entrée avec `durationS = 0` est acceptée par le serveur, et elle n'est pas « vue ».

## Minor

À corriger dans la même vague (peu coûteux), sauf mention contraire.

### Points reportés des relectures de tâches, revus

**M1 (point reporté n° 1) : réessai après une erreur d'enchaînement.**
- Où : `EpisodePlayback.kt:166-176`.
- Problème : `current` et `series` sont assignés, puis `fail(PREFS_UNKNOWN, ...)` ou `fail(FAIL_PLAY, ...)` sort **avant** `lastPosS = startS`. « Réessayer » rouvre alors l'épisode suivant à la position de l'épisode précédent. Le bug est visible.
- Correction : remettre `lastPosS = resumeAtS ?: 0` et `played = false` juste après `this.current = episode`. Ajouter un test (enchaînement, `urlFor` null, `retry()`, l'épisode s'ouvre à 0).

**M2 (point reporté n° 2) : `categoryId` null dans `begin`.** Intégré à I1, correction 3.

**M3 (point reporté n° 3) : `nextEpisode` saute les numéros en double, et l'accueil ne fait pas pareil.**
- Où : `EpisodePlayback.kt:41-47` et `SeriesRows.kt:138-155`.
- Problème : `nextEpisode` saute les numéros en double. L'accueil (`resumeItem`, liste à plat triée, `index + 1`) annonce au contraire le doublon comme « Ensuite ». L'enchaînement automatique contredit donc l'affichage.
- Correction : une seule fonction partagée. Elle construit la liste à plat (saisons triées, épisodes dédupliqués par `id` et triés de façon stable par numéro) et renvoie l'élément `indexOf(current.id) + 1`. L'utiliser dans les deux fichiers. Ajouter un test avec deux « É5 ».

**M4 (point reporté n° 4) : la limite de 2 s dans `onStop` ne limite rien.**
- Où : `MainActivity.kt:155-157` et `:168`.
- Problème : `persistNow()` est bloquant, donc `withTimeoutOrNull` ne limite rien. Le commentaire « au plus 2 s » est faux.
- Correction : appeler `s.persistNow()` directement. Il s'agit d'un `commit()` d'environ 60 Ko au plus, et Android attend de toute façon les `apply()` en attente à l'arrêt de l'activité. Supprimer la constante et corriger le commentaire.

**M5 (point reporté n° 5) : test de profil tautologique.**
- Où : `EpisodePlaybackTest.kt:258`.
- Problème : un seul profil est construit, donc l'assertion « seul pA » est vraie par construction.
- Correction : deux `EpisodePlayback` (pA et pB) sur la même file. On arrête celle de pA pendant que celle de pB existe, puis on vérifie que la file ne contient que pA.

**M6 (point reporté n° 6) : la demande d'envoi arrivée pendant un envoi en cours n'est pas testée.**
- Où : `ProgressFlusher.kt:27-47` et `EpisodePlaybackTest.kt:414`.
- Correction : un test avec un faux client bloqué sur un `CompletableDeferred`. Appeler `request()` pendant `flush()`, libérer le client, puis vérifier un second `flush()`.

### Nouveaux points

**M7 : adresse du serveur dans les affiches.**
- Où : `SeriesCover.kt:7-22`.
- Problème : une affiche hébergée chez le fournisseur garde son hôte, c'est-à-dire l'adresse du serveur IPTV. Elle part dans `watch_progress.series_cover`. L'exposition reste derrière la même barrière RLS (membres du foyer) que la table des identifiants, mais c'est une copie de plus de l'adresse du serveur.
- Correction : quand l'hôte est celui de `creds.server`, n'envoyer que le chemin (forme relative déjà acceptée) et recomposer l'adresse à l'affichage.

**M8 : vu côté serveur, en cours côté client.**
- Où : `SeriesDetailViewModel.kt:315-316` et `SeriesHomeData.kt:49`.
- Problème : un épisode « vu » que l'on revoit à 30 % est « vu » sur le serveur (`watched` ne redevient jamais faux), mais « en cours » tant que la ligne locale, plus récente, n'est pas envoyée. L'état change d'un coup après l'envoi.
- Correction : à la fusion, `watched = any(rows.watched)` pour un même épisode.

**M9 : course entre « non vu » et un envoi en cours.**
- Où : `SeriesDetailViewModel.kt:216-218`.
- Problème : `dropQueued` est appelé après `set_watched(false)`. Un `flush` déjà en cours peut renvoyer, entre les deux, une ancienne position à au moins 90 %, ce qui remet l'épisode « vu ». La fenêtre est étroite.
- Correction : retirer les entrées de la file avant l'appel, et les remettre en cas d'échec. Ou bien laisser `set_watched(false)` avancer `client_updated_at` à `now()` seulement pour les lignes démarquées. Cela fait remonter la série, ce qui est à juger.

**M10 : performance de la file.**
- Où : `ProgressQueue.kt:66` et `LocalStore.kt` (`loadProgressQueue`).
- Problème : chaque tour de `flush` redécode toute la file en JSON, soit O(n²) jusqu'à 300 entrées.
- Correction : lire la file une fois par passage et ne relire qu'après une mutation, ou garder une copie décodée en mémoire dans `AndroidLocalStore`.

**M11 : rangée de catégorie lue en entier.**
- Où : `SeriesRepository.kt:77-79` et `SeriesHomeViewModel.kt:104-105` (`take(ROW_SIZE)`).
- Problème : la rangée lit toute la catégorie depuis SQLite (des milliers de séries possibles) pour n'en garder que 20.
- Correction : une requête `selectSeriesItemsPage` avec `LIMIT`.

**M12 : `selectItemCategory` sans index.**
- Où : `Series.sq:121-122`.
- Problème : la recherche par `id` seul parcourt toute la table `series_item`, sans index. Cela devient plus fréquent avec I1, correction 3.
- Correction : ajouter `CREATE INDEX series_item_id ON series_item(id)` dans le schéma et dans `1.sqm`, ou dans un `2.sqm` si la 1.sqm est déjà livrée. Elle ne l'est pas.

**M13 : texte en anglais.**
- Où : `XtreamApi.kt:166`.
- Problème : le titre de secours `"Episode $number"` est visible à l'écran et n'est pas en français.
- Correction : `"Épisode $number"`.

**M14 : couleur codée en dur.**
- Où : `EpisodePlayerScreen.kt:122`.
- Problème : `Color.Black` contredit la règle « aucune couleur codée en dur ».
- Correction : un jeton du thème (par exemple `t.videoBackground`), ou un commentaire qui justifie l'exception pour le fond vidéo.

## Deferred

**D1 (point reporté n° 7) : focus sur l'accueil Séries après Retour.**
- Le focus retourne sur la carte d'origine (`SeriesHomeScreen.kt:111-120`), mais ce n'est pas vérifié sur la télé.
- Risque : après `reloadProgress`, la carte de Continuer change de place ou sort de la zone composée de la `LazyRow`. `requestFocus` échoue alors en silence (dans un `runCatching`), et le focus est perdu.
- À vérifier sur la TCL. Correction à prévoir : si la demande échoue, se replier sur `resumeRequester`, puis sur `contentRequester`.

**D2 : application de 0011 en production (tâche 8, avec l'accord du propriétaire).**
- 0011 n'est pas rejouable : `create table` et `create function` ne sont pas protégés par `if not exists`. C'est cohérent avec 0001 à 0009, et un rejeu échoue dès la première instruction, sans rien laisser à moitié.
- L'appliquer une seule fois avec `apply_migration`, qui l'enregistre, et pas avec `supabase db push`. Comme 0010 a un numéro plus petit et n'est pas appliquée, `db push` la refuserait, ou l'appliquerait avec `--include-all`.
- Après application : recharger le cache de schéma de PostgREST, et vérifier `select pg_get_function_identity_arguments(oid) from pg_proc where proname = 'record_progress'` (2 lignes attendues).

**D3 : page iPhone.**
- Le choix Direct / Séries n'est gardé que pendant la session.
- Ma liste n'est pas affichée en lecture seule (facultatif dans le spec, §9.4).
- `view.ts` fait environ 750 lignes : à découper avant de nouveaux ajouts.

**D4 : expiration des positions et horloge.** `isPlausibleStamp` ne juge que l'estampille, pas `now`. Une horloge de télé très en avance au moment de l'envoi peut faire expirer des positions légitimes (relecture de la tâche 6, point 7). La probabilité est faible.

**D5 : position à l'arrêt.** Elle est celle du dernier tick, avec jusqu'à 1 s de retard, ou sans un saut de 10 s fait dans la dernière seconde. Lire `snapshot()` dans `stop()` serait plus juste. Ce n'est pas nécessaire.

**D6 : liste à vérifier sur la télé.** Celle de `docs/rapports/4a-tache6-lecture.md` : MKV à plusieurs pistes, D-pad, mise en veille pendant la lecture, coupure du Wi-Fi, puis retour.
