# Étape 4A — Les séries sur la télé

*15 novembre 2026 · Proposition de Claude, à valider par le propriétaire. Les films (4B) viendront ensuite et réutiliseront ce socle.*

Références : `PASSATION.md` ; conception générale `2026-11-14-player-iptv-design.md` §6.5 (séries), §6.6 (films, plus tard), §6.8–6.9 ; maquettes 5 (accueil séries) et 6 (fiche d'une série), copiées dans `docs/maquettes/project/` ; contrat backend `backend/README.md` (tables `watch_progress`, `favorites`, `hidden_items`, `category_order` ; fonctions `record_progress`, `continue_watching`).

## 1. Pourquoi

La sœur du propriétaire regarde des séries presque tous les jours. Ce qui compte pour elle : **reprendre exactement où elle s'est arrêtée**, savoir ce qui est vu, enchaîner l'épisode suivant sans rien toucher, et le tout dans une interface belle, « style plateforme de streaming », sans lenteur. Le catalogue du fournisseur est énorme (400 000 à 500 000 titres films + séries) : il ne doit jamais être téléchargé en entier.

Réussite : la sœur choisit son profil, l'écran Séries s'ouvre en moins de 2 s sur un grand bloc « Reprendre Les Rivages — S2 É5, 32 min restantes » ; OK reprend à la seconde près ; à la fin de l'épisode, l'épisode suivant démarre seul après un court décompte ; si elle reprend plus tard sur l'ordinateur (étape 8), elle retrouve le même point.

## 2. Objectifs (4A)

1. **Navigation par sections** en haut de l'écran de la télé : **Direct** et **Séries** (Films arrivera en 4B), plus le profil et « Changer de profil » déjà existants. Le dernier onglet utilisé par le profil est retenu.
2. **Accueil Séries** (maquette 5), de haut en bas :
   - grand bloc **« Reprendre »** : la série la plus récente en cours, saison/épisode/titre, temps restant, boutons Reprendre · Recommencer l'épisode · Tous les épisodes, et « Ensuite : É6 … » ;
   - rangée **« Continuer à regarder »** (affiches avec barre de progression), d'après `continue_watching` ; une série dont le dernier épisode vu est terminé propose l'épisode suivant ; une série entièrement vue disparaît ;
   - rangée **« Ma liste »** (séries mises de côté) ;
   - rangée **« Nouveaux épisodes dans tes séries »** : séries de « Continuer » ou « Ma liste » qui ont des épisodes plus récents que le dernier vu (vérifiées au plus une fois par jour, au maximum 20 séries) ;
   - puis une rangée par **catégorie de séries du fournisseur**, dans l'ordre du profil, sans les catégories masquées.
3. **Fiche d'une série** (maquette 6) : affiche, titre, genre, année, résumé ; onglets de saisons (« vue » cochée, « 4 / 8 vus ») ; liste des épisodes (vu ✓, en cours avec barre et temps restant, à venir) ; boutons « Dans ma liste » et « Marquer la saison vue » ; OK sur un épisode = lire (ou reprendre).
4. **Lecture d'un épisode** en plein écran avec le lecteur existant (Media3, une seule connexion au fournisseur) :
   - reprise à la position enregistrée ;
   - progression enregistrée toutes les 30 s, à la pause, à l'arrêt et à la sortie ; « vu » à 90 % (règle du backend) ;
   - **épisode suivant automatique** : à la fin (ou au générique final si détectable plus tard), carte « Épisode suivant dans 10 s » avec Annuler ; enchaîne même d'une saison à l'autre ;
   - **choix de la langue audio et des sous-titres** (le MKV du fournisseur en contient souvent plusieurs) depuis un petit panneau ; choix retenu par profil ;
   - contrôles : pause, ±10 s / ±30 s, barre de progression, Retour = fiche de la série.
5. **Organiser depuis l'iPhone** : dans « Organiser », un sélecteur de section **Direct / Séries** ; pour Séries : masquer, réordonner, « tout masquer sauf », Ma liste — en réutilisant les écrans et fonctions de B1 (les tables acceptent déjà `series_category`, `series` et la section `series`).
6. **Catalogue des séries** chargé **par catégorie, à la demande** : la liste des catégories au démarrage, les séries d'une catégorie quand on l'ouvre ou qu'elle devient visible, les saisons/épisodes d'une série quand on ouvre sa fiche. Cache local (SQLDelight) avec fraîcheur (catégories : 1 jour ; séries d'une catégorie : 1 jour ; fiche : 6 h) ; une catégorie masquée pour tout le monde n'est jamais demandée. La télé publie dans le compte en ligne une copie légère **des catégories et des séries vues par catégorie** (noms seulement) pour que l'iPhone puisse organiser, comme pour le direct.

## 3. Non-objectifs (4A)

- Films (4B), recherche (étape 5), téléchargement hors ligne (étape 9), thèmes clair/fun (étape 7).
- Détection automatique du générique de fin (le seuil reste 90 % et la fin du fichier).
- Lecture ou organisation des épisodes sur l'iPhone (pas d'app iPhone).
- « Prendre la main » (étape 8).

## 4. Anti-objectifs

- Télécharger tout le catalogue des séries, ou une catégorie masquée pour tout le monde.
- Perdre une position de reprise (coupure réseau, mise en veille, changement de profil) : la position est gardée en local et envoyée dès que possible ; « le plus récent gagne ».
- Une affiche manquante qui laisse un trou : titre sur fond coloré à la place (comme les maquettes).
- Afficher un identifiant IPTV ou une adresse de flux.

## 5. Contraintes connues

- API Xtream : `get_series_categories`, `get_series&category_id=…` (liste avec nom, affiche, note, année, genre, résumé court), `get_series_info&series_id=…` (saisons, épisodes avec `id`, `container_extension`, titre, durée, affiche d'épisode). Adresse de lecture : `/series/<user>/<pass>/<episode_id>.<ext>`.
- 75 % des fichiers sont des MKV : Media3 lit le conteneur ; les pistes audio/sous-titres doivent être sélectionnables.
- Backend existant : `watch_progress` (clé profil + type + identifiant, avec série/saison/épisode), `record_progress` (90 %, plafond horloge +5 min), `continue_watching`. Défaut connu à corriger au début : `record_progress` accepte un horodatage NULL.
- Supabase coupe à 1 000 lignes : toute grosse réponse en un bloc ou paginée.
- Une seule connexion au fournisseur : la lecture d'un épisode ferme l'aperçu du direct, et inversement.

## 6. Approches considérées

| | Approche | Pour | Contre |
|---|---|---|---|
| **A — proposée** | Catalogue à la demande, par catégorie, avec cache local et fraîcheur ; progression via le backend existant | Rapide à l'ouverture, respecte le masquage, mémoire maîtrisée | Une catégorie jamais ouverte est vide hors ligne |
| B | Télécharger tout le catalogue séries en arrière-plan | Tout disponible hors ligne | Des centaines de milliers d'entrées, lent, gourmand, contraire au masquage |
| C | Ne rien garder en local | Simple | Lent à chaque ouverture, rien hors ligne |

## 7. Décisions proposées (à valider)

- Ordre : séries (4A) puis films (4B) — **validé par le propriétaire**.
- Sections Direct / Séries en haut ; Films ajouté en 4B.
- Épisode suivant automatique après 10 s, annulable.
- Choix audio/sous-titres inclus en 4A, retenu par profil.
- Organisation des séries depuis l'iPhone incluse en 4A (réutilisation de B1).

## 8. Laissé au constructeur

- Schéma exact du cache local et de la copie publiée des séries (sur le modèle de la copie du direct).
- Mise en page fine d'après les maquettes 5 et 6, jetons du thème sombre.
- Taille des pages, nombre d'affiches préchargées, politique de cache des images (Coil).
- Calcul de « Nouveaux épisodes » (comparaison du dernier épisode connu et de la fiche, bornée à 20 séries/jour).

## 9. Précisions après relecture (choix de Claude, à corriger si besoin)

1. **Une nouvelle migration backend est nécessaire** (`20261114000011_series.sql`, 0010 restant locale) : publication des catégories de séries, fonctions `org_*` étendues aux catégories de séries (masquer, réordonner, « tout masquer sauf »), « Ma liste » des séries (`favorites` avec `item_type = 'series'`, ordonnée), `profile_prefs` étendu (masqués et ordre de la section séries, Ma liste), correction de `record_progress` (horodatage NULL refusé). Appliquée en production seulement avec l'accord du propriétaire.
2. **Progression enrichie** : `record_progress` reçoit aussi le nom de la série, son affiche et sa catégorie (facultatifs), gardés dans `watch_progress`, pour que « Reprendre » et « Continuer » s'affichent tout de suite, sans attendre le fournisseur. La logique « épisode suivant » et « série terminée » utilise la fiche de la série, chargée en arrière-plan (cache 6 h) : le bloc Reprendre s'affiche d'abord, le reste se complète. **Masqué gagne** : une série dont la catégorie est masquée (pour tout le monde ou le profil) n'apparaît pas dans Reprendre/Continuer.
3. **Marquer vu / non vu** : nouvelle fonction `set_watched(p, items, watched)` qui marque ou démarque des épisodes (saison entière ou un épisode) sans faire remonter la série en tête de « Reprendre » (l'horodatage d'une ligne existante n'est pas changé ; une nouvelle ligne prend une date ancienne). Durée inconnue : 1 s. « Recommencer l'épisode » remet la position à 0 sans changer « vu ».
4. **iPhone : catégories seulement.** Pour les séries, l'iPhone organise les **catégories** (masquer, réordonner, tout masquer sauf). La télé publie seulement la liste des catégories de séries (pas les séries elles-mêmes). « Ma liste » se gère sur la télé (bouton sur la fiche) et s'affiche sur l'iPhone en lecture seule si simple, sinon plus tard. Masquer une série individuelle : plus tard.
5. **Langue audio et sous-titres** : retenues **sur la télé, par profil**, par code de langue (s'applique à toutes les séries). Le dernier onglet utilisé aussi, en local. Pas de synchro de ces réglages pour l'instant.
