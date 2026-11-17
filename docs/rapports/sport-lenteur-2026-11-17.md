# Onglet Sport : pourquoi il est lent et parfois faux à l'ouverture

*17 nov. 2026. Analyse seulement : aucun code modifié. Demande du propriétaire : l'onglet Sport met 5 à 6 s à
afficher quelque chose (le prochain match de Manchester City), puis environ 10 s de plus pour montrer les matchs du
moment.*

## Mesures

Faites ici, sur un serveur rapide, avec le code de l'application et les vrais horaires du 17 nov. pour le profil
Hamza (Canadiens, Barça, Steelers, Manchester City, Marseille). La télé est plusieurs fois plus lente qu'un serveur :
les durées de calcul ci-dessous sont un plancher, pas ce qu'on voit sur la TCL. Le fournisseur IPTV n'a pas été
mesuré (aucun identifiant ici, règle absolue).

| Étape | Mesure |
|---|---|
| Horaires ESPN + LNH, tout (59 demandes, 6 à la fois) | 2,2 s, 1,2 Mo |
| Horaires d'aujourd'hui seulement (9 demandes) | 0,6 s |
| Lecture des 59 réponses gardées en mémoire | 30 ms (165 matchs) |
| Préparation des chaînes visibles (8 000 chaînes fictives) | 75 ms |
| Chaînes proposées pour les 25 matchs affichés, 4 445 chaînes sport | 4 100 à 4 400 ms |
| … dont le tri des chaînes par langue | 4 250 à 4 300 ms (99 %) |
| Même calcul avec 1 500 chaînes sport | 1 330 ms |
| Même calcul avec 500 chaînes sport | 475 à 540 ms |

Le nombre réel de chaînes « sport » visibles sur la télé n'est pas connu d'ici ; le calque de diagnostic de la télé
(3 appuis sur Info, Menu ou 0) affiche la ligne « Sport : … » avec les vraies durées.

## Causes, par ordre d'importance

1. **Tri par langue recalculé sans arrêt** (`sportCandidates`, `regionOf` dans `SportMatching.kt`). Pour chaque match,
   la région (Québec, France, É.-U., Canada) de chaque chaîne sport est recalculée : nettoyage du nom, découpage,
   trois expressions régulières recompilées à chaque appel. 25 matchs × des milliers de chaînes. Or la région d'une
   chaîne ne change jamais.
2. **Le même calcul complet refait 3 à 5 fois à l'ouverture** (`SportViewModel.rebuild`) : après la mémoire, après les
   catégories Sport, après le réseau, après les confirmations du guide, à chaque changement de préférences. Rien
   n'est gardé d'un passage à l'autre, et les 9 matchs « mes équipes » des 7 prochains jours sont calculés alors que
   seuls ceux de la bande sont visibles.
3. **L'affichage à jour attend le fournisseur IPTV** (`refresh` : `extras.await()`). Les horaires frais (2 s) ne sont
   montrés qu'une fois relues les catégories Sport : `get_live_categories` puis une vingtaine de `get_live_streams`
   (≈ 2 200 chaînes), 4 à la fois, plus une lecture Supabase. Ce fichier n'est gardé qu'une heure : presque chaque
   ouverture le repaie. C'est très probablement l'essentiel des « 10 s de plus ».
4. **La mémoire affichée en premier est incomplète et périmée**, sans que rien ne le dise. Les ligues sans équipe
   favorite (NBA…) ne sont gardées que pour « aujourd'hui » : le lendemain, l'adresse du jour a changé et il n'y a
   rien en mémoire pour elles. Il ne reste que les matchs à venir des équipes favorites, d'où Manchester City seul.
   Les états et scores gardés sont ceux de la dernière ouverture (un match « à venir » déjà commencé, un score figé).
5. **59 demandes d'horaires quand 9 suffisent** pour l'écran : les jours suivants (50 demandes) sont attendus avant
   d'afficher, alors que seul aujourd'hui compte pour la bande.
6. **Tout démarre en même temps** quand le profil s'ouvre sur Sport (Hamza, Mahmoud) : lecture du catalogue du
   Direct, regroupement des copies, fiches TMDB, Sport. Non mesuré ; secondaire.
7. **Confirmations par le guide** : jusqu'à 30 demandes au fournisseur, puis encore un calcul complet.

## Corrections proposées

| # | Correction | Effet attendu | Travail |
|---|---|---|---|
| A | Région, langue et « sport » d'une chaîne calculés une seule fois (dans `SportChannelIndex`), expressions régulières sorties des fonctions | calcul des chaînes ≈ 70 fois plus court (4 300 → ≈ 60 ms ici) | petit |
| B | Garder le résultat par match tant que rien n'a changé ; ne calculer que les matchs de la bande ; un seul recalcul regroupé | 3 à 5 calculs → 1 | petit |
| C | Afficher les horaires dès qu'ils arrivent ; les chaînes d'événements complètent ensuite | l'écran à jour n'attend plus le fournisseur | petit |
| D | Lire d'abord aujourd'hui (9 demandes, 0,6 s), afficher, puis les jours suivants en arrière-plan | écran à jour en ≈ 1 s de réseau | petit |
| E | Mémoire honnête : garder « demain » pour toutes les ligues ; un match gardé « à venir » dont l'heure est passée est montré « commencé » ; si la mémoire a plus de quelques minutes, attendre aujourd'hui jusqu'à 1 s avant le premier affichage | plus de premier écran faux | moyen |
| F | Catégories Sport gardées 12 h au lieu de 1 h, relues en arrière-plan (les chaînes d'événements du jour changent peu) ; réglage de la maison lu en parallèle | plus de 20 demandes au fournisseur à chaque ouverture | petit |
| G | Préparer le Sport dès « Qui regarde ? » pour les profils qui ouvrent sur Sport | onglet prêt à l'arrivée | moyen |
| H | Durées de chaque étape gardées dans le calque de diagnostic (déjà là) + un test de vitesse qui échoue si le calcul dépasse un seuil | la lenteur ne revient pas sans qu'on le voie | petit |

Ordre conseillé : A, B, C, D (une soirée, l'essentiel du gain), puis E et F, puis G si l'attente reste sensible.
