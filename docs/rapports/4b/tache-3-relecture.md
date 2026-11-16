# Tâche 3 — Relecture indépendante

16 novembre 2026. Périmètre : diff applications 6863bf8..476e5ff, brief tâche 3, conception approuvée et rapport de réalisation. Revue statique interrompue à la demande du propriétaire pour sauvegarde et passation ; conclusions présentes conservées, revue non clôturée. Aucune modification produit, aucun test relancé, aucune intervention externe.

Verdict spécification provisoire : **corrections importantes nécessaires**. Verdict qualité provisoire : architecture cohérente, mais propagation de l'état local et dépendances réseau encore insuffisantes. Aucun Critical établi. Les points ci-dessous sont déduits du code ; leurs régressions restent à écrire et exécuter.

## Important I1 — Le réseau retarde encore les catégories et les fiches déjà en cache

`apps/tv/src/main/kotlin/home/iptv/tv/movies/MovieHomeViewModel.kt:51` appelle `reloadNow()` avant `catalog.categories()` ligne 53. Les deux RPC de progression/liste sont séquentiels (117–118). Avec un serveur suspendu, la première reconstruction affiche bien les données locales, mais `categories` reste vide : les catégories locales ne sont jamais accessibles tant que ces RPC ne terminent pas. Le nouveau test démontre seulement Reprendre/Ma liste. Charger le catalogue local indépendamment de la synchronisation.

Même dépendance dans `MovieDetailViewModel.kt:61` : `client.progress` précède la lecture de fiche potentiellement fraîche du dépôt (66), puis `client.list` (73) précède sa publication finale (74). Une fiche connue reste en chargement lorsque le serveur est suspendu, même si le fournisseur/cache est disponible. Publier le contenu local sans attendre ces RPC, tout en gardant les contrôles de masque.

## Important I2 — Le conflit de catégorie masquée reste visible à l'accueil

`apps/tv/src/main/kotlin/home/iptv/tv/movies/MovieHomeViewModel.kt:132` et ligne 133 consultent `cachedCategory` uniquement si la catégorie persistée est vide. Exemple : progression/Ma liste référence M dans A autorisée, cache connaît M dans B masquée, M n'est pas dans les 20 récents autorisés. `buildMovieHome` reçoit A sans connaître B et affiche M dans Reprendre/Continuer/Ma liste. Le contrôle renforcé de fiche/lecture refuse ensuite M, mais la règle « masqué gagne dans toutes les rangées » est déjà violée. Revalider l'union des catégories connues pour chaque référence, même non vide, avant de construire les cartes.

## Important I3 — Retour accueil : les changements locaux attendent le serveur

`apps/tv/src/main/kotlin/home/iptv/tv/movies/MovieHomeViewModel.kt:64` lance `reloadNow()` avant toute reconstruction ; les lignes 117–119 n'actualisent l'écran qu'après les RPC. Le ViewModel conserve sa copie `progress/list` initiale et ne relit pas `MovieHomeState`. Après une lecture, ou après un RPC manuel réussi sur la fiche puis perte réseau, Retour peut montrer l'ancienne position, un film maintenant vu dans Continuer, ou l'ancienne Ma liste tant que le serveur est suspendu. La fiche écrit pourtant la copie durable (`MovieDetailViewModel.kt:84`, `:97`). Relire/fusionner immédiatement état durable et file avant la synchronisation, puis réconcilier la réponse serveur. Tester notamment marquage réussi puis serveur suspendu au Retour.

## Important I4 — Le statut vu connu sur la fiche peut disparaître au retour de lecture

`apps/tv/src/main/kotlin/home/iptv/tv/movies/MovieDetailViewModel.kt:54` remplace `row` depuis copie durable + file. Or la progression distante lue ligne 62 n'est jamais persistée dans `saved`. Scénario : film déjà vu absent du cache de continuation, fiche apprend `watched=true` par `client.progress`, Recommencer, position locale en attente à 60 s. `playbackClosed()` ligne 76 conserve d'abord vu avec sa fusion, puis `reload()` remet immédiatement `row` à une entrée de file `watched=false` (54). Si le prochain RPC est suspendu/en panne, le statut vu est perdu localement. Le test actuel injecte déjà vu dans `saved`, ce qui ne couvre pas ce parcours réel. Préserver le statut connu lors du rechargement et/ou persister l'état dédié de fiche, sans empêcher Marquer non vu explicite.

## Points solides et limites

La requête de héros supplémentaire reste sur le cache, exclut les catégories masquées en SQL et utilise LIMIT 1 ; les récents restent paginés/bornés et sans dates inventées. La synchronisation des actions manuelles avec `ProgressQueue.withPausedFlush` réutilise le mutex d'envoi ; la suppression branchée dans MainActivity cible profil + movie + identifiant après réussite RPC. Le coordinateur ferme le lecteur, conserve le profil original, invalide les chargements annulés et ne lance aucun film suivant. Les composants de pistes et préférences existants sont réutilisés. Le repli Retour est explicite.

Preuves rapportées, non réexécutées : 264 tests shared et 284 tests TV sans Android verts, compilation de tous les tests Kotlin et APK debug construit. Les 5 MovieDpadTest sont compilés **non exécutés** ; la suite officielle est bloquée avant exécution par HTTP403 Robolectric. Cela ne prouve ni succès ni échec du focus réel. Aucun contournement requis. Restent non vérifiés : navigation D-pad réelle, rendu, Media3/MKV, pistes, pause/veille et enchaînements rapides sur TCL. La revue statique restante doit reprendre depuis ces constats, puis les corrections doivent être relues avant clôture.
