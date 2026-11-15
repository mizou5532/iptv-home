# Correctifs de progression — tâche 3

Base : d7c1ac1, branche plan-4-series. Travail limité à la tâche 3. Aucune production, publication, migration, clé de signature ou identifiant réel utilisé.

## Preuves TDD

Toutes les commandes Gradle s'exécutent après `source /workspace/iptv-env/activate.sh`, dans `apps`, avec `./gradlew --max-workers=2`.

| Exigence | Rouge observé avant correction | Vert / état |
|---|---|---|
| Conserver les enrichissements nulls au remplacement | `:shared:jvmTest --tests '*ProgressQueueTest.replacementRetainsMissingMetadata'` : 1 test, 1 échec AssertionError ligne 50 (seriesId perdu) | `:shared:jvmTest --tests '*ProgressQueueTest*'` : BUILD SUCCESSFUL, 11 tests |
| Horloge strictement croissante | `:shared:jvmTest --tests '*ProgressQueueTest.replacementTimestamp*'` : 1 test, 1 AssertionError ligne 49 | `:shared:jvmTest --tests '*ProgressQueueTest*'` : 12 tests verts ; horloge constante puis régressive | 
| Classification erreurs / attempts / 14 jours / résultat | À faire | À faire |
| Modifications atomiques | À faire | À faire |
| Capacité 300 | `:shared:jvmTest --tests '*ProgressQueueTest.queueDrops*'` : 1 AssertionError (302 au lieu de 300) | `:shared:jvmTest --tests '*ProgressQueueTest*'` : 13 tests verts |
| apply / persistNow | À faire | À faire |
| Codec partagé / corruption / secours | À faire | À faire |
| Affiche sans paramètres ni userinfo | À faire | À faire |
| Auth record et publication, non-vu, file vide | À faire | À faire |
| Constantes onglets | À faire | À faire |

## Checkpoints

- ee7629c : test de régression enrichissements sauvegardé et poussé avant correction.

## Interfaces pour les tâches 4–6

À compléter après les corrections.

## Vérification finale et limites

En cours ; aucune déclaration de complétude.
