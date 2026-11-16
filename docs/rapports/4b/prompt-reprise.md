# Message à donner à l'IA qui reprend la 4B (copier-coller)

> Tu reprends le projet **iptv-home** (GitHub), branche **`plan-4-films`**. Fais `git fetch origin && git checkout plan-4-films && git pull`.
>
> **Lis d'abord** `PASSATION.md` : la section 2 (règles absolues) et, en tête de la section 0, le bloc **« ÉTAT COURANT APRÈS REPRISE CODEX »** avec son tableau (plus récent que « ÉTAT EXACT AU 16 NOVEMBRE 2026 »). Il fait foi. Puis le plan `docs/superpowers/plans/2026-11-15-plan-4b-films.md` et la conception `docs/superpowers/specs/2026-11-15-etape4b-films-design.md`. Ne redemande pas la validation de la conception : elle est donnée.
>
> **Où on en est :** tâches1 (backend),2 (Kotlin) et4 (iPhone) implémentées et relues. Dernier correctif Kotlin `6863bf8` :262 tests partagés verts ;267 tests de logique TV via harnais sans Android verts ; APK debug construit. Tâche3 (écrans Films télé) en cours : lire son rapport vivant `docs/rapports/4b/tache-3-tele.md` et `git status` pour identifier les dernières sous-étapes. Ne pas réimplémenter le catalogue/progression déjà achevés. La suite Robolectric officielle reste bloquée HTTP403 ici ; les résultats du harnais ne remplacent pas ces tests.
>
> **Ensuite, dans l'ordre :**
> 1. Lire les rapports tâche2 et sa relecture (terminées). Vérifier le résultat courant de tâche3, préserver les modifications en cours ; ne pas réappliquer l’ancien stash local sur les fichiers récents.
> 2. Tâche 3 : écrans Films sur la télé (accueil, fiche, lecture), avec des tests Robolectric qui imitent la télécommande pour chaque écran. Relis les « Rappels de livraison » de PASSATION (pièges de focus et de la touche Retour trouvés en 4A).
> 3. Tâche 5 : relecture globale, toutes les suites, version 0.5.0 (versionCode 6), APK signé, certificat vérifié (SHA-256 `5d8877ce9c75eebe8d4a0cfb20427cf13dbf443402fc5c055608358d76e615d4`).
> 4. **Demande-moi mon accord** avant la migration 0012 en production et avant toute publication (page iPhone + APK ensemble).
>
> **Règles :** aucun identifiant IPTV nulle part ; jamais la clé service_role ; jamais de test contre la production ; ne jamais régénérer la clé de signature. Sauvegarde sur GitHub après chaque petite étape (`git pull --rebase` puis `git push`) et tiens à jour PASSATION, le plan et `docs/rapports/journaux/plan-4b-journal.md`. Parle-moi en français simple : je ne suis pas développeur.
