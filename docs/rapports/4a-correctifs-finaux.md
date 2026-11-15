# Correctifs de la relecture finale, étape 4A

*15 novembre 2026, branche `plan-4-series`. Source : `docs/rapports/4a-relecture-finale.md`.*

## Statut : DONE_WITH_CONCERNS (voir « Réserves »)

## Ce qui a été corrigé

| Point | Correction |
|---|---|
| I1 | `series_progress` renvoie `series_category` (0011 modifiée sur place, README mis à jour). `SeriesRepository.cachedCategory` et `SeriesCatalog.cachedCategory` lisent le cache SQLDelight sans réseau. Accueil : le masquage se juge sur la progression non filtrée puis sur le cache, avant tout `info`. Fiche : la catégorie du cache est lue au départ et entre dans `knownCategories` (start et reload). `EpisodePlayback.begin` consulte le cache et répond `Failed(MASKED)` sans `loadInfo`. |
| I2 | 0011 : contrainte `duration_s >= 0` (remplace celle de 0005), « vu » seulement si `duration_s > 0`, enveloppe à 9 arguments inchangée et compatible (elle appelle la version à 12). Le client acceptait déjà 0 (test ajouté). |
| M1 | `start` remet `lastPosS` et `played` dès que l'épisode courant est assigné. |
| M3 | `orderedEpisodes` + `nextEpisode` uniques (index de l'identifiant + 1), utilisés par l'enchaînement et par « Ensuite » de l'accueil. |
| M4 | `onStop` appelle `persistNow()` directement, une fois de plus s'il renvoie faux ; constante et `runBlocking` retirés. |
| M5 | Test à deux profils (pA et pB lisent, on arrête pA, la file ne contient que pA, puis pB). |
| M6 | Test : demande d'envoi pendant un envoi bloqué, l'envoi échoue, un second passage a lieu. |
| M7 | `coverWithoutServer` (envoi : chemin seul si l'hôte est celui du fournisseur) et `coverForDisplay` + `LocalCoverServer` (affichage). Branchés dans la lecture, la fiche (marquer vu) et les trois affiches. |
| M12 | `CREATE INDEX IF NOT EXISTS series_item_id` dans `Series.sq` et `1.sqm` (modifiée sur place : jamais livrée). |
| M13 | « Épisode N » (test mis à jour). |
| M14 | Jeton `videoBackground` dans `IptvTokens`. |

## Preuves TDD

- RED backend (`npm test -w backend -- series`) : 3 échecs attendus (catégorie absente de `series_progress`, clés différentes, contrainte `watch_progress_duration_s_check` violée). GREEN : 167 tests.
- RED shared : `itemCategoryLookupByIdHasAnIndex` (index manquant) et `coverOnTheProviderServerKeeps...` (stub). GREEN après correction.
- RED tv (stubs compilables, 7 échecs) : accueil `expected:[b] but was:[b, a]` et `[a, b]` ; fiche « Expected value to be true » ; `begin` « le fournisseur n'est pas appele » ; doublons `expected b5 but was s1e6` ; M1 `@1500000` au lieu de 0 ; M7 hôte conservé. GREEN après correction.
- Écart : le test `cachedCategoryReadsTheCache...` a été écrit en même temps que son implémentation triviale (pas de RED séparé). Les tests M5, M6 et « durée 0 client » passent aussi sur le code précédent (ils comblent des trous de couverture, pas des défauts de code).

## Suites finales

| Suite | Résultat |
|---|---|
| Gradle (`:shared:jvmTest :tv:testDebugUnitTest :tv:assembleDebug --rerun-tasks`) | code 0 ; shared 172, tv 241, 0 échec ; APK construit |
| `npm test -w backend` | 167 réussis (11 fichiers) |
| `npm test -w web/config` | 124 réussis (10 fichiers) |

## Reporté (notes)

- M2 : intégré à I1.
- M8 (vu serveur / en cours client), M9 (course « non vu » et envoi en cours), M10 (relecture JSON de la file à chaque tour), M11 (rangée de catégorie lue en entier) : non traités, peu coûteux mais hors périmètre demandé ; à reprendre si l'essai sur la télé les montre.
- D1 à D6 inchangés (focus après Retour, application de 0011 en production avec `apply_migration`, page iPhone, horloge, position à l'arrêt, essai TCL).
- Limite connue notée dans PASSATION : une série inconnue partout est demandée une fois.

## Réserves

- Les affiches déjà en file avec l'adresse complète partent telles quelles (aucune donnée en production).
- La lecture du cache pour `cachedCategory` est synchrone (SQLite indexé) ; elle se fait hors fil principal dans l'accueil, la fiche et `begin`.
- Aucune migration appliquée en production, rien publié.
