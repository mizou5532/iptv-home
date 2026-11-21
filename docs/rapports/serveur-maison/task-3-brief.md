### Task 3 : Backend, tables et fonctions du serveur maison

**Files :**
- Create : `backend/supabase/migrations/20261121000035_serveur_maison.sql`
- Create : `backend/test/serveur-maison.test.ts`
- Modify : `backend/README.md` (contrat : nouvelles tables et fonctions)

**Interfaces (SQL, toutes `security definer`, `search_path = public`, `revoke ... from public, anon`, `grant ... to authenticated`) :**
- Table `home_server(household_id uuid primary key references households on delete cascade, server_user_id uuid not null, url text not null, pin text not null, version text, last_seen timestamptz not null default now())`.
  RLS activée. Lecture : `is_member(household_id)`. Aucune écriture directe.
- Table `home_server_tokens(household_id uuid references households on delete cascade, device_user_id uuid not null, token_hash bytea not null, created_at timestamptz default now(), primary key (household_id, device_user_id))`.
  RLS activée sans politique : accès seulement par les fonctions.
- `register_home_server(h uuid, url text, pin text, version text) returns void` : l'appelant doit être membre `device`
  du foyer. Insère ou remplace la ligne, avec `server_user_id = auth.uid()` et `last_seen = now()`. `url` doit
  commencer par `https://` ; `pin` doit commencer par `sha256/`. Erreurs : `acces-refuse`, `adresse-invalide`,
  `empreinte-invalide`.
- `issue_home_server_token(h uuid) returns text` : l'appelant doit être membre. Crée un jeton de 32 octets aléatoires
  (`gen_random_bytes`), en hexadécimal ; garde seulement son `sha256`. Un nouvel appel remplace l'ancien jeton du même
  appareil.
- `check_home_server_token(h uuid, token text) returns uuid` : l'appelant doit être le `server_user_id` de
  `home_server` pour `h`. Rend le `device_user_id` si le hachage correspond **et** si cet appareil est encore dans
  `household_members` ; sinon `null`.

- [ ] **Step 1 : écrire les tests qui échouent** dans `serveur-maison.test.ts` (même structure que
  `rapports-lenteur.test.ts`, helper `maison()` avec une télé **et** un serveur, tous deux membres `device`) :
  - « le serveur s'enregistre ; un membre lit l'adresse et l'empreinte ; un étranger ne lit rien » ;
  - « refus : non membre, propriétaire anonyme, adresse http, empreinte sans sha256/ » ;
  - « la télé obtient un jeton ; le serveur le vérifie et reçoit l'identifiant de la télé ; un faux jeton rend null » ;
  - « un nouveau jeton remplace l'ancien » ;
  - « jeton refusé après revoke_device » (Review Focus 2) ;
  - « seul le serveur enregistré peut vérifier un jeton (une autre télé du foyer reçoit acces-refuse) ».
- [ ] **Step 2 :** `npm test -w backend -- serveur-maison`. Attendu : ÉCHEC (fonctions absentes).
- [ ] **Step 3 :** écrire la migration (commentaire d'en-tête en français, comme les autres).
- [ ] **Step 4 :** `npm test -w backend`. Attendu : tous les tests passent, nouveaux compris.
- [ ] **Step 5 :** mettre à jour `backend/README.md`, commit `feat(backend): serveur maison (adresse, empreinte, jetons)`.
  **Ne pas appliquer en production** : ce sera fait à la tâche 12, avec l'accord du propriétaire.

