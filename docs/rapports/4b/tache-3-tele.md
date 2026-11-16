# Tâche 3 — Films télé

## Checkpoint accueil / état (16 novembre 2026)

Ajout du contrat MovieCatalog, adaptateur du dépôt déjà testé, état local distinct par profil pour progression et Ma liste, publication quotidienne des catégories, accueil à la demande. Aucune demande de contenu avant préférences connues ; revalidation du masque après réponse ; références sans catégorie rétablies par cache seul. Les récents (20) viennent de la requête paginée récemment ajoutée du dépôt, jamais d'un catalogue global ; dates invalides exclues. Catégories toutes accessibles, mémoire de rangées évincée (24 / 40000 affiches hors rangées actives).

Vérification : nouvelles classes absentes au premier passage de compilation (rouge), puis compilation et harness JavaExec externe `/workspace/iptv-work/4b/task-3-plain-tests.gradle` : **272 tests TV JVM verts**, dont 5 Films (4 rangées + 1 accueil masquage/préférences). Journal `/workspace/iptv-work/4b/task3-home.log`. Le harness ne contourne aucun test produit ; il exécute seulement les tests sans runtime Android.

À poursuivre : fiche/actions, lecture, écrans et intégration, tests D-pad à compiler et exécuter si le téléchargement Robolectric redevient possible. Le parcours Films n'est pas encore intégré à l'application. Aucun changement de version, publication, production ou configuration réseau.

## Checkpoint fiche / actions

Fiche et demandes de lecture typées Films : ancienne référence sans catégorie indisponible jusqu'à redécouverte ; refus avant demande et après réponse si masquée. État de progression fusionné avec positions locales et copie durable, Ma liste conservée hors ligne. Marquage vu/non vu conserve l'horodatage de lecture et retire uniquement les positions Films concernées après réussite du RPC. Une méthode additive `ProgressQueue.withPausedFlush` utilise le mutex d'envoi existant : un RPC manuel attend tous les envois partis, puis effectue son RPC et sa suppression dans la même exclusion ; annulation propagée, erreur préserve la file. Branchement concret MainActivity à venir.

Vérification : tests fiche absents puis compilation rouge ; **274 tests TV JVM verts** (2 nouveaux), suite shared verte (**263 tests**, comptage XML à confirmer en validation finale), incluant nouveau test d'ordre envoi suspendu/action manuelle. Commande : Gradle `:shared:jvmTest :tv:task3PlainTests`, harness externe ; journal `task3-detail.log`.

## Checkpoint coordinateur de lecture

Coordinateur Films séparé de la logique épisodes, réutilisant PlayerController, ProgressQueue, ProgressFlusher et normalisation des langues. Reprise, enregistrement 30 s/pause/sortie avec capture finale du lecteur, profil d'origine, génération d'annulation, fermeture préalable du lecteur, masquage revalidé après chargement et pendant lecture, fin sans enchaînement. Préférences audio/sous-titres du profil réutilisées.

Vérification : tests rouges par absence de coordinateur puis **276 tests TV JVM verts** (2 nouveaux scénarios : reprise/30 s/pause/sortie/profil/single lecteur et masquage pendant chargement/référence inconnue). Journal `task3-playback.log`. Les écrans et leur intégration restent à terminer ; pas encore un parcours utilisable.

## Checkpoint écrans / intégration

Parcours intégré à Direct/Séries/Films (valeur locale `movies`, anciennes valeurs conservées), accueil paresseux, fiche métadonnées et actions, lecteur plein écran, pistes existantes, retour à la fiche puis à l'affiche d'origine. Réutilisation directe des composants Poster/RowSection Séries (seule visibilité changée), TrackPanel, PlayerSurface et langues ; aucune copie des règles d'épisodes/saisons. Fabriques dans MainActivity/IptvApp ; arrêt Films avant lecteur et persistance de file dans onStop, arrêt épisode avant Films et inversement. Mise à jour immédiate fiche/accueil après lecture.

