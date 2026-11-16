# Journal — étape 5, recherche

- 16 nov. 2026 : conception écrite par Claude, relue par un sous-agent « constructeur ». Points tranchés par Claude : faute tolérée sur le mot en cours de frappe ; remplissage selon les masquages du profil actif. Point tranché par le propriétaire : **choix A** (OK sur une chaîne → liste du Direct avec aperçu). Conception validée, plan en 6 tâches écrit. Le propriétaire fait réaliser le code par une autre IA ; aucun code écrit.

## Reprise Codex — réalisation autorisée

- Lecture de l'état distant main598cf85 : Films4B terminés et en production0.5.0, recherche conçue/validée et plan6 tâches prêt. Ancien checkout local plan-4-films propre, conservé ; main avancé puis branche plan-5-recherche créée/poussée.
- Tâche1 moteur lancée avec tests d'abord et revue indépendante ensuite. Choix A Direct et masquages du profil actif conservés. Aucune publication ni modification environnement cloud.
- Outils /workspace/iptv-env/activate.sh encore présents. Accès gcp.mirrors.maven.org HTTP200 confirmé sur nouvel environnement publié : les échecs403 de4B sont historiques ; tests officiels à retenter.
- Vérification des seuils : « montrael » a8lettres (la première note locale était erronée) ; tests ajoutés aussi pour deux substitutions sur mot8+ et refus de deux erreurs sur mot7.

- Tâche1 commit57385b1 poussé,16 tests moteur et280shared verts,20requêtes/50000titres maximum40,846ms. Relecture indépendante en cours.
- Suite officielle TV de référence après restauration réseau : BUILD SUCCESSFUL58s, APKdebug construit ; compteurs XML à inscrire ci-dessous. Les anciens harnais sans Android ne sont plus nécessaires pour les tests officiels sur cette machine.
- Compteurs officiels :320TV et280shared,0échec/erreur/ignoré. Aucun test Android exclu de la suite officielle.

- Revue tâche1 : réserve importante sur Damerau restreint (OSA) ; deux erreurs combinées sur mot8+ peuvent être rejetées (`abcdefca sport` vs `abcdefabc sport`). Correction avec régression demandée ; tâche1 non clôturée tant que correctif non relu.

- Tâche1 terminée/revue sans réserve aprèsdc7f948 :19tests moteur/283shared verts,perf max89,659ms. Damerau sans restriction corrige les chevauchements de fautes. Tâche2 sources de recherche/cache filtré démarrée.

- Tâche2 code86cc57e poussé :7tests sources/290shared verts, relecture en cours. Lecture cache seule, catégories listes+fiches conservées. Direct : groupe sélectionnable via copies visibles, catégories connues de toutes les copies du groupe conservées ; caller devra fournir carte de groupes complète puis masque. Aucun réseau dans les sources.

- Tâche2 relecture approuvée,aucun défaut. Tâche3 CatalogFiller démarrée : fraîcheur existante,masques actifs,priorité écran,arrêt/reprise et avancement.

- Tâche3 codee8aeaaf poussé,14tests remplissage/304shared verts, relecture en cours. Lectures de fraîcheur ajoutées sans schéma/migration ; catégories fraîches non redemandées, fallback périmé pas pris pour réussite. Métadonnées de catégories connues globalement, contenu/progression filtrés par masques actifs. Unicité processus et cycle onStop restent pour intégration tâche5.

- Revue tâche3 : I1 important, les réussites retenues dans attempted empêchent tout rafraîchissement après24h sans stop/start. Correction ciblée avec horloge avancée sans arrêt demandée ; tâche3 non clôturée.

- Tâche3 terminée et relue aprèsf7f0d6b :15tests ciblés/305shared verts, refresh après25h corrigé sans réintroduire boucle erreurs. Tâche4 modèle d’écran démarrée, frappe rapide/masques/cachechanged/messages/avancement et helper seriesHidden.

- Tâche4 e9bcc40 poussé,11tests modèle/331tests TV officiels verts ; relecture en cours. Anti-rebond120ms, garde génération/préférences, reconstruction cache,query conservée,masques immédiats,progression séparée des messages. Régression avancement après prefs inconnues corrigée ; helper seriesHidden extrait4usages.

- Tâche4 relecture approuvée,aucun défaut bloquant ; Kdoc confinement Main à préciser en intégration. Tâche5 démarrée : écran/Dpad/voix,boutonbarre,DirectrevealchoixA,fillerprocess/lifecycle etprioritéforeground. TestsAndroidofficiels disponibles.

- Tâche5 premier palier4f0e474 poussé :9nouveauxDpad verts (ouverture3sections,champ/résultats,retourfocus,fichesfilm/série). AssertionboutonParler corrigée après fauxpositif du helper sous-chaîne ; preuve distincte dans rapport. Direct réel/source/invalidation,fillerprocess/lifecycle,voix et suitescomplètes encore à finir.

- Interruption technique pendant tâche5 après message de préparation de l'environnement : agent interrompu, client Java devenu zombie, daemon Gradle inactif ; dernier task5-key-debug.log sans résultat final. Fichiers conservés, agent relancé au même point (pas de réexécution des tâches1–4). Le test interrompu n'est compté ni vert ni rouge. Premier palier9Dpad demeure le dernier checkpoint validé ; Direct/focus dynamique et reste intégration toujours en cours.

## Pause quota explicite
Tout sauvegardé en WIP : tâches1–4 relues ; tâche5 écran/fiches9Dpad verts au4f0e474 puis Direct/focus en diagnostic (4échecs), testsRuntime anticipés non implémentés donc compilation potentiellement rouge. Diagnostictemporaire retiré. Agent arrêté. Lire blocpause PASSATION pour suite ; aucun déploiement/version0.6.0.

- 16 nov., reprise par Claude après l'arrêt de l'autre IA : dernière sauvegarde ne compilait pas (tests visant `SearchRuntime` absent) et le test de vitesse échouait (barca 127 ms). Corrigé : moteur ≈ 4× plus rapide ; `SearchRuntime` ; branchement réel dans `MainActivity`/`IptvApp` (avant : recherche absente de l'app) ; écrans prioritaires sur le remplissage ; chaîne trouvable par ses copies visibles ; focus (Direct derrière, réglages relus, « Recherche… »). Cause du « OK ne fait rien » : le test appuyait sur Bas avant les résultats (le focus allait à l'état vide) et le Direct derrière volait le focus. 305 partagés, 346 télé verts.
- Décisions du propriétaire : **pas de voix** pour l'instant ; **essais complets à la fin du projet**, après les thèmes. Tâche 6 reportée.
