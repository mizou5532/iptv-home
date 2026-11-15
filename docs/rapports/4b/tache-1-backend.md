# Tâche 1 — Backend Films

Statut : DONE. Commit : `7d2791f` (`feat(backend): add movie organisation and typed progress RPCs`). Relecture indépendante à effectuer par le contrôleur.

## Changements

- Migration additive `20261115000012_movies.sql` : copie légère des catégories, RLS membre, publication, masques `vod_category`, ordre `vod`, Ma liste ordonnée avec métadonnées propres, clés supplémentaires de `profile_prefs`.
- RPC exactes du brief implémentées : `publish_movie_categories`, `org_movie_categories`, `org_movie_hide_all_except`, `org_set_movie_category_order`, `org_set_movie_list`, `org_reorder_movie_list`, `movie_list`, `record_movie_progress`, `continue_movies`, `movie_progress`, `set_movie_watched`.
- Métadonnées `movie_*` ajoutées à `watch_progress` et `favorites`. Anciennes RPC conservées ; projection et regroupement `continue_watching` adaptés au type + identifiant. Masquage films appliqué avec `vod_category` avant limite.
- Marquage manuel sans changement de date ; non vu réinitialise position/statut ; progression plus récente gagne, durée inconnue acceptée, vu collant à 90 %.
- README : contrat complet, paramètres, retours, autorisations, limites, compatibilité.
- `films.test.ts` : 5 scénarios intégration PostgreSQL avec nombreuses assertions sur catégories, organisation, liste/métadonnées, collisions films/épisodes, anciennes signatures, masquage avant limite, vu/non vu, isolation et droits de tous les nouveaux RPC.
- `series.test.ts` : assertion devenue obsolète (vod_category invalide) remplacée par `invalid_category`, avec accord du contrôleur ; couverture du refus conservée.

## Preuves RED / GREEN

Prérequis vérifiés : `id` → uid=1000(agent), gid=1000(agent). Image PostgreSQL épinglée disponible : inspect → `sha256:6b7038c60456795649816e6ad91b015284a75af3bca7d236b7c6124b56a1d9a3`.

Commande de base (aucun secret, aucune production) :

```bash
docker run --rm --user 1000:1000 \
  -v /etc/passwd:/etc/passwd:ro \
  -v /workspace/iptv-home:/workspace/iptv-home \
  -v /opt/codex/runtimes/codex-primary-runtime/dependencies/node:/opt/node:ro \
  -w /workspace/iptv-home/backend \
  postgres@sha256:dd2bf3ce2dc83a81674cf7f217fcfd8a5009a4b325dda5b843bfee82a06cff6f \
  /opt/node/bin/node ../node_modules/vitest/vitest.mjs run test/films.test.ts
```

RED, avant migration : 1 fichier échoué, **5 tests échoués**. Erreurs fonctionnelles attendues : `function publish_movie_categories(unknown, jsonb) does not exist`, `function org_set_movie_list(...) does not exist`, `function record_movie_progress(...) does not exist`. Infrastructure et fixtures démarraient normalement. Sortie intégrale locale : `/tmp/films-red.log`.

GREEN, même commande après implémentation : **1 fichier / 5 tests réussis**, durée 2.81 s. Sortie intégrale : `/tmp/films-green.log`.

Suite backend entière, même commande sans `test/films.test.ts` : **12 fichiers / 172 tests réussis**, 0 échec, durée 43.50 s. Comprend 18 tests séries et toutes les anciennes suites. Sortie intégrale : `/tmp/films-full.log`. Le shell des captures utilisait une redirection puis `tail` ; les nombres et résultats ci-dessus sont ceux explicitement imprimés par Vitest.

`git diff --check` : aucune erreur. Aucun changement de comportement après la suite complète ; uniquement contrat README et correction d'un commentaire films.

## Auto-revue

- Vérifié signatures exactes / noms des paramètres des RPC et droits explicites authenticated, refus public/anon ; helper d'ordre privé.
- Vérifié filtrage foyer/profil, restriction propriétaire pour périmètre global, RLS catalogue et refus d'écriture directe.
- Vérifié identité de progression primaire profil/type/id inchangée et regroupement de continuation comprenant le type ; métadonnées films jamais stockées dans series_* par les nouveaux RPC.
- Vérifié ancien `record_progress` conserve movie_* et anciennes signatures séries non remplacées ; projection `continue_watching` alignée sur le type composite enrichi.
- Vérifié aucun accès production, migration 0010 non modifiée et aucune dépendance nouvelle envers 0010 ; aucun push.
- Commit limité aux quatre fichiers backend ; journal du contrôleur laissé intact.

## Limites et points de suivi

Aucun blocage connu. Comme documenté, le backend conserve les références de liste et autorise la lecture d'état d'un film masqué : le client doit appliquer « masqué gagne » à l'affichage/lecture et rendre indisponible une référence de catégorie inconnue. Les URL d'affiche sont nettoyées par le client avant envoi. Ces choix suivent le modèle séries et le brief ; aucune consultation fournisseur n'est introduite.

Essai télé et déploiement production hors périmètre. Relecture indépendante encore attendue du contrôleur ; aucun succès d'essai matériel ni publication n'est revendiqué.
