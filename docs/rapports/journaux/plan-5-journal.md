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
