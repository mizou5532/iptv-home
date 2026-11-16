# Étape 5 — tâche 4 : modèle de recherche

16 novembre 2026 — branche `plan-5-recherche`.

## Réalisation

`SearchViewModel` expose `StateFlow<SearchUi>` et `setQuery(text)` selon le contrat du plan. La section est fixée à la construction ; le texte reste conservé pendant les reconstructions de cache et changements de préférences. La saisie attend 120 ms. Un changement de cache ne concerne que sa section ; il reconstruit et relance la requête sans nouvelle frappe. Chaque modèle garde son propre état ; sa durée de vie est celle du `scope` fourni (à annuler à la fermeture définitive).

Les préférences inconnues ne lisent aucune source et produisent le message exact prévu. Chaque émission de préférences efface les résultats avant toute suspension, invalide l'index et recalcule les masques. Construction, recherche et résolution locale des détails passent sur `io`. Une requête annule la précédente ; un numéro de génération et une vérification des préférences avant publication protègent aussi des calculs obsolètes. Les détails absents, dont l'identité diffère, sans catégorie ou dans une catégorie masquée sont omis. Aucun appel réseau ajouté.

L'index consomme `visibleSearchItems(items(), hiddenFor(prefs))` : les catégories connues sont conservées pour « masqué gagne ». L'intégration Direct devra fournir `liveSearchItems` avec le **plan complet des groupes connus**, les sources visibles pour déterminer la sélection et les masques Direct fusionnés ; ne jamais reconstruire les groupes à partir des seules copies visibles. `anchorSourceId` reste distinct de l'union des catégories. Revalidation au OK dans la tâche 5.

`ProfilePrefs.seriesHidden()` remplace les quatre filtres existants : `SeriesRows`, `SeriesHomeViewModel`, `SeriesDetailViewModel`, `MainActivity`. Les autres types de masque sont ignorés. Aucun changement de comportement des écrans intégré dans cette tâche.

## Messages et interface avec la tâche 5

`SearchUi.message` contient exactement les textes d'aide, de préférences inconnues ou d'absence de résultat du plan. Une exception locale inattendue produit `Recherche indisponible.` sans donnée sensible. `SearchUi.progress` expose seulement l'avancement incomplet de la section courante ; il est absent avec préférences inconnues et réapparaît dès leur chargement, même sans nouvelle émission d'avancement.

La tâche 5 doit afficher **séparément** cette ligne à partir de `progress` : `Catalogue en préparation : <done> catégories sur <total>. D'autres résultats peuvent apparaître.` Le moteur partagé garde ses règles de surnoms/classement et sa limite de 60. Les callbacks `items` et `details` doivent lire exclusivement le cache local ; `details` peut retourner `null` si un titre a disparu. Conserver le même modèle au retour d'une fiche préserve la requête ; reconstruire par profil évite de mélanger leurs états.

## Tests d'abord et preuves

Fichier : `apps/tv/src/test/kotlin/home/iptv/tv/search/SearchViewModelTest.kt`.

- Premier lancement : échec de compilation attendu, API et extension absentes (`task-4-red.log`).
- Squelette de l'API sans comportement : **10 tests, 9 échecs** attendus (`task-4-red-behavior.log`). Le dixième vérifie la section immuable/ignorance des notifications d'autres sections.
- Implémentation : **10/10 verts** (`task-4-green.log`).
- Auto-relecture : ajout d'un cas null→préférences connues avec avancement inchangé ; **11 tests, 1 échec** attendu (`task-4-progress-red.log`). Correction de la lecture de l'avancement actuel lors du changement des préférences.
- Vérification ciblée finale officielle : **11/11 verts** (`task-4-final-focused.log`).
- Suite TV officielle complète après le dernier changement : **331 tests, 0 échec, 0 erreur**, y compris les tests Robolectric existants (`task-4-full-tv.log`). `git diff --check` propre.

Commandes exécutées après `source /workspace/iptv-env/activate.sh`, depuis `apps` :

```sh
./gradlew --max-workers=2 :tv:testDebugUnitTest --tests '*SearchViewModelTest*'
./gradlew --max-workers=2 :tv:testDebugUnitTest
```

Les journaux détaillés locaux sont sous `/workspace/iptv-work/5/`. Les onze cas couvrent frappe rapide, cache changé sans retaper, masquage pendant une reconstruction suspendue, préférences inconnues et retrait des résultats, messages exacts, avancement incomplet/terminé et restauration, ancienne lecture non coopérative, union des catégories masquées/détails absents, section fixe et extraction des masques séries.

## Auto-relecture et limites

Diff limité au modèle, ses tests, l'extraction des quatre filtres séries et ce rapport. Aucune modification fournisseur, configuration, production, signature ou version. L'UI, la voix, les règles de focus, l'intégration Direct et la revalidation d'un résultat au OK restent la tâche 5 ; aucun essai TCL revendiqué ici. Le contrôleur coordonne la relecture indépendante et la mise à jour du plan/passation/journal. La suite partagée n'a pas été relancée : aucun fichier partagé modifié ; le dernier état communiqué par le contrôleur est 305 tests partagés verts.
