# Message à donner à l'autre IA (copier-coller)

> Tu reprends le projet iptv-home (dépôt GitHub, branche `plan-4-series`). Lis d'abord `PASSATION.md` en entier, en particulier la **section 0 « Reprise immédiate »**, puis `docs/rapports/tele-4a-series.md` (essai télé raté de la 0.4.0) et `docs/rapports/journaux/plan-4-journal.md`.
>
> Trois agents de Claude travaillaient en parallèle sur les problèmes 1 à 9. Les branches sont `plan-4-series`, `fix-epg-refresh` et `fix-series-rows`. Fais `git fetch origin`, regarde ce qui est fini (rapports `docs/rapports/tele-4a-enquete.md`, `tele-4a-guide-maj.md`, `tele-4a-series-rangees.md`) et ce qui reste.
>
> Pour chaque problème restant : trouve la cause, écris un test rouge qui imite la télécommande, corrige, test vert. Pas de correctif à l'aveugle. Fusionne les deux branches dans `plan-4-series`, relance toutes les suites, puis prépare la 0.4.1. **Demande-moi mon accord avant toute mise en ligne.**
>
> Règles absolues de la section 2 de PASSATION (aucun identifiant IPTV, jamais la clé service_role, etc.). Sauvegarde sur GitHub après chaque petite étape (`git pull --rebase` puis `git push`), et note tout dans le journal.
