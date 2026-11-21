### Task 1 : Déplacer le regroupement du Direct vers `apps/shared`

**Files :**
- Create : `apps/shared/src/commonMain/kotlin/home/iptv/shared/groups/GroupIndex.kt`
- Modify : `apps/tv/src/main/kotlin/home/iptv/tv/live/Sections.kt` (retirer `categoryCountries`, `autoKeys`,
  `cachedTitle`, `cachedAutoKey`, `groupSourceIndex` et importer ceux de `shared`)
- Test : `apps/shared/src/jvmTest/kotlin/home/iptv/shared/groups/GroupIndexTest.kt`

**Interfaces :**
- Produces (paquet `home.iptv.shared.groups`) :
  - `fun categoryCountries(categories: List<LiveCategory>): Map<String, String>`
  - `fun cachedTitle(name: String): String`
  - `fun cachedAutoKey(name: String, country: String?): String?`
  - `data class PrecomputedKey(val autoKey: String?, val title: String)`
  - `fun groupSourceIndex(visibleSources: List<GroupSource>, snapshot: GroupSnapshot, enabled: Boolean, countries: Map<String, String> = emptyMap(), precomputed: (sourceId: String) -> PrecomputedKey? = { null }): Map<String, LiveGroup>`
    : quand `precomputed(id)` n'est pas nul, ses valeurs remplacent `cachedAutoKey` et `cachedTitle` pour cette
    source.
- Le cache mémoire de `commonMain` ne peut pas utiliser `java.util.concurrent` : un `HashMap` protégé par un verrou
  `kotlinx.atomicfu` ou `synchronized` d'un `expect/actual` minimal. *Au choix du constructeur*, à condition de garder
  la limite `AUTO_KEYS_MAX = 200_000`.

- [ ] **Step 1 : écrire le test qui échoue**, `GroupIndexTest.sameGroupsAsBeforeOnFixture` : sur
  `fixtures/live-groups-v2.json` (déjà utilisé par les tests de `LiveGroups`), `groupSourceIndex(...)` donne
  exactement les mêmes groupes (identifiants, titres, sources dans l'ordre) que
  `buildGroups(sources, snapshot, keyOf = { autoKey(it.name, countries[it.categoryId]) }, titleOf = ::baseTitle)`.
- [ ] **Step 2 :** écrire `precomputedKeysWinOverParsing` : une source dont `precomputed` rend
  `PrecomputedKey("cle-forcee", "Titre forcé")` est regroupée avec une autre source ayant la même clé forcée, même si
  leurs noms ne se ressemblent pas.
- [ ] **Step 3 :** lancer `cd apps && ./gradlew --max-workers=2 :shared:jvmTest --tests '*GroupIndexTest*'`. Attendu :
  ÉCHEC (fonctions inconnues).
- [ ] **Step 4 :** créer `GroupIndex.kt` en déplaçant le code de `Sections.kt` tel quel, puis ajouter `precomputed`.
- [ ] **Step 5 :** relancer le test, puis `:tv:testDebugUnitTest`. Attendu : tout passe (les tests télé du Direct,
  du Sport et de la recherche qui utilisent ces fonctions restent verts sans modification de comportement).
- [ ] **Step 6 :** commit `refactor(shared): regroupement du Direct déplacé dans le code commun`.

