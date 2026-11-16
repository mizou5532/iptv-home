# Relecture indépendante — tâche 2 Kotlin

16 novembre 2026. Périmètre : différences `apps` de `b305eff` à `943f550`, catalogue distant compris, brief initial, rapport Kotlin et conception Films validée. Relecture statique des implémentations, contrats backend et tests ; aucune suite relancée, aucune production ni donnée IPTV réelle consultée. Seul ce rapport est modifié.

## Verdict

**Spécification : largement conforme, avec une réserve importante sur la protection du cache des fiches face à une réponse invalide. Qualité : changements cohérents et bien couverts ; corriger I1 avant validation sans réserve de la tâche 2.** Aucun problème Critical identifié.

Les signatures effectives de MovieClient sont conformes à la sémantique attendue : `MovieListItem` pour les quatre colonnes de Ma liste, `ProgressRow?` pour l'état fiche et `MovieSummary` pour les mutations. Les noms/signatures provisoires du brief ne constituent pas des exigences supplémentaires.

## Important

**I1 — Une erreur imbriquée dans `info` est prise pour une fiche valide et remplace le cache.** `apps/shared/src/commonMain/kotlin/home/iptv/shared/xtream/XtreamApi.kt:158` et `apps/shared/src/commonMain/kotlin/home/iptv/shared/movies/MovieRepository.kt:137`.

Après une vraie fiche déjà enregistrée, attendre son expiration puis recevoir HTTP 200 avec `{"info":{"error":"temporarily unavailable"}}`. `movieInfo` accepte tout objet `info` non vide, même sans aucun champ film reconnu, et fabrique un MovieSummary avec titre de remplacement, extension mkv et métadonnées nulles. La valeur n'est pas null : `refreshIfStale` la considère valide, supprime l'ancienne fiche, écrit cette nouvelle valeur et renouvelle la fraîcheur pour six heures. Sans film listé, le titre, l'affiche et les détails précédents disparaissent ; avec une liste en cache, les informations exclusives à la fiche disparaissent toujours. Cela contredit la conservation d'un cache valide face aux réponses mal formées exigée par la conception (§ Données).

Refuser les enveloppes d'erreur ou objets sans contenu film exploitable, tout en conservant la tolérance aux fiches partielles reconnues (par exemple résumé seul). Ajouter à `emptyOrMalformedProviderAnswersNeverOverwriteAValidCache` un cas d'erreur dans `info` et dans `movie_data`, en vérifiant aussi que la date de fraîcheur ne change pas. Le constat est déduit directement du chemin de code ; ce scénario n'a pas été exécuté pendant cette relecture. Le test existant couvre les erreurs à la racine, les listes vides et les formes illisibles, pas cet objet imbriqué non vide.

## Vérifications positives

- Xtream : catégorie obligatoire pour les listes, refus local des identifiants blancs, parsing tolérant nombres/chaînes, extension filtrée, erreurs réseau sans URL complète. Aucune requête globale ajoutée.
- Catalogue : TTL 24 h catégories/listes et 6 h fiches ; panne et réponse vide préservent le cache valide ; transactions de remplacement et d'horodatage ; annulation propagée. `moviesOf` ne lit ni ne demande une catégorie masquée reçue en argument. Les récents utilisent uniquement les catégories connues, une date positive au plus demain et des pages bornées ; les doublons visibles sont éliminés.
- Migration `2.sqm` additive : seulement tables/index films ; aucune modification/destruction des tables direct/séries. Les tests construisent un schéma 2 avec données, migrent vers 3, vérifient contenu et dates et comparent le schéma frais au schéma migré.
- Progression : identité profil + type + id dans remplacement et recherche d'antériorité ; flush/suppression par entrée complète préserve les autres positions et les remplacements pendant une suspension. Les champs film restent distincts des champs séries dans les RPC ; les couvertures synchronisées sont nettoyées.
- Compatibilité : type par défaut episode dans ProgressEntry et ProgressRow, codec tolérant les anciennes entrées, préférences movieOrder null/movieList vide ; routage épisodes conserve son corps explicite de 12 clés. Films vers record_movie_progress avec huit clés, autres opérations comparées au contrat backend/README.md ; lecture d'état incluant les films vus.
- Télé : suppression des seules entrées épisode dans MainActivity via dropQueuedEpisodes ; queuedAsRows et regroupements home/detail filtrent le type avant fusion. Les régressions du même id film/épisode sont couvertes.

