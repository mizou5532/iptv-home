# Étape 5 — Tâche 1 : moteur partagé

16 novembre 2026. Implémentation limitée au moteur pur Kotlin ; aucune modification Android, fournisseur, backend, production ou configuration.

## Contrat livré

`SearchItem(id, title, categoryIds)`, `SearchIndex(items)`, `size`, `search(query, limit = SEARCH_LIMIT)`, `SEARCH_LIMIT = 60`, `searchWords(text)` et `NICKNAMES` dans `home.iptv.shared.search`.

Normalisation via `searchNorm`, découpage des séparateurs, index inversé des mots distincts groupés par première lettre et longueur. Tous les groupes de première lettre restent candidats pour autoriser une faute initiale. Intersection des résultats de chaque mot, union des équivalents normalisés. Aucun calcul de distance sur chaque titre. Dernier mot : distance sur préfixe ; autres mots : distance sur le mot entier. Damerau-Levenshtein sans restriction (récurrence Lowrance-Wagner), avec longueur du préfixe candidat bornée par le budget. La correction de relecture ci-dessous remplace le calcul OSA initial. Seuils : 0 avant 4 lettres, 1 de 4 à 7, 2 à partir de 8. Score du pire mot : égalité du titre, préfixes, contenus, fautes ; égalités départagées par longueur puis titre normalisé, titre original et identifiant.

Les identifiants dupliqués conservent le premier titre et réunissent leurs catégories ; `size` compte les identifiants uniques. Le plafond 60 s'applique aussi si l'appelant demande davantage ; une limite <= 0 renvoie une liste vide. Le moteur reçoit les éléments déjà filtrés : il ne décide pas lui-même des masquages.

## Tests réellement exécutés

Environnement : `source /workspace/iptv-env/activate.sh`, puis `cd /workspace/iptv-home/apps`.

1. RED : `./gradlew --max-workers=2 :shared:jvmTest --tests '*SearchEngineTest*'` : sortie 1, `BUILD FAILED in 20s`, tâche `:shared:compileTestKotlinJvm`, références absentes `SearchIndex`, `SearchItem`, `searchWords` ; aucun test exécuté. Journal externe `task-1-red.log`.
2. GREEN ciblé : même commande : sortie 0, `BUILD SUCCESSFUL in 15s`, **16 tests, 0 échec, 0 erreur, 0 ignoré**. Journal externe `task-1-green.log`.
3. Après nettoyage d'une assertion Kotlin inutile : `./gradlew --max-workers=2 :shared:jvmTest` : sortie 0, `BUILD SUCCESSFUL in 13s`, **280 tests, 0 échec, 0 erreur, 0 ignoré**. Journal externe `task-1-full.log`. Seul avertissement restant : dépréciation préexistante dans `SyncClient.kt:107`.

Couverture : tous les cas demandés, surnoms accentués et valeurs accentuées, faute initiale, tous les mots obligatoires, faute sur préfixe seulement en dernier mot, séparation, catégories réunies, limites et stabilité. Comptage vérifié : `montrael` est bien composé de **8** caractères (`m o n t r a e l`) ; le cas reste couvert comme transposition. Le seuil 8+ est aussi testé avec `barxeloza` (deux substitutions) et un mot de 7 lettres avec deux substitutions doit échouer.

## Performance

50 000 titres effectivement indexés, graine Kotlin `Random(571)`, 20 racines sémantiques et 6 000 entrées de vocabulaire enrichies de suffixes alphabétiques aléatoires de 2 à 6 caractères ; trois mots par titre. 20 requêtes variées (partiels, surnoms, fautes et plusieurs mots), échauffement de trois requêtes hors mesure ; construction hors mesure. Chronométrage de la suite complète en ms :

```text
SEARCH_PERF canadi 22.15 ms
SEARCH_PERF montreal 22.674 ms
SEARCH_PERF habs 33.256 ms
SEARCH_PERF barca 40.846 ms
SEARCH_PERF aventure 24.409 ms
SEARCH_PERF mystre 20.074 ms
SEARCH_PERF documentair 26.975 ms
SEARCH_PERF scence 28.404 ms
SEARCH_PERF nature 26.045 ms
SEARCH_PERF histoire 31.992 ms
SEARCH_PERF cinema 15.447 ms
SEARCH_PERF sport 17.132 ms
SEARCH_PERF qc 18.74 ms
SEARCH_PERF psg 17.258 ms
SEARCH_PERF voyge 15.802 ms
SEARCH_PERF ocean 14.397 ms
SEARCH_PERF foret 16.519 ms
SEARCH_PERF musique 20.709 ms
SEARCH_PERF culture eto 10.769 ms
SEARCH_PERF xanadiens 30.493 ms
```

