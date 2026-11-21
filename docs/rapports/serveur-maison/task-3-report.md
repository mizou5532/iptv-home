# Task 3 - Backend serveur maison

## Implementation
Migration `backend/supabase/migrations/20261121000035_serveur_maison.sql`:
- Tables `home_server` (RLS, select for is_member, no direct write) and `home_server_tokens` (RLS, no policy, all table rights revoked).
- `register_home_server`, `issue_home_server_token`, `check_home_server_token`: security definer, search_path=public, revoke from public/anon, grant to authenticated.
- pgcrypto: followed precedent of 0003 (`create extension if not exists pgcrypto with schema extensions`, calls qualified `extensions.gen_random_bytes` / `extensions.digest`). Works locally and on Supabase.
- check_home_server_token: acces-refuse unless caller is the registered server_user_id (also for anonymous/no uid); returns null for empty/false token; join on household_members so a revoked device yields null.
- README.md: new section documenting tables and RPCs.

## Tests (TDD)
- RED: `serveur-maison` 6/6 failed (functions absent).
- GREEN: 6/6 pass after migration.
- Full backend suite: 30 files, 272/272 pass (baseline 266 + 6).

## Files
backend/supabase/migrations/20261121000035_serveur_maison.sql, backend/test/serveur-maison.test.ts, backend/README.md.

## Self-review
- Token stored only as sha256; plaintext returned once; replacement per (household, device).
- Slight additions beyond brief: url/pin truncated (300/100), version truncated to 20; `register` caller must be role `device` (owner refused, per brief).
- Tests also check that tokens table is unreadable and home_server not directly writable.

## Concerns
- NOT applied to production (task 12). Production Supabase has pgcrypto in schema `extensions`; the migration's `create extension if not exists` is a no-op there. Nothing run against Supabase.
- No expiry on tokens and no rate limiting on check_home_server_token (token is 256-bit random, so brute force is impractical); `last_seen` is only updated on register.
- Any device member can overwrite the registered server (by design per brief: "insère ou remplace").

## Fix round 1 (serveur retiré non désenregistré)
- Migration amendée (même fichier): `check_home_server_token` exige que le serveur soit encore membre `device` (join household_members); trigger `home_server_member_left` (after delete on household_members, security definer) supprime la ligne `home_server` du serveur retiré.
- Test ajouté: « retirer le serveur le désenregistre » (revoke du serveur -> check = acces-refuse, membres lisent 0 ligne). RED avant le fix (1 failed | 6 passed), GREEN après.
- Commande: `tester.sh 'npm test -w backend -- serveur-maison appairage'` -> 2 files, 21/21 passed.
- README mis à jour (contrat de check + trigger). Suite complète non relancée (demandé).
