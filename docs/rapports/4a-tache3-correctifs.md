# Correctifs de progression — tâche 3

Base : d7c1ac1, branche plan-4-series. Travail limité à la tâche 3. Aucune production, publication, migration, clé de signature ou identifiant réel utilisé.

## Preuves TDD

Toutes les commandes Gradle s'exécutent après `source /workspace/iptv-env/activate.sh`, dans `apps`, avec `./gradlew --max-workers=2`.

| Exigence | Rouge observé avant correction | Vert / état |
|---|---|---|
| Conserver les enrichissements nulls au remplacement | `:shared:jvmTest --tests '*ProgressQueueTest.replacementRetainsMissingMetadata'` : 1 test, 1 échec AssertionError ligne 50 (seriesId perdu) | `:shared:jvmTest --tests '*ProgressQueueTest*'` : BUILD SUCCESSFUL, 11 tests |
| Horloge strictement croissante | `:shared:jvmTest --tests '*ProgressQueueTest.replacementTimestamp*'` : 1 test, 1 AssertionError ligne 49 | `:shared:jvmTest --tests '*ProgressQueueTest*'` : 12 tests verts ; horloge constante puis régressive |
| Erreur permanente ne bloque pas | `:shared:jvmTest --tests '*ProgressFailureTest*'` : AssertionError (1 requête au lieu de 2), réponse SDK réelle HTTP403/code42501 via MockEngine | `:shared:jvmTest --tests '*Progress*Test*'` vert |
| Abandon au cinquième échec permanent | `:shared:jvmTest --tests '*ProgressFailureTest.permanentFailureDropped*'` : AssertionError (entrée encore présente après cinq appels) | `:shared:jvmTest --tests '*Progress*Test*'` vert |
| Expiration 14 jours | `:shared:jvmTest --tests '*ProgressFailureTest.entriesExpire*'` : AssertionError (appel réseau malgré expiration) | `:shared:jvmTest --tests '*Progress*Test*'` vert ; abandon à âge >= 14 jours |
| FlushResult / file vide | `:shared:jvmTest --tests '*ProgressQueueTest.emptyFlush*'` : ComparisonFailure (Unit au lieu de résumé) | `:shared:jvmTest --tests '*Progress*Test*'` vert ; sent/dropped/blocked vérifiés aussi sur envoi, expiration, cinquième refus |
| Classification SDK | Régression permanente rouge ci-dessus ; matrice de couverture ajoutée sans changement production | HTTP400/403/404/422 permanents ; 401/408/429/500/503 transitoires ; codes42501/P0001 permanents hors statut transitoire explicite, tests via vrai SDK et MockEngine verts |
| Modifications atomiques | `:shared:jvmTest --tests '*ProgressQueueTest.concurrentEnqueues*'` : AssertionError, pertes sur 100 insertions concurrentes | `:shared:jvmTest --tests '*ProgressQueueTest*'` : 14 verts, dont enqueue pendant flush ; `update` obligatoire, Android synchronized |
| Capacité 300 | `:shared:jvmTest --tests '*ProgressQueueTest.queueDrops*'` : 1 AssertionError (302 au lieu de 300) | `:shared:jvmTest --tests '*ProgressQueueTest*'` : 13 tests verts |
| apply / persistNow | `:tv:testDebugUnitTest --tests '*ProgressPersistenceTest*'` : 2 AssertionError (apply absent et méthode absente) | Même commande verte : écriture apply sans commit ; `LocalStore.persistNow(): Boolean` commit, résultat vrai/faux testé |
| Codec partagé, Instant/nulls, corruption entrée par entrée, clés inconnues, champs facultatifs | API d'abord absente (compile, pas rouge fonctionnel), extraction ancien décodeur ; `:shared:jvmTest --tests '*ProgressQueueCodecTest*'` : 2 tests, 1 AssertionError sur voisins valides perdus | `:shared:jvmTest --tests '*Progress*Test*'` vert ; codec commun branché dans Android |
| JSON illisible conservé sous clé de secours | `:tv:testDebugUnitTest --tests '*ProgressPersistenceTest*'` : AssertionError (secours null après remplacement) | Codec JVM + persistance Android ciblés verts ; `progress_queue_corrupt` conserve le document ; `{}`/`null` traités illisibles aussi |
| Affiche sans paramètres ni userinfo | `:shared:jvmTest --tests '*ProgressQueueTest.coverPersists*' --tests '*ProgressClientTest.recordAndWatchedStrip*'` : 2 ComparisonFailure | `:shared:jvmTest --tests '*Progress*Test*'` vert ; nettoyage queue, RPC record et setWatched, fragment retiré aussi |
| Auth record et publication, non-vu, délais/IOException | Tests de couverture demandés : comportements déjà corrects, aucun défaut production ni rouge artificiel | `:shared:jvmTest --tests '*Progress*Test*' --tests '*SyncClientTest.publishSeriesCategoriesWithout*'` vert : aucune requête sans session, false explicite, timeout/IOException bloquent sans incrément attempts ; annulation propagée |
| Constantes onglets | `:tv:testDebugUnitTest --tests '*ProgressPersistenceTest*'` : 2 AssertionError (valeur inconnue acceptée, API chaîne libre) | Même commande verte : HomeTab.DIRECT/SERIES, roundtrip isolé par profil, inconnu ignoré |
| Priorité des statuts HTTP transitoires sur codes PG | `:shared:jvmTest --tests '*ProgressFailureTest.serverFailureWith*' --tests '*ProgressFailureTest.transientHttpStatus*'` : 2 tests, 2 AssertionError (401+42501 et 500+P0001) | Suite Progress* verte ; 401/408/429/>=500 prioritaires pour ne pas abandonner sur auth/proxy |

