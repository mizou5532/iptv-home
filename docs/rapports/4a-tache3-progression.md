# Task 3 report — progress client, offline queue, series prefs, series categories

## Implemented
- `shared/progress/ProgressClient.kt`: `ProgressEntry`, `ProgressRow`, `WatchedItem`, `ProgressClient`, `SupabaseProgressClient`. `record_progress` always sends all 12 keys (explicit JSON null). `continue_watching(p, max_rows)`, `set_watched(p, items, watched)` (empty list = no call), `org_set_series_list(p, series_id, on_)`.
- `shared/progress/ProgressQueue.kt`: `ProgressStore` (storage interface), `QueuedProgress`, `ProgressQueue(client, store, clock)`. One entry per (profile, itemId), latest wins; `clientUpdatedAt` stamped by the queue clock at enqueue (the value passed in `ProgressEntry` is overwritten); `flush()` in order, removes only after success (a `false` from the server = it keeps a newer position = counts as success), rethrows `CancellationException`, stops at first other exception; mutex against concurrent flushes; an entry re-enqueued during a send is not lost.
- `SyncModels`: `ProfilePrefs.seriesOrder: List<String>? = null`, `seriesList: List<String> = emptyList()`.
- `SyncClient.publishSeriesCategories` (RPC `publish_series_categories`, h + cats) + internal `SupabaseClient.requireAuthenticated()` shared by both clients.
- `LocalStore` now extends `ProgressStore`; added `audioLang`, `subtitleLang`, `saveTrackPrefs`, `lastTab`, `saveLastTab`; Android implementation in SharedPreferences (queue written with `commit()`).
- No-op overrides added to all SyncClient fakes (6 files) and LocalStore fakes (3 files).

## TDD
RED (queue stubbed with TODO(); client/model tests already green because they only needed the compiled API):
`./gradlew --max-workers=2 :shared:jvmTest --tests '*ProgressQueueTest' --tests '*ProgressClientTest' --tests '*SyncClientTest'`
-> `39 tests completed, 9 failed` (all ProgressQueueTest, `kotlin.NotImplementedError`).
GREEN after implementation: `./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest` -> BUILD SUCCESSFUL (shared 143 tests incl. 10 queue, 6 client, 3 new SyncClient; tv unit tests pass).
One test of mine (`entryEnqueuedDuringSendIsNotLost`) had a wrong expectation at first (flush correctly sends the newer 105 right after 100); fixed the assertion, not the code.

## Tests added
Queue: kept on network error then emptied; latest per episode; two profiles kept; stamped at enqueue; order + stop at first error; server-newer removes entry; cancellation rethrown and entry kept; survives restart; enqueue during send not lost; no stream/credential field in `ProgressEntry` descriptor.
Client (MockEngine): 12 keys incl. explicit nulls (both enriched and no-series cases); continue_watching decode; set_watched body; empty set_watched; org_set_series_list `on_`.
Sync: old prefs JSON without series keys -> defaults; series keys decoded; publish_series_categories body.

## Concerns
- `ProgressEntry.seriesCover` is a poster image URL by design (not a stream address); the "no URL" test checks field names only.
- A permanent server rejection (non-network exception) would block the queue head forever (stop at first error, per brief). No dead-letter handling; Task 6 may want one.
- Commit trailer follows implementer-rules.md (`Claude Opus 5.5` + Claude-Session), not the system-reminder's trailer.