Maximum 40,846 ms sur cette JVM ; les vingt assertions < 100 ms passent. Cela ne mesure pas le matériel TCL.

## Auto-relecture et limites

Vérifiés : limites de longueurs sans exclusion d'une faute initiale, tableau de distance borné, déduplication, classement déterministe, intersection de tous les mots, normalisation symétrique des surnoms, aucune dépendance Android et aucun journal de catalogue réel. Le vocabulaire est parcouru par mots distincts ; les listes de résultats candidates sont ensuite triées. L'index n'effectue aucun téléchargement et doit être reconstruit après changement de catalogue/préférences par les tâches suivantes. Les tests TV et suites web/backend relèvent de la validation globale, non exécutés par cette tâche. Aucune réserve bloquante identifiée ; relecture indépendante à effectuer par le coordinateur.

## Relecture indépendante — correction 1

La relecture a relevé une réserve importante valide : OSA interdit de rééditer une lettre transposée et refusait donc deux éditions autorisées. Remplacement par la récurrence Damerau-Levenshtein sans restriction de Lowrance-Wagner, mémorisant la dernière ligne de chaque caractère et la dernière colonne correspondante. Matrice des mots distincts seulement ; préfixe candidat tronqué à longueur de requête + budget, minimum des colonnes admissibles à la dernière ligne. La longueur est bornée sans couper les transpositions qui se chevauchent.

Régressions indépendantes : `abcdefca sport` trouve `abcdefabc sport` (transposition puis insertion), et `abcdefcaxy` trouve `abcdefabcxyz` sur préfixe. Le suffixe `xy` dans ce second cas est essentiel : `abcdefca` seul pouvait déjà trouver le préfixe court `abcdefa` par une suppression, sans démontrer le défaut OSA. Ajout du classement exact > préfixe > contenu > faute (`Sport`, `Sportif`, `Transport`, `Spart`).

Commandes réelles (même activation et répertoire `apps` que ci-dessus) :

- RED : `./gradlew --max-workers=2 :shared:jvmTest --tests '*SearchEngineTest*'` → sortie 1, `19 tests completed, 2 failed`, les deux régressions nommées `deux_editions_qui_se_chevauchent` et `deux_editions_sur_prefixe_qui_se_chevauchent`, `BUILD FAILED in 3s`. Journal externe `task-1-review-red.log`.
- GREEN ciblé : même commande → sortie 0, **19 tests, 0 échec/erreur/ignoré**, `BUILD SUCCESSFUL in 7s`. Journal externe `task-1-review-green.log`.
- Suite partagée après dernier changement de code : `./gradlew --max-workers=2 :shared:jvmTest` → sortie 0, **283 tests, 0 échec/erreur/ignoré**, `BUILD SUCCESSFUL in 7s`. Journal externe `task-1-review-full.log`. Aucun changement de code après cette validation.

Performance réelle de cette dernière suite (même corpus 50 000 titres et échauffement de trois requêtes) :

```text
SEARCH_PERF canadi 18.547 ms
SEARCH_PERF montreal 20.33 ms
SEARCH_PERF habs 28.414 ms
SEARCH_PERF barca 89.659 ms
SEARCH_PERF aventure 21.198 ms
SEARCH_PERF mystre 22.177 ms
SEARCH_PERF documentair 32.434 ms
SEARCH_PERF scence 15.505 ms
SEARCH_PERF nature 15.758 ms
SEARCH_PERF histoire 16.988 ms
SEARCH_PERF cinema 15.645 ms
SEARCH_PERF sport 14.796 ms
SEARCH_PERF qc 15.868 ms
SEARCH_PERF psg 19.003 ms
SEARCH_PERF voyge 22.933 ms
SEARCH_PERF ocean 17.515 ms
SEARCH_PERF foret 14.985 ms
SEARCH_PERF musique 17.076 ms
SEARCH_PERF culture eto 15.173 ms
SEARCH_PERF xanadiens 62.593 ms
```

Les vingt requêtes restent sous 100 ms ; maximum **89,659 ms**. La marge de cette mesure est moindre que celle du calcul OSA initial, qui était incorrect. Aucune mesure de la TCL revendiquée. Auto-relecture : sentinelles, initialisation, positions précédentes et bornes du préfixe vérifiées contre la récurrence sans restriction ; les scénarios de classement et les tests initiaux sont conservés. Aucune réserve fonctionnelle restante identifiée par l'implémenteur ; relecture indépendante de la correction à effectuer.
