# Rapport — Tâche 5 : fiche d'une série (télé)

## Ce qui est fait
- `series/SeriesDetailViewModel.kt` : `SeriesDetailViewModel(seriesId, categoryId, catalog, progress, profileId, serverProgress, localProgress, prefs, scope, io)`.
  `state` (titre, affiche, genre, année, résumé, saisons avec `watchedCount/total`, saison choisie, épisodes `Watched | InProgress(posS, durS) | Upcoming`,
  `inMyList`, `masked`, `message`), `selectSeason`, `toggleMyList`, `markSeasonWatched`, `markEpisodeWatched`, `reload`, `play(episodeId, fromStart)`.
  - `play` RENVOIE la `PlayEpisodeRequest` (ou `null` si refusée : série masquée, épisode inconnu) ; l'écran la passe à `onPlayEpisode`. Sans `fromStart`, un épisode commencé reprend à sa position.
  - Saison initiale = celle de l'épisode en cours le plus récent, sinon la première saison non entièrement vue, sinon la première. Elle suit la progression tant que l'utilisateur n'a pas choisi ou agi sur une saison.
  - Masqué gagne : catégories connues = celle passée par l'appelant, celle de la fiche, celle gardée avec la progression ; une seule masquée (préférences du profil, qui contiennent aussi les masquages « pour tout le monde ») suffit. Préférences inconnues : rien n'est demandé. Catégorie masquée connue d'avance : `info` n'est jamais appelé. Masquage apparu pendant l'écran : « Série masquée », lecture refusée.
  - État des épisodes = progression serveur + file locale (ligne la plus récente par épisode), « vu » à 90 % ; les marques posées ici sont appliquées tout de suite et annulées (avec message) si l'envoi échoue. Marquer envoie nom, affiche et catégorie de la série ; durée absente acceptée (`null`).
  - Fiche absente (réseau, sans cache) : « Fiche indisponible… » + « Réessayer », jamais d'exception (le cache même périmé est servi par `SeriesRepository.info`). Saison sans épisode : message, aucun envoi.
  - Tout hors du fil principal (`io`), instantané hors verrou, comme la tâche 4. La catégorie connue est transmise à `repo.info(seriesId, categoryId)` via `SeriesCatalog`.
- `series/SeriesDetailScreen.kt` : maquette Fiche-Serie avec les jetons `IptvTheme` seuls ; affiche (Coil, titre sur fond en repli), boutons « Ajouter à ma liste / Dans ma liste » et « Marquer la saison vue / non vue », onglets de saisons (« ✓ », « 4 / 8 vus » sur la saison choisie), épisodes dans une `LazyColumn` (✓ Vu, barre + « N min restantes », durée), OK = lire/reprendre, appui long = marquer vu/non vu. Premier focus : épisode en cours, sinon premier non vu. Retour = accueil Séries.
- Câblage : `App.kt` (fiche ouverte à la place du provisoire `Open`, lecture demandée depuis la fiche affichée par-dessus avec le provisoire de la tâche 6 : Retour revient à la fiche), `MainActivity.kt` (usine `seriesDetail`), `SeriesHomeScreen.kt` (focus restauré sur la carte d'origine à la fermeture de la fiche : demandeurs de focus par clé « rangée:seriesId », bloc Reprendre = bouton Reprendre).

## Tests (15, `SeriesDetailViewModelTest`)
saison_initiale_est_celle_de_l_episode_en_cours, sans_episode_en_cours_la_premiere_saison_non_vue, compte_des_episodes_vus_par_saison (4 / 8),
regle_des_90_pour_cent_et_file_locale_fusionnees, marquer_la_saison_vue_puis_non_vue_envoie_les_champs_d_enrichissement, echec_d_envoi_annule_la_marque_et_explique,
duree_absente_acceptee, saison_sans_episode_ne_plante_pas, fiche_absente_sans_cache_affiche_un_message_sans_planter, fiche_en_cache_servie_et_progression_indisponible_signalee,
categorie_masquee_refuse_la_lecture_et_ne_demande_rien, masquage_apparu_pendant_l_ecran_refuse_la_lecture, la_categorie_connue_est_transmise_au_depot,
lecture_reprend_a_la_position_ou_recommence, dans_ma_liste_est_optimiste_et_annule_en_cas_d_echec.

- **TDD, écart honnête** : le ViewModel a été écrit avant ses tests (pas de stub rouge séparé). Les deux premiers passages ont trouvé 2 vrais bogues, corrigés : (1) marquer une saison vue faisait sauter l'écran à la saison suivante (la saison sur laquelle on agit est maintenant figée) ; (2) un échec de « Ma liste » annulait aussi une marque précédente (on restaure la marque d'avant).
  Rouge a posteriori par mutation (saison initiale = première saison, lecture non refusée si masquée) : `15 tests completed, 4 failed` (saison_initiale…, sans_episode_en_cours…, saison_sans_episode…, masquage_apparu…) ; mutation retirée.
- **Final** : `cd apps && ./gradlew --max-workers=2 :tv:testDebugUnitTest :tv:assembleDebug --offline` -> BUILD SUCCESSFUL, 201 tests TV, 0 échec.

