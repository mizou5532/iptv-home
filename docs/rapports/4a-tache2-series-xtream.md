# Task 2 report - Client Xtream series et cache local

## Implemented
- Models (XtreamModels.kt): SeriesCategory, SeriesSummary, Episode, Season, SeriesInfo (exact brief names).
- XtreamApi: seriesCategories(), series(categoryId), seriesInfo(seriesId): SeriesInfo? (null on empty provider answer).
  Handles: episodes as object keyed by season, flat array with `season` field, array of season arrays;
  duration_secs (int/string) or duration "HH:MM:SS"/"MM:SS"; missing/empty container_extension -> "mkv";
  cover or movie_image (info.movie_image for episodes); numbers as strings; seasons without episodes kept empty
  ("Saison N" default name); seasons only present in episodes are derived; year from year or releaseDate.
  `StreamKind.SERIES` / `streamUrl` already existed (tested for episodes).
- SeriesRepository.kt: `SeriesSource` (+ `SeriesSource.of(api)`), `SeriesRepository(db, api, clock: kotlin.time.Clock = Clock.System)`.
  categories(hidden), seriesOf(categoryId, hidden = emptySet()), info(seriesId). TTL: 1 d / 1 d / 6 h.
  Stale cache served on provider error; error propagated when nothing was ever cached; CancellationException always rethrown.
  Data + freshness date written in one transaction; a failed fetch never alters the cache.
- Series.sq (same CatalogDatabase, queries in `seriesQueries`) + `1.sqm`.

## Migration choice
SQLDelight migration `1.sqm` (schema version 1 -> 2) in the same database, rather than a separate `series.db`.
Reason: no second driver/file to wire on Android, and the existing driver upgrades automatically (AndroidSqliteDriver
runs Schema.migrate). Existing live cache is untouched (only CREATE TABLE IF NOT EXISTS). Tested: a hand-built
version-1 DB with a live row migrates, keeps the live row, and the series repository works on it.
Caveat: table DDL is duplicated between Series.sq and 1.sqm (SQLDelight requirement for fresh vs upgraded installs).

## Design decisions to note for later tasks
- `seriesOf` takes an optional `hidden` set (brief signature has none): hidden category => empty result, no request, no cache read.
  `info()` cannot know the category before fetching, so "hidden wins" for a series fiche must be enforced by the caller (tasks 4/5).
- Cache holds all provider categories; `categories(hidden)` only filters on read.
- When a category disappears from the provider list, its cached series and freshness entry are deleted.

## TDD evidence
RED (compiling stubs returning empty/null): `cd apps && ./gradlew --max-workers=2 :shared:jvmTest`
-> "110 tests completed, 19 failed" (XtreamSeriesTest and SeriesRepositoryTest assertion failures, e.g.
`seriesListParsedWithStringNumbersAndMissingFields FAILED AssertionError`, `hiddenCategoriesAreFilteredOut FAILED`).
GREEN: same command -> BUILD SUCCESSFUL, 110 tests, 0 failures. `:shared:compileDebugKotlinAndroid` BUILD SUCCESSFUL.

## Files
- apps/shared/src/commonMain/kotlin/home/iptv/shared/xtream/XtreamApi.kt, XtreamModels.kt
- apps/shared/src/commonMain/kotlin/home/iptv/shared/series/SeriesRepository.kt
- apps/shared/src/commonMain/sqldelight/home/iptv/shared/catalog/Series.sq, 1.sqm
- apps/shared/src/jvmTest/kotlin/home/iptv/shared/xtream/XtreamSeriesTest.kt
- apps/shared/src/jvmTest/kotlin/home/iptv/shared/series/SeriesRepositoryTest.kt

## Self-review
No credential in any message/toString (errors reuse XtreamError; test asserts no password in message).
Non-ASCII test names: none. No new warnings from new code.
XtreamUrls.kt needed no change.