## Minor

Aucun autre problème suffisamment établi pour être signalé. Le code garde les chaînes de type en dur dans quelques filtres TV alors que des constantes existent, mais cela ne constitue pas ici un défaut fonctionnel.

## Preuves et limites

Résultats rapportés par l'implémenteur, sans réexécution indépendante : 260 tests shared verts ; 267 tests de logique TV verts dans le harnais externe ; APK debug construit. Ces nombres ne constituent pas une validation de la suite Android officielle.

La suite Robolectric officielle reste bloquée avant exécution par le téléchargement HTTP 403. Le premier élargissement du harnais simple produisait six erreurs Android `Stub!` dans ProgressPersistenceTest ; cette classe a été exclue du passage final, avec les classes Robolectric. Ces six erreurs ne sont ni présentées comme six régressions produit ni comptées comme vertes. La persistance Android réelle, les interactions télécommande et les tests d'écran Films ne sont donc pas vérifiés ici.

Aucun contrôle d'intégration supplémentaire exécuté. Les cas déterminants ont été suivis statiquement dans le code et leurs tests existants. Après correction de I1, un test ciblé MockEngine → MovieRepository → SQLite en mémoire suffira pour cette régression, puis la suite shared ; il ne nécessite ni fournisseur réel ni backend réel. Les validations Android officielles attendront la disponibilité des dépendances.

Le masquage modifié pendant une requête et l'autorisation des fiches/lecture restent à réaliser et vérifier dans la tâche 3 : MovieRepository.info ne reçoit pas de préférences masquées. Cette limite d'intégration n'est pas assimilée à un manquement de la tâche 2 isolée. Pas de validation ici de performance réelle sur appareil, de migration à partir d'un fichier Android de production ni de déploiement.

## Relecture du correctif — ronde 1, commit 6863bf8

**I1 : ADDRESSED. Verdict actualisé : réserve I1 levée ; aucun nouveau problème identifié dans le correctif.** Cette relecture est limitée au diff du correctif et aux preuves ajoutées au rapport Kotlin ; aucune suite relancée.

Le garde de `XtreamApi.movieInfo` exige désormais au moins une valeur de champ film reconnu dans `info` ou `movie_data`. `label()` refuse les valeurs nulles, blanches, booléennes ou structurées ; les objets contenant seulement `error` ou des clés inconnues lèvent `XtreamError.Unexpected`. La liste des champs inclut les champs descriptifs et techniques déjà interprétés : les fiches légitimes pauvres, notamment résumé seul et identifiant seul, restent acceptées, avec des assertions dédiées. Les enveloppes vides conservent leur comportement null.

Le chemin repository existant intercepte cette erreur avant toute transaction d'écriture et conserve le cache déjà chargé. Le nouveau test d'intégration `nestedErrorDetailsPreserveWarmCacheAndDoNotRenewFreshness` chauffe une vraie fiche, avance de sept heures, puis vérifie pour les deux réponses invalides que la fiche et son horodatage sont inchangés. Son compteur de trois requêtes vérifie aussi que le deuxième échec n'est pas empêché par un renouvellement de fraîcheur accidentel. Le test du parser couvre l'erreur sous `info`, les clés inconnues sous `movie_data`, les deux sections invalides ensemble et les deux formes pauvres valides.

Preuves rapportées : RED ciblé 53 tests / 2 échecs correspondant aux deux régressions ajoutées ; GREEN ciblé 53 / 0 ; suite shared finale 262 / 0. Ces résultats n'ont pas été réexécutés ici. Les limites Android officielles, persistance Android et tâche 3 précédemment décrites restent applicables ; ce correctif ne les revendique pas validées.
