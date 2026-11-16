# Journal — Étape 4B Films

## 15 novembre 2026 — Conception

- Demande explicite de commencer 4B, après la 0.4.1 fusionnée (`e07d72b`).
- Choix du propriétaire : fonctionnement identique aux séries, nouveautés des catégories déjà chargées.
- Branche `plan-4-films` créée depuis le checkout existant, arbre initial propre.
- Inspection du client Xtream, du cache séries, de la navigation, de la progression et du backend : les tables acceptent les films mais le client et sa file imposent actuellement les épisodes. Le plan devra traiter les collisions d'identifiants, les anciennes files et les appels de la 0.4.1.
- Conception rédigée dans `docs/superpowers/specs/2026-11-15-etape4b-films-design.md` ; relecture indépendante terminée : aucune question importante restante ; validation détaillée non encore reçue.
- Aucun code produit modifié, aucun test produit relancé pour cette étape documentaire. Prérequis Android à préparer après validation.
- Aucun déploiement, aucune migration de production, aucune republication de l'environnement cloud.

- Conception validée : « Oui, tu peux y aller. ». Plan 4B rédigé et exécution autorisée, sans autre validation intermédiaire. Méthode du projet : sous-agent par tâche, revue indépendante, sauvegardes.

### Préparation des outils Android

- JDK21 complet ajouté hors dépôt via un paquet Debian authentifié par les index signés ; SDK36/build-tools36.0.0 installés via sdkmanager. Outils Android 19 vérifiés contre l'empreinte du manifeste Google officiel.
- Activation : `source /workspace/iptv-env/activate.sh`. Gradle utilise le proxy courant de la machine, sans désactivation TLS. Ces helpers dépendent de cette machine ; aucun brouillon cloud modifié ni environnement republié.
- Compilation/tests de référence Android en cours ; aucune réussite annoncée avant résultat.

### Checkpoint de continuité demandé par le propriétaire

- Il demande un maximum de traces pour reprendre avec une autre IA après quota, et une sauvegarde aussi sur main. Autorisation conservée ; aucune publication comprise dans cette demande.
- Tâche1 backend committée `7d2791f`, 172 tests verts ; rapport copié dans `docs/rapports/4b/tache-1-backend.md` afin de survivre à la machine. Revue indépendante en cours.
- PASSATION section0 et `docs/rapports/4b/reprise.md` décrivent les commandes, fichiers, état réel, risques et tâches2–5 encore à faire.

- Relecture indépendante tâche1 approuvée (conformité + qualité, aucun défaut identifié). Rapport durable `docs/rapports/4b/tache-1-relecture.md`. Tâche2 à démarrer.

- Sauvegarde sur main demandée : fusion `7b0eb49` poussée (backend vérifié + rapports + conception/plan), puis plan-4-films avancée au même commit. Tâche2 catalogue/cache/progression Kotlin démarrée ; rapport prévu `docs/rapports/4b/tache-2-kotlin.md`. Pas encore de résultat tâche2.

- Build de référence Android : shared193 verts ; tests télé bloqués par image Robolectric redirigée vers gcp.mirrors.maven.org puis HTTP403. Deux URLs officielles Maven testées, même redirection. Tâche2 poursuit les tests shared, détail dans reprise.md.

- **Reprise par Claude (session cloud /home/user/iptv-home).** État trouvé : tâche 1 faite et relue ; tâche 2 annoncée « en cours » mais **aucun code Kotlin poussé** (seulement des notes). Ici Gradle et Robolectric fonctionnent (les suites télé ont tourné en 4A, avec réessais quand Maven Central répond 429) : le blocage réseau de l'autre machine ne s'applique pas. Les chemins `/workspace/iptv-env` n'existent pas ici. Tâche 2 lancée par Claude (sous-agent), relecture ensuite.

- **16 nov., 01:10 UTC — Claude rend la main.** Tâche 4 (iPhone) fusionnée dans plan-4-films (143 tests web). Tâche 2 : client Xtream films (`0d57f54`) et cache + MovieRepository (`efaec8f`) faits ; progression typée coupée en cours (commit `wip`, non vérifié). État exact dans PASSATION section 0 ; message de reprise : `docs/rapports/4b/prompt-reprise.md`.

