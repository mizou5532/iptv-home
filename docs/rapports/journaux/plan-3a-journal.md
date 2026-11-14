# Journal de construction — plan 3a (copie du registre de travail, en anglais)

Décisions (`Ruling:`), relectures, corrections et points reportés (`minor`/`deferred`), dans l'ordre.

```text
# SDD ledger — plan: docs/superpowers/plans/2026-11-14-plan-3a-tele-connexion-socle.md

## Pre-flight
| Tasks | Shared | Finding |
|---|---|---|
| T1→T2,T3,T7 | XtreamCreds, apiUrl, streamUrl, StreamKind | consistent |
| T2→T6,T7 | XtreamApi, LiveCategory, LiveStream; T6 adapter XtreamLiveSource | consistent after plan fix |
| T3→T5,T7 | SyncClient, PairingState, Profile, HiddenItem, SupabaseConfig | consistent |
| T5→T7 | LocalStore.creds() | consistent |
| T8 self | step 2 needs owner approval (Vercel) | stop point |
Ruling: owner asleep (2026-11-14 night) — execute T1–T7 and T8 step 1 (signing) only; stop before T8 step 2 (publish needs approval) — owner's instruction — cost: none.
Ruling: implementers and task reviewers on sonnet (owner's usage is limited tonight); final whole-branch review on opus — cost: per-task reviews slightly less deep.
Ruling: branch plan-3a-tele from main 2713975+docs — cost: none.
Task 1: implementer ab0da09957c1fd818 DONE 979bb05 (Kotlin 2.3.21, AGP 8.13.2, JDK 21 target, repo1 mirror, proxy 429 → --max-workers=2)
Task 1: review (sonnet a2267610994847287): Important: no android:banner/icon (Google TV launcher).
Task 1: Ruling: re-grade minor 1 (normalizeServer keeps user:pw@ userinfo) to Important — credentials could leak into URLs; parity with TS URL.origin — cost: one test.
Task 1: minor (deferred): default ports not dropped; tests jvm-only; installer re-runs sdkmanager each call, no unzip/JDK check; MainActivity hard-coded colours.
Task 1: fix round 1/5 (2 addressed, 0 open; commits 979bb05..fe5ae87)
Task 1: complete (commits 71b4e9f..fe5ae87, review clean)
Task 2: implementer ae2fc3aeea1a0c2b0 DONE b59a86c (ktor 3.4.3, serialization 1.11.0, kotlin.time.Instant)
Task 2: review (sonnet a52d0f66aa466d0c7): Approved with Important 1 (non-array list → silent []), Important 2 (userInfo empty body / [] behaviour untested).
Task 2: Ruling: also map HTTP 401/403 to Refused (minor bundled with Important 2 — same code path) — cost: none.
Task 2: minor (deferred): unused coroutines-test dep; EPG date-string variant and decimal timestamps ignored.
Task 2: fix round 1/5 (2 addressed, 0 open; commits b59a86c..785709c)
Task 2: minor (deferred): object-keyed lists keep document order (no numeric sort); {} → Unexpected(200) — both untested.
Task 2: complete (commits fe5ae87..785709c, review clean)
Task 3: implementer a1a1fd23d4ef0d717 DONE_WITH_CONCERNS 8e43f10 (supabase-kt 3.6.0; RED not observed — reviewer to check tests can fail)
Task 3: review dispatch acff7e474ad6a5ef3 interrupted by usage limit (resets 12:40 UTC) — resume or re-dispatch the review on restart.
Task 3: review (sonnet acff7e474ad6a5ef3): Approved; mutation runs confirm tests catch regressions (RED concern resolved).
Task 3: minor (deferred): signup body not asserted; profiles sorted client-side; ensureSignedIn not concurrency-safe (call once in Task 5); unused imports.
Task 3: complete (commits 785709c..8e43f10, review clean)
Task 4: implementer a173fcbdaf49099c0 DONE f82514e (supabase-js 2.118.0)
Task 4: review (sonnet abf688c09d35f8027): Important 1 partial setup dead-end; 2 double submit; 3 code lost across email-confirmation round-trip (+ emailRedirectTo).
Task 4: Ruling: fix 3 with sessionStorage (try/catch) + emailRedirectTo = page origin + clear wording when code missing — owner will scan on iPhone and confirm email in Mail app (new tab) — cost: user may need to rescan the QR on the TV.
Task 4: minor (deferred): Retirer/Me déconnecter under 44px; existing-email sign-up shows confirmation notice; password not cleared after sign-up notice; DOM flow untested.
Task 4: fix round 1/5 (3 addressed, 0 open; commits f82514e..e49d6c0)
Task 4: minor (deferred): pending code is per-tab (new tab after email link → re-enter code; hint shown); revoke button unguarded.
Task 4: complete (commits 8e43f10..e49d6c0, review clean)
Task 5: implementer ae5d5dd19b32af6ca DONE_WITH_CONCERNS b9c102d (RED not observed; extra scope/networkTimeout params; ZXing 3.5.4; creds in private SharedPreferences unencrypted)
Task 5: review (sonnet acd4b41fcfc78cf46): Important 1 allowBackup default true exposes creds/session; Important 2 hung-network (withTimeout) untested — mutation survives.
Task 5: Ruling: re-grade "profiles not saved locally" (surviving mutation) to Important — Review Focus 3 offline start depends on it — cost: one assertion.
Task 5: minor (deferred): profile row doesn't scroll for 10 profiles; every exception shown as "Pas de connexion"; VM rebuilt on activity recreation; Ready with 0 profiles has no focus; session/local store untested on Android.
Task 5: fix round 1/5 (0c40cef) — re-review by controller (66-line diff): allowBackup/fullBackupContent false + extraction rules exclude all; 3 hung-network tests + profile cache test; RED shown (4 failed) — all 3 Importants closed.
Task 5: complete (b9c102d..0c40cef)
Task 6: implemented 64256d6 (SQLDelight 2.4.1, 36 shared tests, 20k in ~306ms) — review dispatched (sonnet)
Task 6: review (sonnet): Important 1 CancellationException rethrow untested; Important 2 per-category transaction atomicity untested (Review Focus 4). Minor: position/count mutations survive, liveCategories-throws untested, 5s timing margin, global stream PK "last category wins" (accepted — Xtream ids unique).
Task 6: fix round 1 dispatched (Importants + cheap minors: report.streams, liveCategories throws, 10s timing).
Task 6: fix round 1/5 (a5d5921, test-only) — re-review by controller: cancellation rethrow + driver-level atomicity tests with RED shown, report.streams, liveCategories throws, 10s bound — both Importants closed.
Task 6: complete (0c40cef..a5d5921)
Task 7: dispatched (sonnet) with controller rulings: hidden-list fetch failure => no provider refresh; ts unless allowed formats lack it; refresh once per app start; never log stream URLs.
Task 7: implemented aa18c9b (Media3 1.11.1, tv 24 tests, shared 39) — review dispatched (sonnet)
Task 7: review (sonnet): Important 1 refresh (parse + 20k-row tx) on Main — threading untested (M6 survived); Important 2 restore-focus effect re-fires on category switch (restoreId never cleared) + no fallback focus. Creds leakage verified clean; single-connection verified.
Task 7: minor (deferred): refresh guard per-activity not per-process; refresh API call may overlap playback (real-TV check). Cheap minors folded into fix round (3 French messages).
Task 7: fix round 1 dispatched.
Task 7: fix round 1/5 (fd75245) — re-review by controller: withContext(io) around refresh + off-main test (both mutations killed); restore keyed on id only + consumed via onRestored + FirstCategory fallback (pure restoreAction tested); side effects moved to LaunchedEffect; 3 messages. Both Importants closed. Minor accepted: empty-category text may flash during load.
Task 7: complete (a5d5921..fd75245)
Task 8: step 1 done by controller (9e1bba2): PKCS12 RSA4096 30y key, signing.properties, release APK 35 MB, apksigner verify OK (CN=iptv-home, SHA-256 5d8877ce…). Stopping before step 2 (owner approval for Vercel deploy).
Task 8: step 5 guide drafted (docs/installer-sur-la-tele.md), URL to confirm at deploy. Final whole-branch review dispatched (opus).
Final review (opus, 71b4e9f..9e1bba2): Needs fixes — builds/tests green (shared 39, tv 26, web 18). Important: (1) session JSON drops expiresAt (no encodeDefaults) → 401/offline after 1h; (2) ensureSignedIn signs in anonymously on RefreshFailure → loses pairing; (3) no-session reads run as anon → hidden list [] → hidden categories downloaded; (4) no keepScreenOn → screensaver stops playback; (5) first launch empty/unfocusable during whole refresh. Minor: cross-protocol redirects, per-activity singletons/configChanges, old-code wording, ConfigPage.URL to confirm at deploy, sequential creds/profiles.
Final fix wave dispatched (sonnet): Importants 1-5 + minors redirects + process singletons/configChanges. Deferred: old-code wording, parallel creds/profiles; ConfigPage.URL at deploy.
Final fix wave: 0696ae9 1aac52b 1c84a35 08f1ca5 185f853 — shared 46, tv 27, assembleRelease OK. Controller check of ensureSignedIn/requireSession: anonymous sign-in only on NotAuthenticated, RefreshFailure/timeout → IOException (offline path), all reads guarded. All 5 Importants closed. Note: 08f1ca5 message over-lists items actually in 185f853 (cosmetic). Remaining deferred: old-code wording, sequential creds/profiles, per-VM refresh guard on true recreation, ConfigPage.URL to confirm at deploy.
Plan 3a: tasks 1–7 + 8 step 1/5 complete. STOP: step 2 needs owner approval for Vercel deploy.
Task 8: step 2 owner approved deploy. Step 3: Vercel project iptv-home-config (prj_Ndsrl3w7jlLPehIJzrRP2PN1INef) deployed from inlined source (vite build) → https://iptv-home-config.vercel.app (matches ConfigPage.URL). APK (35 MB, too big for MCP upload) hosted in Supabase public bucket `apk` (temporary anon insert policy created, used once, dropped — no policies left on storage.objects); vercel.json rewrite /iptv-home.apk → bucket; download verified byte-identical, content-type apk; version.json OK.
Task 8: step 6 owner test all OK (install, QR, 3 profiles, categories/channels, several channels play, 2nd open very fast). Step 7 committed. Task 8 complete. Plan 3a complete — awaiting merge choice.
```