| Capacité persistée / ancienne file | `:shared:jvmTest --tests '*ProgressQueueCodecTest*'` : 2 AssertionError (302 entrées au décodage ancien et encodage direct) | Progress* + persistanceTV verts ; limite commune300, plus anciens retirés aussi au codec |
| Roundtrip Android | Couverture additionnelle, aucune correction nécessaire | Save/apply, persistNow, nouvelle instance AndroidLocalStore, load/update conservent Instant/nulls/attempts et profils |

## Checkpoints et sauvegardes

Chaque checkpoint ci-dessous a été précédé de `git pull --rebase --autostash origin plan-4-series`, puis poussé avec `git push origin HEAD:plan-4-series`. Aucun reset ni écrasement de travail tiers. Le checkpoint WIP des tests précède toute correction ; les sous-étapes vertes ont été poussées immédiatement.

- `ee7629c` : test(series): sauvegarder la regression des enrichissements.
- `2d02085` : fix(series): conserver les enrichissements de progression.
- `fad61f9` : fix(series): garantir un horodatage de remplacement croissant.
- `eceea8f` : fix(series): borner la file de progression a 300 entrees.
- `2071579` : fix(series): modifier atomiquement la file de progression.
- `75f53e1` : fix(series): poursuivre les envois apres un refus permanent.
- `d08af3a` : fix(series): abandonner apres cinq refus permanents.
- `2f0a72f` : fix(series): expirer les progressions apres quatorze jours.
- `d300c88` : fix(series): retourner le bilan des envois et verifier les erreurs SDK.
- `9903bc0` : fix(series): decoder la file entree par entree dans le code partage.
- `56f4c4e` : fix(series): prioriser les statuts transitoires sur les codes PostgreSQL.
- `d2d904b` : fix(series): conserver la file illisible avant remplacement.
- `ef9d6bb` : fix(series): differer les ecritures et exposer la sauvegarde onStop.
- `87346b3` : fix(series): retirer les donnees sensibles des adresses daffiche.
- `c90cd43` : fix(series): typer les onglets locaux Direct et Series.
- `1bfcdd0` : test(series): couvrir authentification non-vu et delais de progression.
- `e32029e` : docs(series): consigner la verification complete des correctifs.
- `0b0825c` : fix(series): borner aussi les files deja persistees.

## Interfaces exactes consommées par les tâches 4–6

Code partagé, package `home.iptv.shared.progress` :

```kotlin
data class ProgressEntry(
    val itemId: String, val seriesId: String?, val season: Int?, val episode: Int?,
    val positionS: Int, val durationS: Int, val clientUpdatedAt: Instant,
    val seriesName: String? = null, val seriesCover: String? = null,
    val seriesCategory: String? = null,
)
data class QueuedProgress(val profileId: String, val entry: ProgressEntry, val attempts: Int = 0)
data class FlushResult(val sent: Int, val dropped: Int, val blocked: Boolean)
interface ProgressStore {
    fun loadProgressQueue(): List<QueuedProgress>
    fun saveProgressQueue(queue: List<QueuedProgress>)
    fun update(transform: (List<QueuedProgress>) -> List<QueuedProgress>)
}
class ProgressQueue(client: ProgressClient, store: ProgressStore, clock: () -> Instant) {
    fun enqueue(p: String, entry: ProgressEntry)
    suspend fun flush(): FlushResult
}
interface ProgressClient {
    suspend fun record(p: String, entry: ProgressEntry): Boolean
    suspend fun continueWatching(p: String, max: Int = 20): List<ProgressRow>
    suspend fun setWatched(p: String, items: List<WatchedItem>, watched: Boolean)
    suspend fun setSeriesList(p: String, seriesId: String, on: Boolean)
}
object ProgressQueueCodec {
    fun encode(entries: List<QueuedProgress>): String
    fun decode(raw: String): DecodedProgressQueue
}
data class DecodedProgressQueue(val entries: List<QueuedProgress>, val unreadable: String? = null)
```

`sent` compte les RPC réussis, y compris `record=false` quand le serveur garde une position plus récente. `dropped` compte les suppressions de ce flush (expiration ou cinquième échec permanent) ; les débordements de capacité à enqueue/codec ne sont pas des envois. `blocked=true` signifie arrêt sur erreur transitoire ; des refus permanents encore en attente peuvent subsister avec `blocked=false`. Une entrée permanente reçoit au plus une tentative par passage. Un remplacement représente une nouvelle position et remet attempts à zéro ; les enrichissements nulls viennent de l'entrée précédente encore en file. L'horloge augmente d'au moins 1 ms par remplacement d'un même épisode/profil en attente.