### Reprise Codex après le checkpoint Claude

- Lecture PASSATION bloc ÉTAT EXACT, prompt-reprise, plan et conception ; pas de nouvelle validation demandée.
- Checkout /workspace encore ancien et sale : diff binaire + fichiers non suivis archivés sous /workspace/iptv-work/recovery-4b, stash conservé, avance rapide vers db55352. Préservation des deux implémentations ; ne pas écraser le cache distant avec l'ancienne version locale.
- Vérification avant correction : le code compile, shared256 tests dont8 échecs progression attendus. Log local remote-compile.log dans le dossier recovery. Tâche2 reprise sur les manques listés dans PASSATION.
- Relecture tâche4 : conformité approuvée, défaut mineur démo ordre non cloisonné par profil, à traiter avant validation finale.
- Robolectric /workspace toujours HTTP403 sur gcp.mirrors.maven.org ; pas de changement de réglages ni de publication à cette reprise.

- Correctif du seul détail de relecture web : test rouge d’isolation ordre par profil puis144 tests web verts + build ; commit37d1e3f, correctif relu et approuvé.
- Tâche2 : shared260 tests verts après implémentation progression/MovieClient/prefs ; tests télé ciblés/compilation et relecture non terminés.

- Tâche2 : commits8c1a005 (progression partagée) et943f550 (isolation série TV), poussés. shared260 verts, TV sans runtime Android267 verts, assembleDebug réussi ; suite Robolectric toujours bloquée403. Rapport durable tache-2-kotlin.md. Revue globale tâche2 en cours : défaut important réponse erreur imbriquée en fiche fournisseur, correction avec régression demandée avant clôture.

- Correctif6863bf8 : RED53/2 sur erreur imbriquée, GREEN53 puis262 shared verts. I1 relu et résolu, aucune réserve de code restante tâche2. Suite Android complète reste non exécutée ici (403). Tâche3 écrans Films démarrée ; prochaine étape à reprendre si interruption. Harnais logique conservé dans dépôt pour traçabilité.

- Tâche3 sous-étape accueil/état : commit5d7c687 poussé,272 tests de logique TV verts dont5 Films. Contrat/adaptateur MovieCatalog, état par profil, chargement différé et masques, publication quotidienne des catégories. Fiche/actions en cours ; lecture/écrans/intégration restent à faire. Rapport vivant tache-3-tele.md.

- Tâche3 sous-étape fiche/actions :9ccb4f8 poussé,263 shared et274 logique TV verts. ProgressQueue.withPausedFlush sérialise l’action vu/non vu après tout envoi déjà parti ; suppression typée après réussite, erreurs préservent la file. Lecture en cours ; branchement activité/écrans/commande encore à faire.

- Tâche3 sous-étape lecture :68f99f0 poussé,276 tests de logique TV verts. Coordinateur distinct sans enchaînement, reprise/sauvegarde30s/pause/sortie, masques revalidés et langues du profil. Écrans/intégration activité en cours, pas encore parcours utilisable.

- Tâche3 intégration écrans/activité :c2c206e poussé. Troisième onglet Films persistant, accueil/fiche/lecture connectés, arrêt Films avant fermeture contrôleur à onStop, réutilisation affiches/panneau pistes existants. Compilation tests Dpad4 et produit OK,276 tests logique TV verts, APKdebug construit ; Dpad pas exécutés car dépendance Android403. Renforcement régressions et revue indépendante restent à finir.

- Tâche3 dernier checkpoint35b9b85 :263shared/282logiqueTV verts, APKdebug. Auto-revue signale3 défauts concrets : cache affiché après attente serveur, héros absent sans date ajoutée, repli Retour différent des séries. Corrections demandées avec tests avant relecture indépendante ; tâche3 toujours en cours. Suite officielle retentée une fois, échec403 avant tout test d’écran.

- Corrections3 points terminées :476e5ff poussé,264shared/284logiqueTV verts, APKdebug construit,5Dpad compilés/non exécutés. Relecture indépendante tâche3 lancée sur l’ensemble apps6863bf8..476e5ff. Aucune publication, version encore0.4.1/code5.

