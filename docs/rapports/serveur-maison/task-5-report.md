# Task 5 - rapport

## Implémentation
- `5.sqm` : `ALTER TABLE live_stream ADD COLUMN auto_key TEXT` et `base_title TEXT`. NB : le schéma courant était la version 5 (4 migrations), donc `5.sqm` fait passer 5 -> 6 (le brief dit "4 -> 5" par abus).
- `Catalog.sq` : colonnes ajoutées en fin de `CREATE TABLE` (même ordre que l'ALTER, pour que le test "migré == neuf" passe ; pas de commentaire dans la table, il avalerait la colonne suivante dans le dump normalisé). `insertStream` prend `auto_key`, `base_title` ; nouvelle requête `selectStreamKeys` (base_title non nul).
- `CatalogRepository` : `replaceLive(snapshot)` (une transaction : wipe streams + catégories puis insertions ; liste vide + cache rempli -> `XtreamError.Unexpected(200)` avant toute écriture ; `providerCategories = snapshot.categories`), `liveKeys()`, `refreshLive` écrit nul/nul.
- Android : `CatalogDriverFactory` utilise `AndroidSqliteDriver(CatalogDatabase.Schema, ...)`, la migration est automatique, aucun changement.
- Tests existants adaptés : `assertEquals(5L, Schema.version)` -> 6L (SeriesRepositoryTest, MovieRepositoryTest).

## TDD
- RED : `ReplaceLiveTest` ne compilait pas (replaceLive/liveKeys absents).
- GREEN : 5 tests (writesStreamsAndKeys, keepsCacheWhenSnapshotEmpty, emptyCacheWithEmptySnapshotIsHarmless, refreshLiveClearsKeys, migration5KeepsExistingRows).
- Un échec intermédiaire utile : test schéma migré vs neuf (commentaire dans CREATE), corrigé.

## Résultats
`:shared:jvmTest` 419 (414 + 5), `:tv:testDebugUnitTest` PASS.

## Fichiers
5.sqm, Catalog.sq, CatalogRepository.kt, ReplaceLiveTest.kt, SeriesRepositoryTest.kt, MovieRepositoryTest.kt.

## Concerns
Aucun notable. `hidden` du snapshot n'est pas utilisé par replaceLive (le serveur a déjà filtré).
