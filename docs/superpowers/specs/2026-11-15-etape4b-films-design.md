# Étape 4B — Films sur la télé

15 novembre 2026. Approche choisie par le propriétaire : même fonctionnement que les séries ; ajouts récents limités aux catégories déjà chargées. Conception détaillée validée par le propriétaire le 15 novembre 2026.

## Objectif

Permettre de choisir et regarder un film sur la TCL, puis retrouver sa position avec le même profil. Reprendre les conventions de navigation et de présentation de la 0.4.1 : interface française, rapide, utilisable entièrement avec les flèches, OK et Retour.

Références : conception générale §6.6, conception 4A et ses précisions §9, PASSATION.md, contrat backend. La 4A est terminée et fusionnée ; ses anciennes instructions de reprise ne sont pas à rejouer.

## Approches

- **Choisie : catalogue à la demande, comme les séries.** Cache local, catégories chargées au fil de la navigation ; récents calculés sur les films déjà connus. Démarrage rapide, pas de téléchargement global. Les nouveautés ne sont pas exhaustives.
- Catalogue exhaustif des catégories autorisées : nouveautés plus complètes, mais beaucoup plus de requêtes et de stockage pour un fournisseur comptant des centaines de milliers de titres. Non retenu par le propriétaire.
- Sans cache : plus simple mais lent à chaque ouverture et inutilisable en cas de panne du fournisseur. Écarté pour les mêmes raisons qu'en 4A.

## Parcours et comportements

1. Ajouter **Films** à Direct / Séries. Mémoriser le dernier onglet par profil en conservant les valeurs déjà enregistrées. Un changement de profil ferme la lecture et garde les progrès du profil quitté.
2. Accueil : grand bloc Reprendre pour le film en cours le plus récent ; sinon mise en avant d'un film visible déjà connu. Puis Continuer à regarder, Ma liste, Ajoutés récemment et catégories du fournisseur dans l'ordre choisi. Une rangée vide n'affiche pas de contenu inventé. Si rien n'est connu, les catégories restent accessibles avec un état de chargement sélectionnable.
3. Les ajouts récents sont triés par date d'ajout du fournisseur, sur le cache autorisé uniquement. Expliquer discrètement « Parmi les catégories déjà parcourues ». Une date absente ou invalide ne devient pas une fausse nouveauté ; ces films restent dans leur catégorie.
4. Fiche : image ou affiche, titre, année, durée, genre, résumé et note si disponibles. Boutons Lire/Reprendre, Recommencer, Ajouter/Retirer de Ma liste et Marquer vu/non vu. Les champs absents sont omis ; sans image, montrer le titre sur un fond de remplacement. Retour rend la sélection à l'affiche d'origine.
5. Lecture plein écran avec Media3 : reprise, pause, déplacements temporels et panneau audio/sous-titres existants. Réutiliser les préférences locales de langue du profil. Aucun enchaînement automatique vers un autre film. À la fin, montrer un état terminé permettant de revenir à la fiche ou recommencer.
6. Progression toutes les 30 secondes, à la pause, à la sortie et à la mise en veille ; persistance locale avant synchronisation. Vu à 90 % avec durée connue. Reprise comme en 4A : position positive sauf film déjà à 90 % ou à moins de 30 secondes de la fin, auquel cas repartir de zéro. Un film vu quitte Continuer. Recommencer conserve le statut vu ; Marquer non vu remet position et statut à zéro. Une action manuelle vu/non vu ne remonte pas artificiellement le film dans les récents de lecture.
7. iPhone : ajouter Films au sélecteur Organiser ; masquer et réordonner les catégories, et tout masquer sauf, pour tous ou pour un profil. Ma liste se gère sur la télé, comme en 4A. Pas de lecteur ni de catalogue complet sur l'iPhone.

## Catalogue, masquage et erreurs

Client Xtream : get_vod_categories, get_vod_streams avec category_id obligatoire, get_vod_info avec vod_id ; lecture via le constructeur d'URL movie existant et l'extension fournie (MKV compris). Parser les nombres et chaînes courants ; rejeter les identifiants vides. Une réponse mal formée ne doit jamais effacer un cache valide.

Proposition technique : cache SQLDelight dédié aux films ; fraîcheur catégories/listes 24 h, fiches 6 h ; cache périmé utilisable sur panne. Charger les rangées au fil de la navigation, avec anticipation bornée comme en 4A et mémoire bornée. Ne jamais appeler la liste globale de films. Taille des pages et bornes précises laissées au constructeur, avec tests sur grand catalogue.

