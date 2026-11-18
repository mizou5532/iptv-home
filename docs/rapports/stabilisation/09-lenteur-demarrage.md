# Lenteur au démarrage signalée après la 0.32.0 (18 nov. 2026)

*Signalement du propriétaire : « l'application est devenue super lente, les chaînes en direct ne s'affichent plus… même chose pour le sport », puis « le direct s'est affiché, mais ça a pris presque une minute ». Photos du panneau de diagnostic : Sport « chaînes 6385 ms », Direct « Aucune chaîne pour l'instant » alors que la mise à jour est finie (105 catégories), puis une deuxième mise à jour (« 1 / 105 »).*

## Cause

Les copies d'une même chaîne (« ESPN HD », « ESPN FHD »…) sont regroupées pour n'afficher qu'une ligne. Pour cela, chaque nom de chaîne est analysé (plusieurs expressions régulières). Le catalogue compte environ **56 000 chaînes** : un regroupement complet prend 1,3 à 1,5 s sur un ordinateur rapide, **6 s ou plus sur la télé**.

Ce regroupement complet était refait de nombreuses fois au démarrage :
- par le Direct, à la lecture de la liste, puis à l'arrivée des réglages du profil, puis à l'arrivée des copies regroupées à la main, puis **toutes les 15 s pendant la mise à jour**, puis à la fin ;
- par le Sport, séparément, après chacun de ces recalculs du Direct ;
- à nouveau entièrement si l'app était relancée (retour, puis relance) : la mise à jour complète repartait de zéro.

Le processeur de la télé était saturé près d'une minute ; la liste du Direct restait vide en attendant, avec un message trompeur (« vérifie la connexion internet »).

La 0.32.0 elle-même n'a rien changé à ce chemin (vérifié : seuls la lecture vidéo et le serveur ont changé). Explication probable : le problème grossissait avec le catalogue, et la 0.32.0, installée par mise à jour, a démarré « à froid » (tout recalculé d'un coup).

## Corrections — 0.32.1

| # | Correction | Où |
|---|---|---|
| 1 | L'analyse de chaque nom de chaîne (clé de regroupement et titre) est gardée en mémoire : seul le premier regroupement fait le travail complet ; les suivants passent de 1,4 s à **0,03–0,08 s** (mesure sur 56 000 chaînes), soit 20 à 50 fois plus vite. Résultat identique (test) | `live/Sections.kt` (`cachedAutoKey`, `cachedTitle`), `shared/groups/LiveGroups.kt` (`keyOf`, `titleOf`) |
| 2 | Le Direct ne refait plus le regroupement quand seuls les favoris, l'ordre ou les playlists changent | `LiveBrowseViewModel.rebuild` (`IndexKey`) |
| 3 | Pendant une mise à jour, une liste déjà affichée n'est plus relue toutes les 15 s : relue seulement à la fin (premier lancement, liste vide : affichage progressif inchangé) | `LiveBrowseViewModel.onProgress` |
| 4 | « Chargement… » tant que la liste n'est pas lue ; jamais « Aucune chaîne… vérifie la connexion » pendant ce temps | `LiveBrowseViewModel.listShown`, `LiveBrowseScreen` |
| 5 | Une mise à jour terminée est retenue tant que l'app vit (même compte IPTV) : relancer l'app ne la refait plus ; au-delà de 6 h elle est refaite comme avant | `LiveSessions` (`doneBefore`), `IptvApp.liveCatalogDone` |

Tests : 361 partagés, 490 télé (5 nouveaux), 0 échec ; analyse Android 0 erreur.

**Essai TCL** : éteindre puis rallumer la télé (ou forcer l'arrêt de l'app), ouvrir un profil : le Direct doit afficher ses catégories en quelques secondes, le Sport ses matchs et chaînes sans attente ; le panneau de diagnostic (Info trois fois) doit montrer « chaînes » en centaines de ms au plus après le premier calcul.
