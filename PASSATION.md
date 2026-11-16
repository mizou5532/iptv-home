# PASSATION — iptv-home

> **À lire en premier par toute IA ou personne qui reprend le projet.**
> Ce fichier dit ce qui existe, où on en est, ce qui reste, comment travailler et ce qu'il ne faut jamais faire.
> Il est tenu à jour **à chaque étape franchie** (voir « Règle de mise à jour » en bas).

*Dernière mise à jour : 16 nov. 2026. **4B films terminée** (0.5.0 en ligne, essai télé réussi, fusionnée dans main). **Étape 5 (recherche) : conception validée, plan écrit, AUCUN CODE ÉCRIT.** Le propriétaire la fera réaliser par une autre IA. Commencer par le bloc « ÉTAPE 5 » ci-dessous.*

> **Note du 15 nov. 2026 (Cosmo, assistant personnel du propriétaire — pas l'agent de développement habituel) :** correction « confirmation intégrée à la page au lieu de confirm() native » poussée sur `main` (commit `9198d98`), tests web 124/124 verts, **déployée le 15 nov. au soir par remplacement de contenu dans Supabase et sélection des 66 catégories du profil « Tout le monde » appliquée et vérifiée (66 affichées sur 916)** — la page ne se déploie pas depuis GitHub, voir le rapport pour la méthode et le piège de cache rencontré. Détail complet, fichiers touchés et confirm() restantes : [`docs/rapports/correction-confirmation-integree-2026-11-15.md`](docs/rapports/correction-confirmation-integree-2026-11-15.md).

---

## 0. Reprise immédiate (à lire avant tout le reste)

**ÉTAPE 5 — RECHERCHE : À RÉALISER (16 nov. 2026). CE BLOC FAIT FOI ; LES BLOCS 4B CI-DESSOUS SONT DE L'HISTORIQUE.**

- **Décisions du propriétaire** : la recherche trouve dans toutes les catégories gardées, jamais dans les masquées ; elle donne les résultats **de la section où l'on se trouve** (Direct → chaînes, Séries → séries, Films → films). **Choix A** : OK sur une chaîne trouvée ferme la recherche et ramène dans la liste du Direct, sur la chaîne, avec l'aperçu. Il demande de **ne pas redemander la validation** de la conception ni du plan.
- **Conception validée** : `docs/superpowers/specs/2026-11-16-etape5-recherche-design.md`.
- **Plan en 6 tâches avec tests** : `docs/superpowers/plans/2026-11-16-plan-5-recherche.md` (moteur, sources filtrées, remplissage en arrière-plan, modèle d'écran, écran + voix + Direct, validation et 0.6.0). Lire ses « Global Constraints » et « Review Focus ».
- **État** : rien de commencé côté code. Créer la branche `plan-5-recherche` depuis `main` et suivre le plan tâche par tâche (tests d'abord, relecture indépendante, commit + push après chaque tâche, journal `docs/rapports/journaux/plan-5-journal.md` à créer).
- **Machine** : sur une machine neuve, `tools/android/installer-sdk.sh` installe le SDK ; Gradle depuis `apps` avec `--max-workers=2` ; si Maven Central répond 429, relancer la même commande. Postgres 16 requis pour `npm test -w backend`.
- **Mise en ligne** : 0.6.0 / code 7, APK + `version.json` seulement (pas de migration), **avec l'accord du propriétaire**, puis essai TCL, puis fusion dans `main` (accord permanent après essai réussi).
- **En production aujourd'hui** : télé 0.5.0, migrations jusqu'à `movies` (0012), 0010 non appliquée, 0 règle sur `storage.objects`.
- **À essayer encore par le propriétaire (4B)** : Reprendre/Recommencer, Ma liste, Marquer vu/non vu, audio et sous-titres, masquer une catégorie de films depuis l'iPhone.


**REPRISE DU 16 NOV. 2026 PAR CLAUDE — CE BLOC FAIT FOI SUR TOUS LES BLOCS SUIVANTS (qui sont de l'historique).**

- **Branche** : `claude/peaceful-davinci-pi93u7` = `plan-4-films` (jusqu'à `4f16c20`) fusionnée avec `main` (`755d997`, correction « confirmation intégrée » de la page). Le conflit de `web/config/src/org/view.ts` est résolu en gardant les deux : confirmation intégrée + RPC par type (`orgKindHideAllExcept`). Les deux tests Films de confirmation suivent la confirmation intégrée. `plan-4-films` n'est plus la branche à jour.
- **Machine actuelle : `/home/user/iptv-home`** (pas `/workspace`). `tools/android/installer-sdk.sh` installe le SDK dans `/root/android-sdk`. Gradle : `cd apps && ./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest :tv:assembleDebug`. Maven Central renvoie parfois 429 au premier essai : **relancer la même commande** (les fichiers déjà téléchargés restent en cache ; 2 essais ont suffi). Robolectric se télécharge sans blocage ici. Postgres 16 présent : `npm test -w backend` fonctionne.
- **Tâche 3 corrigée** (commit `38ccb37`) : I1 accueil et fiche n'attendent plus la synchro ; I2 une référence dont la catégorie du cache est masquée est écartée de l'accueil ; I3 le Retour à l'accueil relit tout de suite la copie durable et la file, et une ancienne réponse du serveur n'écrase plus rien ; I4 le statut vu appris du serveur est gardé en mémoire et en copie durable. 7 tests rouges puis verts.
- **Tests réellement exécutés ici** : 264 partagés, **318 télé (suite officielle complète, dont les 5 `MovieDpadTest`, 8 Live, 3 Séries, 1 barre)**, 172 backend, 144 web, build web et APK debug réussis. Les harnais « sans Android » de `docs/rapports/4b/` ne servent plus sur cette machine.
- **Relecture indépendante faite** (rapport `docs/rapports/4b/tache-5-validation.md`) : I1–I4 confirmés résolus, aucun défaut critique ; N1 (réponse Ma liste tardive qui annulait un ajout) et les mineurs M2, M3 (fiche), M5, M6 **corrigés** avec tests ; M1, M4 et le focus de l'accueil (M3) reportés/à contrôler sur la télé.
- **Tests exécutés après corrections** : 264 partagés, **320 télé** (suite officielle complète, tests de télécommande compris), 172 backend, 144 web, build web réussi.
- **Version 0.5.0 / code 6 prête** : `:tv:assembleRelease` réussi, certificat vérifié (SHA-256 attendu, section 6). L'APK n'est pas dans le dépôt : le reconstruire dans une nouvelle session.
- **0.5.0 MISE EN LIGNE le 16 nov. 2026 avec l'accord du propriétaire** (« Oui, mets en ligne ») :
  - migration `movies` (0012) appliquée en production par `apply_migration` ; les 15 fonctions comparées une à une au fichier du dépôt (empreintes identiques), `anon` ne peut en exécuter aucune, RLS active sur `movie_catalog_category`, advisors sécurité sans nouveauté de niveau ERROR. 0010 toujours non appliquée ;
  - page iPhone : `assets/app-XEFLPVLI.js`, `app-SAQZZUTD.css`, `demo-OVLARHBR.js` envoyés dans le compartiment `web` (vérifiés identiques), `index.html` + `version.json` 0.5.0 + `vercel.json` déployés sur Vercel (`dpl_HTFiKJKenhJDHySTo6xygqZGeevy`). Essai `?demo` sur iPhone simulé : onglet Films visible, 0 erreur. **Les noms de fichiers sont de nouveau synchronisés avec leur contenu** (le remplacement de contenu fait par Cosmo n'a plus cours ; les anciens `app-ALUE2QLD.js` / `app-WPUQFUB4.css` restent dans le compartiment, inutilisés) ;
  - APK 0.5.0 dans le compartiment `apk`, vérifié identique au fichier signé, aussi par `https://iptv-home-config.vercel.app/iptv-home.apk` (SHA-256 `dfcd88db…fb810`) ;
  - 4 règles temporaires `deploy_temp_*` créées puis supprimées : **0 règle sur `storage.objects`** (vérifié).
- **Essai TCL 0.5.0 du 16 nov. : réussi sur ce qui a été essayé.** Le propriétaire a vu l'onglet Films, les catégories, les films, une fiche, et a lancé une lecture : « ça semblait bien fonctionner ». **Pas encore essayés par lui** : Reprendre/Recommencer, Ma liste, Marquer vu/non vu, pistes audio/sous-titres, masquage d'une catégorie depuis l'iPhone, focus de l'accueil après « Chargement… » (M3). À lui redemander à l'occasion ; aucun problème signalé.
- **Fusion dans `main` faite** (accord permanent après essai réussi) : `main` = `claude/peaceful-davinci-pi93u7`. Étape 4B terminée.
- **PROCHAINE ACTION : attendre la consigne du propriétaire** pour la suite (étape 5 Recherche, d'après la feuille de route). Rien de commencé.
- **Retour arrière possible** : la migration est additive et la 0.4.1 reste compatible ; l'APK 0.4.1 se reconstruit depuis `main`.

**PAUSE EXPLICITE DEMANDÉE PAR LE PROPRIÉTAIRE — CHECKPOINT DE REPRISE PRIORITAIRE.** Il a demandé de tout sauvegarder/pousser, mettre la passation à jour et **s'arrêter ici** à cause du quota. Aucune correction supplémentaire à lancer dans cette pause. Branche `plan-4-films`.

- **Code produit dernier checkpoint : `476e5ff`**. Accueil, fiche, lecture et onglet Films intégrés ; progression typée, cache et backend en place ; organisation iPhone faite. Le code compile et l'APK **debug** est construit. Version toujours **0.4.1 / code5** : ne pas annoncer une 0.5.0 livrée.
- **Tests effectivement verts** :264 shared,284 logique TV (harnais sans Android),172 backend,144 web,78 test-lecture,24 source-sport ; build web et APK debug réussis. Les **5 tests Dpad Films sont seulement compilés, pas exécutés**. Suite TV officielle bloquée avant exécution par image Robolectric redirigée vers `gcp.mirrors.maven.org` puis HTTP403. Les succès de la machine Claude `/home/user` ne s'appliquent pas à cette machine `/workspace`.
- **Tâches1,2,4 terminées et relues. Tâche3 implémentée mais PAS approuvée** : la relecture vient de trouver des défauts importants, à corriger en premier à la prochaine reprise. Rapport `docs/rapports/4b/tache-3-relecture.md` (relecture interrompue sur demande de pause, lire ses limites).
- **Quatre points signalés (non corrigés), suivre les IDs du rapport** : **I1** catégories accueil et fiche locale toujours retardées par les RPC progression/liste (séparer publication locale de la synchronisation) ; **I2** accueil ne confronte la catégorie persistée non vide à `catalog.cachedCategory`, donc une catégorie contradictoire connue masquée peut rester visible dans Reprendre/Ma liste ; **I3** retour accueil ne relit/publie pas immédiatement les mutations de l'état durable et la file avant attente serveur ; **I4** le statut vu appris par le RPC de fiche n'est pas conservé dans l'état durable : après Recommencer, reload peut le perdre face à une position locale en attente, alors que le test actuel préremplit déjà le cache vu. Tests rouges/verts à écrire avec RPC suspendus, vrai parcours fiche et catégories contradictoires ; relecture du correctif ensuite.
- **À faire ensuite** : finir revue/corrections tâche3 ; résoudre l'accès Robolectric et exécuter toute la suite Android et Dpad ; revue globale de branche ; tâche5 version0.5.0/code6, APK release signé avec clé existante/certificat attendu vérifié, rapport final ; demander accord distinct pour migration0012 + page + APK. **Aucune publication, aucun changement de version, aucune migration production, aucune fusion de cette reprise dans main.** Main contient seulement les checkpoints précédemment validés/autorisés.
- **Pour repartir** : lire `docs/rapports/4b/prompt-reprise.md`, rapports tâches2/3 et leurs relectures, plan et conception déjà approuvés. `source /workspace/iptv-env/activate.sh`, Gradle depuis apps, un seul à la fois. Harnais reproductibles suivis dans docs/rapports/4b. Logs détaillés locaux dans `/workspace/iptv-work/4b/`; données importantes et résultats copiés dans rapports Git.
- **Préservation** : ancien stash `reprise-4b-local-avant-synchronisation` et archives recovery conservés ; ne pas réappliquer tout le stash sur le catalogue récent. Le travail actuel est committé/poussé, vérifier `git status` et l'historique avant toute nouvelle modification.


**ÉTAT COURANT APRÈS REPRISE CODEX — fait foi sur le checkpoint Claude historique ci-dessous.** Branche `plan-4-films`, derniers commits produit `8c1a005` (progression partagée), `943f550` (isolation séries TV), `6863bf8` (protection fiche cache). Tous poussés et vérifiés sur le distant.

| Tâche | État courant |
|---|---|
| 1 Backend | Terminée et relue ; 172 tests backend validés précédemment, migration0012 locale seulement. |
| 2 Catalogue/progression Kotlin | **Implémentée et relue sans réserve** après correction I1 ; 262 tests shared verts, 267 tests de logique TV verts via harnais sans Android, APK debug construit. Suite Robolectric officielle encore bloquée403 : elle n'est pas revendiquée verte. Rapport `docs/rapports/4b/tache-2-kotlin.md`, revue `tache-2-relecture.md`. |
| 3 Écrans Films télé | **En cours** : accueil/état local implémentés au checkpoint `5d7c687` (272 tests de logique TV verts, dont5 Films). Fiche/actions implémentées au checkpoint `9ccb4f8` :263 shared et274 tests de logique TV verts. Lecture implémentée au checkpoint `68f99f0` :276 tests de logique TV verts. Écrans et intégration implémentés au checkpoint `c2c206e` : compilation du produit et de tous les tests Kotlin,276 tests de logique TV et assembleDebug réussis. Quatre tests Dpad compilés mais non exécutés (Robolectric403). Dernier checkpoint `35b9b85` :263 shared/282 logique TV verts, APKdebug compilé. Les3 points de l’auto-revue sont corrigés (`476e5ff`) : données locales immédiates, héros local sans date, repli Retour robuste.264 shared et284 tests de logique TV verts,5 Dpad compilés mais non exécutés. Relecture indépendante tâche3 en cours ; ne pas la déclarer approuvée avant son rapport. Rapport vivant `docs/rapports/4b/tache-3-tele.md`. Le parcours Films n’est pas encore intégré à l’application. |
| 4 iPhone | Implémentée, relue et corrigée (ordre démo par profil, commit37d1e3f) ;144 tests web verts, build OK. |
| 5 Validation/livraison | Pas commencée : revue globale, suite Android complète, version0.5.0/code6 et APK signé restent à faire avant demande distincte de déploiement. |

**Environnement réel ici : /workspace.** `source /workspace/iptv-env/activate.sh` puis Gradle depuis apps. Gcp.mirrors.maven.org reste refusé HTTP403 dans cette machine ; le succès sur /home/user de Claude n'est pas transposable. Ne pas lancer deux Gradle simultanés. Tests UI à écrire/compiler, mais pas à annoncer exécutés sans image Robolectric. Harnais de logique reproductible conservé dans `docs/rapports/4b/harnais-tests-sans-android.gradle` (chemin de cache /workspace à adapter ailleurs) ; ses limites et les6 erreurs Stub! de l'essai initial sont dans le rapport tâche2.

**Reprise sûre** : vieux travail local archivé/stash `reprise-4b-local-avant-synchronisation`, ne pas réappliquer tout le stash car le catalogue distant est plus récent. Conception/plan autorisés, ne pas redemander validation. Ancien état rouge256/8 désormais résolu. Après chaque checkpoint, tenir ce bloc, journal, rapport et plan à jour puis commit/push. Aucun déploiement, migration production ni republication cloud à cette reprise.

**ÉTAT EXACT AU 16 NOVEMBRE 2026, 01:10 UTC (écrit par Claude avant de rendre la main ; fait foi sur le bloc suivant).** Branche `plan-4-films`.

| Tâche du plan 4B | État |
|---|---|
| 1 Backend (migration 0012, locale) | ✅ faite et relue (`7d2791f`), 172 tests backend |
| 2 Catalogue et progression Kotlin | 🟡 **à moitié faite** (détail ci-dessous) |
| 3 Écrans Films sur la télé | ❌ pas commencée |
| 4 Organisation Films sur l'iPhone | ✅ faite, vérifiée par Claude, **fusionnée dans `plan-4-films`** : 143 tests web, build OK, rapport `docs/rapports/4b/tache-4-iphone.md`. Reste : relecture indépendante (facultative, diff petit). |
| 5 Validation, 0.5.0, accord de mise en ligne | ❌ pas commencée |

**Tâche 2, précisément :**
- ✅ `0d57f54` : client Xtream des films (`movieCategories`, `movies(categoryId)`, `movieInfo`), avec tests.
- ✅ `efaec8f` : cache SQLDelight des films (`Movies.sq`, migration `2.sqm`, schéma 2 → 3) et `MovieRepository`, avec tests.
- 🟡 Dernier commit `wip(4b): progression typée film/épisode EN COURS` : l'agent a été coupé par la limite **au milieu**. Il venait d'écrire les **tests rouges** (`ProgressClientTest`, `ProgressQueueCodecTest`, `ProgressQueueTest`) et de commencer `ProgressClient.kt`. **Ce commit n'est pas vérifié : il peut ne pas compiler et ses tests sont censés échouer** tant que l'implémentation manque.
- ❌ Reste dans la tâche 2 : `ProgressEntry.itemType` (défaut `"episode"`) + `movieName/movieCover/movieCategory` ; identité de file profil + type + id (remplacer, lire, envoyer, supprimer) ; anciens JSON sans type lus comme épisodes ; `SupabaseProgressClient.record` qui route `movie` vers `record_movie_progress` ; `progress/MovieClient.kt` + `SupabaseMovieClient` ; `ProfilePrefs.movieOrder/movieList` ; dans `apps/tv/MainActivity.kt`, filtre `itemType == "episode"` sur `dropQueued` des séries (avec test) ; rapport `docs/rapports/4b/tache-2-kotlin.md` ; cases du plan ; relecture indépendante.
- Références avant la tâche 2 : 193 tests partagés, 285 tests télé, tous verts.

**Environnement de Claude (différent de `/workspace/...`)** : dépôt dans `/home/user/iptv-home` ; `cd apps && ./gradlew --max-workers=1 :shared:jvmTest :tv:testDebugUnitTest :tv:assembleDebug -q` fonctionne, Robolectric compris. Maven Central répond parfois 429 : réessayer, au besoin une fois avec `--refresh-dependencies`. `apksigner` : `/root/android-sdk/build-tools/36.0.0/apksigner`.

**Rappels de livraison (appris en 4A)** : tout nouvel écran a un test Robolectric qui imite la télécommande ; poser `Modifier.backKey` sur le fond focalisable d'un lecteur ; ne jamais mettre `focusProperties { canFocus }` sur un conteneur (utiliser `focusBlockedWhen`) ; la page iPhone avec « Films » ne se publie qu'en même temps que la migration 0012 (sinon erreur en choisissant Films) ; appliquer 0012 avec `apply_migration`, jamais `db push` (0010 reste locale) ; les demandes d'autorisation Supabase expirent si le propriétaire n'est pas devant son ordinateur ; après un envoi de fichier, supprimer les règles temporaires et vérifier qu'il en reste 0.

**Reprise actuelle — 4B Films (15 novembre 2026).** Le propriétaire a autorisé toute la réalisation (« Oui, tu peux y aller »), et demande des traces exhaustives pour une autre IA ainsi qu'une sauvegarde sur `main`. **Ne pas redemander la validation de conception/plan.** Branche de réalisation `plan-4-films`.

- **Choix validé** : même fonctionnement que Séries ; ajouts récents limités aux catégories déjà chargées. Accueil, fiche, lecture/reprise, Ma liste et catégories sur iPhone. Conception : `docs/superpowers/specs/2026-11-15-etape4b-films-design.md`. Plan à cinq tâches : `docs/superpowers/plans/2026-11-15-plan-4b-films.md`.
- **Fait et testé** : tâche 1 backend implémentée (`7d2791f`), migration additive `20261115000012_movies.sql`, 5 nouveaux tests d'intégration, **172 tests backend verts** (12 fichiers), anciennes séries incluses. Contrat RPC dans `backend/README.md`. Rapport durable : `docs/rapports/4b/tache-1-backend.md`. Relecture indépendante **approuvée**, rapport `docs/rapports/4b/tache-1-relecture.md`.
- **En cours** : build Android de référence terminé en échec réseau : 193 tests shared verts, tests TV non exécutés. Le téléchargement Robolectric depuis repo1.maven.org redirige vers gcp.mirrors.maven.org, refusé HTTP403. Journal `/workspace/iptv-env/android-baseline.log`. SDK/JDK prêts ; tâche2 autorisée à utiliser Gradle pour les tests shared. Ne pas lancer un deuxième Gradle simultanément. Politique réseau restricted/enforced confirmée ; demande au propriétaire pour ajouter `gcp.mirrors.maven.org` envoyée, réponse non encore reçue.
- **À faire dans l'ordre** : terminer relecture tâche 1 et corriger si nécessaire ; tâche 2 **en cours** catalogue films/cache/progression typée Kotlin ; tâche 3 parcours télé complet + tests télécommande ; tâche 4 organisation iPhone ; tâche 5 revue globale, suites complètes, APK signé 0.5.0/code6 proposé et certificat vérifié, puis accord distinct de déploiement. Aucun écran Films n'existe encore.
- **Piège d'intégration trouvé** : `MainActivity.kt`/seriesDetail/dropQueued filtre actuellement profil+itemId seulement. Ajouter `entry.itemType == "episode"` en tâche 2, sinon une action épisode peut effacer une progression film de même id. Les regroupements et anciennes files doivent distinguer profil+type+id ; ancien JSON sans type = épisode.
- **Outils de cette machine** : `source /workspace/iptv-env/activate.sh` (JDK21 complet, SDK36, caches Gradle). Hors dépôt : dépend de la machine, voir `docs/rapports/4b/reprise.md` pour provenance/commandes. Les anciennes instructions `/workspace/iptv-env` de B2 ne sont pas la preuve que ces outils existaient dans cette nouvelle machine.
- **Checkpoint distant confirmé** : `main` contient backend + rapports + conception/plan via fusion `7b0eb49`. Branche `plan-4-films` remise au même commit pour continuer. Autorisation de sauvegarde sur main reçue explicitement ; la publication reste distincte.
- **Sauvegarde** : commit et push après chaque sous-étape ; mettre à jour ce bloc, le journal et le plan avant fin de quota. Le propriétaire demande aussi `main` : intégrer uniquement les checkpoints vérifiés, et signaler le travail restant. Demande distincte de toute publication.
- **Aucune production touchée** : migration 0012 locale seulement, 0010 non déployée, aucun APK/page publié, aucun environnement cloud republié. Ne jamais tester contre Supabase production ni lire service_role. La 4A est terminée en 0.4.1 ; les instructions 4A ci-dessous sont historiques.

**Situation (15 nov. 2026, soir).** La 0.4.0 (séries) est en ligne : migration 0011 en production, page iPhone et APK publiés, règles temporaires de stockage supprimées (0 règle sur `storage.objects`). **L'essai sur la TCL a échoué.** Le propriétaire n'aime pas ce qu'il voit. Rapport complet, avec ses essais ciblés : `docs/rapports/tele-4a-series.md`.

**Problèmes signalés (n° utilisés partout) :**
1. Direct, télécommande : passer des catégories aux chaînes demande OK ; gauche/droite font « disparaître » le focus.
2. Plein écran du direct **noir** (le son joue). L'image n'apparaît qu'après OK (commandes affichées) ou un changement de chaîne (haut/bas). Attendre 15 s ne suffit pas.
3. OK sur un épisode : bref écran noir, puis retour immédiat à la fiche, rien ne joue. OK maintenu (marquer vu) fonctionne.
4. D'autres petits problèmes non détaillés (à demander au propriétaire).
5. Guide « Programme indisponible » partout, même sur les grandes chaînes.
6. « Mise à jour » reste affiché en permanence, même après un changement de profil.
7. Séries : seulement 2 rangées d'environ 10 séries (catégories en arabe).
8. Direct à l'ouverture : parfois rien (Favoris vide) ; il faut ~5 s ou bouger la télécommande.
9. Lenteur au passage sur chaque catégorie. Le propriétaire veut rapide et fluide.

**Cause de fond :** aucune interface n'avait de test qui imite la télécommande. Les tests ne couvraient que la logique, et il n'y a ni télé ni émulateur ici (pas de `/dev/kvm`).

**Hypothèses de Claude (à confirmer) :**
- **(2)** Une SurfaceView ajoutée dans un AndroidView n'est pas redessinée quand le lecteur passe de l'aperçu au plein écran. Elle s'affiche dès que Compose dessine par-dessus. Correction prévue : TextureView (PlayerView en `surface_type=texture_view`) ou une surface unique.
- **(3)** `EpisodePlayerScreen` lit un `state` périmé (`Idle`, via `collectAsState`) juste après `begin()`, et `LaunchedEffect(state, begun)` appelle alors `onClose()`.

**État du travail (15 nov. 2026, tard — quota de Claude épuisé, passation à l'autre IA) :**

| Agent | Problèmes | Branche | État |
|---|---|---|---|
| A | 1, 2, 3, 8, 9, plus des tests d'interface et un diagnostic | `plan-4-series` | ✅ **Terminé** (`3178869`), rapport `docs/rapports/tele-4a-enquete.md`. 1, 3, 8 et 9 ont leur cause confirmée par des tests et sont corrigés. 2 (écran noir) est corrigé par TextureView mais **non vérifié** sans la télé. A a aussi corrigé un piège de focus Direct↔Séries, un titre de guide en Latin-1 qui vidait le guide (`e080595`), et une carte « Chargement… » de séries désormais focalisable, sans plafond de 20 par rangée (`87e14a4`). |
| B | 5 (guide), 6 (« Mise à jour ») | `fix-epg-refresh` | ✅ **Terminé et fusionné** dans `plan-4-series` (`4a292bf`). Rapport : `docs/rapports/tele-4a-guide-maj.md`. (6) causes confirmées et corrigées ; (5) formats et secours ajoutés, cause exacte à confirmer sur la télé (calque de diagnostic, ligne « Guide »). |
| C | 7 (rangées de séries) | `fix-series-rows` | ✅ **Terminé et fusionné** dans `plan-4-series` (`e3b3e78`). Rapport : `docs/rapports/tele-4a-series-rangees.md`. Même cause de fond que A (une rangée non chargée n'avait rien de focalisable). En plus : chargement anticipé de 2 rangées sous l'écran, requêtes en vol gardées, nouvel essai d'une rangée en échec, mémoire bornée, lecture Xtream tolérante, « Mise à jour » de l'onglet Séries corrigé. |

On a demandé à B et C de se rebaser sur `origin/plan-4-series` (après les commits de A) avant de finir ; c'est peut-être fait, peut-être pas.

**Tests à la fin de A :** 174 partagés, 258 télé (dont 13 tests d'écran Robolectric), 167 backend, 124 web, tous verts.
**Attention Gradle :** Maven Central renvoyait 404/429. L'agent A a utilisé un script d'initialisation Gradle **hors dépôt** qui met le miroir Google de Maven Central en premier. Exemple : fichier `~/.gradle/init.d/miroir.gradle` contenant `allprojects { repositories { maven { url "https://maven-central.storage-download.googleapis.com/maven2/" } } }` et, pour les plugins, `settingsEvaluated { it.pluginManagement.repositories { maven { url "https://maven-central.storage-download.googleapis.com/maven2/" }; gradlePluginPortal(); google() } }`. Ne pas le committer.

**Écran de diagnostic (ajouté par A) :** appuyer 3 fois en 2 s sur Info, Menu ou 0 ; ou haut haut bas bas gauche droite gauche droite en 6 s. Il affiche l'état du lecteur, si une vue est attachée, si la première image est rendue, la taille de la vidéo et le dernier code d'erreur. Jamais d'adresse ni d'identifiant.

**Mise à jour (15 nov., encore plus tard) :** A, B, C fusionnés ; relecture indépendante faite (`docs/rapports/4a1-relecture.md`) ; **ses 5 points importants I1–I5 sont corrigés par Claude**, chacun avec un test rouge puis vert (détail en bas de ce rapport). Un piège de plus trouvé et corrigé : dans les lecteurs, **Retour était avalé** et il fallait appuyer deux fois (`Modifier.backKey`). Suites complètes : **193 partagés, 285 télé, 167 backend, 124 web, 0 échec**. **0.4.1 (versionCode 5) construite et signée, certificat vérifié** (`apps/tv/build/outputs/apk/release/tv-release.apk`, à reconstruire avec `:tv:assembleRelease` dans une nouvelle session : le fichier n'est pas dans le dépôt). **0.4.1 PUBLIÉE** avec l'accord du propriétaire : APK dans le compartiment `apk` (vérifié identique au fichier signé, aussi par le lien `https://iptv-home-config.vercel.app/iptv-home.apk`) et `version.json` 0.4.1 sur Vercel. Règles temporaires supprimées et vérifiées (0 règle sur `storage.objects`). Les étapes 1 à 6 sont faites ; reste 7 (essai télé) et 8.

**Si tu reprends (autre IA), dans l'ordre :**
1. `git fetch origin`. Comparer `git log origin/plan-4-series..origin/fix-epg-refresh` et `..origin/fix-series-rows`. Lire les rapports de B et C s'ils existent. S'ils sont absents, reprendre : (5) guide « Programme indisponible » : suivre l'appel EPG Xtream, le stream_id passé, le décodage base64, les horodatages et le fuseau, et les exceptions avalées ; (6) vérifier que `d75accc` suffit, avec un test qui reproduit « refreshing » bloqué ; (7) vérifier que toutes les catégories de séries non masquées s'affichent en rangées et se chargent en descendant.
2. Pour chaque problème pas encore résolu : trouver la cause, écrire un test rouge, corriger, test vert. Pas de correctif à l'aveugle.
3. Fusionner `fix-epg-refresh` et `fix-series-rows` dans `plan-4-series` (résoudre les conflits sans écraser), puis relancer **toutes** les suites :
   - `cd apps && ./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest :tv:assembleDebug --rerun-tasks -q`
   - `npm test -w backend`
   - `npm test -w web/config`
4. Faire une relecture indépendante de l'ensemble des correctifs.
5. Version **0.4.1** (versionCode 5), `version.json`, `:tv:assembleRelease`, vérifier le certificat (SHA-256 en section 6).
6. **Demander l'accord du propriétaire**, puis publier l'APK seul (procédure `docs/rapports/deploiement-page.md` : règle d'écriture temporaire sur le compartiment `apk`, envoi, **suppression de la règle**, vérifier qu'il reste 0 règle). Pas de nouvelle migration prévue.
7. Le propriétaire essaie avec le lien `https://iptv-home-config.vercel.app/iptv-home.apk` (application Downloader sur la télé). Lui expliquer comment ouvrir l'écran de diagnostic s'il existe.
8. Ne fusionner dans main qu'après un essai réussi et son accord.

**Leçons à garder :** chaque écran doit avoir un test qui imite la télécommande. Les demandes d'autorisation de Supabase expirent si le propriétaire n'est pas devant : le prévenir avant. Pour Vercel, déployer les fichiers avec `projectSettings` `{framework:null, buildCommand:"", installCommand:"", outputDirectory:"."}`. Sinon le déploiement échoue sur `npm install`. Le `vercel.json` du dépôt contient désormais la réécriture `/assets/:file`.

---

## 1. Le projet en 30 secondes

Un **player IPTV familial**, fait maison, pour remplacer IPTV Player Pro sur la télé du salon :
rapide, fluide, bien organisé, personnalisable, **sans abonnement de player**, avec profils, favoris,
listes et progression **synchronisés** entre appareils.

- **Propriétaire** (il parle français, pas développeur) : sport en direct — Canadiens/LNH, FC Barcelone toutes compétitions, NFL, NBA en bonus.
- **Sa sœur** : séries (la progression est essentielle). **Son frère** : films.
- **Appareil principal** : télé **TCL QM85 65" (65QM851G), Google TV**. Plus tard : app **Windows/Mac**. **Pas d'app iPhone** (refus de l'abonnement Apple de 99 $/an). Les iPhone servent seulement à **configurer et organiser** via une page web.
- **Fournisseur IPTV** : API **Xtream Codes**, en **http seulement**, ~75 % de films en MKV, **une seule connexion à la fois** (`max_connections = 1`), refuse la vidéo aux adresses IP de serveurs (datacenter) mais accepte l'API.
- **Non-objectifs** : enregistrement, multi-écran, contrôle parental. Téléchargement hors ligne : à la toute fin.

Documents de référence :
- Conception générale : [`docs/superpowers/specs/2026-11-14-player-iptv-design.md`](docs/superpowers/specs/2026-11-14-player-iptv-design.md)
- Conception de B1 (organiser depuis le téléphone) : [`docs/superpowers/specs/2026-11-14-b1-organiser-depuis-le-telephone-design.md`](docs/superpowers/specs/2026-11-14-b1-organiser-depuis-le-telephone-design.md)
- Feuille de route (texte, source de vérité sur GitHub) : [`docs/superpowers/plans/2026-11-14-feuille-de-route.md`](docs/superpowers/plans/2026-11-14-feuille-de-route.md)
- Feuille de route interactive (privée, tenue par Claude, miroir de la précédente) : https://claude.ai/artifact/79SZ97WaDCLXmm1t4tVVNs

---

## 2. Règles absolues (ne jamais enfreindre)

1. **Aucun identifiant IPTV** (adresse du serveur, nom d'utilisateur, mot de passe) dans le dépôt, un commit, un journal, un rapport, une capture, un message d'erreur ou une conversation. Le propriétaire les saisit lui-même sur la page de configuration. Les adresses de flux contiennent ces identifiants : ne jamais les journaliser ni les afficher.
2. **Jamais la clé `service_role` de Supabase** (ni la lire, ni l'écrire, ni l'utiliser). La clé « publishable » est publique par conception.
3. **Ne jamais créer de foyer ni s'inscrire sur le vrai projet Supabase depuis des tests** : il n'accepte qu'un foyer, celui du propriétaire. Les tests backend tournent sur un Postgres local.
4. **Publier ou créer un service en ligne = accord explicite du propriétaire d'abord** (déploiement, nouveau projet, etc.). Appliquer une migration à la production : il l'approuve dans l'invite Supabase.
5. **Ne jamais régénérer la clé de signature** de l'app télé (`apps/tv/signature/`) : sinon la mise à jour refuse de s'installer par-dessus et il faut tout réappairer.
6. Ne pas mettre de nom, courriel ou détail personnel du propriétaire dans des requêtes vers des services externes.
7. Une catégorie masquée « pour tout le monde » ne doit **jamais être téléchargée** chez le fournisseur.
8. Textes visibles en **français**. Le propriétaire n'est pas développeur : lui parler simplement, sans jargon.

---

## 3. État actuel (où on en est)

| Étape | Statut | Détail |
|---|---|---|
| 1. Vérifications et fondations | ✅ terminé | Lecture navigateur impossible (http + MKV) ; serveurs refusés par le fournisseur → décision : app télé native + app ordinateur plus tard. Rapports : `docs/rapports/lecture-navigateur.md`, `source-sport.md`. |
| 2. Service de synchro (Supabase) | ✅ terminé, en production | Migrations 0001–0007. `docs/rapports/synchro-production.md`, `plan-2-decisions.md`. |
| 3A. App télé : connexion et socle | ✅ terminé, **installée sur la TCL (0.1.0)** | QR + code, « Qui regarde ? », chaînes en direct, lecture plein écran. Essai réussi : `docs/rapports/tele-premiere-version.md`. |
| 3B1. Organiser depuis le téléphone + nouvel écran du direct | ✅ terminé, **0.2.0 installée, essai réussi** | Fusionné dans `main`. Rapport : `docs/rapports/tele-b1-organiser.md`. |
| 3B2. Le direct complet | ✅ 0.3.0 en ligne, fusionné dans main | Guide, copies et aperçu livrés ; essai TCL à faire. « Prendre la main » reporté à l'étape 8. Migration 0010 locale non appliquée. |
| 4A. Séries | ✅ terminée | 0.4.1 publiée, essai télé réussi, fusionnée dans main. Guide reporté. |
| 4B. Films | ✅ terminée | 0.5.0 en ligne le 16 nov., essai télé réussi (accueil, catégories, fiche, lecture), fusionnée dans main. Quelques fonctions restent à essayer par le propriétaire, voir section 0. |

Conception : `docs/superpowers/specs/2026-11-15-etape4a-series-design.md`, section 9 comprise. Plan : `docs/superpowers/plans/2026-11-15-plan-4a-series.md`. Corrections prioritaires : `docs/rapports/4a-tache3-corrections-a-faire.md`. Aucun nouveau déploiement, aucune migration de production ni fusion autorisés.
| 4. Films et séries | à faire | ≈ 3–4 séances |
| 5. Recherche | conception et plan prêts, code à faire | ≈ 1 séance — voir section 0 |
| 6. Sport | à faire | ≈ 2 séances |
| 7. Thèmes (clair, fun) et tailles | à faire | ≈ 1–2 séances |
| 8. App ordinateur (Windows, Mac) | plus tard | ≈ 3–4 séances |
| 9. Plus tard | plus tard | **Bascule automatique vers une autre copie** (repoussée à la fin à la demande du propriétaire), téléchargement hors ligne |

Une « séance » ≈ une soirée de travail d'IA (3 à 5 h), sans compter les essais du propriétaire.

### Limite connue de l'accueil Séries

« Nouveaux épisodes dans tes séries » = séries dont le dernier épisode vu est terminé et dont la fiche connaît la suite (vérifié au plus une fois par jour, 20 séries) ; une série de Ma liste sans aucune progression n'y figure pas.

Limite connue : une série inconnue partout (aucune progression, jamais listée dans le cache, jamais vue) est demandée une fois au fournisseur, seule façon d'apprendre sa catégorie ; toute série dont une catégorie est connue et masquée n'est jamais demandée.

### Prochaines actions, dans l'ordre

1. Relecture de la tâche 6 (lecture, reprise, progression, épisode suivant, audio/sous-titres) ; essai sur la vraie télé : liste dans `docs/rapports/4a-tache6-lecture.md`.
2. Claude reprend les tâches 7–8.


---

## 4. Architecture et carte du dépôt

```
apps/                 Projet Gradle Kotlin Multiplatform (wrapper 8.14.3, Kotlin 2.3.21, AGP 8.13.2, JDK 21)
  shared/             Cœur partagé (jvm + android) : Xtream (XtreamApi, XtreamUrls), synchro Supabase
                      (sync/SyncClient.kt, supabase-kt 3.6.0), cache SQLDelight (catalog/), publication de la
                      copie du catalogue (CatalogPublisher), searchNorm (text/)
  tv/                 App Android TV (Compose for TV, Media3 ExoPlayer, Coil 3) : startup/ (QR, Qui regarde ?),
                      live/ (écran du direct, sections, LiveSessions, PrefsWatcher), player/, theme/ (jetons IptvTheme)
  tv/signature/       Clé de signature (PKCS12) + mot de passe (README.md) — VERSIONNÉE EXPRÈS, ne jamais régénérer
backend/              Supabase : migrations SQL (supabase/migrations/0001…0008), tests Vitest sur Postgres 16 local
                      (test/db.ts démarre un Postgres jetable), README.md = CONTRAT du backend (tables, fonctions, règles)
web/config/           Page de configuration + onglet « Organiser » (Vite + TypeScript, sans framework, Vitest) ;
                      ?demo = données inventées
tools/android/        installer-sdk.sh : installe le SDK Android dans $HOME/android-sdk
tools/web/            construire-page.sh : construit les fichiers de la page pour la mise en ligne
tools/test-lecture, tools/source-sport : outils du plan 1
docs/superpowers/specs   Conceptions validées par le propriétaire
docs/superpowers/plans   Feuille de route + plans détaillés par étape (tâches, tests, contraintes)
docs/rapports/           Rapports, décisions, procédures, journaux de construction
docs/installer-sur-la-tele.md   Guide d'installation et de mise à jour pour le propriétaire
```

Flux principal : la télé (à la maison) parle au fournisseur Xtream (seule à pouvoir le faire), garde le catalogue
en cache local (SQLDelight), en **publie une copie légère** (noms seulement) dans Supabase ; l'iPhone lit cette
copie et écrit les préférences via des fonctions `org_*` ; la télé relit `profile_prefs` quand `prefs_rev` change
(interrogé toutes les 4 s quand l'écran du direct est ouvert).

---

## 5. Services en ligne (production)

| Service | Détail |
|---|---|
| **GitHub** | Dépôt privé `mizou5532/iptv-home`. Branches : `main` (étapes 1, 2, 3A), `plan-3b1-organiser` (B1, en ligne mais pas fusionnée). |
| **Supabase** | Projet `gisjeuukbrgjgkrmlyvs` (région Canada, gratuit). URL `https://gisjeuukbrgjgkrmlyvs.supabase.co`. Clé publishable : voir `docs/rapports/synchro-production.md`. Migrations appliquées : foyer_profils, identifiants_xtream, appairage, preferences, progression, qui_regarde, correctifs, **organiser** (0008). Connexions anonymes activées (pour la télé). Site URL / Redirect URLs = la page Vercel. Compartiments publics : `apk` (l'APK) et `web` (fichiers JS/CSS de la page). **Aucune règle d'écriture** sur `storage.objects` (on en crée une temporaire pour chaque envoi, puis on la supprime). |
| **Vercel** | Équipe du propriétaire, projet `iptv-home-config`, adresse **https://iptv-home-config.vercel.app**. L'app Vercel n'a **pas** accès au dépôt GitHub : on déploie des fichiers. Procédure : `docs/rapports/deploiement-page.md`. Ancien projet de test `iptv-test-lecture` : le propriétaire doit le supprimer (lien déjà donné). |
| **Télé** | App `home.iptv.tv`, installée par **Downloader** depuis `iptv-home-config.vercel.app/iptv-home.apk`. Version en ligne : **0.5.0 (versionCode 6)**. |

Accès : ces comptes appartiennent au propriétaire. Une autre IA doit obtenir ses propres accès (connecteurs
Supabase/Vercel/GitHub) ou demander au propriétaire de faire les gestes en ligne.

---

## 6. Construire et tester

Environnement utilisé : Linux, Node 22, JDK 21, Postgres 16 (`/usr/lib/postgresql/16/bin`) pour les tests backend.

```bash
npm install                                   # à la racine (espaces de travail npm)
npm test -w backend                           # 113 tests, Postgres local jetable
npm test -w web/config && npm run build -w web/config   # 47 tests
tools/android/installer-sdk.sh                # une fois : SDK Android + apps/local.properties
cd apps && ./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest :tv:assembleRelease
```

- `--max-workers=2` : le réseau de l'environnement de Claude limitait les téléchargements parallèles (erreurs 429).
- Noms de tests Kotlin en ASCII (les accents faisaient planter).
- APK signé : `apps/tv/build/outputs/apk/release/tv-release.apk` ; vérifier avec `apksigner verify --print-certs`
  (SHA-256 du certificat attendu : `5d8877ce9c75eebe8d4a0cfb20427cf13dbf443402fc5c055608358d76e615d4`).
- **Publier une nouvelle version télé** : augmenter `versionCode`/`versionName` dans `apps/tv/build.gradle.kts`,
  construire, envoyer l'APK dans le compartiment `apk` (même nom), mettre à jour `web/config/public/version.json`,
  redéployer la page. Le propriétaire met à jour par Downloader (même adresse).

---

## 7. Méthode de travail utilisée

**Sauvegarde prioritaire (nouvelle instruction du propriétaire)** : après chaque sous-étape verte, correctif de relecture ou mise à jour de documents, faire un commit et un push sur la branche courante `plan-4-series`, au besoin avec un message `wip`. Jamais plus de 30 minutes sans push ; viser des points de sauvegarde plus courts. Avant une longue commande ou un sous-agent, pousser ce qui est fait. Toujours `git pull --rebase origin plan-4-series` avant chaque push, car Claude travaille aussi sur la branche ; conserver les changements locaux et résoudre les conflits sans écraser son travail. En cas de quota proche, pousser immédiatement et noter l'état dans le journal. Une tâche n'est pas terminée tant que sa sauvegarde distante et ses documents ne sont pas à jour.

- **Conception → plan → construction**. Pour chaque étape : conception écrite et validée par le propriétaire
  (`docs/superpowers/specs/`), plan détaillé en tâches avec tests (`docs/superpowers/plans/`), puis construction.
- Construction « pilotée par sous-agents » : une tâche à la fois, tests d'abord (TDD), **relecture indépendante
  après chaque tâche**, corrections, puis **relecture finale de toute la branche** avant de livrer.
- Une branche par étape (`plan-…`), fusion dans `main` avec l'accord du propriétaire.
- Commits en français, style conventionnel (`feat(tv): …`, `fix(web): …`).
- Les décisions prises en cours de route sont notées dans les journaux : `docs/rapports/journaux/`.
- Le propriétaire aime : voir l'avancement (feuille de route avec durées), des explications simples,
  qu'on avance sans lui quand c'est possible, et qu'on lui demande seulement ce qui exige sa présence.

---

## 8. Décisions importantes (résumé)

- Télé d'abord ; app ordinateur à l'étape 8 ; pas d'iPhone ni de site de lecture.
- Appairage : QR code + code à 6 caractères, configuration sur la page iPhone (option A).
- Thèmes : B1 en **sombre seulement**, construit sur des jetons (`IptvTheme`) ; clair et fun à l'étape 7.
- Bascule automatique vers une autre copie : **à la toute fin** (étape 9). En B2, copies regroupées avec choix manuel.
- Organisation : c'est le **compte du propriétaire** qui organise tous les profils (pas de comptes séparés pour l'instant).
- « Masqué gagne » ; ordre des catégories = profil, sinon « tout le monde », sinon fournisseur ; nouvelles catégories à la fin.
- Identité d'une chaîne = `stream_id` Xtream (risque si le fournisseur renumérote).
- Supabase coupe les réponses à 1 000 lignes : les grosses réponses sont renvoyées en un seul bloc jsonb/tableau.
- Clé de signature versionnée dans le dépôt privé (pour que les mises à jour s'installent par-dessus).
- Détail complet : `docs/rapports/plan-2-decisions.md` et les journaux `docs/rapports/journaux/`.

---

## 9. Points connus reportés (non bloquants)

Voir la fin des journaux `docs/rapports/journaux/plan-3a-journal.md` et `plan-3b1-journal.md` (lignes « minor » / « deferred »). Les principaux :
- Nombre de chaînes affiché seulement pour la section ouverte (écran télé).
- Pas de bouton « revenir à l'ordre de tout le monde » pour un profil qui a son propre ordre.
- `view.ts` de la page fait ~720 lignes (à découper avant de le modifier beaucoup).
- Une catégorie réaffichée « pour tout le monde » ne revient sur la télé qu'au démarrage suivant.
- `org_favorites` renvoie des lignes (limite 1 000 favoris).
- Recherche serveur sans index trigramme (à ajouter si le catalogue dépasse ~50 000 chaînes).

---

## 10. Règle de mise à jour (pour toute IA)

**Règle de sauvegarde fréquente (demande du propriétaire)** : il change souvent d'IA quand un quota se termine. Pousser sur GitHub après **chaque** sous-étape verte, correction ou décision (`git pull --rebase` avant), jamais plus de 30 minutes sans push ; noter dans le journal de l'étape ce qui est fait, en cours et la suite, pour qu'une autre IA reprenne sans rien perdre.


À **chaque** changement d'état (tâche commencée ou finie, mise en ligne, essai du propriétaire, décision) :
1. mettre à jour la section **3. État actuel** et la date en haut de ce fichier ;
2. mettre à jour `docs/superpowers/plans/2026-11-14-feuille-de-route.md` ;
3. consigner les décisions dans le journal ou le rapport de l'étape (`docs/rapports/`) ;
4. committer et pousser sur GitHub.

Si Claude reprend : la feuille de route interactive (lien en haut) doit refléter la même chose.

### Environnement cloud B2

Procédure de reprise sur la machine actuelle : [`docs/rapports/environnement-cloud.md`](docs/rapports/environnement-cloud.md). JDK21 complet et SDK36 prêts ; backend testé dans PostgreSQL16 conteneurisé (113 tests passants), sans accès à la production. Les helpers hors dépôt nécessitent l’instantané de cet environnement ; Git seul ne les restaure pas.

### Actualisation réseau (reprise)

Le propriétaire ne connaissait pas ce domaine ; explication donnée : serveur de téléchargement de l'Android simulé des tests, pas un service à intégrer à l'application. Ajout `gcp.mirrors.maven.org` préparé et sauvegardé dans le nouveau brouillon réseau, état backend saved/requires_publish=true. Aucune publication effectuée ; scripts, dépôts, secrets et autres champs non réappliqués. Le brouillon seul n'active pas le réseau. Attendre un changement réel et retester le téléchargement. L'instruction utilisateur de ne rien publier reste en vigueur.
