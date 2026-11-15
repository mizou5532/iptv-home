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
