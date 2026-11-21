### Task 5 : Cache de la télé, colonnes de clés et remplacement depuis le serveur

**Files :**
- Create : `apps/shared/src/commonMain/sqldelight/home/iptv/shared/catalog/5.sqm`
- Modify : `Catalog.sq` (colonnes, requêtes), `CatalogRepository.kt`
- Test : `apps/shared/src/jvmTest/kotlin/home/iptv/shared/catalog/ReplaceLiveTest.kt`

**Interfaces :**
- `live_stream` gagne `auto_key TEXT` et `base_title TEXT` (nuls pour les chaînes téléchargées par la télé elle-même).
- `fun CatalogRepository.replaceLive(snapshot: LiveSnapshot): RefreshReport` : en **une transaction**, remplace
  catégories et chaînes par celles de la liste reçue, clés comprises. **Si la liste reçue n'a aucune catégorie alors
  que le cache en a, ne touche à rien** et lève `XtreamError.Unexpected(200)` (même règle que `refreshLive`). Rend un
  `RefreshReport` avec `providerCategories = snapshot.categories` (pour l'écran et la publication).
- `fun CatalogRepository.liveKeys(): Map<String, PrecomputedKey>` : les clés gardées (chaînes dont `base_title` n'est
  pas nul).
- `refreshLive` (chemin de secours) met `auto_key` et `base_title` à nul pour les chaînes qu'il écrit.

- [ ] **Step 1 :** tests `replaceLiveWritesStreamsAndKeys`, `replaceLiveKeepsCacheWhenSnapshotEmpty` (Review Focus
  1), `refreshLiveClearsKeys`, `migration5KeepsExistingRows` (une base au schéma 4 avec des chaînes passe au schéma 5
  sans rien perdre).
- [ ] **Step 2 :** ÉCHEC, implémenter, PASS (`:shared:jvmTest`), puis `:tv:testDebugUnitTest` (PASS).
- [ ] **Step 3 :** commit `feat(shared): cache du Direct rempli depuis le serveur maison`.

