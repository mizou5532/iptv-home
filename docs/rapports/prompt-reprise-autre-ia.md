# Message à donner à l'autre IA (copier-coller tel quel)

> Tu reprends le projet **iptv-home** sur GitHub, branche **`plan-4-series`**. C'est un lecteur IPTV familial pour une télé TCL Google TV. L'iPhone ne sert qu'à configurer, via une page web. La synchro passe par Supabase. Le propriétaire n'est pas développeur : parle-lui en français simple.
>
> **1. Lis d'abord, dans l'ordre :**
> - `PASSATION.md` en entier, **surtout la section 0 « Reprise immédiate »** et la section 2 (règles absolues) ;
> - `docs/rapports/tele-4a-series.md` : l'essai de la 0.4.0 sur la télé a raté (9 problèmes, plus les essais ciblés) ;
> - `docs/rapports/tele-4a-enquete.md` : l'agent A a corrigé les problèmes 1, 2, 3, 8 et 9 ;
> - `docs/rapports/journaux/plan-4-journal.md`.
>
> **2. Ce qui reste :**
> - Fais `git fetch origin`.
> - Les branches **`fix-epg-refresh`** (guide et « Mise à jour » bloqué) et **`fix-series-rows`** (seulement 2 rangées de séries) sont peut-être inachevées. Lis leurs rapports s'ils existent (`docs/rapports/tele-4a-guide-maj.md`, `docs/rapports/tele-4a-series-rangees.md`), sinon leurs commits.
> - Termine ce qui manque. Méthode : trouve la cause, écris un **test rouge**, corrige, puis le test passe au vert. Pas de correctif au hasard.
> - Pour tout écran, ajoute un test qui imite la télécommande (Robolectric avec Compose, voir `apps/tv/src/test/kotlin/home/iptv/tv/ui/`).
>
> **3. Ensuite :**
> 1. Fusionne les deux branches dans `plan-4-series`. Résous les conflits sans écraser les corrections de l'agent A.
> 2. Relance toutes les suites :
>    - `cd apps && ./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest :tv:assembleDebug --rerun-tasks -q`
>    - `npm test -w backend`
>    - `npm test -w web/config`
>
>    Si Maven Central refuse (404/429), suis la note Gradle de la section 0 de PASSATION.
> 3. Fais une relecture indépendante de l'ensemble des correctifs.
> 4. Passe en **0.4.1** : `versionCode = 5`, `versionName = "0.4.1"` dans `apps/tv/build.gradle.kts` et dans `web/config/public/version.json`. Lance `:tv:assembleRelease` et vérifie le certificat (SHA-256 `5d8877ce9c75eebe8d4a0cfb20427cf13dbf443402fc5c055608358d76e615d4`).
> 5. **Demande l'accord du propriétaire avant toute mise en ligne.** Ensuite : APK dans le compartiment Supabase `apk` (règle d'écriture temporaire, envoi, **suppression de la règle**, vérifier qu'il reste 0 règle) et `version.json` sur Vercel. Procédure dans `docs/rapports/deploiement-page.md` et dans la section 0 de PASSATION.
> 6. Le propriétaire essaie sur la télé avec l'application Downloader : `https://iptv-home-config.vercel.app/iptv-home.apk`. Explique-lui comment ouvrir l'écran de diagnostic (3 appuis rapides sur Info, Menu ou 0) et demande-lui une photo s'il y a un problème. Vérifie surtout :
>    - le plein écran, qui n'est plus noir ;
>    - les flèches ;
>    - un épisode qui se lance ;
>    - le guide ;
>    - « Mise à jour » qui disparaît ;
>    - toutes les séries ;
>    - la rapidité ;
>    - la fluidité du sport.
> 7. Ne fusionne dans `main` qu'après un essai réussi et l'accord du propriétaire.
>
> **Règles absolues :**
> - Aucun identifiant IPTV nulle part.
> - Jamais la clé service_role.
> - Jamais de foyer ni d'inscription créés sur la production Supabase depuis des tests.
> - Accord explicite avant toute publication, migration en production ou fusion.
> - Ne jamais régénérer la clé de signature.
>
> **Sauvegarde :** fais `git pull --rebase` puis `git push` après **chaque** petite étape. Note tout dans le journal et dans la section 0 de PASSATION.
