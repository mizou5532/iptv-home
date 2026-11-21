# Task 9 - rapport
Implémentation : EpgSource (+ of(api)) déplacée dans shared/xtream/EpgSource.kt, imports tv corrigés (MainActivity, LiveSessions, EpgRepository, EpgRepositoryTest). EpgCache (LRU 2000, clé stream|limit|full, TTL 5 min max / fin ou début du prochain programme, Semaphore(2), limit borné 1..8, erreurs non mises en cache). Route epgRoute dans Routes.kt (400 sur id/limit/full invalides, 502 sans détail, log = nom de classe seulement), câblée dans Main.kt avec EpgSource.of(::api).
TDD : RED = EpgCache retiré -> :server:compileTestKotlin en échec ; GREEN après implémentation.
Tests : server 45 (EpgCacheTest 4, EpgRouteTest 4 ajoutés), shared 419, tv 585 (= base), 0 échec.
Concerns : mode full filtre les programmes déjà finis puis take(n) (le TV utilise around(), un peu différent) ; pas de dédoublonnage des demandes simultanées identiques (seulement limité à 2).
