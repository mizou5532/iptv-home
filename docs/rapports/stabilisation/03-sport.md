# Stabilisation — Zone 3 : le Sport (bilan)

*18 nov. 2026, après la 0.30.2. Relecture seule. Fichiers relus : `apps/tv/.../sport/` (SportData : horaires, mémoire, modèle de l'onglet ; SportExtra : catégories Sport, votes, guide ; SportScreen : affiche, lecture d'un match), `App.kt` (création de l'onglet, préparation), parseurs de `shared/.../sport/Sport.kt`. La vitesse avait déjà été traitée les 17 et 18 nov. (rapport `sport-lenteur-2026-11-17.md`, corrections A à G).*

## Ce qui est solide

- **Vitesse** : mémoire affichée tout de suite, aujourd'hui d'abord puis les jours suivants, calcul des chaînes gardé par match, préparation dès « Qui regarde ? ».
- **Lecture des horaires sûre** : une réponse illisible d'ESPN ou de la LNH ne fait jamais planter (lecture protégée), le cache prend le relais.
- **Masqué gagne** : les candidats sont recalculés dès qu'un masquage arrive de l'iPhone.
- **Une seule connexion** : chaque chaîne passe par `PlayerController.open` ; quitter l'onglet ferme le lecteur.

## Constats

### Importants

**1. Lecture d'un match : écran noir sans message après une mise en veille.**
Quand la télé passe en veille (ou qu'on change d'app) pendant un match, l'app ferme le flux (une seule connexion). Au retour, l'écran du match reste noir, sans message et sans rien qui reparte : il faut appuyer sur une flèche. Le Direct et les films gèrent ce cas (retour à la liste ou à la fiche), pas le Sport.
→ Proposition : au retour, revenir à l'affiche du match (comme le Direct revient à la liste), rien ne reprend seul.

**2. Lecture d'un match : aucune reconnexion.**
Les corrections de la zone 1 (image figée → « Reconnexion… », erreur → nouvel essai) ne couvrent que le Direct, les films et les séries. Dans le Sport, une image figée le reste, et une erreur affiche aussitôt « Impossible de lire cette chaîne : ▶ pour la suivante ».
→ Proposition : même comportement que le Direct : image figée 8 s ou erreur → la même chaîne est rouverte une fois (« Reconnexion… ») ; si ça recommence moins d'une minute après, message actuel (▶ pour la suivante).

**3. Les scores en direct ne bougent que toutes les 30 minutes.**
Les horaires sont relus à l'ouverture de l'onglet, puis toutes les 30 min. Pendant un match, l'affiche montre donc un score qui peut avoir 30 min de retard.
→ Proposition : quand un match de la bande est en cours, relire seulement les tableaux d'aujourd'hui (une demande par ligue concernée, pas les jours suivants) toutes les 2 min ; sinon 30 min comme aujourd'hui.

### Gênants

**4. Mise à jour du Sport en arrière-plan.**
La relecture toutes les 30 min continue quand la télé est en veille avec l'onglet Sport ouvert (sites d'horaires, et toutes les heures les catégories Sport chez le fournisseur). Et au retour de veille, l'affiche garde les anciens scores jusqu'au prochain tour.
→ Proposition : arrêt pendant la veille ; au retour, mise à jour aussitôt si la dernière a plus de 5 min.

**5. Une panne passagère du fournisseur peut vider les chaînes d'événements pendant 1 h.**
`AndroidSportSources.extraChannels` : si la liste d'une catégorie Sport ne répond pas, ses chaînes sont remplacées par rien, et ce résultat est gardé 1 h (toutes les catégories en panne : la liste gardée entière est remplacée par une liste vide). Les chaînes d'événements du soir disparaissent alors des propositions.
→ Proposition : une catégorie sans réponse garde ses chaînes de la dernière fois ; une liste entièrement vide ne remplace jamais une liste gardée.

### Détails

**6.** `runCatching` autour des demandes d'horaires attrape aussi l'annulation (quitter l'onglet pendant la lecture des horaires) : sans conséquence visible aujourd'hui, mais la lecture est comptée « hors ligne ». À rendre propre en même temps que le point 3.

## Ordre de correction proposé

1 et 2 (lecture d'un match), puis 3 et 4 (rythme des mises à jour, même endroit), puis 5, puis 6. Tests pour chaque point, relecture indépendante, une version publiée.

Essai TCL ensuite, un soir de match : regarder un match, mettre la télé en veille 1 min et revenir (retour à l'affiche) ; débrancher le Wi-Fi 5 s pendant un match (« Reconnexion… ») ; laisser l'affiche ouverte pendant un match en direct (le score bouge toutes les 2 min).

## Corrections faites et publiées — 0.30.3 (code 53), 18 nov. 2026

Choix du propriétaire : « corrige tout ».

| Point | Correction | Où |
|---|---|---|
| 1 | Lecteur fermé par l'app (veille, autre app) → retour à l'affiche du match ; télé en veille pendant la recherche de l'adresse d'une chaîne → rien ne s'ouvre, retour à l'affiche | `SportPlayerScreen` |
| 2 | Image figée 8 s, erreur, ou aucune image 12 s après l'ouverture → même chaîne rouverte une fois (« Reconnexion… ») ; nouvelle coupure moins d'une minute après → « Impossible de lire cette chaîne : ▶ pour la suivante » | `SportPlayerScreen`, `player/Recovery.kt` (`LiveRecovery`) |
| 3 | Match en cours (ou dont l'heure est passée sans nouvelles) → tableaux d'aujourd'hui de sa ligue seulement relus toutes les 2 min ; le rythme suit l'affiche (2 min dès qu'un match commence) ; sinon 30 min | `SportViewModel.tick/refreshScores`, `SportTab` |
| 4 | Rien pendant la veille ; au retour, mise à jour aussitôt si la dernière a plus de 5 min | `SportTab` (cycle de vie), `refreshIfStale` |
| 5 | Catégorie Sport sans réponse : ses chaînes de la dernière fois restent, nouvel essai dans 10 min ; une liste vide après des pannes ne remplace jamais la liste gardée | `AndroidSportSources.extraChannels` |
| 6 | Annulation des demandes d'horaires propagée (plus comptée « hors ligne ») ; mises à jour protégées contre tout plantage | `SportSchedule.run`, `refresh`, `refreshScores` |

Relecture indépendante : une seule connexion respectée, pas de boucle ; corrigé ensuite : rythme calculé avant l'arrivée des horaires (30 min au lieu de 2 au premier tour), ouverture possible en veille pendant la recherche de l'adresse, ancienne adresse rouverte pendant un changement de chaîne, lecture sans image jamais abandonnée, match à l'heure passée oublié, plantage possible d'une mise à jour, liste volontairement vide bloquée.

Tests : 361 partagés, 471 télé (9 nouveaux), 0 échec ; analyse Android 0 erreur. APK SHA-256 `40daa36733b611373bbefe6957c1caac7e3526741bf1435a6ef530303cab1553`, 14 584 251 octets, `minSdkVersion 25`, certificat attendu, identique par Supabase et Vercel ; page inchangée ; Vercel `dpl_2pdKpV6NYAsFoh9N4KYZhpEghBUJ` ; règles stockage : les 4 `avatars_*`.

**Essai TCL, un soir de match** : regarder un match, veille 1 min puis retour (affiche du match) ; couper le Wi-Fi 5 s (« Reconnexion… ») ; laisser l'affiche ouverte pendant un match (score mis à jour toutes les 2 min).