Vérification : **276 tests TV JVM verts**, production et tous tests Kotlin compilés, **APK debug construit** (`:tv:task3PlainTests :tv:assembleDebug`, journal `task3-ui.log`). 4 tests Robolectric Films écrits et compilés : toutes rangées avec Bas, OK/Retour fiche et focus, barre Films↔Séries et préférence persistée, commandes/panneau pistes/Retour. Leur exécution reste à tenter avec le réseau actuel ; la compilation ne prouve pas la navigation réelle. Version toujours 0.4.1/code5, aucune publication.

## Durcissement après auto-revue

Deux défauts reproduits puis corrigés : Réessayer pouvait demander les catégories avec préférences inconnues ; une reprise d'un film déjà vu faisait disparaître localement son statut vu pendant que la position était dans la file, contrairement au backend. Réessayer exige maintenant des préférences ; fusion commune progression Films conserve le statut vu connu face à une position en attente et complète les métadonnées. Test masquage en vol renforcé : la requête de liste est réellement suspendue avant modification du masque.

Preuve : `task3-hardening-red.log` (Réessayer, 277 tests/1 échec), `task3-hardening.log` (statut vu, 278/1 échec), `task3-hardening-green.log` **278 tests TV JVM verts**. Aucun ajustement de délai de test. L'exécution D-pad Android reste non vérifiée.

## Validation finale avant relecture indépendante

Dernier correctif RED→GREEN : une référence entrante d'une catégorie autorisée pouvait ignorer une catégorie masquée déjà connue du cache et demander la fiche. La fiche et la lecture examinent maintenant l'union des catégories connues (demande/cache/progression/liste/fiche), avant la demande et après réponse. Le lecteur garde aussi cette union pendant ses ticks.

Tests supplémentaires : fin avec durée connue marque vu et ferme sans autre lecteur ; durée inconnue reprend une position positive ; arrêt avant réponse de chargement empêche toute ouverture ; seuil 90 % et moins de 30 secondes repartent à zéro. `task3-final-red.log` montre les 2 régressions masque reproduites. `task3-final-green.log` : **263 shared / 0 échec, 282 TV JVM / 0 échec, tous tests Kotlin compilés, assembleDebug réussi**. `git diff --check` propre.

Commande finale indépendante : `source /workspace/iptv-env/activate.sh; cd apps; ./gradlew --max-workers=1 -I /workspace/iptv-work/4b/task-3-plain-tests.gradle :shared:jvmTest :tv:task3PlainTests :tv:assembleDebug`.

Tentative officielle `:tv:testDebugUnitTest` effectuée ensuite, **échec avant exécution** : résolution de `org.robolectric:android-all-instrumented:15-robolectric-14663667-2-i7` redirigée de repo1.maven.org vers gcp.mirrors.maven.org, HTTP403. Journal `/workspace/iptv-work/4b/task3-official-tv.log`. Aucun faux artefact, contournement de vérification, filtre produit ni changement réseau.

Le harness externe ne lance pas les classes nécessitant Android : `data.ProgressPersistenceTest` et, sous Robolectric, `player.PlayerViewTest`, `ui.HarnessTest`, `ui.LiveDpadTest`, `ui.SeriesDpadTest`, `ui.SectionBarDpadTest`, `ui.MovieDpadTest`. Toutes compilent. Les **4 tests MovieDpadTest ne sont pas déclarés exécutés**. Ils couvrent flèches sur les catégories, fiche/Retour/focus, barre et onglet persisté, lecture/commandes/pistes/Retour. La suite officielle et l'essai TCL restent indispensables pour valider le runtime, MKV, image, pistes et navigation.

Commits de réalisation : `5d7c687` accueil/état ; `9ccb4f8` fiche/actions et exclusion avec flush ; `68f99f0` lecture ; `c2c206e` écrans/intégration ; `92c81a9` préférences et statut vu ; correctif final masque/cache dans le commit portant ce rapport. Relecture indépendante organisée par le contrôleur ; pas de sous-agent par l'implémenteur. Ni version changée, ni push, ni publication, ni production ou environnement cloud modifié par l'implémenteur.

