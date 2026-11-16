# Tâche 5 — Relecture globale, validation et version 0.5.0 préparée

16 novembre 2026, machine `/home/user/iptv-home`, branche `claude/peaceful-davinci-pi93u7` (= `plan-4-films` + `main`).

## Correctif I1–I4 de la tâche 3 (commit `38ccb37`)

7 tests rouges puis verts (`MovieHomeViewModelTest`, `MovieDetailViewModelTest`). Détail des décisions dans le journal `docs/rapports/journaux/plan-4b-journal.md`.

## Relecture indépendante (sous-agent, lecture seule)

Périmètre : correctif `38ccb37`, fin de la relecture de la tâche 3, revue globale du diff `755d997..HEAD`.
Verdict : I1–I4 réellement résolus, aucun défaut Critique, **un défaut Important (N1) introduit par le correctif**, six mineurs.

| Id | Constat | Suite donnée |
|---|---|---|
| N1 Important | Une réponse `movie_list` partie avant « Ajouter/Retirer de Ma liste » écrasait l'action. | **Corrigé** : compteur `listGen` dans la fiche ; test rouge puis vert `stale_list_response_does_not_undo_my_list_toggle`. |
| M1 | Copie durable écrite par l'accueil et la fiche sans verrou commun (fenêtre étroite). | Reporté. Les écritures de l'accueil se font maintenant sous son verrou, avec contrôle de génération. |
| M2 | Film vu puis relancé : pouvait revenir dans « Continuer » après la synchro (le serveur ne renvoie que les non vus). | **Corrigé** : l'accueil garde la ligne « vu » d'un film dont la position attend encore dans la file ; test `watched_movie_restarted_stays_out_of_continue_after_sync`. |
| M3 | Focus possiblement perdu quand le bouton focalisé disparaît. | Fiche : **corrigé** (le focus est redemandé quand la fiche arrive). Accueil (« Chargement… », « Réessayer », carte retirée au Retour) : **à contrôler sur la TCL**, Retour récupère dans tous les cas. |
| M4 | `continue_watching` (Séries) renvoie aussi les films non terminés, que la télé filtre : beaucoup de films inachevés récents peuvent raccourcir la rangée « Continuer » des Séries. | Reporté (contrat backend déjà relu et testé ainsi, `films.test.ts`). À traiter dans une prochaine migration si le cas se présente. |
| M5 | « Chargement… » et « Lire » pouvaient porter un instant le même demandeur de focus. | **Corrigé** : fiche et fin de chargement publiées ensemble. |
| M6 | Lecture du cache répétée pour les références sans catégorie. | **Corrigé** : l'absence est mémorisée pendant la reconstruction. |

Points vérifiés sans défaut par la relecture : Retour (ni double ni avalé), lecteur qui ne se ferme pas seul, film et épisode de même identifiant bien séparés, masquage revalidé pendant la lecture, Direct et Séries, droits de la migration, page web, aucun identifiant IPTV ni clé `service_role` dans le diff.

## Tests réellement exécutés après ces corrections

| Suite | Résultat |
|---|---|
| `:shared:jvmTest` | 264, 0 échec |
| `:tv:testDebugUnitTest` (suite officielle, Robolectric compris : 5 `MovieDpadTest`, 8 Live, 3 Séries, 1 barre) | 320, 0 échec |
| `npm test -w backend` (Postgres 16 local jetable) | 172, 0 échec (code backend inchangé depuis) |
| `npm test -w web/config` + build | 144, 0 échec, build réussi |

## Version préparée

- `apps/tv/build.gradle.kts` : **0.5.0 / versionCode 6** ; `web/config/public/version.json` idem.
- `:tv:assembleRelease` réussi, signé avec la clé existante. Certificat SHA-256 `5d8877ce9c75eebe8d4a0cfb20427cf13dbf443402fc5c055608358d76e615d4` (celui attendu).
- Fichier (hors dépôt, à reconstruire dans une nouvelle session) : `apps/tv/build/outputs/apk/release/tv-release.apk`, 37 Mo, SHA-256 du fichier `dfcd88dbf9d71eaf1b7a10c4b7a31d7f5e77a95e90a642d0c97d8dfb7d5fb810`.

## Non vérifié ici (pas de télé ni d'émulateur)

Rendu réel, focus réel sur la TCL (M3), lecture Media3/MKV, pistes audio et sous-titres, pause/veille, enchaînements rapides.

## Mise en ligne : rien n'est fait, accord du propriétaire requis

Dans l'ordre : 1) migration `20261115000012_movies.sql` en production (0010 reste locale, jamais `db push`) ; 2) page iPhone (procédure `docs/rapports/deploiement-page.md`) ; 3) APK dans le compartiment `apk` + `version.json` ; règles temporaires supprimées et vérifiées à 0. La migration d'abord : la 0.4.1 reste compatible avec elle (signatures séries conservées). Puis essai TCL, puis fusion dans `main`.

Essai TCL à faire : onglet Films, rangées par catégorie qui se chargent en descendant, fiche, Lire/Reprendre/Recommencer, Ma liste, Marquer vu/non vu, Retour à chaque niveau, pistes audio/sous-titres, reprise après sortie, catégorie masquée depuis l'iPhone, et vérifier que Direct et Séries marchent comme avant.
