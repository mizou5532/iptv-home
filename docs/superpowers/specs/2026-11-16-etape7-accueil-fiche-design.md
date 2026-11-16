# Étape 7 (suite) : bannière d'accueil et nouvelles fiches (0.9.0)

Choix du propriétaire le 16 nov. 2026 sur les maquettes avec photos (artefact « Maquettes iptv-home ») :
**Accueil B** (carte flottante + « À l'affiche ensuite ») et **Fiche B** (affiche à gauche, liste à droite),
aux couleurs de chaque profil (fond clair et rose pour Maha).

## Images de fond
- `SeriesSummary.backdrop` et `MovieSummary.backdrop` (champ `backdrop_path` du fournisseur : tableau ou adresse).
- Cache : colonne `backdrop` dans `series_item`, `series_detail`, `movie_item`, `movie_detail` (migration `3.sqm`,
  schéma 4). Une fiche de série sans image reprend celle de la liste.
- Seule l'adresse est stockée ; l'image est chargée par Coil à la taille d'affichage (1280×520 pour la bannière,
  320×180 pour les vignettes), avec son cache borné.

## Accueil Séries et Films (`feature/`)
- `FeatureHero` : carte arrondie de 300 dp, image de fond (ou affiche recadrée), dégradé de la couleur de fond du
  profil, raison (« REPRENDRE », « PARCE QUE TU REGARDES « … » », « DANS TA LISTE », « NOUVEAU »…), titre, détails,
  résumé, boutons. À droite, les 3 suivantes (OK en affiche une). Change toutes les 10 s ; le compte repart quand la
  télécommande bouge dans la bannière. Les tests coupent le défilement (`LocalFeatureAutoRotate`).
- Suggestions (`buildSeriesFeatures`, `buildMovieFeatures`, 5 au plus) : le titre en cours, les nouveaux épisodes,
  puis les mieux notés des catégories regardées (`pickFrom`, 2 par catégorie, 3 au plus), Ma liste, les ajouts récents.
  Les suggestions sont tirées **du cache local seul** (aucune demande en plus des rangées) ; les films vont chercher la
  fiche des 5 suggestions sans image de fond.
- Rangée « Reprendre » en cartes paysage (`LandscapeCard`) ; OK relance la lecture.

## Fiches
- Série : fond = image de la série voilée à 85-97 % par le fond du profil ; affiche 260×370, bouton « ▶ Reprendre
  S·É » (ou « Lire »), Ma liste, saison vue ; titre en gras, détails avec note ; épisodes avec vignette (image de
  l'épisode, sinon celle de la série), état et durée. Appui long : vu / non vu (inchangé).
- Film : même disposition ; Lire/Reprendre, Recommencer, Ma liste à gauche ; détails, reste à voir, résumé, Marquer vu.