Choix d'implémentation : rangées spéciales bornées à 20 ; catégories et affiches de catégorie accessibles par listes paresseuses ; mémoire 24 rangées/40000 affiches hors rangées actives. Les récents ne font qu'une lecture paginée du cache. Les changements de Ma liste/vu attendent la réussite du RPC (boutons occupés, erreur française), puis mettent à jour la copie locale ; hors ligne, les données existantes restent affichables. L'absence de runtime Android interdit toute conclusion sur le focus réel malgré les tests écrits.

### Points transmis à la relecture (non résolus à ce checkpoint)

La dernière lecture des sources a identifié trois points à apprécier dans la phase de revue : l'accueil attend actuellement le premier appel serveur avant sa première reconstruction (un serveur lent peut retarder l'affichage des données locales) ; la mise en avant de secours vient de Reprendre/récents/Ma liste et peut manquer lorsque seuls des films de catégorie sans date valide sont connus ; le Retour de MovieTab n'a pas le repli `focusFailed` de SeriesTab si la barre ne peut pas prendre le focus. Ces points sont explicitement transmis au contrôleur, sans affirmation de préparation à la livraison. Le parcours doit encore franchir la relecture et la validation Android réelle.

## Clôture des trois points résiduels avant relecture

Les trois points ci-dessus sont corrigés dans ce checkpoint, sans élargissement de périmètre :

1. **Données locales avant réseau.** `start` reconstruit explicitement l'accueil dès la lecture disque et les préférences connues, avant l'appel serveur suspendant. La régression pilote les deux tâches IO dans l'ordre observateur-préférences puis disque (l'ordre inverse existe en production), garde le serveur suspendu et exige Reprendre et Ma liste immédiatement visibles. Avant correction, Reprendre était nul ; après correction, la copie locale s'affiche sans réponse réseau.
2. **Mise en avant sans date valide.** Ajout de `MovieRepository.featuredMovie` et requête SQL `selectFeaturedMovie`, cache seul, catégories masquées exclues dans SQL, `LIMIT 1`. L'accueil l'utilise uniquement si Reprendre/récents/Ma liste ne fournissent aucun héros. Les dates ne sont pas modifiées : un film sans date ou avec date invalide peut être mis en avant, jamais devenir une nouveauté. Régressions dépôt et ViewModel démontrent l'absence de requête fournisseur et une rangée récente vide.
3. **Retour sans piège.** `MovieHomeBackHandler` utilise le mécanisme éprouvé de Séries : sur échec/exception de demande de focus de barre, désactiver son BackHandler puis déléguer au dispatcher parent depuis un effet, avant réarmement. Le nouveau scénario D-pad « back_exits_once_when_section_bar_cannot_take_focus » est écrit et compilé ; son exécution est **non vérifiée**, comme les quatre autres, à cause du blocage Robolectric documenté.

Preuves RED : `task3-concerns-behavior-red.log` : **264 shared / 1 échec** (héros du cache), **284 TV JVM / 2 échecs** (copie locale retardée et héros sans date). Preuves GREEN : `task3-concerns-green.log` : **264 tests shared / 0 échec, 284 tests TV JVM / 0 échec, tous tests Kotlin compilés (5 MovieDpadTest), APK debug construit**, commande habituelle `:shared:jvmTest :tv:task3PlainTests :tv:assembleDebug` avec harness externe. Comptage shared vérifié dans les XML. `git diff --check` propre. Le blocage officiel HTTP403 observé au checkpoint précédent reste la limite d'exécution Android ; aucune nouvelle tentative identique ni changement réseau.

La réalisation est remise au contrôleur pour relecture indépendante. Les trois préoccupations connues de ce checkpoint sont closes dans le code ; les affirmations de navigation Android restent conditionnées à l'exécution Robolectric et à l'essai TCL. Aucun changement de version, push, production, publication ni environnement cloud par l'implémenteur.
