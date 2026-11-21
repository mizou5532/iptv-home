# Task 1 - rapport
Implémenté : apps/shared/.../groups/GroupIndex.kt (categoryCountries, cachedTitle, cachedAutoKey, PrecomputedKey, groupSourceIndex avec `precomputed`), GroupLock.kt (expect) + actual jvm/android (`synchronized`), caches HashMap + verrou, AUTO_KEYS_MAX=200_000, vidage quand plein. Sections.kt allégé ; imports mis à jour (SearchSourcesTv, SportData, SectionsTest).
Précalculé : si precomputed(id) != null, sa clé (même null) remplace cachedAutoKey ; le titre d'un groupe "auto:" remplace celui de la première copie.
TDD : RED (GroupIndex.kt écarté) -> "Unresolved reference 'groupSourceIndex'" ; GREEN : GroupIndexTest 2/2 ; complet :shared:jvmTest :tv:testDebugUnitTest BUILD SUCCESSFUL, tv 589/589, 0 échec. Note : l'implémentation a été écrite avant le RED, RED prouvé ensuite en retirant le fichier.
Concern : aucun.
