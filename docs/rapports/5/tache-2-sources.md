# Étape 5 — tâche 2 : sources de recherche

16 novembre 2026. Réalisation sur `plan-5-recherche`, moteur tâche 1 conservé. Auto-relecture terminée ; relecture indépendante pilotée par le contrôleur.

## Résultat et interfaces

- `SeriesRepository.searchItems(): List<SearchItem>` et `MovieRepository.searchItems(): List<SearchItem>` sont des méthodes publiques synchrones, lecture seule du cache SQLDelight ; aucune demande réseau, aucune condition de fraîcheur. Les sources réseau des tests échouent immédiatement si appelées.
- `selectAllSeriesTitles` / `selectAllMovieTitles` lisent par `UNION ALL` les tables liste **et** fiche, y compris les fiches isolées et les catégories contradictoires. Seuls id, nom et catégorie sont lus. Aucun changement de table, index, migration ou schéma.
- Un élément par id, avec union de toutes les catégories non blanches. Le titre retenu est celui de la première ligne dans l'ordre SQL déterministe id/catégorie/nom ; aucune préférence arbitraire liée à l'ordre des insertions.
- `visibleSearchItems(items, hidden)` élimine tout titre ayant une catégorie masquée ou aucune catégorie non blanche. Un ensemble de masquages vide signifie des préférences connues sans masquage. **Préférences nulles : l'appelant doit produire zéro résultat avant cet appel** (tâche 4).
- `liveSearchItems(sources, groups)` conserve les groupes ayant au moins une copie dans les sources visibles ; id/titre sont ceux du groupe. Les catégories combinent toutes les copies connues dans `group.sources` et les copies visibles fournies. **L'appelant doit fournir la carte des groupes avec toutes les copies connues**, même quand la liste des sources a déjà été filtrée. Ensuite appliquer `visibleSearchItems` avec les masquages de catégories effectifs. Les masquages de chaînes restent pris en charge en amont par `visibleStreams`.

## Point de contrat tranché avec le contrôleur

Le brief mentionne des copies déjà filtrées ; l'exigence globale et la conception §6.2 imposent pourtant « une catégorie connue masquée écarte le titre ». La carte des groupes complète conserve donc cette connaissance : une copie masquée retirée de `sources` ne doit pas effacer sa catégorie connue. Si les groupes eux-mêmes ont déjà été reconstruits sur les seules copies visibles, cette connaissance est perdue ; tâches 4/5 doivent fournir la carte complète. Régression explicite : RDS HD/FHD, catégories a/b, copie b retirée des sources, groupe toujours connu dans a/b → filtre b écarte le groupe.

## Vérification réellement exécutée

Commandes après `source /workspace/iptv-env/activate.sh`, depuis `apps`, un seul Gradle :

1. `./gradlew --max-workers=2 :shared:jvmTest --tests '*SearchSourcesTest*'` : premier rouge de compilation pour API absente (`task-2-red.log`), puis stubs minimaux pour rouge comportemental : **7 tests, 6 échecs d'assertion** (`task-2-red-behavior.log`). Le contrat ensemble caché vide passe déjà.
2. Après implémentation : **7/7 verts**. Renforcement du contrat Direct : **7 tests, 1 échec d'assertion** (`task-2-live-red.log`), puis correction et **7/7 verts** (`task-2-green.log`).
3. `./gradlew --max-workers=2 :shared:jvmTest` : **290 tests, 0 échec, 0 erreur, 0 ignoré**, BUILD SUCCESSFUL (`task-2-full-shared.log`). Dont **7 SearchSourcesTest** et **38 MovieRepositoryTest**, avec migration 2→3, comparaison de schéma migré/frais et migration répétée.
4. `git diff --check` : réussi.

Logs locaux dans `/workspace/iptv-work/5/`. Aucun test TV/backend/web revendiqué pour cette tâche limitée aux sources partagées. Avertissement de dépréciation préexistant `SyncClient.kt:107` lors de compilation ; aucune erreur.

## Auto-relecture

Périmètre vérifié : deux requêtes nouvelles uniquement, deux méthodes dépôt, fonctions pures et sept tests SQLite réels. Catégories multiples liste/fiche, fiche seule, catégorie inconnue/blanche, doublon d'id, titre stable, masquage et groupe filtré couverts. Aucun accès réseau, écriture de cache, changement backend/page/version/signature ou migration. Pas de blocage ; contrat de carte de groupes complète à respecter à l'intégration. Les préférences utilisées sont déjà effectives (fusion profil / Tout le monde à la charge de l'appelant) ; aucune nouvelle fusion de préférences introduite ici.
