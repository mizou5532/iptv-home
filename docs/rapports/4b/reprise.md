# Reprendre 4B avec une autre IA

Lire d'abord PASSATION.md section 0 (état courant), puis conception 4B, plan 4B et journal `docs/rapports/journaux/plan-4b-journal.md`. Ne pas rejouer `prompt-reprise-autre-ia.md` : il concernait la 4A déjà finie.

## Autorisations et état

Le propriétaire a validé l'approche puis la conception et l'exécution (« Oui, tu peux y aller »), demandé des traces très détaillées et « soumettre sur le main aussi ». Pas besoin d'une nouvelle approbation du plan ni d'une sélection de méthode : méthode du dépôt, sous-agent par tâche et relecture indépendante. Utiliser le checkout cloud isolé existant, pas de worktree supplémentaire.

Tâche 1 : commit `7d2791f`, backend et README, 172/172 tests verts ; rapport `tache-1-backend.md`. Relecture indépendante approuvée, voir `tache-1-relecture.md`. Tâche2 en cours (catalogue/cache/progression Kotlin) ; tâches3–5 non commencées. Au checkpoint backend, aucun code produit Kotlin/TV/web Films : vérifier git status et le rapport tâche2 pour les changements ultérieurs. Le code de migration est local, pas en production.

## Commandes réellement validées

Depuis `/workspace/iptv-home`, dépendances npm déjà installées avec `npm ci --cache /workspace/.npm` (lock inchangé).

Backend (PostgreSQL16 jetable, évite le chemin natif absent) :

```bash
docker run --rm --user 1000:1000 \
  -v /etc/passwd:/etc/passwd:ro \
  -v /workspace/iptv-home:/workspace/iptv-home \
  -v /opt/codex/runtimes/codex-primary-runtime/dependencies/node:/opt/node:ro \
  -w /workspace/iptv-home/backend \
  postgres@sha256:dd2bf3ce2dc83a81674cf7f217fcfd8a5009a4b325dda5b843bfee82a06cff6f \
  /opt/node/bin/node ../node_modules/vitest/vitest.mjs run
```

Vérifier `id` et `command -v node` si autre machine : montages et uid peuvent changer. Résultat tâche1 : 12 fichiers, 172 tests verts. Logs d'origine `/tmp/films-{red,green,full}.log` sur cette machine ; résumé durable dans le rapport tâche1.

Outils Android installés maintenant (pas encore de build vert) :

```bash
source /workspace/iptv-env/activate.sh
cd /workspace/iptv-home/apps
./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest :tv:assembleDebug
```

Un premier build de référence est en cours : `/workspace/iptv-env/android-baseline.log`. Vérifier les processus avant de le relancer. La session de shell de ce chat est 7168, mais cet identifiant n'est pas portable entre conversations. Utiliser le journal et les processus réels.

## Provenance des outils hors dépôt

- JRE21 Debian système copié dans `/workspace/jdk21/java-21-openjdk-amd64`, complété par le paquet Debian `openjdk-21-jdk-headless` 21.0.13+9-1~deb13u2 téléchargé via APT et index signés. Aucun contrôle de signature désactivé. Javac21 vérifié.
- Config APT locale : `/workspace/iptv-env/apt/config`, sources Debian trixie/trixie-security HTTPS, clé `/usr/share/keyrings/debian-archive-keyring.gpg`. Téléchargement extrait avec dpkg-deb, pas besoin de sudo.
- Android CLI19 : archive officielle `commandlinetools-linux-13114758_latest.zip` Google ; SHA1 `5fdcc763663eefb86a5b8879697aa6088b041e70` contrôlé contre repository2-1.xml officiel. SDK36/build-tools36.0.0 installés ; Gradle a ajouté build-tools35.0.0. `apps/local.properties` ignoré contient sdk.dir.
- `/workspace/.gradle/gradle.properties` : proxy Java généré depuis HTTPS_PROXY de la machine, pas de secret ; JDK local, mémoire3G, Kotlin in-process. Régénérer host/port si machine différente, ne pas copier une ancienne adresse. TLS actif.
- Les scripts/helpers/caches ne sont pas versionnés. Ne pas republier l'environnement cloud : instruction explicite de l'utilisateur.

## Contrats et risques pour la suite

Les signatures exactes backend sont dans README et tâche1 du plan. `movie_*` distinct de `series_*`, movie/vod_category/section vod existants. La file actuelle Kotlin est spécialisée épisodes : tâche2 doit ajouter type par défaut episode, préserver anciens JSON et distinguer les collisions. Ajouter le filtre itemType à la suppression de file des épisodes dans MainActivity, identifié après rédaction du plan.

Tâche2 doit tester la migration SQLDelight depuis version2 (séries) vers version3 sans effacement. Les tests existants SeriesRepositoryTest imposent actuellement version2 : faire évoluer la vérification sans la supprimer. Maintenir les appels 0.4.1 après migration SQL backend ; ne jamais déployer 0010 par inadvertance.

La validation réelle de l'image/son/veille sur TCL reste indispensable après construction. Aucun test de cette IA n'a encore prouvé la lecture d'un film sur la télé.

## Sauvegarde main

Fusion `7b0eb49` poussée sur origin/main avec accord explicite du propriétaire. La branche plan-4-films a été avancée au même commit et reste la branche de réalisation. Reprendre là ; ne pas redéployer les anciennes versions.

## Résultat du premier build Android de référence

193 tests shared, 0 échec/erreur/ignoré (résultats XML). Build complet terminé en échec après7m33s : téléchargement de `org.robolectric:android-all-instrumented:15-robolectric-14663667-2-i7` refusé. repo1.maven.org et repo.maven.apache.org redirigent tous deux HTTP302 vers `https://gcp.mirrors.maven.org/maven2/...`, puis403. Le miroir Google storage-download est également refusé par le proxy CONNECT403. Les tests télé n'ont pas tourné. Aucun contournement TLS/empreinte/politique réseau tenté. Résolution réseau à poursuivre, domaine gcp.mirrors.maven.org potentiellement à autoriser dans les paramètres de l'environnement si la restriction est confirmée ; ne pas remplacer une liste inconnue et ne pas republier automatiquement. Les avertissements Kotlin de dépréciation/opt-in préexistaient.

Le premier build est terminé ; le créneau Gradle appartient désormais à la tâche2 (tests shared uniquement dans l'attente de Robolectric).

## Réseau confirmé et demande au propriétaire

L'outil environment_status a confirmé une politique réseau restricted/enforced et l'absence de gcp.mirrors.maven.org et maven-central.storage-download.googleapis.com. Une demande asynchrone au propriétaire propose d'autoriser gcp.mirrors.maven.org, domaine de redirection effectif. Aucun paramètre réseau n'a été modifié par l'IA. Après ajout par le propriétaire, refaire la requête exacte puis relancer les tests TV. Ne pas considérer le silence comme une autorisation ou une résolution.

### Actualisation réseau (reprise)

Le propriétaire ne connaissait pas ce domaine ; explication donnée : serveur de téléchargement de l'Android simulé des tests, pas un service à intégrer à l'application. Ajout `gcp.mirrors.maven.org` préparé et sauvegardé dans le nouveau brouillon réseau, état backend saved/requires_publish=true. Aucune publication effectuée ; scripts, dépôts, secrets et autres champs non réappliqués. Le brouillon seul n'active pas le réseau. Attendre un changement réel et retester le téléchargement. L'instruction utilisateur de ne rien publier reste en vigueur.
