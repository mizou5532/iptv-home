# Séries : seulement 2 rangées (essai TCL 0.4.0) — cause et correction

*15 novembre 2026 · branche `fix-series-rows` (à partir de `plan-4-series`). Aucune mise en ligne, aucune nouvelle version.*

## En bref (pour le propriétaire)

**Ce que tu as vu.** Dans Séries, tout en bas : 2 rangées d'une dizaine de séries, toutes deux en arabe. Le fournisseur
a pourtant des dizaines de catégories de séries (françaises, anglaises…).

**Pourquoi.** Rien n'était perdu chez le fournisseur ni dans la mémoire de la télé : la liste des catégories arrivait
bien en entier, dans l'ordre du fournisseur, et les deux premières sont en arabe. Mais l'écran ne chargeait une rangée
que lorsqu'elle apparaissait à l'écran, et une rangée pas encore chargée n'avait rien sur quoi poser le curseur. Résultat :
depuis la 2e rangée, la flèche Bas ne trouvait rien en dessous, l'écran ne descendait pas, la 3e rangée n'apparaissait
jamais… donc ne se chargeait jamais. Un serpent qui se mord la queue. Les « une dizaine » : en 0.4.0, une rangée
montrait au plus 20 séries ; ce plafond est retiré.

**Ce qui est corrigé.**
- Toutes les catégories de séries non masquées sont des rangées, dans l'ordre du profil (sinon celui du fournisseur).
- En descendant, chaque rangée se charge, et les **2 rangées suivantes sont préparées d'avance** : quand tu appuies sur
  Bas, les affiches sont déjà là (moins d'attente « Chargement… »).
- Une rangée montre **toute** sa catégorie (on peut aller à droite jusqu'au bout).
- On ne charge jamais tout le catalogue d'un coup : seulement ce que tu regardes, plus 2 rangées. La mémoire de la télé
  reste bornée (les rangées loin derrière sont oubliées puis relues du cache de la télé si tu y reviens).
- Une catégorie masquée n'est jamais demandée au fournisseur.
- La lecture des réponses du fournisseur est plus tolérante (champs vides, nombres écrits bizarrement, séries sans nom) :
  aucune série ouvrable n'est plus écartée.

**Ce qu'il faudra vérifier sur la télé** (avec la prochaine version, quand tu l'autorises) :
1. Séries : descendre avec Bas jusqu'en bas ; toutes les catégories (françaises, anglaises…) doivent défiler.
2. Sur une rangée, aller à droite : plus de 20 séries si la catégorie en a plus.
3. Descendre lentement : les affiches de la rangée suivante doivent déjà être là la plupart du temps.
4. Descendre très vite puis remonter : rien ne doit rester bloqué sur « Chargement… ».

---

## Détails techniques

### Cause racine (preuves)

1. **Chargement déclenché seulement par la composition.** `SeriesHomeScreen` appelle `vm.loadCategory(id)` dans un
   `DisposableEffect` de chaque rangée du `LazyColumn` et `vm.cancelCategory(id)` à sa sortie. En 0.4.0, une rangée non
   chargée affichait un `Hint("Chargement…")` non focalisable. La recherche de focus de Bas composait la rangée suivante
   (hors écran), n'y trouvait rien de focalisable, la retirait : `cancelCategory` annulait sa demande pendant l'attente
   de 300 ms. Seules les rangées visibles à l'ouverture (les deux premières du fournisseur, en arabe) se chargeaient.
   Preuve : `SeriesDpadTest.bas_parcourt_toutes_les_rangees_de_categories` (écrit par l'autre agent sur
   `plan-4-series`, commit `87e14a4`, rouge sur 0.4.0 puis vert) ; ce commit a aussi rendu la carte d'attente
   focalisable et retiré le plafond `ROW_SIZE = 20`.
2. **Dans la vue-modèle (ce correctif)**, trois faiblesses restaient, chacune démontrée par un test rouge :
   - aucune rangée n'était préparée d'avance : chaque Bas attendait une réponse du fournisseur (lent en http) ;
   - `cancelCategory` annulait aussi une demande **déjà partie** : une rangée quittée pendant sa réponse ne se chargeait
     jamais et la réponse était jetée ;
   - une rangée composée avant l'arrivée des préférences n'était jamais chargée ensuite (retour anticipé sans mémoire).
   - aucune borne mémoire : toutes les rangées visitées restaient en mémoire, catégories entières comprises.
3. **Lecture Xtream** (fixtures inventées à types mélangés, `apps/shared/src/jvmTest/resources/xtream/series_*_mixed.json`) :
   - une catégorie à `category_id` vide (`""`) était gardée : `get_series&category_id=` renvoie **tout** le catalogue
     de séries sur la plupart des panneaux ;
   - une catégorie à nom nul disparaissait ; une série sans nom aussi ;
   - `"cover": false` devenait l'adresse `false` ; `"series_id": 5005.0` donnait l'identifiant `5005.0` ;
   - `{}` (catégorie vide), un BOM ou un avertissement PHP avant le JSON faisaient échouer toute la rangée.
   Les vraies réponses du fournisseur n'ont pas pu être observées (identifiants interdits ici) : ces points rendent la
   lecture robuste, ils n'expliquent pas à eux seuls les « 2 rangées ».
4. **Vérifié sans défaut** : pas de `LIMIT` ni de page dans `Series.sq` ou `SeriesRepository` (400 catégories et une
   catégorie de 5 000 séries gardées en entier, dans l'ordre) ; le plafond de 300 fiches ne concerne que les fiches
   (saisons/épisodes), pas les rangées ; « une réponse vide n'écrase jamais le cache » est inchangé.

### Correction

- `SeriesHomeViewModel` : les rangées composées forment l'ensemble « à l'écran » ; une seule boucle charge, une par
  une après 300 ms de calme, les rangées à l'écran puis les `LOOKAHEAD_ROWS = 2` rangées sous la plus basse (jamais une
  masquée, déjà en mémoire ou en échec). Une demande partie va au bout ; une rangée en échec est réessayée à son retour à
  l'écran ; tout repart quand les préférences ou l'ordre des rangées changent. Mémoire : `MAX_ROWS_IN_MEMORY = 24`
  rangées et 40 000 affiches au plus (LRU, jamais une rangée à l'écran).
- `XtreamApi` : `label()`/`idOf()` tolérants, catégorie sans identifiant écartée, noms de secours
  (`UNTITLED_SERIES`, `UNNAMED_CATEGORY`), `{}` = liste vide, BOM et avertissement PHP tolérés, délai triplé (45 s) pour
  les listes de séries.
- `SeriesRepository` : un identifiant de catégorie vide n'est jamais demandé ni listé (même depuis un ancien cache).
- `SeriesHomeScreen.kt` : non modifié.
- `SeriesHomeViewModel` : sans préférences connues, « Mise à jour » (`loading`) ne dure que pendant la première lecture
  du catalogue (avant : sans fin si les préférences ne venaient jamais) ; un message explique quoi faire.

### Tests

Rouge puis vert (chaque test nouveau a été vu échouer sur l'ancien code) :
- `XtreamSeriesTest` : `mixedTypeSeriesListKeepsEveryOpenableSeries`, `mixedTypeCategoriesKeepOrderAndNeverAnEmptyId`,
  `emptyObjectIsAnEmptyCategory`, `byteOrderMarkAndPhpNoticeBeforeJsonAreTolerated`,
  `seriesWithBlankNameKeptWithFallbackTitle` (remplace `seriesWithBlankNameSkipped`) ; verts d'emblée :
  `authErrorObjectStillUnexpectedForSeries`, `largeCategoryParsedEntirely`.
- `SeriesRepositoryTest` : `blankCategoryIsNeverRequestedNorListed` ; vert d'emblée :
  `manyCategoriesAndLargeCategoryKeptEntirelyInProviderOrder`.
- `SeriesHomeViewModelTest` : 8 échecs sur l'ancienne vue-modèle — `la_rangee_juste_sous_l_ecran_est_prechargee_et_pas_plus`,
  `descendre_rangee_par_rangee_charge_chaque_rangee_une_fois_sans_tout_le_catalogue` (60 rangées descendues, 63
  demandées, chacune une fois, ≤ 24 en mémoire), `le_prechargement_ne_demande_jamais_une_categorie_masquee`,
  `une_rangee_sortie_de_l_ecran_pendant_sa_demande_garde_son_resultat`,
  `une_rangee_en_echec_n_est_pas_redemandee_en_boucle_mais_l_est_a_son_retour_a_l_ecran`,
  `une_rangee_composee_avant_les_preferences_se_charge_des_qu_elles_arrivent`,
  `sans_preferences_ni_rangee_ni_chargement_de_categorie` (« Mise à jour » sans fin, signalé par l'agent B) et
  `une_categorie_masquee_n_est_jamais_demandee` (adapté : la rangée 2 est maintenant préchargée) ; vert d'emblée :
  `toutes_les_categories_non_masquees_deviennent_des_rangees_dans_l_ordre_du_profil` (300 catégories).

Suites (après rebase sur `plan-4-series` `d8f79eb`) : partagé **188/188** ; `SeriesHomeViewModelTest` **23/23** ;
backend **167/167** ; page web **124/124** ; `:tv:assembleDebug` et compilation des tests télé : OK.

**Non lancée : la suite télé complète (`:tv:testDebugUnitTest`)**. Cinq jars nécessaires à Robolectric
(`hamcrest-integration-1.3`, `conscrypt-openjdk-uber-2.8.0`, `snakeyaml-2.7`, `test-parameter-injector-1.25`,
`javawriter-2.1.1`) sont marqués « introuvables » dans le cache Gradle partagé de cette machine (Maven Central a
répondu 404/429). Le script d'init vers le miroir Google a été refusé par le système d'autorisations pour cet agent.
`SeriesHomeViewModelTest` (sans Robolectric) a donc été lancé directement avec JUnit sur les classes compilées par
Gradle. À relancer en entier (dont `SeriesDpadTest`) dès que le cache est réparé (`--refresh-dependencies` quand Maven
Central répond, ou le miroir).

### Rapport avec le correctif de l'agent A (`87e14a4`)

Même cause de fond, vue des deux côtés : l'agent A a corrigé l'**écran** (carte « Chargement… » focalisable, donc Bas
atteint toujours la rangée suivante, et plus de plafond de 20). Ce correctif **s'y ajoute** côté vue-modèle et
fournisseur, sans le remplacer : 2 rangées préparées d'avance, demande partie jamais jetée, nouvel essai d'une rangée en
échec, départ dès l'arrivée des préférences, mémoire bornée, et lecture Xtream tolérante (dont le `category_id` vide
qui aurait demandé tout le catalogue). `SeriesHomeScreen.kt` n'a pas été modifié ; le rebase n'a eu aucun conflit.

### Reste à vérifier sur la télé

- Les 4 essais ci-dessus.
- Le temps de réponse réel de `get_series` pour une grosse catégorie (délai porté à 45 s ; au-delà, « Indisponible pour
  l'instant », réessayé au retour sur la rangée).
- Le nombre réel de séries par catégorie chez le fournisseur (si une catégorie en a vraiment ~10, c'est normal).
