# Plan 3b1 — Organiser depuis le téléphone et nouvel écran du direct

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Le propriétaire organise chaque profil depuis son iPhone (masquer, ordre, favoris, playlists, recherche) et la télé suit en moins de ~5 s, avec un nouvel écran du direct sombre.

**Architecture:** La télé publie une copie légère du catalogue du direct (noms, numéros, nom normalisé) dans Supabase par des fonctions `security definer`, catégorie par catégorie et seulement si elle a changé. La page `web/config` gagne un onglet « Organiser » qui n'appelle que des fonctions `org_*` (testées en Postgres réel). La télé lit un instantané des préférences du profil (`profile_prefs`), le garde en local, surveille une révision du foyer toutes les 4 s quand l'écran du direct est ouvert, et compose ses sections (Favoris, playlists, catégories) par une fonction pure.

**Tech Stack:** Postgres 16 / Supabase (migrations SQL, tests Vitest + `pg`), Kotlin Multiplatform (`:shared`), Compose for TV + Media3 (`:tv`), supabase-kt 3.6.0, Coil 3 (logos), Vite + TypeScript + Vitest (`web/config`).

**Spec:** `docs/superpowers/specs/2026-11-14-b1-organiser-depuis-le-telephone-design.md` (lire aussi §9 « Règles précisées »). Parent : `docs/superpowers/specs/2026-11-14-player-iptv-design.md` §6.3, §6.8. Contrat backend : `backend/README.md`.

## Global Constraints

- Jamais d'identifiant Xtream ni d'adresse de flux dans la copie du catalogue, un journal, un message d'erreur, la page ou un commit.
- Jamais la clé `service_role`. Les tests backend tournent sur le Postgres local (`backend/test/db.ts`), les tests Kotlin et web sur des faux ; aucun test n'appelle le vrai projet Supabase.
- Toute évolution du schéma = **nouvelle migration** `backend/supabase/migrations/20261114000008_organiser.sql` (ne jamais modifier 0001–0007). Mettre à jour `backend/README.md` (tables, fonctions, contrat).
- Une catégorie masquée pour tout le monde n'est **jamais téléchargée** chez le fournisseur (règle de la partie A, inchangée).
- « Masqué gagne » ; ordre = profil, sinon tout le monde, sinon fournisseur ; catégories absentes de l'ordre ajoutées à la fin dans l'ordre du fournisseur ; identifiants inconnus ignorés (spec §9.1, §9.4).
- Identité d'une chaîne = `stream_id` Xtream, en texte (`item_type = 'channel'`).
- Volume visé : 100 000 chaînes, 2 000 catégories.
- Nom normalisé pour la recherche : minuscules, décomposition NFD, marques diacritiques retirées, espaces consécutifs réduits à un, bords coupés. Mêmes vecteurs de test en Kotlin et en TypeScript : `"Télé-Québec HD"` → `"tele-quebec hd"`, `"  RDS   2 "` → `"rds 2"`, `"ÇA Sport"` → `"ca sport"`, `"Cœur Æther"` → `"coeur aether"` (œ et æ ne se décomposent pas en NFD : les remplacer explicitement des deux côtés).
- Télé : thème **sombre seulement**, mais toutes les couleurs, formes et tailles de texte de l'écran du direct passent par des jetons `IptvTheme` (étape 7 ajoutera clair et fun).
- Gradle : `--max-workers=2` (le proxy renvoie 429 en parallèle), réessayer sur erreur de téléchargement. Noms de méthodes de test en ASCII.
- Textes visibles en français.
- Commits en français, style conventionnel, terminés par les deux lignes de signature données par le contrôleur.

## Review Focus