Android, package `home.iptv.tv.data` :

```kotlin
enum class HomeTab(val storedValue: String) { DIRECT("direct"), SERIES("series") }
interface LocalStore : ProgressStore {
    fun persistNow(): Boolean
    fun lastTab(profileId: String): HomeTab?
    fun saveLastTab(profileId: String, tab: HomeTab)
    fun audioLang(profileId: String): String?
    fun subtitleLang(profileId: String): String?
    fun saveTrackPrefs(profileId: String, audioLang: String?, subtitleLang: String?)
    // Les autres méthodes existantes restent inchangées.
}
```

`AndroidLocalStore` synchronise lecture, écriture, update et persistNow. L'application possède une instance partagée dans `IptvApp.store`. Les appels ordinaires passent par apply ; tâche 6 doit appeler `store.persistNow()` lors de onStop et traiter false sans journaliser les données. Ne pas réimplémenter load + save pour une mutation : utiliser update. Les tâches 4–5 peuvent consommer continueWatching et HomeTab ; la tâche 6 consomme enqueue/flush/persistNow et les préférences de pistes. Aucun branchement ni écran des tâches 4+ n'a été implémenté ici.

## Classification SDK vérifiée

Inspection des classes JVM de supabase-kt **3.6.0** par javap : `PostgrestRestException` hérite de `RestException`, expose `code: String`, et `RestException` expose `statusCode: Int` (ainsi que response). Les tests `ProgressFailureTest` passent par `SupabaseProgressClient`, le vrai SDK et Ktor MockEngine : aucun mock de la fonction de classification, aucun appel externe. Le code n'analyse pas les messages d'erreur.

Priorité finale décidée avec le contrôleur : HTTP401/408/429/>=500 sont transitoires même avec code PG, puis 42501/P0001 et les autres4xx sont permanents. IOException, absence de session et délais sont transitoires ; les exceptions inconnues sont conservées par prudence. CancellationException est propagée ; le délai interne TimeoutCancellationException bloque ce passage sans consommer attempts. Le premier choix de priorité PG sur HTTP500 a été corrigé après deux nouveaux rouges indépendants (voir tableau).

## Autorevue et limites

- Vérifié : retrait et incrément attempts ne touchent que l'objet envoyé ; un remplacement pendant la suspension réseau reste en file. 100 enqueue concurrents conservent leurs entrées ; la limite300 conserve les plus récentes.
- Vérifié : ancien JSON sans attempts reste lisible ; une entrée défectueuse n'efface pas ses voisines ; document illisible/non-tableau conservé sous `progress_queue_corrupt` avant remplacement. La sauvegarde reste privée, sans journalisation du contenu.
- Vérifié : nettoyage des affiches à enqueue et aux deux RPC ; userinfo, paramètres et fragment retirés. Les anciens chemins d'image relatifs sont conservés sans paramètres/fragment pour compatibilité.
- Autorevue a détecté et corrigé la borne manquante des anciennes files et des encodages directs, avec deux rouges supplémentaires.
- AndroidLocalStore est réellement exécuté dans les tests JVM contre une implémentation mémoire de SharedPreferences. Cela couvre encode/decode, nouvelles instances, update, apply/commit et secours ; aucune simulation de coupure électrique ni essai physique TCL n'a été réalisé.
- Les tests manquants de comportements déjà présents passent sans correction production : aucune panne d'environnement ni rouge artificiel compté comme preuve TDD. L'absence initiale d'API codec n'est pas comptée comme rouge fonctionnel ; le test corrompu a échoué contre le décodeur existant extrait avant sa correction.
- Aucun service de production, migration, signature ou identifiant réel touché. Revue indépendante et tâches 4+ laissées au contrôleur.

## Vérification finale

Commande finale après tous les changements de code et de tests :

```text
./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest :tv:assembleDebug
BUILD SUCCESSFUL in 10s
70 actionable tasks: 6 executed, 64 up-to-date
shared : 166 tests, 0 failures, 0 errors, 0 skipped
tv     : 163 tests, 0 failures, 0 errors, 0 skipped
```

Comptages lus dans les XML JUnit des deux suites. APK debug construit localement, sans publication. `git diff --check d7c1ac1` passe. Baseline **143** déclarations @Test confirmé dans d7c1ac1, sans relance inutile ; 23 tests shared et 7 tests TV ajoutés. Avertissements de compilation connus : `SyncClient.cause` déprécié (préexistant) et lateinit inutile dans le double SharedPreferences de test ; aucun échec. Les traces brutes des commandes restent locales sous `/tmp/task3-*.log` ; ce rapport versionné contient les commandes et résultats nécessaires à la reprise.

État : **DONE**, prêt pour la revue fraîche du contrôleur. Dernier checkpoint code testé : `0b0825c` ; le commit documentaire final porte ce rapport et la note de journal.
