# Rapport — Tâche 4 : sections et accueil Séries (télé)

## Ce qui est fait
- **Logique pure** `series/SeriesRows.kt` : `buildSeriesHome(progress, list, categories, prefs, knownNew?, infoOf)`.
  Reprendre = série la plus récente ; épisode vu : épisode suivant (même d'une saison à l'autre) si la fiche le connaît,
  sinon « à jour » ; dernier épisode connu vu : série retirée ; masqué gagne partout (catégorie de la ligne de progression,
  sinon de la fiche, y compris pour Ma liste et les rangées) ; catégories : ordre du profil puis fournisseur, nouvelles à la fin ;
  « Nouveaux épisodes » plafonnés à 20.
- **ViewModel** `series/SeriesHomeViewModel.kt` : tout sur `io`. Bloc Reprendre dès la progression locale (copie du serveur
  gardée sur le disque + file d'envoi du profil, la ligne la plus récente par série gagne) ; serveur, catégories et fiches ensuite.
  Hors ligne : cache local + message, jamais d'écran vide sans message. Rangées de catégorie chargées à la demande
  (`loadCategory`, 20 séries max, jamais pour une catégorie masquée). Demandes au fournisseur sérialisées (une connexion).
  Publication de TOUTES les catégories (masquées pour tous comprises, noms seulement) via `publishSeriesCategories`, dans un try
  qui ne casse jamais l'écran, au plus une fois par jour et par foyer. Fiches : au plus 20 séries par ouverture (cache 6 h du
  dépôt) ; détection « nouveaux épisodes » refaite au plus une fois par 24 h (heure et ids gardés en local), entre-temps les ids gardés servent.
- **Données** `series/SeriesHomeData.kt` : interface `SeriesCatalog` (+ `RepoSeriesCatalog`), `SeriesHomeState`
  (SharedPreferences `iptv_home_series`), `queuedAsRows` (« vu » à 90 %).
- **Écrans** : `nav/SectionBar.kt` (pastilles Direct / Séries, OK pour ouvrir, le focus seul ne change rien) intégrée à la
  barre du haut existante (`LiveTopBar` reçoit un emplacement `title`, `LiveBrowseScreen`/`LiveRoute` un `topBarTitle`) ;
  `series/SeriesHomeScreen.kt` (bloc Reprendre, `LazyColumn` + `LazyRow`, affiches Coil ~240 px, titre sur fond en repli, focus
  `focusLook`) ; `series/SeriesPlaceholder.kt` (écran provisoire minimal pour les tâches 5 et 6).
- **Câblage** : `App.kt` (`HomeForProfile`, onglet retenu par profil via `lastTab/saveLastTab`, Retour sur Séries = d'abord la
  barre des sections puis on quitte, passer à Séries ferme le lecteur par `PlayerController.close()`), `IptvApp.kt` (une seule base
  de catalogue partagée, `ProgressClient`, état local), `MainActivity.kt` (usine du ViewModel).
- Rappels vers les tâches suivantes : `onOpenSeries(seriesId, categoryId)` et `onPlayEpisode(PlayEpisodeRequest)` ouvrent pour
  l'instant `SeriesPlaceholder` (à remplacer en tâches 5 et 6).

## Tests
- `SeriesRowsTest` (6) : reprendre_est_la_serie_la_plus_recente, episode_vu_propose_le_suivant, serie_terminee_disparait,
  categorie_masquee_retire_la_serie_partout, ordre_categories_profil_puis_fournisseur, nouveaux_episodes_limites_a_20.
- `SeriesHomeViewModelTest` (7) : cache local hors ligne avec message, jamais d'écran vide sans message, progression locale avant
  le serveur, catégorie masquée jamais demandée + 20 séries par rangée, publication masquées comprises + échec sans casse,
  vérification quotidienne (une fois / 24 h) et 20 séries max, masqué gagne dans Ma liste et Reprendre.
- **RED** (stub qui compile, `buildSeriesHome` renvoyant `SeriesHome.EMPTY`) : `./gradlew --max-workers=2 :tv:testDebugUnitTest --tests '*SeriesRowsTest*'`
  -> « 6 tests completed, 6 failed » (AssertionError sur chaque test, NoSuchElementException sur episode_vu_propose_le_suivant).
- **GREEN** : même commande -> BUILD SUCCESSFUL. Un test a ensuite révélé un bogue réel (-1 pris pour un index), corrigé.
- Les tests du ViewModel ont été écrits après le ViewModel (pas de rouge séparé) ; un premier passage a échoué (backgroundScope non
  avancé par `advanceUntilIdle`, récursion du faux catalogue, Ma liste non masquée par la catégorie de la progression), corrigé.
- **Final** : `./gradlew --max-workers=2 :tv:testDebugUnitTest :tv:assembleDebug --offline` -> BUILD SUCCESSFUL, 176 tests TV, 0 échec.