1. **Une catégorie fraîchement masquée pour tout le monde depuis le téléphone pendant que la télé rafraîchit** : la télé ne doit pas la télécharger au rafraîchissement suivant, ne doit pas effacer ses chaînes de la copie publiée, et ses favoris disparaissent de la télé. (Tests : tâches 1 et 4.)
2. **Profil sans aucune préférence, ou télé hors ligne au démarrage** : la télé affiche les catégories dans l'ordre du fournisseur (ou la dernière organisation gardée en local), jamais un écran vide ni une attente. (Tests : tâche 4.)
3. **Page avec 2 000 catégories et une catégorie de 20 000 chaînes** : la liste reste fluide (rendu fenêtré), la recherche répond par le serveur avec une limite de résultats. (Tests : tâches 2 et 6.)
4. **Écritures concurrentes** (deux onglets du téléphone, ou réordonner pendant qu'un masquage part) : chaque appel `org_*` est atomique et l'état relu après coup est cohérent ; la révision augmente à chaque changement. (Tests : tâche 2.)
5. **Publication interrompue** (réseau coupé au milieu d'une grande catégorie découpée en morceaux) : l'empreinte n'est écrite qu'au dernier morceau, donc la catégorie est republiée entièrement au prochain rafraîchissement. (Tests : tâches 1 et 3.)

---

### Task 1: Copie du catalogue du direct dans Supabase

**Files:**
- Create: `backend/supabase/migrations/20261114000008_organiser.sql` (partie « catalogue » ; les tâches 2 l'étendent dans le même fichier)
- Create: `backend/test/catalogue.test.ts`
- Modify: `backend/README.md`

**Interfaces:**
- Produces (SQL) :
  - `live_catalog_category(household_id uuid, id text, name text, position int, fingerprint text null, updated_at timestamptz)` — PK `(household_id, id)`.
  - `live_catalog_channel(household_id uuid, id text, category_id text, name text, norm text, number int null, position int)` — PK `(household_id, id)`, index `(household_id, category_id, position)`. FK vers la catégorie avec `on delete cascade`.
  - RLS : lecture `is_member(household_id)` ; aucune écriture directe.
  - `publish_live_categories(h uuid, cats jsonb) returns table(id text, fingerprint text)` — `cats` = `[{id,name,position}]` ; upsert de toutes, suppression des catégories absentes (cascade sur leurs chaînes) ; renvoie les empreintes connues. Membre seulement (`acces-refuse`).
  - `publish_live_channels(h uuid, category text, fingerprint text, chunk int, is_last boolean, channels jsonb) returns void` — `channels` = `[{id,name,norm,number,position}]` ; `chunk = 0` efface d'abord les chaînes de la catégorie ; `is_last` écrit l'empreinte ; une chaîne d'un autre identifiant de catégorie déjà présente est déplacée (dernier gagne). Membre seulement. Lève `categorie-inconnue` si la catégorie n'est pas publiée.

- [ ] **Step 1: Écrire les tests** dans `catalogue.test.ts` (style de `appairage.test.ts`, foyer + télé appairée) :
  - `publie_categories_puis_chaines_et_le_proprietaire_les_lit` : 3 catégories, 2 chaînes dans `"1"` ; le propriétaire lit 3 catégories et 2 chaînes ; l'empreinte renvoyée pour `"1"` vaut celle envoyée.
  - `categorie_disparue_supprimee_avec_ses_chaines` : republier sans `"2"` → plus de catégorie ni de chaînes `"2"`.
  - `categorie_absente_de_l_envoi_de_chaines_garde_ses_chaines` : republier les catégories (toutes présentes) sans republier les chaînes de `"3"` → ses chaînes restent (copie figée, spec §9.2).
  - `morceaux_empreinte_ecrite_seulement_au_dernier` : chunk 0 (`is_last=false`) puis rien → empreinte toujours l'ancienne (ou nulle) ; chunk 1 `is_last=true` → empreinte nouvelle, chaînes des deux morceaux présentes.
  - `etranger_ni_lecture_ni_publication` : un utilisateur hors foyer lit 0 ligne et `publish_*` lève `acces-refuse`.
  - `aucune_ecriture_directe` : `insert into live_catalog_channel` par la télé échoue.
  - `cent_mille_chaines` : publier 100 000 chaînes en 50 morceaux de 2 000 ; total = 100 000 ; durée < 60 s.
- [ ] **Step 2: Lancer** `npm test -w backend -- catalogue` — Expected: FAIL (fonctions absentes).
- [ ] **Step 3: Écrire la migration** (fonctions `security definer set search_path = public`, `jsonb_to_recordset`, `grant execute ... to authenticated`, `revoke ... from anon, public`). Garder le style des migrations 0004–0007.
- [ ] **Step 4: Lancer** `npm test -w backend` — Expected: PASS (toute la suite backend).
- [ ] **Step 5: README** : ajouter les deux tables, les deux fonctions, la règle « copie figée » et « empreinte au dernier morceau ».
- [ ] **Step 6: Commit** — `feat(backend): copie du catalogue du direct publiée par la télé`

### Task 2: Fonctions d'organisation et révision des préférences

**Files:**
- Modify: `backend/supabase/migrations/20261114000008_organiser.sql`
- Create: `backend/test/organiser.test.ts`
- Modify: `backend/README.md`

**Interfaces:**
- Consumes: tables de la tâche 1 ; `favorites`, `playlists`, `playlist_items`, `hidden_items`, `category_order` existantes.
- Produces (SQL ; `p uuid null` = « tout le monde » ; écritures « tout le monde » = propriétaire seulement, écritures d'un profil = `can_use_profile(p)`) :
  - `alter table favorites add column position int not null default 0`.
  - `prefs_revision(household_id uuid primary key, rev bigint not null default 0, updated_at timestamptz)` ; RLS lecture membre ; déclencheurs `after insert/update/delete` sur les 5 tables de préférences qui font `rev = rev + 1` pour le foyer concerné.
  - `org_categories(h uuid, p uuid) returns table(id text, name text, provider_position int, hidden_global boolean, hidden_profile boolean, channel_count int)` — triées selon l'ordre effectif de la portée (règle des Global Constraints).
  - `org_channels(h uuid, category text, p uuid, off int, lim int) returns table(id text, name text, number int, favorite boolean, hidden_global boolean, hidden_profile boolean)` — `lim` plafonné à 1 000.
  - `org_search(h uuid, q text, p uuid, lim int) returns table(id text, name text, number int, category_id text, category_name text, favorite boolean, hidden_global boolean, hidden_profile boolean)` — `q` déjà normalisé par le client, recherche `norm like '%' || q_échappé || '%'`, `lim` plafonné à 200, tri par position de catégorie puis de chaîne.
  - `org_set_hidden(h uuid, p uuid, item_type text, ids text[], hidden boolean) returns int` — `item_type` ∈ `live_category`, `channel` ; renvoie le nombre de lignes changées.
  - `org_hide_all_except(h uuid, p uuid, keep text[]) returns int` — masque dans la portée toutes les catégories publiées hors `keep`, réaffiche celles de `keep` ; renvoie le nombre masqué.
  - `org_set_category_order(h uuid, p uuid, ids text[]) returns void` — upsert `category_order` (`section = 'live'`).
  - `org_set_favorite(p uuid, channel text, on_ boolean) returns void` (ajout en fin de liste) ; `org_reorder_favorites(p uuid, ids text[]) returns void`.
  - `org_playlist_create(p uuid, name text) returns uuid` ; `org_playlist_rename(id uuid, name text)` ; `org_playlist_delete(id uuid)` ; `org_playlists_reorder(p uuid, ids uuid[])` ; `org_playlist_set_items(id uuid, channel_ids text[])` (remplace la liste ordonnée).
  - `org_playlists(p uuid) returns table(id uuid, name text, position int, channel_ids text[])`.
  - `org_favorites(p uuid) returns table(id text, name text, number int, category_id text, state text)` — `state` ∈ `ok`, `categorie-masquee`, `masquee`, `n-existe-plus` (spec §9.1).
  - `profile_prefs(p uuid) returns jsonb` : `{"rev": n, "hidden": [{"type","id"}], "favorites": [ids ordonnés], "playlists": [{"id","name","items":[ids]}], "order": [ids] | null}` — `hidden` = global + profil ; `order` = profil sinon foyer sinon `null`. Membre seulement (la télé l'appelle).
  - `prefs_rev(h uuid) returns bigint`.

- [ ] **Step 1: Écrire les tests** (`organiser.test.ts`) :
  - `ordre_effectif_profil_puis_foyer_puis_fournisseur` : sans ordre → position fournisseur ; ordre foyer `[c,a]` → `c,a,b` ; ordre profil `[b]` → `b,a,c` ; id inconnu `zz` dans l'ordre ignoré.
  - `tout_masquer_sauf_portee_profil_et_foyer` : 5 catégories, `keep=[a,b]` sur le profil → renvoie 3, `org_categories` montre `hidden_profile` pour c,d,e ; en `p=null` par le propriétaire → `hidden_global` ; par la télé (non propriétaire) en `p=null` → `acces-refuse`.
  - `favoris_ordre_et_etat` : favoris x,y,z, `org_reorder_favorites([z,x,y])` → ordre z,x,y ; masquer la catégorie de x pour tout le monde → état `categorie-masquee` ; supprimer y de la copie → `n-existe-plus`.
  - `playlists_crud_et_ordre` : créer « Matchs » et « Séries », renommer, réordonner, `set_items([c2,c1])` → `org_playlists` renvoie l'ordre exact ; nom vide → erreur de contrainte.
  - `recherche_normalisee_et_limitee` : 300 chaînes `RDS n` → `org_search('rds', lim 500)` renvoie 200 ; `'%'` ne renvoie que les noms contenant un `%` littéral.
  - `profile_prefs_masque_gagne_et_ordre` : instantané contient `hidden` global + profil, `favorites` dans l'ordre, `order` du profil (sinon foyer, sinon null).
  - `revision_augmente_a_chaque_changement` : `prefs_rev` augmente après chaque `org_*` en écriture, y compris `org_hide_all_except` (≥ +1), et pas après une lecture.
  - `etranger_refuse` : un utilisateur hors foyer → `acces-refuse` sur une lecture et une écriture.
- [ ] **Step 2: Lancer** `npm test -w backend -- organiser` — Expected: FAIL.
- [ ] **Step 3: Implémenter** dans la migration 0008 (fonctions `security definer`, contrôles `is_member` / `is_owner` / `can_use_profile`, échappement de `%`, `_`, `\` dans `org_search`).
- [ ] **Step 4: Lancer** `npm test -w backend` — Expected: PASS.
- [ ] **Step 5: README** : fonctions `org_*`, `profile_prefs`, `prefs_rev`, `favorites.position`, états des favoris.
- [ ] **Step 6: Commit** — `feat(backend): fonctions d'organisation par profil et révision des préférences`

### Task 3: Publication du catalogue par la télé (code partagé)

**Files:**
- Create: `apps/shared/src/commonMain/kotlin/home/iptv/shared/catalog/CatalogPublisher.kt`, `apps/shared/src/commonMain/kotlin/home/iptv/shared/text/SearchNorm.kt`
- Modify: `apps/shared/src/commonMain/kotlin/home/iptv/shared/catalog/CatalogRepository.kt` (le rapport porte toutes les catégories du fournisseur), `apps/shared/src/commonMain/kotlin/home/iptv/shared/sync/SyncClient.kt`, `SyncModels.kt`
- Test: `apps/shared/src/jvmTest/kotlin/home/iptv/shared/catalog/CatalogPublisherTest.kt`, `apps/shared/src/jvmTest/kotlin/home/iptv/shared/text/SearchNormTest.kt`, ajout dans les tests `SupabaseSyncClient` existants

**Interfaces:**
- Consumes: `CatalogRepository.categories()/streams()`, `LiveCategory`, `LiveStream`, RPC de la tâche 1.
- Produces:
  - `fun searchNorm(s: String): String` (règle des Global Constraints).
  - `RefreshReport` gagne `val providerCategories: List<LiveCategory>` (toutes, masquées comprises, dans l'ordre du fournisseur ; vide si `liveCategories()` a échoué).
  - `data class PublishedCategory(val id: String, val name: String, val position: Int)`, `data class PublishedChannel(val id: String, val name: String, val norm: String, val number: Int?, val position: Int)`.
  - `SyncClient` gagne : `suspend fun publishLiveCategories(householdId: String, cats: List<PublishedCategory>): Map<String, String?>` (id → empreinte) ; `suspend fun publishLiveChannels(householdId: String, categoryId: String, fingerprint: String, chunk: Int, isLast: Boolean, channels: List<PublishedChannel>)` ; `suspend fun profilePrefs(profileId: String): ProfilePrefs` ; `suspend fun prefsRevision(householdId: String): Long`.
  - `@Serializable data class ProfilePrefs(val rev: Long, val hidden: List<HiddenItem>, val favorites: List<String>, val playlists: List<PlaylistPrefs>, val order: List<String>?)` et `@Serializable data class PlaylistPrefs(val id: String, val name: String, val items: List<String>)` (JSON de `profile_prefs` : `hidden` = `[{"type","id"}]`).
  - `fun fingerprint(streams: List<LiveStream>): String` (FNV-1a 64 bits en hexadécimal sur `id|name|number` par ligne).
  - `class CatalogPublisher(sync: SyncClient, repo: CatalogRepository, chunkSize: Int = 2000) { suspend fun publish(householdId: String, report: RefreshReport): PublishReport }` avec `data class PublishReport(val categories: Int, val publishedCategories: Int, val channels: Int)`.
- Règles de `publish` : ne rien faire si `report.providerCategories` est vide ; publier toutes les catégories du fournisseur ; pour chaque catégorie présente dans le cache local, calculer l'empreinte et ne publier ses chaînes que si elle diffère de celle renvoyée ; découper en morceaux de `chunkSize` ; une erreur sur une catégorie est notée et n'arrête pas les autres, sauf `CancellationException` qui remonte. Les icônes et `epg_channel_id` ne sont **pas** publiés.

- [ ] **Step 1: Écrire les tests** :
  - `SearchNormTest` : les trois vecteurs des Global Constraints, plus `""` → `""`.
  - `CatalogPublisherTest` (faux `SyncClient` qui enregistre les appels, vrai `CatalogRepository` en mémoire comme dans `CatalogRepositoryTest`) : `publie_toutes_les_categories_meme_masquees` (le rapport en contient 3, le cache 2 → 3 catégories publiées, chaînes de 2) ; `empreinte_identique_rien_n_est_renvoye` ; `grande_categorie_decoupee_en_morceaux` (4 500 chaînes, `chunkSize=2000` → 3 appels, `chunk` 0,1,2, `isLast` seulement au dernier) ; `erreur_sur_une_categorie_les_autres_continuent` ; `annulation_remonte` ; `rapport_sans_categories_ne_publie_rien`.
  - Tests `SupabaseSyncClient` (MockEngine) : `profilePrefs` décode le JSON exemple ci-dessus ; `publishLiveChannels` envoie `chunk`, `is_last` et des chaînes sans champ `icon`.
- [ ] **Step 2: Lancer** `cd apps && ./gradlew --max-workers=2 :shared:jvmTest` — Expected: FAIL (assertions, sur une ébauche qui compile).
- [ ] **Step 3: Implémenter** (`searchNorm` : `java.text.Normalizer` n'existe pas en commun → table de correspondance des lettres accentuées latines courantes (à, â, ä, á, ã, å, ç, é, è, ê, ë, í, ì, î, ï, ñ, ó, ò, ô, ö, õ, ú, ù, û, ü, ý, ÿ, œ→oe, æ→ae, majuscules comprises) ; `requireSession()` sur les nouvelles fonctions comme les autres).
- [ ] **Step 4: Lancer** `./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest` — Expected: PASS.
- [ ] **Step 5: Commit** — `feat(shared): publication de la copie du catalogue et préférences du profil`

### Task 4: La télé suit l'organisation du profil

**Files:**
- Create: `apps/tv/src/main/kotlin/home/iptv/tv/live/Sections.kt`, `apps/tv/src/main/kotlin/home/iptv/tv/live/PrefsWatcher.kt`
- Modify: `apps/shared/src/commonMain/sqldelight/home/iptv/shared/catalog/Catalog.sq` (requête `selectStreamsByIds`), `CatalogRepository.kt` (`fun streamsByIds(ids: List<String>): List<LiveStream>` dans l'ordre des ids, absents ignorés), `apps/tv/src/main/kotlin/home/iptv/tv/data/LocalStore.kt` (préférences par profil), `LiveRefresher.kt` (publication après rafraîchissement), `LiveBrowseViewModel.kt` (sections), `MainActivity.kt`, `App.kt` (profil choisi transmis)
- Test: `apps/tv/src/test/kotlin/home/iptv/tv/live/SectionsTest.kt`, `PrefsWatcherTest.kt`, ajouts dans `LiveBrowseViewModelTest.kt`, `apps/shared/src/jvmTest/.../CatalogRepositoryTest.kt`

**Interfaces:**
- Consumes: `ProfilePrefs`, `SyncClient.profilePrefs/prefsRevision`, `CatalogPublisher`, `CatalogRepository`.
- Produces:
  - `sealed interface LiveSection { val key: String; val title: String }` avec `data class Favorites(...)`, `data class Playlist(val id: String, ...)`, `data class Category(val id: String, ...)` ; `key` = `"fav"`, `"pl:<id>"`, `"cat:<id>"`.
  - `fun buildSections(categories: List<LiveCategory>, prefs: ProfilePrefs?): List<LiveSection>` — Favoris (si au moins un favori), puis playlists dans l'ordre, puis catégories selon l'ordre effectif, sans les catégories masquées (`live_category` global ou profil).
  - `fun visibleStreams(streams: List<LiveStream>, prefs: ProfilePrefs?): List<LiveStream>` — retire les chaînes masquées (`channel`).
  - `LocalStore` : `fun prefs(profileId: String): ProfilePrefs?` et `fun savePrefs(profileId: String, prefs: ProfilePrefs)` (JSON dans les préférences partagées, pas de sauvegarde Google déjà garantie).
  - `class PrefsWatcher(sync: SyncClient, store: LocalStore, householdId: () -> String?, profileId: String, scope: CoroutineScope, periodMs: Long = 4_000) { val prefs: StateFlow<ProfilePrefs?>; fun start(); fun stop() }` — émet d'abord le local, puis le serveur ; interroge `prefsRevision` toutes les `periodMs` tant qu'il est démarré ; relit `profilePrefs` seulement si la révision change ; erreurs réseau silencieuses (garde la dernière valeur).
  - `LiveBrowseViewModel` : prend `prefs: StateFlow<ProfilePrefs?>`, expose `sections: StateFlow<List<LiveSection>>` et `fun selectSection(key: String)` ; le contenu d'une section Favoris/Playlist vient de `repo.streamsByIds` (masqué gagne).
  - `LiveRefresher.run` publie le catalogue **après** le rafraîchissement local, dans un `try` qui n'échoue jamais le rafraîchissement (publication en erreur = notée, ignorée).

- [ ] **Step 1: Écrire les tests** :
  - `SectionsTest` : `sans_preferences_ordre_fournisseur_sans_favoris` ; `favoris_puis_playlists_puis_categories` ; `ordre_profil_et_nouvelles_categories_a_la_fin` (ordre `[c,a]`, fournisseur `a,b,c,d` → `c,a,b,d`) ; `categorie_masquee_globale_ou_profil_absente` ; `chaine_masquee_absente_des_favoris_et_categories`.
  - `CatalogRepositoryTest` : `streamsByIds_garde_l_ordre_et_ignore_les_absents`.
  - `PrefsWatcherTest` (temps virtuel) : `emet_le_local_puis_le_serveur` ; `revision_inchangee_ne_relit_pas` ; `revision_changee_relit_en_moins_de_5_s` ; `hors_ligne_garde_le_local` ; `stop_arrete_l_interrogation`.
  - `LiveBrowseViewModelTest` : `preferences_changent_sections_mises_a_jour` ; `favori_dans_categorie_masquee_globale_absent` (Review Focus 1) ; `profil_sans_preferences_affiche_le_fournisseur` (Review Focus 2).
  - `LiveRefresherTest` (créer si absent) : `publication_en_erreur_n_echoue_pas_le_rafraichissement` ; `categorie_masquee_globale_jamais_demandee_au_fournisseur` (garde la règle A).
- [ ] **Step 2: Lancer** `cd apps && ./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest` — Expected: FAIL.
- [ ] **Step 3: Implémenter** ; `PrefsWatcher` démarré quand l'écran du direct est visible et arrêté en `onStop` (pas d'interrogation en arrière-plan).
- [ ] **Step 4: Lancer** la même commande + `:tv:assembleDebug` — Expected: PASS.
- [ ] **Step 5: Commit** — `feat(tv): la télé suit l'organisation du profil et publie le catalogue`

### Task 5: Nouvel écran du direct sur la télé (thème sombre par jetons)

**Files:**
- Create: `apps/tv/src/main/kotlin/home/iptv/tv/theme/IptvTheme.kt`
- Modify: `apps/tv/src/main/kotlin/home/iptv/tv/theme/Theme.kt` (garder `IptvColors` pour les écrans de démarrage ou les faire passer par les jetons), `apps/tv/src/main/kotlin/home/iptv/tv/live/LiveBrowseScreen.kt`, `LiveRoute.kt`, `App.kt`, `apps/tv/build.gradle.kts`, `apps/gradle/libs.versions.toml` (Coil 3)
- Test: `apps/tv/src/test/kotlin/home/iptv/tv/theme/IptvThemeTest.kt`, `apps/tv/src/test/kotlin/home/iptv/tv/live/LiveScreenModelTest.kt`

**Interfaces:**
- Consumes: `LiveBrowseViewModel.sections/streams/selectSection` (tâche 4), `PlayerController`, `restoreAction` (partie A).
- Produces:
  - `@Immutable data class IptvTokens(val background: Color, val surface: Color, val surfaceRaised: Color, val text: Color, val textMuted: Color, val accent: Color, val focusRing: Color, val radiusCard: Dp, val radiusChip: Dp, val titleSize: TextUnit, val rowTextSize: TextUnit, val metaTextSize: TextUnit)`, `val DarkTokens: IptvTokens` (fond `#0E1116`, surface `#171C24`, surface relevée `#1F2632`, texte `#F2F4F8`, texte secondaire `#A3ADBD`, accent `#2C64F0`, anneau blanc), `val LocalIptvTokens`, `@Composable fun IptvTheme(tokens: IptvTokens = DarkTokens, content: @Composable () -> Unit)`.
  - `fun channelLabel(stream: LiveStream): String` (« 12 · RDS HD » si numéro, sinon le nom) et `fun sectionSubtitle(section: LiveSection, count: Int): String` (« 12 chaînes », « 1 chaîne », « Vide ») — utilisés par l'écran.
- Écran (d'après la maquette 3 de https://claude.ai/artifact/F7jLeEDSvJYRa69UMLh5Y4 — la lire avec l'outil Artifact `read` si disponible ; sinon suivre cette description) :
  - barre du haut : pastille du profil (couleur + initiale), nom du profil, heure ;
  - colonne gauche (~32 % de la largeur) : sections (⭐ Favoris, playlists, catégories) avec nombre de chaînes, élément focalisé sur fond `surfaceRaised` + anneau ;
  - liste de droite : lignes de 72 dp avec numéro, logo 48 dp (Coil 3, `icon` du cache ; lettre initiale si absent ou en erreur), nom, focus avec anneau 3 dp et léger grossissement (1,03) ; `LazyColumn` (20 000 lignes) ;
  - sans aperçu, sans guide (B2) ; messages vides en français : « Aucune chaîne dans cette section. », « Ajoute des favoris depuis ton téléphone : page de configuration → Organiser. » ;
  - animations ≤ 150 ms, aucune ombre lourde ni flou.
- Garder : restauration du focus après lecture, messages de la partie A, `keepScreenOn`.

- [ ] **Step 1: Écrire les tests** : `IptvThemeTest.jetons_sombres_valeurs_de_la_maquette` (couleurs exactes ci-dessus) ; `LiveScreenModelTest` : `etiquette_avec_et_sans_numero`, `sous_titre_singulier_pluriel_vide`.
- [ ] **Step 2: Lancer** `./gradlew --max-workers=2 :tv:testDebugUnitTest` — Expected: FAIL.
- [ ] **Step 3: Implémenter** l'écran ; aucune couleur ou taille codée en dur dans `live/` (tout via `LocalIptvTokens.current`).
- [ ] **Step 4: Vérifier** `./gradlew --max-workers=2 :tv:testDebugUnitTest :tv:assembleDebug` — Expected: PASS ; `grep -n "Color(0x" apps/tv/src/main/kotlin/home/iptv/tv/live/` ne renvoie rien.
- [ ] **Step 5: Commit** — `feat(tv): nouvel écran du direct, thème sombre par jetons`

### Task 6: « Organiser » sur le téléphone — catégories

**Files:**
- Create: `web/config/src/org/api.ts`, `web/config/src/org/norm.ts`, `web/config/src/org/categories.ts`, `web/config/src/org/view.ts`
- Modify: `web/config/src/main.ts` (onglets « Configuration » / « Organiser »), `web/config/src/ui.css`
- Test: `web/config/test/org-norm.test.ts`, `web/config/test/org-categories.test.ts`

**Interfaces:**
- Consumes: RPC de la tâche 2 ; `Db` de `src/flow.ts` (seulement `rpc`).
- Produces:
  - `searchNorm(s: string): string` (mêmes vecteurs que Kotlin).
  - `api.ts` : une fonction par RPC, typée, qui lève une `Error` au message français (réutiliser le motif `call`/`friendly` de `flow.ts`) : `orgCategories(db, h, p)`, `orgChannels(db, h, cat, p, off, lim)`, `orgSearch(db, h, q, p, lim)`, `orgSetHidden(db, h, p, type, ids, hidden)`, `orgHideAllExcept(db, h, p, keep)`, `orgSetCategoryOrder(db, h, p, ids)`, et celles des favoris/playlists utilisées à la tâche 7. `p: string | null` (null = tout le monde).
  - `categories.ts` (pur, testable) : `filterCategories(list, query): Category[]` (sur `searchNorm` du nom) ; `moveCategory(ids: string[], from: number, to: number): string[]` ; `hideAllExceptPlan(list, keep: Set<string>): { toHide: number; toShow: number }` ; `confirmText(scopeIsEveryone: boolean, toHide: number): string | null` → en « tout le monde » : « Ces N catégories ne seront plus téléchargées par la télé, pour tout le monde. Continuer ? », sinon `null`.
- Écran (`view.ts`, rendu DOM sans framework comme `main.ts`) :
  - sélecteur de portée en haut : chaque profil + « Tout le monde » ;
  - champ de recherche qui filtre les catégories ;
  - liste fenêtrée (seules les lignes visibles sont dans le DOM ; hauteur de ligne fixe 52 px) avec case « affichée », nom, nombre de chaînes, poignée ≡ ; en portée profil, une catégorie masquée pour tout le monde est grisée « masquée pour tout le monde » et non modifiable ;
  - mode « Réordonner » : boutons ▲ ▼ et glisser par la poignée (événements pointer), enregistrement par `orgSetCategoryOrder` à la sortie du mode ;
  - bouton « Tout masquer sauf ce que j'ai coché » (+ « Tout réafficher ») avec la confirmation de `confirmText` ;
  - si `orgCategories` est vide : « Ouvre l'app sur la télé une fois pour envoyer la liste des chaînes. »
  - toucher une catégorie → vue des chaînes (tâche 7).

- [ ] **Step 1: Écrire les tests** : `org-norm.test.ts` (trois vecteurs + vide) ; `org-categories.test.ts` : `filterCategories_insensible_aux_accents`, `moveCategory_haut_bas_bornes`, `hideAllExceptPlan_compte`, `confirmText_seulement_pour_tout_le_monde`, et `api` avec le faux `Db` de `flow.test.ts` : `orgHideAllExcept_envoie_p_null_pour_tout_le_monde`, `erreur_acces_refuse_traduite`.
- [ ] **Step 2: Lancer** `npm test -w web/config` — Expected: FAIL.
- [ ] **Step 3: Implémenter.**
- [ ] **Step 4: Lancer** `npm test -w web/config && npm run build -w web/config` — Expected: PASS ; ouvrir la page avec Playwright (Chromium préinstallé) sur `vite preview` avec un faux client injecté **ou** vérifier au minimum que l'onglet s'affiche sans erreur de console (capture d'écran iPhone 13).
- [ ] **Step 5: Commit** — `feat(web): organiser les catégories par profil depuis le téléphone`

### Task 7: « Organiser » sur le téléphone — chaînes, favoris, playlists, recherche

**Files:**
- Create: `web/config/src/org/channels.ts`, `web/config/src/org/favorites.ts`, `web/config/src/org/playlists.ts`
- Modify: `web/config/src/org/view.ts`, `web/config/src/org/api.ts`, `web/config/src/ui.css`
- Test: `web/config/test/org-lists.test.ts`

**Interfaces:**
- Consumes: `api.ts` (tâche 6), RPC de la tâche 2.
- Produces:
  - `channels.ts` : `pageLoader(fetchPage: (off: number, lim: number) => Promise<Channel[]>, pageSize = 500)` → `{ get(index): Channel | undefined; ensure(from, to): Promise<void>; total(): number | null }` (charge par pages à la demande pour la liste fenêtrée).
  - `favorites.ts` : `stateLabel(state: string): string` → `ok` : `""`, `categorie-masquee` : « Catégorie masquée », `masquee` : « Masquée », `n-existe-plus` : « N'existe plus » ; `reorder(ids, from, to)`.
  - `playlists.ts` : `validName(s): string | null` (1 à 40 caractères après coupe, sinon `null`) ; `addTo(items: string[], id: string): string[]` (sans doublon, en fin).
- Écrans :
  - chaînes d'une catégorie : liste fenêtrée, par ligne ⭐ (favori), « + Playlist » (choix d'une playlist ou « Nouvelle playlist »), « Masquer » (selon la portée ; « pour tout le monde » seulement en portée « Tout le monde ») ;
  - onglet **Favoris** (portée profil seulement ; en « Tout le monde » : « Choisis un profil ») : liste ordonnée, réordonner, retirer, éléments grisés avec `stateLabel` ;
  - onglet **Playlists** : créer, renommer, supprimer (confirmation), réordonner les playlists et leurs chaînes, ajouter par la recherche ;
  - **Recherche** globale (champ en haut de l'onglet Organiser) : `orgSearch` avec `searchNorm(q)`, à partir de 2 caractères, délai de 250 ms, 200 résultats max avec la mention « Affine ta recherche » si 200 ; chaque résultat montre la catégorie et les mêmes actions que la liste des chaînes.

- [ ] **Step 1: Écrire les tests** (`org-lists.test.ts`) : `pageLoader_charge_par_pages_et_pas_deux_fois`, `stateLabel_quatre_etats`, `validName_bornes`, `addTo_sans_doublon`, `reorder_favoris`, et `api` : `orgSetFavorite_puis_orgReorderFavorites_arguments`, `orgPlaylistSetItems_ordre_conserve`.
- [ ] **Step 2: Lancer** `npm test -w web/config` — Expected: FAIL.
- [ ] **Step 3: Implémenter.**
- [ ] **Step 4: Lancer** `npm test -w web/config && npm run build -w web/config` — Expected: PASS ; capture Playwright iPhone 13 des trois onglets avec un faux client (données d'exemple inventées).
- [ ] **Step 5: Commit** — `feat(web): favoris, playlists et recherche depuis le téléphone`

### Task 8: Mise en production 0.2.0 et essai chez le propriétaire

**Files:**
- Modify: `apps/tv/build.gradle.kts` (`versionCode = 2`, `versionName = "0.2.0"`), `web/config/public/version.json` (au déploiement), `docs/installer-sur-la-tele.md` (section « Mettre à jour » et « Organiser depuis le téléphone »)
- Create: `docs/rapports/tele-b1-organiser.md`

- [ ] **Step 1:** `cd apps && ./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest :tv:assembleRelease` + `npm test -w backend` + `npm test -w web/config` — Expected: tout PASS ; `apksigner verify` OK avec le même certificat (SHA-256 `5d8877ce…`).
- [ ] **Step 2 (contrôleur):** appliquer la migration `organiser` au projet Supabase `gisjeuukbrgjgkrmlyvs` (`apply_migration`), puis `get_advisors` sécurité : aucune alerte nouvelle de niveau ERROR.
- [ ] **Step 3 (contrôleur):** redéployer la page sur Vercel (projet `iptv-home-config`, même méthode que la partie A : fichiers sources en ligne, `vite build`), `version.json` = `{"versionCode":2,"versionName":"0.2.0"}` ; déposer l'APK dans le compartiment `apk` (règle d'écriture temporaire créée, utilisée, supprimée ; vérifier qu'il ne reste aucune règle sur `storage.objects`) ; vérifier le téléchargement octet pour octet.
- [ ] **Step 4:** guide mis à jour ; demander au propriétaire : mise à jour installée par-dessus sans perdre l'appairage ? onglet Organiser visible avec ses catégories ? « tout masquer sauf » + favoris + playlist faits ? la télé suit en moins de ~5 s ? écran du direct jugé beau ? 2ᵉ ouverture toujours rapide ? nombre réel de catégories et de chaînes (pour ajuster la spec §9.6) ; identifiants des chaînes stables d'un jour à l'autre (spec §9.5).
- [ ] **Step 5:** consigner dans `docs/rapports/tele-b1-organiser.md` ; commit — `chore(tv): version 0.2.0, organiser depuis le téléphone`
