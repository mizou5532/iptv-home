# Stabilisation — Zone 6 : démarrage, navigation, profils, thèmes, mises à jour, recherche (bilan)

*18 nov. 2026, après la 0.30.4. Relecture seule (sauf le nouveau thème demandé). Fichiers relus : `startup/` (StartupViewModel, WhoIsWatchingScreen, appairage), `update/` (Updates, AndroidUpdates), `search/` (SearchViewModel, SearchRuntime, SearchScreen) et `shared/search/CatalogFiller.kt`, `App.kt`, `MainActivity.kt`, `theme/`.*

## Demande du propriétaire faite : thème « Doux nuit »

« Qui regarde ? » avait deux thèmes : **Doux** (clair, par défaut) et **Aurore** (que le propriétaire n'aime pas). Ajout de **Doux nuit** : exactement le même dessin que Doux (bulles de couleur qui tournent autour des photos, anneau blanc, formes qui flottent, étoile), sur fond nuit ; texte clair ; anneau du profil sélectionné en rose vif (un anneau clair se perdait sur le blanc). Décors de fête compris (dessinés en version sombre).
- Télé : `WhoIsWatchingScreen` (`DouxPalette`, `DOUX`, `DOUX_NUIT`, `WELCOME_DOUX_NUIT = "doux_nuit"`). Une ancienne version de l'app qui reçoit `doux_nuit` affiche simplement Doux.
- Page iPhone : Profils → « Écran Qui regarde ? » → **Doux nuit** (`look.ts` : `WELCOME_THEMES`, `welcomeOf` ; aperçu `.wprev-doux_nuit`).
- Captures : `docs/rapports/captures/doux-nuit/` (Doux, Doux nuit, Doux nuit à Noël). Tests : `SeasonScreenTest` (toutes les fêtes en Doux nuit, choix d'un profil), `look.test.ts`.

## Ce qui est solide

- **Démarrage** : chaque appel au serveur est borné (4 s) ; hors ligne, les profils enregistrés s'affichent ; sans rien d'enregistré, message clair et bouton Réessayer ; l'appairage redemande un code quand il expire.
- **Mises à jour** : `version.json` mal formé ignoré, taille et empreinte SHA-256 vérifiées avant d'installer, une seule opération à la fois, permission Android gérée (Fire Stick : réglage global), échec affiché avec Réessayer.
- **Recherche** : saisie attendue avant calcul, calcul hors du fil de l'écran, réponses périmées ignorées, masqué gagne ; le remplisseur passe toujours après les demandes d'écran.

## Constats

### Gênant

**1. Le remplissage de la recherche continue pendant qu'on regarde une vidéo (probable cause de saccades sur le Fire Stick).**
Pour que la recherche trouve tous les titres, l'app télécharge en arrière-plan, une par une, toutes les catégories de séries et de films (plusieurs centaines ; une fois par jour), puis les enregistre. Ça continue pendant la lecture d'un film ou d'une chaîne : téléchargements de gros fichiers et écritures sur le disque en même temps que la vidéo. Sur la TCL c'est probablement invisible ; sur le Fire Stick 1re génération (appareil faible), ça peut faire sauter des images ou ralentir le démarrage d'une chaîne.
→ Proposition : mettre le remplissage en pause pendant toute lecture (Direct, Sport, films, séries) et le reprendre 3 s après.

### Détails

**2. Démarrage hors ligne gardé toute la session.** Si la télé démarre avant que le Wi-Fi soit prêt, « Hors ligne — profils enregistrés » reste affiché et les profils (photo, thème) ne sont pas relus tant que l'app n'est pas relancée. Tout le reste fonctionne (les réglages sont relus à part). → Nouvel essai discret toutes les 30 s tant qu'on est hors ligne.

**3. Vérification des mises à jour pendant la veille** (toutes les 6 h, minuscule). → Seulement quand l'app est à l'écran (elle est déjà refaite au retour, au plus une fois par heure).

**4. Test du clavier de la recherche fragile** (`SearchDpadTest.clavier_qwerty…`, échoué 2 fois en suite complète depuis le 17 nov., jamais seul) : attente de 5 s trop courte quand la machine de test est chargée. Ce n'est pas un bogue de l'app. → Attente portée à 10 s comme les tests voisins.

### À décider (optimisation, risque)

**5. Compactage de l'app (R8, constat 2 de la zone 0).** ≈ 38 Mo de code embarqué, compacté il deviendrait plus léger et plus rapide à démarrer, surtout sur le Fire Stick. Mais une règle oubliée fait planter l'app au démarrage, et les tests d'ici ne tournent pas sur la version compactée : il faudrait un essai prudent sur la TCL et le Fire Stick. Recommandation : seulement si le Fire Stick te paraît lent à ouvrir l'app.

## Ordre proposé

Publier Doux nuit avec 1, 2, 3 et 4 dans la même version ; 5 sur ta décision.
