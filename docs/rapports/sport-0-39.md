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

## Tests renforcés avant publication (demande du propriétaire : « un maximum de tests »)

- **Serveur** : 261 (dont 25 nouveaux) — adresses acceptées et refusées (pièges : autre site, `http`, `..`, `@`, espaces,
  majuscules), durées de vie, relais (non-membre → rien demandé à la LNH/ESPN, mémoire en panne, réponses périmées,
  source en panne ou illisible, doublons, 80 adresses au plus, 8 à la fois), mémoire par maison (deux maisons séparées,
  télé de la maison, anonyme refusé, trop d'éléments, trop gros, effacement après 3 jours), prendre la main (cas limites,
  nom du profil, profil d'une autre maison).
- **Calculs partagés** : 392 (+16) — match serré dans chaque sport, affiche automatique (ordre des favoris, plusieurs
  matchs serrés, sans résultat), compte à rebours (limites, jours, fuseau), classements (réponses incomplètes, séries).
- **Télé** : 576 (+28), dont **10 tests à la télécommande** de l'écran Sport (classements, compte à rebours, score caché
  puis montré avec Info, « Voir le score », grille en rangées dans l'écran, proposition de fin de match : Regarder, Non
  merci, Retour lent, flèches) et **4 pour « Quelqu'un regarde déjà »**.
- **Page iPhone** : 280 (+5) — choix de l'affiche, « Cacher les scores », erreur d'enregistrement.

**Problèmes trouvés par ces tests et corrigés** : pendant la proposition de fin de match, OK et Retour allaient à
l'écran de lecture au lieu des boutons (OK affichait le bandeau, Retour fermait la lecture) ; un relâchement de Retour
pouvait fermer la lecture après la proposition ; un autre match sur la même chaîne n'était pas rouvert ; l'heure de
l'affiche ne suivait pas l'horloge du modèle ; une réponse illisible du serveur bloquait le repli de la télé.

APK : SHA-256 `daab96990a071a0b9a9f13f043b6158c9af3647113450022ccdba62c9b9baf0a`, 14 813 631 octets, code 65,
`minSdkVersion 25`, certificat attendu ; analyse Android 0 erreur. Page : `index-CnN5WoVB.js`, `demo-BZ_QZriK.js`.
**À publier** : migration `sport_horaires` (accord dans Supabase), fonction `sport-horaires` v3 (logique déplacée dans
`logic.ts`), APK, page.