### Pause explicite du propriétaire — état sauvegardé

Il demande de sauvegarder/envoyer/mettre la passation à jour et s'arrêter. Aucun correctif après cette consigne. Dernier code476e5ff :264shared/284logiqueTV,172backend/144web/78+24outils verts, APKdebug + webbuild ;5Dpad compilés mais bloqués403 à l'exécution.

Revue tâche3 a signalé4 défauts importants avant la pause : I1 catégories/fiches après RPC ; I2 accueil ignore conflit de catégorie persistée/cachée non vide ; I3 refresh accueil ne publie pas mutations locales avant attente serveur ; I4 statut vu appris par RPC fiche non persisté, perdu lors du reload après Recommencer (test actuel préremplit le cache et ne couvre pas ce vrai parcours). Pas encore corrigés ; rapport de revue conservé pour la prochaine IA. Tâche3 ne doit pas être déclarée approuvée ; tâche5/version/release/global review non faites. PASSATION section0 et prompt-reprise remplacés par un checkpoint de pause prioritaire. Aucun déploiement/migrationproduction/fusionmain effectué à cette reprise.


### Reprise du 16 novembre par Claude (machine /home/user)

- Branche `claude/peaceful-davinci-pi93u7` : `plan-4-films` fusionnée avec `main` (confirmation intégrée de la page). Conflit `view.ts` résolu en gardant les deux ; 2 tests Films adaptés ; 144 tests web verts, build OK. 172 backend verts.
- SDK installé par le script du dépôt. Premier Gradle : 429 de Maven Central ; second essai avec `--max-workers=2` : tout se télécharge, **Robolectric compris**. La suite télé officielle s'exécute enfin.
- Tests rouges écrits pour I1–I4 (7 échecs sur 318, uniquement les nouveaux ; les 5 `MovieDpadTest` passent dès leur première exécution réelle). Correctif `38ccb37` : 264 partagés et 318 télé verts, APK debug construit.
- Décisions du correctif : progression et Ma liste demandées par deux tâches séparées avec compteur de génération (seule la dernière réponse est retenue) ; à l'accueil, `cachedCategory` est consulté pour chaque référence et une catégorie masquée connue écarte la carte ; la fiche lance la synchro à part et n'attend le serveur (8 s au plus) que pour une ancienne référence sans catégorie ; le statut vu appris est écrit dans la copie durable et la ligne en mémoire reste une référence au rechargement ; une action manuelle invalide une réponse de progression demandée avant elle.
- Relecture indépendante de la branche lancée. Version toujours 0.4.1/code 5, rien publié.
- Relecture indépendante rendue : N1 important (Ma liste) et mineurs M2, M3 fiche, M5, M6 corrigés avec 2 tests rouges puis verts ; M1, M4, focus accueil reportés. 264 partagés, 320 télé, 172 backend, 144 web verts. 0.5.0/code 6 signée, certificat attendu vérifié. Rapport `docs/rapports/4b/tache-5-validation.md`. Prochaine action : accord de mise en ligne du propriétaire.
- **Mise en ligne 0.5.0 (accord du propriétaire, 16 nov.)** : premier `apply_migration` en erreur 502 côté Supabase, rien appliqué (vérifié avant de réessayer) ; second essai réussi. 15 fonctions identiques au dépôt (md5 de `prosrc`), droits vérifiés. Fichiers de page et APK envoyés avec la clé publique sous 4 règles temporaires limitées aux noms exacts, supprimées aussitôt (0 règle). Vercel `dpl_HTFiKJKenhJDHySTo6xygqZGeevy` : `version.json` 0.5.0, `?demo` Films visible, 0 erreur. APK en ligne identique au fichier signé. En attente de l'essai TCL.
- **Essai TCL 0.5.0 réussi** (16 nov.) : onglet Films, catégories, films, fiche et lecture vus par le propriétaire, rien à signaler. Non essayés : reprise, Ma liste, vu/non vu, pistes, masquage depuis l'iPhone. Fusion dans `main` faite (accord permanent). Étape 4B terminée.
