# Plan 2 — Service de synchro : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Le service en ligne qui garde, pour le foyer, les identifiants Xtream, les profils, les favoris, les listes, le masquage, la progression et « qui regarde », accessible seulement aux appareils du foyer.

**Architecture:** Base Supabase (Postgres) décrite par des migrations SQL versionnées dans `backend/supabase/migrations/`. Toute la sécurité est dans la base : RLS sur chaque table (accès = être membre du foyer) et fonctions SQL (`security definer`) pour les opérations à règles (création du foyer, appairage de la télé, progression, « qui regarde »). Les clients (plans 3 et 6) parlent à Supabase directement ; ce plan ne contient aucun code client. Tests : une vraie base Postgres 16 jetable démarrée par les tests, avec un petit « décor » qui imite ce que Supabase fournit (rôles, `auth.uid()`), puis les migrations.

**Tech Stack:** PostgreSQL 16 (local, tests), Supabase (production, offre gratuite), Node 22 + Vitest + `pg` pour les tests.

**Spec:** `docs/superpowers/specs/2026-11-14-player-iptv-design.md` (§4 contraintes, §6.1 compte et profils, §6.2 catalogue, §6.5–6.9, §8 synchro, §9 risque 5).

## Global Constraints

- Les identifiants Xtream ne passent jamais par la conversation, un commit ou un journal. Le propriétaire les saisit dans une app ; ils sont stockés une fois dans le compte du foyer, **accessibles seulement aux appareils connectés à ce compte**, jamais réaffichés.
- **RLS activé sur chaque table** ; aucune table lisible sans être membre du foyer. Les rôles `anon` et `authenticated` reçoivent les droits par défaut de Supabase : la RLS est le seul garde-fou, donc chaque test vérifie aussi le refus.
- Un appareil = un utilisateur Supabase. Ordinateur : connexion e-mail + mot de passe du foyer. Télé : utilisateur **anonyme** Supabase, rattaché au foyer par **appairage** (code court affiché par la télé, validé depuis l'app ordinateur ; spec §6.1, §6.10).
- **Une seule connexion à la fois** par défaut ; le nombre maximal vient du compte Xtream (`max_connections`) et est passé par le client.
- Épisode ou film « vu » à partir de **90 %** de la durée (spec §6.5, ma proposition validée avec la spec).
- Offre gratuite de Supabase ; création du projet et réglages **seulement après accord explicite du propriétaire**.
- Codes d'erreur en français, en minuscules avec tirets (`code-invalide`, `code-expire`, `acces-refuse`…), levés avec `raise exception '<code>'`.
- SQL compatible PostgreSQL 15 à 17 (version Supabase), testé localement sur 16.

## Review Focus

1. **Appareil retiré** (télé perdue, `revoke_device`) → perd immédiatement l'accès aux identifiants et aux données. Test dans la tâche 3.
2. **Deux appareils écrivent la progression du même épisode dans le désordre** (réseau lent) → la plus récente côté client gagne, l'ancienne est ignorée. Test dans la tâche 5.
3. **Code d'appairage tapé en minuscules ou avec un espace** (`ab3 k7m`) → accepté. Test dans la tâche 3.
4. **Suppression d'un profil** → supprime ses favoris, listes, masquages et progression, sans toucher aux autres profils. Test dans la tâche 4.
5. **Appareil planté en pleine lecture** (plus de battement) → ne bloque plus les autres après 45 s. Test dans la tâche 6.

---

## Fichiers

```
backend/
  package.json                     tests (vitest, pg)
  tsconfig.json
  README.md                        modèle de données, mise en production, règles
  supabase/migrations/
    20261114000001_foyer_profils.sql
    20261114000002_identifiants_xtream.sql
    20261114000003_appairage.sql
    20261114000004_preferences.sql
    20261114000005_progression.sql
    20261114000006_qui_regarde.sql
  test/
    shim.sql                       décor Supabase minimal (tests seulement)
    db.ts                          démarre/arrête la base jetable, agit « en tant que » un utilisateur
    *.test.ts
```

Le dossier `backend/` est un nouvel espace de travail npm (ajouter `"backend"` à `workspaces` dans le `package.json` racine).

---

### Task 1: Base de test jetable, foyer et profils

**Files:**
- Create: `backend/package.json`, `backend/tsconfig.json`, `backend/test/shim.sql`, `backend/test/db.ts`, `backend/supabase/migrations/20261114000001_foyer_profils.sql`
- Modify: `package.json` (racine : `workspaces`)
- Test: `backend/test/foyer.test.ts`

**Interfaces:**
- Produces (tests) :
  - `startDb(): Promise<Db>` — `initdb` dans un dossier temporaire (via `runuser -u postgres` si le processus tourne en root), `pg_ctl start` sur un port libre, charge `test/shim.sql` puis chaque fichier de `supabase/migrations/` par ordre de nom.
  - `interface Db { admin: pg.Pool; stop(): Promise<void>; newUser(opts?: { email?: string; anonymous?: boolean }): Promise<string>; as<T>(uid: string | null, fn: (c: pg.PoolClient) => Promise<T>): Promise<T> }` — `as` ouvre une transaction, `set local role authenticated` (ou `anon` si `uid` vaut `null`), `set local request.jwt.claims` à `{"sub": uid, "role": …}`, exécute `fn`, valide (annule en cas d'erreur).
- Produces (SQL) :
  - tables `households(id uuid pk, name text not null, owner_id uuid not null references auth.users, created_at timestamptz default now())`, `household_members(household_id uuid references households on delete cascade, user_id uuid references auth.users on delete cascade, role text check (role in ('owner','device')), device_name text, joined_at timestamptz default now(), primary key (household_id, user_id))`, `profiles(id uuid pk, household_id uuid references households on delete cascade, name text check (char_length(btrim(name)) between 1 and 30), color text not null default '#2C64F0', home text check (home in ('sport','series','films','mixte')) default 'mixte', theme text check (theme in ('sombre','clair','fun')) default 'sombre', settings jsonb not null default '{}', position int not null default 0, created_at timestamptz default now())`
  - `is_member(h uuid) returns boolean` et `is_owner(h uuid) returns boolean` (`security definer`, `stable`, sur `auth.uid()`)
  - `create_household(name text, profile_names text[]) returns uuid` — crée le foyer, l'appelant comme `owner`, un profil par nom (positions 0, 1, 2…) ; `acces-refuse` si l'appelant est anonyme ou non connecté
  - RLS : `households` lisible par les membres, modifiable par le propriétaire ; `household_members` lisible par les membres ; `profiles` lisible, insérable, modifiable et supprimable par les membres de son foyer (`with check` sur `household_id`)

Le décor `shim.sql` (tests seulement) : rôles `anon`, `authenticated`, `service_role` (nologin) ; schéma `auth` avec `auth.users(id uuid pk default gen_random_uuid(), email text, is_anonymous boolean default false)` ; `auth.uid()` = `nullif(current_setting('request.jwt.claims', true)::json->>'sub','')::uuid` ; `auth.jwt()` ; `grant usage on schema public, auth` aux trois rôles ; droits par défaut comme Supabase (`alter default privileges in schema public grant all on tables, sequences, functions to anon, authenticated, service_role`) ; `create publication supabase_realtime`.

- [ ] **Step 1: Écrire les tests**

```ts
it('crée le foyer, le propriétaire et les profils', async () => {
  const owner = await db.newUser({ email: 'proprio@exemple.test' });
  const h = await db.as(owner, (c) => c.query(`select create_household('Maison', array['Toi','Sœur','Frère']) id`)).then((r) => r.rows[0].id);
  const profils = await db.as(owner, (c) => c.query('select name, position from profiles where household_id = $1 order by position', [h]));
  expect(profils.rows).toEqual([{ name: 'Toi', position: 0 }, { name: 'Sœur', position: 1 }, { name: 'Frère', position: 2 }]);
  const membres = await db.as(owner, (c) => c.query('select role from household_members where household_id = $1', [h]));
  expect(membres.rows).toEqual([{ role: 'owner' }]);
});
it('un autre utilisateur ne voit rien du foyer', async () => { /* foyer créé par A ; B : 0 ligne dans households, profiles, household_members */ });
it('sans connexion : rien', async () => { /* db.as(null, …) : 0 ligne */ });
it('un non-membre ne peut pas ajouter de profil au foyer', async () => { /* insert par B → erreur RLS (code 42501) */ });
it('anonyme ne peut pas créer de foyer', async () => { /* newUser({anonymous:true}) → rejects 'acces-refuse' */ });
it('nom de profil vide refusé', async () => { /* insert name '  ' → violation de contrainte (23514) */ });
```

- [ ] **Step 2: Vérifier l'échec** — Run: `npm test -w backend` — Expected: FAIL (`create_household` n'existe pas).
- [ ] **Step 3: Implémenter `shim.sql`, `db.ts` et la migration 1**
- [ ] **Step 4: Vérifier le succès** — Expected: PASS, et la base temporaire est arrêtée à la fin (aucun processus `postgres` restant : `pgrep -f "postgres -D /tmp"` vide).
- [ ] **Step 5: Commit** — `git add backend package.json package-lock.json && git commit -m "feat(synchro): base de test, foyer et profils"`

---

### Task 2: Identifiants Xtream du foyer

**Files:**
- Create: `backend/supabase/migrations/20261114000002_identifiants_xtream.sql`
- Test: `backend/test/identifiants.test.ts`

**Interfaces:**
- Consumes: `is_member`, `is_owner`, `create_household` (tâche 1)
- Produces:
  - table `xtream_credentials(household_id uuid primary key references households on delete cascade, server text not null, username text not null, password text not null, updated_at timestamptz default now())`
  - `set_xtream_credentials(h uuid, server text, username text, password text) returns void` — propriétaire seulement (`acces-refuse` sinon) ; `btrim` sur les trois valeurs ; refuse une valeur vide (`identifiants-incomplets`) ; insère ou remplace
  - RLS : lecture par les membres (propriétaire et appareils) ; aucune écriture directe (seulement la fonction)

- [ ] **Step 1: Écrire les tests** — le propriétaire enregistre puis relit ; un appareil membre relit (la télé en a besoin pour lire) ; un non-membre lit 0 ligne ; un appareil membre appelle `set_xtream_credentials` → `acces-refuse` ; `insert` direct par le propriétaire → refusé par la RLS ; mot de passe `'  '` → `identifiants-incomplets`. (Pour créer un appareil membre dans ce test, insérer la ligne `household_members` avec `db.admin` ; l'appairage arrive à la tâche 3.)
- [ ] **Step 2: Vérifier l'échec** — Expected: FAIL.
- [ ] **Step 3: Implémenter la migration 2**
- [ ] **Step 4: Vérifier le succès** — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(synchro): identifiants Xtream du foyer"`

---

### Task 3: Appairage de la télé et retrait d'un appareil

**Files:**
- Create: `backend/supabase/migrations/20261114000003_appairage.sql`
- Test: `backend/test/appairage.test.ts`

**Interfaces:**
- Consumes: tâche 1, `xtream_credentials` (tâche 2)
- Produces:
  - table `pairing_codes(code text primary key, device_user_id uuid not null references auth.users on delete cascade, device_name text not null, created_at timestamptz default now(), expires_at timestamptz not null, household_id uuid references households on delete cascade, claimed_at timestamptz)` — RLS activée, aucune politique (accès seulement par les fonctions)
  - `create_pairing_code(device_name text) returns text` — appelant connecté (anonyme accepté) ; 6 caractères tirés de `ABCDEFGHJKMNPQRSTUVWXYZ23456789` ; valable **10 minutes** ; supprime les anciens codes non utilisés du même appareil
  - `approve_pairing(h uuid, code text) returns text` — appelant **propriétaire** de `h` (un appareil appairé ne peut pas en appairer d'autres, sinon une télé volée garderait un accès après son retrait) ; code normalisé (`upper`, espaces retirés) ; inconnu → `code-invalide` ; expiré → `code-expire` ; déjà utilisé → `code-invalide` ; ajoute l'appareil à `household_members` (`role = 'device'`, `device_name`) ; renvoie `device_name`
  - `my_households() returns setof households` — foyers dont l'appelant est membre (la télé l'appelle en boucle après avoir affiché le code)
  - `revoke_device(h uuid, device uuid) returns void` — propriétaire seulement ; refuse de retirer le propriétaire (`acces-refuse`)

- [ ] **Step 1: Écrire les tests**

```ts
it('la télé anonyme obtient un code, l’ordinateur le valide, la télé rejoint le foyer', async () => {
  const tv = await db.newUser({ anonymous: true });
  const code = await db.as(tv, (c) => c.query(`select create_pairing_code('Télé du salon') code`)).then((r) => r.rows[0].code);
  expect(code).toMatch(/^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$/);
  expect(await db.as(owner, (c) => c.query('select approve_pairing($1, $2) n', [h, code])).then((r) => r.rows[0].n)).toBe('Télé du salon');
  expect((await db.as(tv, (c) => c.query('select id from my_households()'))).rows).toEqual([{ id: h }]);
  expect((await db.as(tv, (c) => c.query('select username from xtream_credentials'))).rowCount).toBe(1);
});
it('code tapé en minuscules avec un espace', async () => { /* approve_pairing(h, 'ab3 k7m' version du code) → accepté */ });
it('code expiré', async () => { /* db.admin: update pairing_codes set expires_at = now() - interval '1 minute' → rejects 'code-expire' */ });
it('code à usage unique', async () => { /* second approve → 'code-invalide' */ });
it('un non-membre ne peut pas valider un code pour ce foyer', async () => { /* → 'acces-refuse' */ });
it('un appareil appairé ne peut pas en appairer un autre', async () => { /* → 'acces-refuse' */ });
it('appareil retiré : perd l’accès tout de suite', async () => {
  /* revoke_device(h, tv) par le propriétaire ; ensuite, en tant que tv : 0 ligne dans xtream_credentials, profiles, my_households() */
});
it('le propriétaire ne peut pas se retirer lui-même', async () => { /* → 'acces-refuse' */ });
```

- [ ] **Step 2: Vérifier l'échec** — Expected: FAIL.
- [ ] **Step 3: Implémenter la migration 3** — génération du code avec `gen_random_bytes` (extension `pgcrypto`, présente sur Supabase ; `create extension if not exists pgcrypto` dans la migration) ; en cas de collision de clé, réessayer.
- [ ] **Step 4: Vérifier le succès** — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(synchro): appairage de la télé et retrait d'un appareil"`

---

### Task 4: Favoris, listes, masquage et ordre des catégories

**Files:**
- Create: `backend/supabase/migrations/20261114000004_preferences.sql`
- Test: `backend/test/preferences.test.ts`

**Interfaces:**
- Consumes: tâche 1
- Produces:
  - `profile_household(p uuid) returns uuid` et `can_use_profile(p uuid) returns boolean` (`security definer`, `stable`) : vrai si l'appelant est membre du foyer du profil
  - types de contenu : `item_type text check (item_type in ('live_category','vod_category','series_category','channel','movie','series'))` ; identifiants du fournisseur stockés en `text`
  - `favorites(profile_id uuid references profiles on delete cascade, item_type text, item_id text, created_at timestamptz default now(), primary key (profile_id, item_type, item_id))` — `item_type` limité à `channel`, `movie`, `series`
  - `playlists(id uuid pk, profile_id uuid references profiles on delete cascade, name text check (char_length(btrim(name)) between 1 and 40), position int default 0)` et `playlist_items(playlist_id uuid references playlists on delete cascade, item_type text, item_id text, position int default 0, primary key (playlist_id, item_type, item_id))`
  - `hidden_items(id uuid pk, household_id uuid references households on delete cascade, profile_id uuid null references profiles on delete cascade, item_type text, item_id text, created_at timestamptz default now())` avec index unique sur `(household_id, coalesce(profile_id, '00000000-0000-0000-0000-000000000000'), item_type, item_id)` — `profile_id` nul = masqué **pour tout le monde** (propriétaire seulement)
  - `category_order(household_id uuid references households on delete cascade, profile_id uuid null references profiles on delete cascade, section text check (section in ('live','vod','series')), category_ids text[] not null, updated_at timestamptz default now())` avec la même unicité `(household_id, coalesce(profile_id, …), section)`
  - `hidden_for(p uuid) returns table (item_type text, item_id text)` — masquages du foyer pour tout le monde + ceux du profil `p`
  - RLS : favoris, listes et éléments de liste via `can_use_profile` ; `hidden_items` et `category_order` lisibles par les membres, écriture des lignes à `profile_id` nul réservée au propriétaire, des autres via `can_use_profile`

- [ ] **Step 1: Écrire les tests** — un membre ajoute un favori à un profil de son foyer ; un membre d'un autre foyer ne le voit pas et ne peut pas en insérer (42501) ; masquage « pour tout le monde » par un appareil → refusé, par le propriétaire → accepté ; doublon de masquage global → violation d'unicité (23505) ; `hidden_for(sœur)` renvoie le global + celui de la sœur, pas celui du frère ; **suppression du profil de la sœur** : ses favoris, listes, éléments de liste et masquages disparaissent, ceux du frère restent.
- [ ] **Step 2: Vérifier l'échec** — Expected: FAIL.
- [ ] **Step 3: Implémenter la migration 4**
- [ ] **Step 4: Vérifier le succès** — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(synchro): favoris, listes, masquage et ordre des catégories"`

---

### Task 5: Progression et « Continuer à regarder »

**Files:**
- Create: `backend/supabase/migrations/20261114000005_progression.sql`
- Test: `backend/test/progression.test.ts`

**Interfaces:**
- Consumes: `can_use_profile` (tâche 4)
- Produces:
  - `watch_progress(profile_id uuid references profiles on delete cascade, item_type text check (item_type in ('movie','episode')), item_id text, series_id text, season int, episode int, position_s int not null check (position_s >= 0), duration_s int not null check (duration_s > 0), watched boolean not null default false, client_updated_at timestamptz not null, primary key (profile_id, item_type, item_id))`
  - `record_progress(p uuid, item_type text, item_id text, position_s int, duration_s int, client_updated_at timestamptz, series_id text default null, season int default null, episode int default null) returns boolean` — `acces-refuse` sans `can_use_profile` ; ignore l'écriture (renvoie `false`) si une ligne existe avec un `client_updated_at` plus récent ou égal ; sinon écrit et renvoie `true` ; `watched` devient vrai dès que `position_s >= 0.9 * duration_s` et **le reste** ensuite
  - `continue_watching(p uuid, max_rows int default 20) returns setof watch_progress` — d'abord **la ligne la plus récente de chaque série** (et de chaque film), puis : un film n'est gardé que s'il n'est pas vu et `position_s > 0` ; un épisode est gardé **même s'il est vu** (l'app propose alors l'épisode suivant, qu'elle seule connaît, ou retire la série s'il n'y en a pas) ; triés par `client_updated_at` décroissant. Un épisode plus ancien abandonné ne revient jamais.
  - RLS : lecture via `can_use_profile` ; pas d'écriture directe

- [ ] **Step 1: Écrire les tests** — écriture puis relecture ; **écriture plus ancienne arrivée en retard ignorée** (`t2` puis `t1 < t2` → `false`, position de `t2` conservée) ; 90 % → `watched = true` ; vu puis relu au début → `watched` reste vrai ; `continue_watching` : deux épisodes de la même série → un seul (le plus récent) ; un film vu n'y figure pas ; un autre foyer → `acces-refuse`.
- [ ] **Step 2: Vérifier l'échec** — Expected: FAIL.
- [ ] **Step 3: Implémenter la migration 5**
- [ ] **Step 4: Vérifier le succès** — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(synchro): progression et continuer à regarder"`

---

### Task 6: « Qui regarde » et prise de la main

**Files:**
- Create: `backend/supabase/migrations/20261114000006_qui_regarde.sql`
- Test: `backend/test/qui-regarde.test.ts`

**Interfaces:**
- Consumes: tâches 1 et 4
- Produces:
  - `active_streams(id uuid pk, household_id uuid references households on delete cascade, device_user_id uuid not null, device_name text not null, profile_id uuid references profiles on delete set null, label text not null, started_at timestamptz default now(), last_heartbeat timestamptz default now())` ; `replica identity full` ; ajoutée à la publication `supabase_realtime` (les appareils écoutent la suppression de leur ligne pour s'arrêter)
  - constante : une lecture est **périmée** après **45 s** sans battement
  - `claim_stream(h uuid, p uuid, device_name text, label text, max_connections int, takeover boolean default false) returns jsonb` — membre seulement ; supprime les lectures périmées du foyer ; supprime l'éventuelle lecture précédente du même appareil ; si le nombre de lectures restantes `>= max_connections` et `takeover` est faux → `{"status":"occupe","occupants":[{"device_name","profile_name","label"}]}` sans rien créer ; si `takeover` est vrai → supprime les autres lectures du foyer puis crée la sienne ; sinon crée la sienne → `{"status":"ok","stream_id": …}`
  - `heartbeat_stream(id uuid) returns boolean` — met à jour `last_heartbeat` si la ligne existe et appartient à l'appelant ; `false` si elle a disparu (prise de la main ailleurs)
  - `release_stream(id uuid) returns void`
  - RLS : lecture par les membres ; pas d'écriture directe

- [ ] **Step 1: Écrire les tests** — télé lit (max 1) ; ordinateur demande → `occupe` avec `device_name` « Télé du salon », profil « Sœur » et le titre ; ordinateur avec `takeover` → `ok`, et `heartbeat_stream` de la télé renvoie `false` ; **lecture périmée** (`db.admin` : `last_heartbeat = now() - interval '60 seconds'`) → ne bloque plus ; `max_connections = 2` → deux lectures acceptées ; un non-membre → `acces-refuse` ; la table est bien dans la publication (`select 1 from pg_publication_tables where pubname = 'supabase_realtime' and tablename = 'active_streams'`).
- [ ] **Step 2: Vérifier l'échec** — Expected: FAIL.
- [ ] **Step 3: Implémenter la migration 6**
- [ ] **Step 4: Vérifier le succès** — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(synchro): qui regarde et prise de la main"`

---

### Task 7: Mise en production sur Supabase

**Files:**
- Create: `backend/README.md`, `docs/rapports/synchro-production.md`

- [ ] **Step 1: Rédiger `backend/README.md`** — schéma des tables en une phrase chacune, les fonctions et leurs codes d'erreur, la règle des 90 % et des 45 s, comment lancer les tests, comment mettre en production.
- [ ] **Step 2: Demander l'accord du propriétaire** pour créer le projet Supabase gratuit (région `ca-central-1`). Sans accord : s'arrêter ici et le signaler.
- [ ] **Step 3: Créer le projet et appliquer les migrations 1 à 6** dans l'ordre (outils Supabase de la session) ; vérifier avec `list_tables` que les 12 tables existent avec RLS activée.
- [ ] **Step 4: Avis de sécurité** — `get_advisors` (sécurité) ; corriger toute alerte sur une table ou fonction de ce plan par une migration `20261114000007_correctifs.sql` testée localement (RED → GREEN) avant application.
- [ ] **Step 5: Réglage à faire par le propriétaire** — dans le tableau de bord Supabase : Authentication → Sign In / Providers → activer **« Allow anonymous sign-ins »** (nécessaire pour la télé) et garder l'inscription par e-mail. Lui donner le chemin exact.
- [ ] **Step 6: Consigner** dans `docs/rapports/synchro-production.md` : l'adresse du projet et la clé publique (`get_publishable_keys` ; elle est publique par conception, la RLS protège les données), la date, les migrations appliquées, le résultat des avis de sécurité. **Jamais** la clé `service_role`.
- [ ] **Step 7: Commit** — `git add backend/README.md docs/rapports/synchro-production.md && git commit -m "docs(synchro): mise en production"`
