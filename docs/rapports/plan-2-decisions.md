# Plan 2 — décisions prises et points reportés

Copie du suivi d'exécution du plan 2 (2026-11-14) : décisions prises au nom du propriétaire, et petits points reportés à traiter plus tard.

## Décisions

- Ruling: anonymity is read from auth.users.is_anonymous inside security-definer functions (not from JWT claims) — works identically in prod Supabase and in the shim — cost: one lookup per call.
- Ruling: work on branch plan-2-synchro (already created from main) — no worktree; single checkout session — cost: none.
- Task 3: Ruling: approve_pairing requires is_owner (not is_member) — spec §4: the computer signs in with the household (owner) account and validates pairing; Review Focus #1 demands immediate loss of access on revoke; plan text updated (commit after 5b481f3) — cost if wrong: a second family computer signed in with a non-owner account couldn't pair a TV (none exists in the spec).
- Task 3: Ruling: re-grade minor 1 (revocation test never checks access before revoke) to Important — it is Review Focus #1's only test and cannot fail as written — cost: one assertion.
- Task 5: Ruling: continue_watching = latest row per series (and per movie) first; movies kept only if not watched and position>0; episodes kept even when watched (client turns it into "next episode", or drops the series if none) — spec §6.5 auto-next + catalogue not synced (§6.9); plan text updated — cost if wrong: clients must handle a watched episode row.
- Task 5: Ruling: re-grade minor 2 (profile deletion doesn't prove progress removed) to Important — Review Focus #4 — cost: one test.
- Ruling (owner request): TV first — desktop app moved to step 8; roadmap renumbered; TV pairing without desktop app is an open point to decide before plan 3.
- Final: Ruling: streams keyed by (device_user_id, device_key) — client sends a stable per-install key to claim_stream; replaces only that install's previous stream — spec §6.9/§6.10 computers share the owner account — cost: one extra parameter for clients.
- Final: Ruling: single-household backend — create_household refuses when any household exists ('foyer-existe-deja'); cap profile_names ≤ 10; households.name 1–40 — this project serves one family — cost: a second family needs a new Supabase project.
- Final: Ruling: fix wave = Important 1–3 + reviewer's "fix before production" minors (households.name, xtream_credentials refusal tests, device_name length, profiles delete owner-only, LWW clock cap now()+5 min) + Minor 1–3 (revoke is_member/is_owner from anon/public; revoke truncate/references/trigger and explicit grants; revoke_device/claim race) + coalesce series metadata on upsert — all land in migration 20261114000007_correctifs.sql — cost: one larger migration.
- Final: Ruling: fix 7 cap accepted as specified (future-dated write may win for ≤ 5 min) — bounded harm vs. permanent freeze — cost: up to 5 min of stale progress in a rare clock-skew case.
- Final: Ruling: parked — record_progress accepts a NULL client_updated_at since migration 7 (least() ignores NULL) and it then wins for ≤5 min — only a buggy client can send NULL; fix (coalesce or raise) at the start of plan 3 in a small migration — cost: a buggy client could overwrite newer progress for 5 min.

## Points reportés

- Task 1: minor (deferred): home/theme/household_members.role nullable checks.
- Task 1: minor (deferred): owner can update households.owner_id (column grant).
- Task 1: minor (deferred): refusal tests missing (non-member insert into household_members as owner; cross-household profile update/delete/move) — carry first one into Task 3 dispatch.
- Task 1: minor (deferred): profiles.id no default; households.name no blank check.
- Task 1: minor (deferred): db.ts polish (hardcoded bin path, uid lookup, freePort, try/finally on stop); backend devDependencies vitest/@types/node not declared.
- Task 2: minor (deferred): direct-insert test asserts no reason (use /row-level security/).
- Task 2: minor (deferred): no tests for direct UPDATE/DELETE, anon role, owner-of-A writing B on xtream_credentials.
- Task 2: minor (deferred): project-wide — revoke TRUNCATE on public tables from anon/authenticated (defence in depth) — for final review.
- Task 3: minor (deferred): device_name length unbounded in create_pairing_code (cap 1–40).
- Task 3: minor (deferred): no attempt limit on approve_pairing; code-expire vs code-invalide reveals existence.
- Task 3: minor (deferred): old/claimed pairing codes never purged; re-pairing doesn't rename; pgcrypto in public on older projects.
- Task 4: minor (deferred): profiles_delete lets any member incl. a device delete profiles (T1 brief mandates members) — final review to weigh owner-only delete.
- Task 4: minor (deferred): no UPDATE/DELETE refusal tests (device on global rows, moving rows/items to foreign profile/playlist); category_order foreign-profile test missing.
- Task 4: minor (deferred): profile_household/playlist_profile executable by any authenticated user (UUID→household leak, low).
- Task 4: minor (deferred): category_order.updated_at never refreshed (no trigger).
- Task 4: minor (deferred): pre-existing profiles_update lets a member of two households move a profile between them (orphans hidden rows).
- Task 5: minor (deferred): refusal tests assert 42501 only, not 'acces-refuse'.
- Task 5: minor (deferred): upsert without series metadata wipes series_id/season/episode (coalesce or require series_id for episodes); item_type vs series_id consistency check.
- Task 5: minor (deferred): raw 23514/23502 instead of French codes; position_s > duration_s accepted.
- Task 5: minor (deferred): episode with null series_id grouped as its own item and kept even if watched (record_progress doesn't require series_id for episodes).
- Task 6: minor (deferred): Realtime — DELETE events carry only id (no RLS on deletes), replica identity full gives nothing; clients must subscribe to DELETE on whole table and match old.id == own stream_id; subscribe DELETE only (quota). Carry into plan 3/6 client dispatch.
- Task 6: minor (deferred): households FOR UPDATE lock blocks FK inserts briefly — use FOR NO KEY UPDATE or advisory lock.
- Task 6: minor (deferred): device_name from client; null label/device_name raw 23502; started_at/last_heartbeat nullable; no ~30 s boundary test for staleness.
- Final: minor (deferred): TypeScript type check fails on backend test files (pre-existing 39 → 52 errors); tests run fine under vitest. Fix before plan 3 adds shared tooling.
- Final: minor (deferred): concurrent create_household waits on a table lock; no dedicated test.
- Final: minor (deferred): no way to recreate the household except SQL editor (delete from households cascades); create the household right after deploy; revoke of TRUNCATE not future-proof for new tables (default privileges); check has_table_privilege on the real project.
- Task 7: minor (deferred): anon keeps table-level SELECT (Supabase default) on public tables — no anon policy so 0 rows; revoke all from anon in a later migration (defence in depth).
- Task 7: minor (deferred): perf advisors — multiple permissive policies (category_order, hidden_items), 3 unindexed FKs, category_order has no primary key.
