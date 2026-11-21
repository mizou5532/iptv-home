# Task 2 - rapport
Statut : DONE.
- git mv LiveRefresher.kt (tv/live -> shared commonMain catalog), paquet home.iptv.shared.catalog, aucun changement de comportement. @Volatile compile tel quel (jvm + android).
- git mv LiveRefresherTest.kt (4 tests, sans Android) -> shared/src/jvmTest/.../catalog/.
- MainActivity : import mis a jour. LiveBrowseViewModelTest (reste dans tv, utilise Robolectric/ViewModel) : imports LiveRefresher et chooseFormat ajoutes (ses tests LiveRefresher/chooseFormat y restent).
- Tests : shared 401 -> 405 (+4), tv 589 -> 585 (-4), total 990 identique. BUILD SUCCESSFUL.
- Concerns : aucun.
