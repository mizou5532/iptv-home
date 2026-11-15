# Correctifs de progression — tâche 3

Base : d7c1ac1, branche plan-4-series. Travail limité à la tâche 3. Aucune production, publication, migration, clé de signature ou identifiant réel utilisé.

## Preuves TDD

Toutes les commandes Gradle s'exécutent après `source /workspace/iptv-env/activate.sh`, dans `apps`, avec `./gradlew --max-workers=2`.

| Exigence | Rouge observé avant correction | Vert / état |
|---|---|---|
| Conserver les enrichissements nulls au remplacement | `:shared:jvmTest --tests '*ProgressQueueTest.replacementRetainsMissingMetadata'` : 1 test, 1 échec AssertionError ligne 50 (seriesId perdu) | `:shared:jvmTest --tests '*ProgressQueueTest*'` : BUILD SUCCESSFUL, 11 tests |
| Horloge strictement croissante | `:shared:jvmTest --tests '*ProgressQueueTest.replacementTimestamp*'` : 1 test, 1 AssertionError ligne 49 | `:shared:jvmTest --tests '*ProgressQueueTest*'` : 12 tests verts ; horloge constante puis régressive | 
| Erreur permanente ne bloque pas | `:shared:jvmTest --tests '*ProgressFailureTest*'` : AssertionError (1 requête au lieu de 2), réponse SDK réelle HTTP403/code42501 via MockEngine | `:shared:jvmTest --tests '*Progress*Test*'` vert |
| Abandon au cinquième échec permanent | `:shared:jvmTest --tests '*ProgressFailureTest.permanentFailureDropped*'` : AssertionError (entrée encore présente après cinq appels) | `:shared:jvmTest --tests '*Progress*Test*'` vert |
| Expiration 14 jours | `:shared:jvmTest --tests '*ProgressFailureTest.entriesExpire*'` : AssertionError (appel réseau malgré expiration) | `:shared:jvmTest --tests '*Progress*Test*'` vert ; abandon à âge >= 14 jours |
| FlushResult / file vide | `:shared:jvmTest --tests '*ProgressQueueTest.emptyFlush*'` : ComparisonFailure (Unit au lieu de résumé) | `:shared:jvmTest --tests '*Progress*Test*'` vert ; sent/dropped/blocked vérifiés aussi sur envoi, expiration, cinquième refus |
| Classification SDK | Régression permanente rouge ci-dessus ; matrice de couverture ajoutée sans changement production | HTTP400/403/404/422 permanents ; 401/408/429/500/503 transitoires ; codes42501/P0001 permanents hors statut transitoire explicite, tests via vrai SDK et MockEngine verts |
| Modifications atomiques | `:shared:jvmTest --tests '*ProgressQueueTest.concurrentEnqueues*'` : AssertionError, pertes sur 100 insertions concurrentes | `:shared:jvmTest --tests '*ProgressQueueTest*'` : 14 verts, dont enqueue pendant flush ; `update` obligatoire, Android synchronized |
| Capacité 300 | `:shared:jvmTest --tests '*ProgressQueueTest.queueDrops*'` : 1 AssertionError (302 au lieu de 300) | `:shared:jvmTest --tests '*ProgressQueueTest*'` : 13 tests verts |
| apply / persistNow | `:tv:testDebugUnitTest --tests '*ProgressPersistenceTest*'` : 2 AssertionError (apply absent et méthode absente) | Même commande verte : écriture apply sans commit ; `LocalStore.persistNow(): Boolean` commit, résultat vrai/faux testé |
| Codec partagé, Instant/nulls, corruption entrée par entrée, clés inconnues, champs facultatifs | API d'abord absente (compile, pas rouge fonctionnel), extraction ancien décodeur ; `:shared:jvmTest --tests '*ProgressQueueCodecTest*'` : 2 tests, 1 AssertionError sur voisins valides perdus | `:shared:jvmTest --tests '*Progress*Test*'` vert ; codec commun branché dans Android |
| JSON illisible conservé sous clé de secours | `:tv:testDebugUnitTest --tests '*ProgressPersistenceTest*'` : AssertionError (secours null après remplacement) | Codec JVM + persistance Android ciblés verts ; `progress_queue_corrupt` conserve le document ; `{}`/`null` traités illisibles aussi |
| Affiche sans paramètres ni userinfo | `:shared:jvmTest --tests '*ProgressQueueTest.coverPersists*' --tests '*ProgressClientTest.recordAndWatchedStrip*'` : 2 ComparisonFailure | `:shared:jvmTest --tests '*Progress*Test*'` vert ; nettoyage queue, RPC record et setWatched, fragment retiré aussi |
| Auth record et publication, non-vu, délais/IOException | Tests de couverture demandés : comportements déjà corrects, aucun défaut production ni rouge artificiel | `:shared:jvmTest --tests '*Progress*Test*' --tests '*SyncClientTest.publishSeriesCategoriesWithout*'` vert : aucune requête sans session, false explicite, timeout/IOException bloquent sans incrément attempts ; annulation propagée | 
| Constantes onglets | `:tv:testDebugUnitTest --tests '*ProgressPersistenceTest*'` : 2 AssertionError (valeur inconnue acceptée, API chaîne libre) | Même commande verte : HomeTab.DIRECT/SERIES, roundtrip isolé par profil, inconnu ignoré |
| Priorité des statuts HTTP transitoires sur codes PG | `:shared:jvmTest --tests '*ProgressFailureTest.serverFailureWith*' --tests '*ProgressFailureTest.transientHttpStatus*'` : 2 tests, 2 AssertionError (401+42501 et 500+P0001) | Suite Progress* verte ; 401/408/429/>=500 prioritaires pour ne pas abandonner sur auth/proxy |

| Capacité persistée / ancienne file | `:shared:jvmTest --tests '*ProgressQueueCodecTest*'` : 2 AssertionError (302 entrées au décodage ancien et encodage direct) | Progress* + persistanceTV verts ; limite commune300, plus anciens retirés aussi au codec |
| Roundtrip Android | Couverture additionnelle, aucune correction nécessaire | Save/apply, persistNow, nouvelle instance AndroidLocalStore, load/update conservent Instant/nulls/attempts et profils |

## Checkpoints

- ee7629c : test de régression enrichissements sauvegardé et poussé avant correction.

## Interfaces pour les tâches 4–6

À compléter après les corrections.

## Vérification finale et limites

En cours ; aucune déclaration de complétude.