## Commits
155615d logique de la fiche ; c14eff9 écran, câblage, focus restauré (+ documents ensuite).

## Limite importante (backend, à décider par Claude)
`continue_watching` ne rend qu'**une ligne par série** (la plus récente). Le serveur ne permet donc pas de lire l'état de TOUS les épisodes d'une série : le câblage actuel (`continueWatching(profile, 200)` filtré sur la série) connaît l'épisode le plus récent, plus la file locale (300 entrées, vidée après envoi) et les marques posées pendant l'écran. Les épisodes vus plus tôt (autre appareil, avant) n'apparaîtront pas « vus » ni dans « 4 / 8 vus » tant qu'il n'existe pas de lecture par série.
Correctif proposé : une fonction `series_progress(p, series_id)` renvoyant toutes les lignes `watch_progress` de la série (dans la migration 0011, locale) + une méthode `ProgressClient.seriesProgress`, puis remplacer la lambda `serverProgress` dans `MainActivity.kt` (aucun changement de ViewModel ni de test). Non fait ici : hors de la tâche (backend, migration). Même limite pour l'épisode « suivant » de l'accueil, qui n'en dépend pas.

## Autres limites / choix
- `play` renvoie la demande au lieu de l'émettre par un flux (plus simple à tester) ; la tâche 6 branche `onPlayEpisode`.
- Démarquer un épisode laisse sa position côté serveur (règle de `set_watched`) : après rechargement un épisode à ≥ 90 % de position réapparaît « vu ». Un « vu » local en file d'envoi peut ressortir après un démarquage tant qu'il n'est pas envoyé.
- Une carte présente dans plusieurs rangées garde son propre focus par rangée ; la restauration retourne à la rangée d'origine.

## À vérifier sur la vraie télé (impossible ici)
1. D-pad : focus initial sur l'épisode en cours, haut/bas dans la liste de plusieurs centaines d'épisodes (fluidité, pas de saut), gauche vers les boutons, haut vers les onglets de saisons, focus toujours visible.
2. OK lit/reprend ; **appui long sur OK** marque vu/non vu (le long appui du D-pad central fonctionne-t-il sur la TCL ?).
3. Retour : fiche -> accueil Séries avec le focus sur la carte (ou le bouton Reprendre) d'origine ; Retour depuis le provisoire de lecture -> fiche (le focus y retombe sur le premier élément, pas restauré).
4. Marquer une saison vue/non vue : la saison reste affichée, le compte « n / total vus » et les ✓ suivent ; hors ligne : message et marque annulée.
5. Rendu des vraies affiches, titres longs, fiche sans résumé/genre/année/durée, saison vide.
6. Série masquée depuis l'iPhone pendant que la fiche est ouverte : « Série masquée ».
7. Ouverture hors ligne avec cache : fiche affichée avec « Progression indisponible ».
8. Taille de texte à 3 m (liste d'épisodes, onglets).

---

# Suite (décisions du coordinateur) : lecture par série, démarquage, focus

1. **`series_progress(p, series_id)`** (migration 0011, modifiée en place, non appliquée) : un seul tableau jsonb de toutes les lignes `watch_progress` de la série pour le profil, trié saison/épisode/identifiant, `[]` si aucune, `can_use_profile` sinon `42501`, droits `authenticated` seulement.
   - **RED** : `npm test -w backend` -> 3 échecs (`series_progress_rend_toutes_les_lignes_de_la_serie_en_une_valeur`, `series_progress_refuse_un_etranger`, démarquage). **GREEN** : 165/165 (1 200 épisodes en un seul bloc, ordre, autre série exclue, étranger refusé).
   - `ProgressClient.seriesProgress` + test MockEngine `seriesProgressCallsRpcAndDecodesEveryEpisode` : **RED** (stub `TODO()`) « 10 tests completed, 1 failed » (NotImplementedError), puis `:shared:jvmTest` vert (167 tests).
   - `MainActivity` : la fiche lit `seriesProgress` (tous les épisodes), fusionnée avec la file locale et les marques de l'écran, règle des 90 % (le ViewModel n'a pas changé). La limite « une seule ligne par série » de la section précédente est levée.
2. **Démarquer** : `set_watched(..., false)` remet aussi `position_s` à 0 (test backend `set_watched_marque_et_demarque…` : positions [0, 0, 0] ; README mis à jour : règle et ligne `series_progress`).
3. **Focus au retour de la lecture** : la fiche retient l'épisode lu et, à la fermeture de la lecture (`playing` passe à faux), fait défiler jusqu'à lui et lui rend le focus. À vérifier sur la télé.

**Final** : `npm test -w backend` 165/165 ; `cd apps && ./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest :tv:assembleDebug --offline` -> BUILD SUCCESSFUL (167 shared, 201 TV, 0 échec).
Commits : e8fb5df backend, 28a1757 client, puis câblage et focus.
Reste valable : un « vu » en file locale non envoyé peut ressortir après un démarquage tant qu'il n'est pas envoyé. Migration 0011 toujours non appliquée en production.
