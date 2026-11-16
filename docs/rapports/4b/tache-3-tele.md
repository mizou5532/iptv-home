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
