# Message à donner à l'IA qui reprend la 4B (copier-coller)

> Tu reprends le projet **iptv-home** (GitHub), branche **`plan-4-films`**. Fais `git fetch origin && git checkout plan-4-films && git pull`.
>
> **Lis d'abord** `PASSATION.md` : la section 2 (règles absolues) et, en tête de la section 0, le bloc **« ÉTAT EXACT AU 16 NOVEMBRE 2026 »** avec son tableau. Il fait foi. Puis le plan `docs/superpowers/plans/2026-11-15-plan-4b-films.md` et la conception `docs/superpowers/specs/2026-11-15-etape4b-films-design.md`. Ne redemande pas la validation de la conception : elle est donnée.
>
> **Où on en est :** tâche 1 (backend) et tâche 4 (iPhone) faites. Tâche 2 (Kotlin partagé) à moitié : le client Xtream des films et le cache sont faits et testés ; la progression typée film/épisode a été coupée en plein milieu. Le dernier commit `wip(4b): progression typée…` contient les tests rouges et un début de code : **vérifie d'abord s'il compile**, puis termine en suivant la liste « Reste dans la tâche 2 » de PASSATION.
>
> **Ensuite, dans l'ordre :**
> 1. Finir la tâche 2 : tests rouges puis verts, `:shared:jvmTest` et les tests télé verts, rapport `docs/rapports/4b/tache-2-kotlin.md`, relecture indépendante.
> 2. Tâche 3 : écrans Films sur la télé (accueil, fiche, lecture), avec des tests Robolectric qui imitent la télécommande pour chaque écran. Relis les « Rappels de livraison » de PASSATION (pièges de focus et de la touche Retour trouvés en 4A).
> 3. Tâche 5 : relecture globale, toutes les suites, version 0.5.0 (versionCode 6), APK signé, certificat vérifié (SHA-256 `5d8877ce9c75eebe8d4a0cfb20427cf13dbf443402fc5c055608358d76e615d4`).
> 4. **Demande-moi mon accord** avant la migration 0012 en production et avant toute publication (page iPhone + APK ensemble).
>
> **Règles :** aucun identifiant IPTV nulle part ; jamais la clé service_role ; jamais de test contre la production ; ne jamais régénérer la clé de signature. Sauvegarde sur GitHub après chaque petite étape (`git pull --rebase` puis `git push`) et tiens à jour PASSATION, le plan et `docs/rapports/journaux/plan-4b-journal.md`. Parle-moi en français simple : je ne suis pas développeur.