Masqué gagne dans toutes les rangées, fiches et demandes de lecture. Attendre de connaître les préférences avant toute demande de contenu. Une catégorie masquée pour tous ou le profil n'est pas téléchargée ; revalider lors d'une réponse tardive et au démarrage de lecture. Les références persistées dans Ma liste et la progression conservent la catégorie connue. Pour une ancienne référence sans catégorie, utiliser d'abord le cache ; si elle reste inconnue, afficher un état indisponible jusqu'à redécouverte par une catégorie autorisée, plutôt que faire une requête non filtrable. Une erreur montre un message français et Réessayer, sans détail technique ni adresse.

## Synchronisation et compatibilité

Le backend accepte déjà item_type=movie, mais le client ProgressEntry et sa file sont spécialisés épisode. Étendre leur identité à **profil + type + identifiant**, y compris remplacement, lecture, envoi et suppression. Un film et un épisode avec le même identifiant restent indépendants. Les anciennes entrées sérialisées sans type se lisent comme épisodes et ne perdent aucune donnée.

Ajouter les métadonnées des films (nom, affiche nettoyée, catégorie) pour afficher Reprendre sans requête fournisseur. Ne pas réutiliser les champs series_* pour stocker des films. Conserver la compatibilité des signatures RPC utilisées par la 0.4.1 ; les anciennes télés doivent continuer à fonctionner après la migration. Les résultats de continuation doivent distinguer film/épisode et filtrer avec le bon type de catégorie, avant leur limite. Prévoir une lecture dédiée aux films pour que la limite des séries ne les exclue pas, et une lecture d'état sur la fiche incluant les films vus.

Nouvelle migration additive 0012 : copie légère des catégories de films, droits/RLS et fonctions de publication et d'organisation, Ma liste par profil, progression enrichie et marquage vu/non vu des films. Étendre profile_prefs sans retirer les clés existantes. Publier seulement les catégories, pas le catalogue complet. Utiliser les types movie/vod_category et la section vod, confirmés dans le schéma existant ; ne pas créer d'alias incompatibles. La migration 0010 (prise de main) reste hors du déploiement.

Un seul lecteur actif : quitter le direct ferme son aperçu avant de lire un film ; quitter un épisode le ferme également. Réutiliser les mécanismes de réservation en production, sans activer le protocole différé B2. L'évolution du cache Android doit préserver le catalogue et la file existants lors d'une mise à jour depuis 0.4.1.

## Hors périmètre

Recherche globale/vocale (étape 5), recommandations externes, bandes-annonces, téléchargement hors ligne, thèmes supplémentaires, regroupement des versions d'un film, guide TV et prise de main. Pas de téléchargement exhaustif du fournisseur. Aucun nouveau service externe ni secret nécessaire pour les tests locaux.

## Validation attendue

- Tests Xtream avec données fictives : catégories, listes, fiches, champs manquants, formats et erreurs ; jamais d'identifiants réels.
- Cache : fraîcheur, panne, réponses invalides, masquage et changement de préférences en cours de requête, migrations depuis cache 0.4.1.
- Progression : collision d'identifiants film/épisode, restauration ancienne file, coupure réseau, reprise, changement de profil, veille, durée inconnue, vu/non vu et réponse serveur plus récente.
- Backend PostgreSQL jetable : isolation des foyers/profils, droits, masques, ordre, Ma liste et compatibilité des appels séries 0.4.1.
- Tests d'écran Robolectric avec télécommande : onglets, toutes les rangées, fiche, lecture, panneau de pistes, Retour et restitution du focus. Pas seulement des tests de logique.
- Suites partagées/télé/backend/web, compilation web et APK. Réinstaller les prérequis Android nécessaires : l'environnement publié n'a validé que web/backend.
- Essai réel TCL indispensable : image plein écran, lecture MKV, son/sous-titres, reprise après veille, navigation rapide et absence de régression Direct/Séries.

## Livraison et décisions laissées au constructeur

Construire sur plan-4-films avec tests et relecture indépendante ; tenir le journal et la passation à jour et sauvegarder les étapes. Le constructeur choisit les classes, RPC exactes, migrations de cache, réutilisations de composants et détails visuels dans le thème existant. Éviter une refonte générale du lecteur des séries.

La version suivante est proposée en 0.5.0, versionCode 6 (à revérifier avant livraison). Préparer l'APK signé avec la clé existante et le comparer au certificat attendu. Aucun déploiement ni migration de production sans accord distinct du propriétaire ; ne pas republier l'environnement cloud. Fusion après essai télé réussi selon son accord permanent. Cette conception n'affirme pas que les tests ou la compilation Android ont déjà été exécutés.

Relecture indépendante du 15 novembre : aucune question importante restante pour établir le plan. Validation du document reçue : « Oui, tu peux y aller. ».
