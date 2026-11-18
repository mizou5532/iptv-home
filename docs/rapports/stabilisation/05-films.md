# Stabilisation — Zone 5 : les Films (bilan)

*18 nov. 2026, après la 0.30.4. Relecture seule. Fichiers relus : `apps/tv/.../movies/` (MovieHomeViewModel, MovieHomeScreen, MovieDetailViewModel, MovieDetailScreen, MovieHomeData), `shared/.../movies/MovieRepository.kt`, seuil « vu » côté serveur (`record_movie_progress`, migration `movies`). Déjà corrigés avant ce bilan : lecture d'un film (zone 1, 0.30.1) et à-coups des rangées de l'accueil (zone 4, 0.30.4).*

## Ce qui est solide

- **Cache du fournisseur** : comme les Séries (1 jour pour les listes, 6 h pour les fiches, une réponse vide ne remplace jamais le cache, cache périmé servi en cas de panne) ; « Ajouts récents » lu par pages sur un index (`movie_item_added`) : la mémoire reste bornée même avec un très gros catalogue.
- **Une seule demande à la fois au fournisseur** ; masqué gagne partout (fiche, rangées, Reprendre, Ma liste, bannière), revérifié après chaque réponse.
- **« Vu » cohérent** : même seuil de 90 % sur la télé (`queuedMovieRows`) et sur le serveur ; « Marquer vu » suspend l'envoi des positions le temps de l'action (`withPausedFlush`) et retire les anciennes positions en attente : une ancienne position ne peut pas défaire la marque.
- **Réponses en retard ignorées** (numéros de génération pour la progression et Ma liste) ; actions de la fiche sérialisées ; message clair en cas d'échec.
- **Reprise d'une version à l'autre** (langues, 4K) seulement tant que ce film n'a pas sa propre position.

## Constats

Aucun défaut à corriger trouvé dans cette zone. Rien qui justifie une nouvelle version.

Remarque sans conséquence visible : deux rechargements de la fiche peuvent se chevaucher (retour de lecture puis « Réessayer » immédiat) ; le second redemande la même fiche (déjà en cache, aucune demande au fournisseur). Pas de correction proposée.

## Suite

Zone 6 : démarrage, « Qui regarde ? », navigation, profils, thèmes, mises à jour et recherche (dont le test du clavier sensible au minutage noté en zone 2).