## Commits
9e09cb2 logique pure ; 71c61e1 ViewModel ; 59c7d46 sections et accueil Séries (+ rapport/passation ensuite).

## Choix et limites à connaître
- « Nouveaux épisodes » (sans ids gardés) = séries dont le dernier épisode vu est terminé et dont la fiche connaît la suite ; une
  série de Ma liste sans aucune progression n'y figure pas (rien à comparer).
- Les fiches de Continuer/Ma liste passent par `SeriesRepository.info` (cache 6 h) : le plafond de 20 et la vérification
  quotidienne limitent la détection, pas le cache des fiches.
- La publication des catégories est « au plus une fois par jour et par foyer » (le dépôt ne dit pas si `categories()` a
  rafraîchi) ; elle est réessayée à la prochaine ouverture si elle échoue.
- Retour depuis l'écran provisoire : le focus retombe sur le premier élément focalisable (pas restauré sur la carte d'origine).
- Si l'accueil s'ouvre avec Séries comme dernier onglet, la session du direct est ouverte mais n'est pas démarrée.

## À vérifier sur la vraie télé (impossible ici)
1. Navigation D-pad : barre des sections (gauche/droite, OK), bas vers Reprendre, haut depuis la première rangée vers la barre, focus visible sur chaque affiche.
2. Retour sur Séries : une pression va à la pastille « Séries », la suivante quitte ; aucun piège ; Retour depuis l'écran provisoire revient à l'accueil.
3. Passer de Direct (en lecture, aperçu ou plein écran) à Séries coupe bien le flux ; revenir à Direct repart sans lecture.
4. Changement de profil : l'onglet retenu de chaque profil est restitué.
5. Fluidité du défilement de l'écran et des rangées (images Coil), chargement paresseux des rangées de catégorie, mémoire.
6. Rendu des affiches réelles du fournisseur (affiches manquantes, très longues listes), taille de texte à 3 m.
7. Hors ligne réel : l'accueil s'ouvre avec la dernière progression connue et un message.
8. Publication des catégories vers Supabase (la migration 0011 n'est pas appliquée en production : l'appel échoue sans effet visible tant qu'elle ne l'est pas).

---

# Correctifs, tour 1 (7 points importants + mineurs)

**RED** (tests ajoutés d'abord, `cancelCategory` en stub vide) : `./gradlew --max-workers=2 :tv:testDebugUnitTest --tests '*series*' --offline`
-> « 23 tests completed, 10 failed » : les 10 nouveaux tests échouent (8 ViewModel, 2 SeriesRows), les 13 anciens passent.
**GREEN** : `./gradlew --max-workers=2 :tv:testDebugUnitTest :tv:assembleDebug --offline` -> BUILD SUCCESSFUL, 186 tests TV, 0 échec. Commit 8ee55df.

- **I1** `reprendre_connait_le_titre_et_l_ensuite_d_une_serie_en_cours` : les fiches sont préchargées pour toutes les séries
  de progression (les plus récentes d'abord, 20 max), en cours comprises.
- **I2** `sans_preferences_ni_rangee_ni_chargement_de_categorie`, `categorie_inconnue_reste_cachee_jusqu_a_la_fiche`,
  `categorie_perimee_de_la_ligne_et_fiche_masquee_exclut_la_serie` : sans préférences rien n'est affiché (« Chargement… » puis message),
  `loadCategory` ne fait rien et les fiches ne sont pas demandées ; une série est masquée si la catégorie de la ligne OU celle de la fiche
  l'est ; sans catégorie connue elle attend sa fiche, et reste visible si la fiche n'en a aucune.
- **I3** `ma_liste_a_son_propre_budget_de_fiches` : 20 fiches pour Continuer + jusqu'à 20 pour Ma liste.
- **I4** `le_chargement_paresseux_est_annulable_et_borne` : un Job par rangée, attente de 300 ms dans le ViewModel (virtuelle en test),
  `cancelCategory` appelé par `DisposableEffect.onDispose` de l'écran ; 10 demandes dont 8 annulées -> 2 appels fournisseur ; une rangée annulée peut être redemandée.
- **I5** `la_progression_locale_est_lue_une_fois_par_ouverture` : `localProgress` lu hors verrou, une fois par `start`/`reloadProgress`, copie sous verrou.
- **I6** `la_verification_quotidienne_n_est_pas_validee_sans_aucune_fiche` : hors ligne rien n'est enregistré ; validée au retour du réseau.
- **I7** `une_publication_lente_ne_retarde_pas_les_fiches` : `publishIfDue` lancée à part.
- Mineurs : `une_liste_serveur_vide_fait_foi_malgre_l_ancienne_copie` ; le focus initial n'est plus pris si l'utilisateur est déjà dans le
  contenu ; `contentDescription` = titre sur les affiches ; limite « Nouveaux épisodes » notée dans PASSATION.md.

Limite : sans préférences ET hors ligne depuis toujours, l'accueil reste sur « Chargement… » avec un message (masqué gagne).
