# Journal — Étape 4 : séries puis films

## 15 novembre 2026
- Le propriétaire n'utilise pas encore l'app au quotidien : on peut travailler librement, y compris sur main. B2 (0.3.0) fusionné dans main ; son essai sur la TCL reste à faire quand il pourra.
- Ordre décidé par le propriétaire : **séries d'abord** (sa sœur, la reprise est essentielle), puis films (réutilisent le socle des séries).
- Branche `plan-4-series` créée depuis main. Prochaine étape : conception (docs/superpowers/specs/), à faire valider, puis plan et construction.
- Conception 4A (séries) proposée : `docs/superpowers/specs/2026-11-15-etape4a-series-design.md`. Maquettes 5 et 6 copiées dans `docs/maquettes/project/` (accessibles sans Claude). Relecture par un « constructeur » en cours, puis validation du propriétaire.
- Relecture « constructeur » de la conception 4A : 5 questions, tranchées par Claude dans la section 9 (migration 0011, progression enrichie, marquer vu/non vu, iPhone = catégories seulement, langue audio/sous-titres en local).
- Plan 4A écrit (`docs/superpowers/plans/2026-11-15-plan-4a-series.md`, 8 tâches). Construction lancée : tâche 1 (backend).
- Tâche 1 (backend séries) construite : `ca60b6a`, 158 tests ; rapport `docs/rapports/4a-tache1-backend.md`. Relecture en cours.
- Tâche 1 relue : une correction demandée (une série d'une catégorie masquée pouvait encore apparaître dans « Continuer » dans un cas précis) + petites protections. En cours.
- Tâche 1 terminée (`ca60b6a`, correctif `d8e0fd9`, 163 tests backend). Tâche 2 (lire les séries chez le fournisseur, cache local) lancée.
- Tâche 2 construite : `da21327` (110 tests partagés), rapport `docs/rapports/4a-tache2-series-xtream.md`. Relecture en cours.
- Tâche 2 relue : corrections en cours (une réponse vide du fournisseur ne doit jamais effacer la mémoire ; garder la catégorie d'une série pour la règle « masqué gagne »).
- Tâche 2 terminée (`da21327`, correctif `9d38da6`, 124 tests). Tâche 3 (progression avec file locale) lancée.
- Tâche 3 construite (progression avec file locale, préférences séries, publication des catégories de séries) ; rapport `.superpowers/sdd/2026-11-15-plan-4a-series/task-3-report.md`. Relecture à faire.
- Tâche 3 construite (`02a8a1f`, 143 tests partagés ; rapport `docs/rapports/4a-tache3-progression.md`). Relecture interrompue par la limite d'utilisation. **Reprise : relire la tâche 3 (point connu : une erreur serveur permanente bloquerait la file de progression — prévoir une règle de mise à l'écart), puis tâches 4 à 8.**
- Tâche 3 relue : corrections en cours (une progression refusée par le serveur ne bloque plus les autres ; file protégée contre la perte et la corruption, taille limitée, testée pour de vrai).
- **Passage de relais à une autre IA (demande du propriétaire).** Claude s'arrête proprement : rien en cours, arbre propre. À faire par l'autre IA, dans l'ordre : (1) corrections de la tâche 3 listées dans `docs/rapports/4a-tache3-corrections-a-faire.md` ; (2) tâches 4, 5, 6 du plan `docs/superpowers/plans/2026-11-15-plan-4a-series.md`. Claude reprendra ensuite la fin : tâche 7 (iPhone), tâche 8 (relecture d'ensemble, 0.4.0, mise en ligne avec accord, essai).

## Reprise Codex — corrections 3 puis tâches 4–6

- Branche distante plan-4-series récupérée et `git pull origin plan-4-series` exécuté (base bfe0d03), arbre initial propre. L'ancien travail B2 local était déjà commité et inclus dans les ancêtres de cette branche ; aucun code B2 à recopier.
- Passation entièrement lue, fin du journal, corrections tâche3, plan4A et conception y compris section9 lus. Les indications B2 anciennes accumulées dans la passation ont été clarifiées au profit de l'état actuel.
- Périmètre explicite : corrections tâche3, puis accueil4, fiche5, lecture6 ; jamais tâches7–8. Tests avant chaque correction, rouge/vert documentés, sous-agent et relecture indépendants par tâche.
- Sauvegarde : pull --rebase plan-4-series et commit/push après chaque sous-étape verte, jamais plus de30min sans push, checkpoint WIP avant longue commande/sous-agent. Aucun droit de production/mise en ligne/fusion.
- Environnement cloud conservé : source /workspace/iptv-env/activate.sh pour JDK21/SDK36 ; un seul Gradle à la fois --max-workers=2 ; backend via helper PostgreSQL16 si nécessaire. Helpers hors dépôt : voir docs/rapports/environnement-cloud.md.

### 2026-11-15 — reprise correctifs tâche 3, checkpoint tests
- Brief et conception section 9 vérifiés contre ProgressQueue/ProgressClient/LocalStore : perte des métadonnées nulles confirmée par inspection ; premier test de régression ajouté, exécution ciblée en cours.
- Aucun changement de production à ce checkpoint ; suites complètes et autres correctifs restent à faire.
- Sous-étape enrichissements verte : test rouge observé (AssertionError, seriesId null), puis 11 tests ProgressQueue verts. Remplacement conserve série/saison/épisode/nom/affiche/catégorie si nouvelle valeur null. Rapport durable commencé.
