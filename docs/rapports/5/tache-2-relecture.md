# Étape 5 — tâche 2 : relecture indépendante

16 novembre 2026. Périmètre : diff `ca5469e..86cc57e`, brief de tâche, rapport de réalisation, schémas SQL existants, modèle et construction des groupes. Aucun changement de code ni opération Git.

## Verdict

**Spécification : conforme. Qualité : approuvée.** Aucun constat Critical, Important ou Minor établi dans le périmètre de cette tâche.

- Les méthodes `SeriesRepository.searchItems()` et `MovieRepository.searchItems()` ne font que lire les nouvelles requêtes et fusionner les résultats. Aucune branche de fraîcheur ni appel de source fournisseur n'est emprunté.
- Les deux `UNION ALL` couvrent les listes et les fiches, dont les fiches isolées. La clé des tables de listes autorise plusieurs catégories par id ; la fusion conserve bien toutes ces catégories et celle de la fiche, même différente. L'ordre SQL rend le choix du titre déterministe. Aucun changement de schéma ou migration.
- `SearchSources.kt:7` élimine les titres sans catégorie non blanche et applique « masqué gagne » sur chaque catégorie. `SearchSources.kt:11` produit un élément par id avec l'union des catégories non blanches.
- `SearchSources.kt:21` respecte la décision du contrôleur : la présence d'au moins un id de source visible décide de l'existence du résultat ; les catégories des copies du groupe complet restent conservées. Un groupe sans copie visible disparaît ; les copies regroupées produisent un seul résultat.
- Les sept tests couvrent les catégories contradictoires liste/fiche, les fiches seules, les catégories inconnues/blanches, les doublons d'id, la stabilité, l'absence de réseau et le groupe dont une copie a été retirée. La régression `SearchSourcesTest.kt:45`–46 protège précisément la décision du contrôleur.

## Contrat à conserver lors de l'intégration

`SearchSources.kt:17`–19 et le rapport de réalisation explicitent la carte de groupes complète : le futur appelant doit fournir toutes les copies connues, puis appliquer le filtre de catégories effectif. Reconstruire cette carte sur les seules sources visibles ferait perdre une catégorie masquée. Ce point est un contrat d'intégration explicite, pas un défaut de cette tâche.

Les préférences inconnues doivent produire zéro résultat chez l'appelant ; l'ensemble vide signifie des préférences connues sans masquage. La fusion profil / Tout le monde et la sélection des masquages par type de section restent également chez l'appelant. Les tests de cette tâche vérifient le filtre sur un ensemble effectif, pas cette future orchestration.

## Vérification

Relecture statique des modifications et des contrats. Résultats fournis par le contrôleur et le rapport : **7 tests ciblés verts ; 290 tests shared verts**. Aucune suite relancée et aucune vérification TV/backend/web revendiquée. Aucun risque concret supplémentaire n'a justifié un test ciblé additionnel.
