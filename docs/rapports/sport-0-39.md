# 0.39.0 — Sport : vitesse, affiche, classements, rangées, sans résultat (18 nov. 2026)

Choix du propriétaire parmi 18 idées : **1, 2, 3, 5, 6, 9, 13, 15, 18**. Précision : « une bande qui reste limitée à
aujourd'hui » (les rangées « Demain » et « Cette semaine » sont seulement dans la Grille).

## Ce qui est fait

| # | Quoi | Où |
|---|---|---|
| 1 | **Horaires préparés par le serveur de la maison** : la télé envoie d'un coup les adresses LNH/ESPN dont elle a besoin à la fonction `sport-horaires` ; le serveur rend ce qu'il a gardé pour la maison (2 min pour aujourd'hui, 6 h pour les jours suivants et les classements) et va chercher le reste (8 à la fois). Serveur muet ou adresse absente : la télé la demande elle-même (comme avant). Mémoire **par maison** (`sport_cache`, fonctions `sport_cache_get` / `sport_cache_put`, membres seulement, adresses LNH/ESPN seulement) : une maison ne peut jamais changer ce que voit une autre. Aucune clé `service_role` : la fonction utilise la session de la télé. | migration `20261119000033_sport_horaires`, `functions/sport-horaires`, `tv/sport/SportRelay.kt`, `SportSchedule(batch)` |
| 2 | **Prêt dès l'allumage** pour tous les profils qui ont la section Sport (pas seulement ceux qui s'ouvrent dessus ; ceux-là attendent 4 s pour laisser passer le reste), classements compris. | `SportPrep.prewarm` |
| 3 | **Chaînes de Sport du fournisseur relues au plus toutes les 6 h** (au lieu de 1 h) ; l'écran utilise toujours tout de suite la liste gardée. | `EXTRA_MAX_AGE_MS` |
| 5 | **Affiche automatique** (par défaut) : mon équipe en direct → match serré en fin de partie (hockey : 1 but en 3e ; soccer : 1 but après la 70e ; NBA/NFL : 8 points au 4e quart) → mon prochain match → direct le plus avancé. iPhone : Automatique / Mes équipes / Le direct. | `autoFeatured`, `isCloseLate`, `Match.period` |
| 6 | **Compte à rebours** : « Dans 2 h 15 · 19 h 00 · LNH », « Dans 12 min », « Samedi 19 h · LNH », « Ça commence ! ». | `countdownLabel`, `heroWhen` |
| 9 | **Classements** sous chaque équipe de l'affiche (LNH officielle ; ESPN pour NFL, NBA, championnats de soccer) : « 3e Atlantique · 28 pts · 2 défaites de suite », « 1er de la Liga · 33 pts », « 8-1 · 1er AFC · 5 victoires de suite ». Relus au plus toutes les 6 h. | `SportStandings.kt` |
| 13 | **Grille en rangées** : En direct maintenant, Ce soir, Demain, Cette semaine pour mes équipes (40 matchs au plus) ; les filtres s'appliquent à toutes. La bande de l'Affiche reste limitée à aujourd'hui. | `sportRows`, `SportGrille.kt` |
| 15 | **Sans résultat** (iPhone : « Cacher les scores ») : aucun score ni classement ; l'affiche automatique ne met jamais en avant un match serré ; touche Info ou « Voir le score » (en maintenant OK) pour un match. | `SportView.hideScores`, `vm.reveal` |
| 18 | **Fin du match regardé** : « Match terminé. Barça – Real est en direct (1-1, 70') : le regarder ? » — Regarder / Non merci, disparaît après 20 s. | `nextLiveMatch`, `NextMatchPrompt` |

## Limites connues

- Les profils qui avaient déjà enregistré un réglage du Sport ont « Mes équipes » d'enregistré : ils restent ainsi ; choisir « Automatique » sur l'iPhone.
- Sur les télécommandes sans touche Info, « Voir le score » est dans le choix de la chaîne (maintenir OK).
