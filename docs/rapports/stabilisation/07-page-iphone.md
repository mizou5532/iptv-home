# Stabilisation — Zone 7 : la page iPhone (bilan)

*18 nov. 2026, après la 0.31.0. Relecture de `web/config/src/` (main, flow, profile-view, org/view, groups-view, mediaview, avatar/sticker/studio/wallpaper, look) et parcours de la page en ligne en mode démo, au format iPhone 13 (Playwright).*

## Ce qui est solide

- **Sécurité de l'affichage** : tous les noms venant du fournisseur ou des profils (chaînes, catégories, films, séries, playlists) passent par `esc()` avant d'être insérés dans la page ; les messages passent par `textContent`. Un nom piégé ne peut pas exécuter de code (vérifié sur toutes les insertions HTML, 89).
- **Grandes listes** : listes de chaînes et de copies virtualisées (seules les lignes visibles sont dessinées), chargement par pages, recherche attendue (fin de frappe), écrans nettoyés à chaque changement (écouteurs de défilement retirés, réponses d'un ancien écran ignorées), écritures d'un écran mises en file dans l'ordre des touches.
- **Photos** : photo d'iPhone réduite avant tout travail (1 600 px pour la photo et l'autocollant, 2 400 px pour le fond, éléments de l'atelier ramenés à 1 024 px, 30 éléments au plus) ; GIF limités (5 Mo, 3 Mo) ; adresses temporaires libérées.
- **Envois** : bouton désactivé pendant l'envoi (pas de double enregistrement) ; messages d'erreur en français ; un thème inconnu reçu du serveur retombe sur une valeur sûre.
- **Parcours en ligne** (mode démo, 18 nov.) : Profils, Organiser (Copies, Catégories, Favoris, Playlists), Réglages, chaque profil, et les 9 réglages d'un profil (Thème et couleur, Fond d'écran, Photo, Autocollant, Séries et films, Langues, Équipes favorites, Réglages du sport, Organiser les chaînes) : **0 erreur**. Seuls les logos d'équipes (`a.espncdn.com`) ne se chargent pas depuis l'environnement de travail de Claude (réseau limité) ; sans lien avec la page.
- Poids : 110 Ko compressés, 265 tests.

## Constats

Aucun défaut à corriger trouvé. Pas de nouvelle version.

Pour plus tard (entretien, pas de bogue) : `org/view.ts` fait 800 lignes ; à découper seulement si on doit beaucoup le modifier. L'écran caché « Protection de la lecture » (`playback-admin.ts`) dépend de la décision sur la migration 0010 (zone 8).

## Suite

Zone 8 : Supabase (sécurité, vitesse, migration 0010).
