# Journal de construction — plan 3b1 (copie du registre de travail, en anglais)

Décisions (`Ruling:`), relectures, corrections et points reportés, dans l'ordre. Mis à jour à chaque étape.

```text
# SDD ledger — plan: docs/superpowers/plans/2026-11-14-plan-3b1-organiser.md
Spec: docs/superpowers/specs/2026-11-14-b1-organiser-depuis-le-telephone-design.md. Branch plan-3b1-organiser (from main 40220e0 + plan commit).

## Pre-flight scan
| Pair / task | Shared | Finding |
|---|---|---|
| T1↔T2 | migration 0008 file | T2 appends to same file; OK (sequential). |
| T1↔T3 | publish_live_categories/channels signatures, chunk/is_last | consistent. |
| T2↔T3 | profile_prefs JSON (hidden [{type,id}]) vs HiddenItem (item_type,item_id serial names) | Conflict: HiddenItem serializes item_type/item_id. Ruling below. |
| T2↔T6/T7 | org_* signatures, p null = tout le monde | consistent. |
| T3↔T4 | ProfilePrefs, CatalogPublisher, RefreshReport.providerCategories | consistent. |
| T4↔T5 | LiveSection, sections/selectSection | consistent. |
| T6↔T7 | api.ts, view.ts | T7 extends; OK. |
| T3 self | searchNorm in commonMain w/o Normalizer | mapping table mandated; OK. |
| T5 self | grep Color(0x in live/ must be empty | OK. |
| T8 | controller steps (migration prod, Vercel, APK) | publish actions: prod migration + deploy are covered by owner approval of design §9.7 ("Claude applique ... publie ... dépose"). |
Ruling: profile_prefs.hidden uses keys {"item_type","item_id"} (not type/id) so it decodes into existing HiddenItem — avoids a second model — cost if wrong: trivial rename.
Task 1: BASE 8d79769, dispatched (sonnet)
Task 1: implemented e82c103 (93/93 backend) — review dispatched
Task 1: review approved (spec ✅, quality approved). Minors: (1) chunk 0 keeps old fingerprint; (2) empty cats wipes copy; (3) dup/null ids raw errors; (4) no stale-channel test; (5) concurrent publishers.
Ruling: fold minors 1+2 into Task 2 (same migration file): chunk 0 sets fingerprint null; empty cats = no-op — cheap, closes truncated-copy and wipe risks — cost if wrong: one test line. Minors 3-5 deferred.
Task 1: complete (commits 8d79769..e82c103, review clean)
Task 2: BASE e82c103, dispatched (sonnet)
Task 2: implemented 27631cc (108/108 backend), DONE_WITH_CONCERNS.
Ruling: revision bumps only on real row change (not on no-op writes) — TV only needs change detection; brief's "after each write" read as "after each change" — cost if wrong: TV misses nothing since no-op changes nothing.
Task 2: review dispatched (sonnet)
Task 2: review approved (spec ✅). Minors deferred: playlist_set_items bumps rev on identical list; per-row rev trigger bloat on bulk; hidden ids unvalidated; no reset of profile order (empty array overrides household); org_search seq scan (pg_trgm if >50k); indentation.
Task 2: complete (commits e82c103..27631cc, review clean)
Task 3: BASE 27631cc, dispatched (sonnet)
Task 3: implemented 57b5a2a (+21 tests) — review dispatched (sonnet)
Task 3: review (sonnet): Important 1 searchNorm closed accent table ≠ NFD (web parity); Important 2 NBSP whitespace parity. Minors: regex per call, no later-chunk failure test, publishLiveCategories outside try (caller T4 handles), fingerprint excludes norm.
Task 3: fix round 1/5 dispatched: real NFD via expect/actual Normalizer, NBSP, fingerprint includes norm, later-chunk failure test.
Ruling: plan's "table de correspondance" superseded by Global Constraint "NFD" — constraint binds; web will use normalize('NFD')+\p{M} — cost if wrong: none.
Task 3: fix round 1/5 (f1bbf16) — scoped re-review by controller (small diff): expect/actual Normalizer NFD dropping Mn/Mc/Me (= JS \p{M}), œ/æ replaced before NFD, lowercase after, explicit space predicate incl. NBSP/FEFF, fingerprint includes norm, later-chunk test. Both Importants closed.
Note for Task 6 (web searchNorm order must mirror): replace œ/Œ/æ/Æ → normalize('NFD') → remove \p{M} → toLowerCase → collapse JS \s → trim.
Task 3: complete (commits 27631cc..f1bbf16, review clean)
Task 4: BASE f1bbf16, dispatched (sonnet)
Task 4: implemented 80b6eed (shared 73, tv 52), DONE_WITH_CONCERNS — review dispatched (sonnet)
Task 4: review (sonnet) Needs fixes: Important 1 dispose-order stops new profile's watcher (side effects in remember); 2 no profile-switch/lifecycle tests; 3 per-VM collector/refresh leak + refresh per profile open. Minors: savePrefs on Main, duplicate fake store, blank line, loadStreams comment.
Task 4: fix round 1/5 dispatched: LiveSessions holder (child scope per session, close only if current), process-level refresh-once guard, DisposableEffect wiring, 4 lifecycle tests.
Task 4: fix round 1/5 (6b47930) — scoped re-review (sonnet): all 3 Closed. Minors: catalogDone not @Volatile (fold into T5), guard per activity not process, failed refresh/publish retried on next profile open (accepted: user-triggered only).
Task 4: complete (commits f1bbf16..6b47930, review clean)
Task 5: BASE 6b47930, dispatched (sonnet)
Task 5: implemented 42ec74f (tv 61), DONE_WITH_CONCERNS (Coil 3.4.0 for compileSdk 36; counts only for selected section; mockup unreadable) — review dispatched (sonnet)
Task 5: review (sonnet) Needs fixes: Important 1 Back trapped with empty sections; 2 chip reachable only by implicit focus search; 3 stale count/rows after section change. Minors: IptvTheme never applied, per-row allocations, first key per item, dup focus look, unused import, no Coil negative cache, per-section counts (deferred, accepted deviation).
Ruling: per-section channel counts deferred (only selected section shows count) — needs VM/repo addition, low value for B1 — cost if wrong: small follow-up.
Task 5: fix round 1/5 dispatched (Importants + cheap minors).
Task 5: fix round 1/5 (9905a36) — scoped re-review (sonnet): all 3 Closed. Minors: null dispatcher swallows one Back; restoreFocusId with no sections gets no focus (unlikely).
Task 5: complete (commits 6b47930..9905a36, review clean; per-section counts deferred by ruling)
Task 6: BASE 9905a36, dispatched (sonnet)
Task 6: implemented c9ecbde (web 35), DONE_WITH_CONCERNS (added Choisir mode; confirmText N=1 grammar; drag untested; RED captured after) — review dispatched (sonnet)
Task 6: review (sonnet) Needs fixes: Important 1 mandated confirm wording replaced when nothing checked (Tout le monde); 2 scope-switch render race + listener leak; 3 tap targets < 44px. Minors: N=1 grammar, O(n²) reorder, a11y open target/labels, demo Config tab can signOut real session.
Ruling: in Tout le monde, a single checkbox untick also confirms (singular text) — it stops downloads for everyone — cost: one tap.
Ruling: Choisir mode kept (serves "tout masquer sauf").
Task 6: fix round 1/5 dispatched.
Task 6: fix round 1/5 (3e96a08) — scoped re-review by controller (77-line diff): confirmText singular/plural/null + tests, createGate token checked after awaits, listeners removed, tap targets measured ≥44, demo hides Config tab + rejects unknown rpc, a11y. All 3 Importants closed.
Task 6: complete (commits 9905a36..3e96a08, review clean)
Task 7: BASE 3e96a08, dispatched (sonnet)
Task 7: implemented f4723a8 (web 45), DONE_WITH_CONCERNS (playlist items without names → 'Chaîne inconnue'; view.ts ~650 lines; favoris/playlist lists not windowed) — review dispatched (sonnet)
Task 7: review (sonnet) Needs fixes: Important 1 playlist items have no server names ("Chaîne inconnue" after reload). Minors: view.ts 721 lines (split later), favoris/playlist lists not windowed (ok at size), channelList writes not serialized, full repaint loses focus, playlist sheet read-modify-write.
Task 7: fix round 1/5 dispatched: org_playlists returns items jsonb with names/states (migration 0008 edited in place, unshipped), serial() for channelList, chips row scroll.
Task 7: fix round 1/5 (157dd7b) — scoped re-review by controller: org_playlists items jsonb with names/states (same precedence as org_favorites) + backend test RED/GREEN, serial() on channelList, chips wrap. Important closed.
Task 7: complete (commits 3e96a08..157dd7b, review clean)
Ruling: run the final whole-branch review before Task 8 (release + owner test), as in plan 3a — release ships what the review approves — cost: none.
Final review: dispatched (opus) on main..157dd7b
Final review (opus): Needs fixes — I1 PostgREST max-rows 1000 cuts hidden_items GET (TV may download hidden categories), org_categories (partial list/partial order save), publish_live_categories (republish beyond 1000). M1 README wording reorder bump; M2 re-shown category returns at next refresh (guide); M3 no reset to household order (guide). All builds/tests green.
Ruling: fix I1 in code (single-value jsonb/text[] returns + new RPC for global hidden live categories) rather than relying on a dashboard Max-rows setting — robust and testable — cost: small API change on 3 call sites.
Final fix wave: BASE 157dd7b, dispatched (sonnet)
Final fix wave (5cf12d6): scoped re-review by controller: publish_live_categories → jsonb object, org_categories → jsonb array (ordered), new global_hidden_live_categories(h) text[] (member check), Kotlin decodes Map/List; README Max-rows note; guide section. backend 113, web 47, gradle green. I1 closed. Residual: org_favorites still a set (cap at >1000 favorites, accepted).
Task 8: started by controller (release).
Task 8: migration 'organiser' applied to prod (owner approved prompt); advisors: no ERROR (WARNs by design). APK 0.2.0 (versionCode 2, same cert) uploaded to bucket apk (temp policies dropped, byte-identical). Vercel git source → 429 always; page deployed as: hashed esbuild assets in new public bucket 'web' (temp policy dropped) + Vercel static index.html/version.json/vercel.json with rewrites. Verified with Playwright iPhone: sign-in page + ?demo, 0 console errors.
Ruling: page assets hosted on Supabase bucket 'web' with supabase-js from jsdelivr (pinned 2.118.0), because Vercel git deploy keeps failing and inlining 80KB is impractical — cost if wrong: CDN outage breaks page; revert by inlining build.
```
