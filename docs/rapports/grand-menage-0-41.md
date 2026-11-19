# 0.41.0 — Grand ménage du catalogue, vitrine, démarrage dans l'ordre, autre version (19 nov. 2026)

Suite des rapports de lenteur (`lenteur-0-40-1.md`) : la mémoire de la TCL (2,4 Go) est le vrai problème. Choix du
propriétaire : « le sport d'abord pour le Direct ; séries et films ensuite ; grand ménage du reste ».

## 1. Ménage fait sur le serveur (masqué pour tout le monde, réversible depuis l'iPhone)

Sauvegarde prise avant : `bd50167f-b16b-447e-8d93-060922496453` (raison « manuelle »).

- **Direct : 6 487 → 2 594 chaînes.** Cachées : Afrique (sauf Maghreb), Canal+ Afrique, Caraïbes, Haïti, Maurice,
  Belgique, Suisse, Pologne, Pays-Bas, Albanie, Turquie, Kurdistan, Grèce, Portugal, Brésil, Nouvelle-Zélande, Autriche
  (sport compris), US non sportif (divertissement, nouvelles, films, enfants), US AT&T (26 catégories). Réseaux US
  NBC/FOX/CBS/ABC/CW : 1 262 chaînes cachées une par une, **Buffalo gardée** (ABC 7, CBS 4, CW 23, FOX 29, NBC 2 ×3).
- Gardés : toutes les catégories sport utiles (Canada, US, France, Espagne, Royaume-Uni, Italie, Allemagne, beIN arabe…),
  Canada, France (général, cinéma, enfants, documentaires, musique), arabe et Maghreb.
- Films (13 catégories) : indien, allemand, grec, italien, turc, latino, « EN - ITALIAN SUB ENG », Viaplay.
  Séries (7) : « ENGLISH SERIES (TR) », Joyn (allemand), Viaplay.

## 2. Vitrine (films et séries en deux niveaux)

`shared/catalog/Showcase.kt`. Seules les catégories de la vitrine ont leurs rangées à l'accueil, sont remplies en
premier, et entrent dans l'index des versions et l'index de recherche gardés en mémoire :
- les deux : enfants, animés et mangas, Crunchyroll, Nickelodeon, animation pour adultes (« adult swim » : Simpson,
  Family Guy, South Park), japonais ;
- films : « FR - », « QC - », nouveautés (« NEW RELEASE »), IMDB Top ;
- séries : France, Québec, « ENGLISH SERIES », « NETFLIX SERIES ».
Doublons 4K / Dolby / HEVC hors vitrine mais **gardés dans l'index des versions** (versions de secours).
Fournisseur aux noms inconnus : tout en vitrine.

Hors vitrine : toujours sur la télé, **trouvé par la recherche** (`OtherTitles` : lu dans la base seulement quand on
cherche, oublié 3 min après), affiché sous les résultats de la vitrine ; OK ouvre la fiche (image, résumé chargés).
Ma liste et Reprendre montrent toujours leurs titres. Le remplisseur n'annonce plus de changement pour les catégories
hors vitrine (avant : index refaits après chaque catégorie remplie).

Ordre de grandeur (19 nov.) : films en vitrine ≈ 21 000 sur 46 000 ; séries ≈ 8 100 sur 11 900.

## 3. Démarrage dans l'ordre

`diag/StartupOrder` : Séries (index des versions), puis Films, puis Sport (préparation), puis Direct (mise à jour de
la liste, sauf premier lancement sans liste), puis le remplissage de la recherche. Chaque étape attend les précédentes,
au plus 20 s par étape depuis le lancement. Ce qu'on demande à l'écran n'attend jamais.

## 4. « Essayer une autre version »

Film qui ne démarre pas : l'écran d'erreur propose d'abord « Essayer une autre version (Anglais · 4K) ». Si l'image
arrive, cette version est **retenue sur la télé** (`feature/VersionMemory`, préférences « versions-retenues ») : elle
s'ouvre à la place de celle en panne, depuis l'accueil comme depuis la recherche ; si elle disparaît, l'ancienne revient.
À faire : la même chose pour les épisodes, et la version retenue partagée par toute la maison (serveur).

## Tests

ShowcaseTest (6), CatalogFillerTest (vitrine d'abord), OtherTitlesSearchTest (3), StartupOrderTest (3),
OtherVersionDpadTest (3).

## 0.41.1 — Fiche lente depuis la recherche (« Cars 3 » : ~10 s, rien à l'écran)

Trois causes additionnées :
1. OK sur un résultat relisait et regroupait toute la vitrine (des dizaines de milliers de titres) pour vérifier les
   masquages : maintenant, l'index déjà construit sert (même masques), sans relecture.
2. La fiche d'un film ou d'une série attendait la fin d'un téléchargement de fond en cours (une grosse catégorie peut
   prendre plusieurs secondes) : les fiches ne passent plus par ce verrou (petite demande).
3. La fiche attendait le fournisseur pour s'afficher : le titre, l'affiche et le résumé déjà sur la télé s'affichent
   tout de suite ; la fiche du fournisseur complète ensuite.
Tests : MovieDetailViewModelTest (2), SearchViewModelTest (OK sans relecture), SearchRuntimeTest (fiches sans attente).
