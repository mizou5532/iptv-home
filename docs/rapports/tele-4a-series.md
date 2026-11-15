# Essai télé 0.4.0 (TCL QM85) — 15 novembre 2026

Retour du propriétaire, mot pour mot résumé :

1. **Télécommande (direct)** : pour passer d'une catégorie aux chaînes, il faut appuyer sur OK ; les flèches gauche/droite font « disparaître » le curseur (le focus se perd).
2. **Écran noir au plein écran (direct)** : 1er OK = aperçu en direct ; 2e OK = plein écran **noir** ; il faut appuyer encore sur OK pour que l'image apparaisse. Se répète sur chaque chaîne.
3. **Séries** : seulement quelques séries apparaissent ; la fiche s'ouvre, mais OK sur un épisode ne lance **rien**.
4. Plein d'autres petits problèmes, non détaillés.
5. **Guide** : « Programme indisponible » presque partout.
6. **« Mise à jour »** reste affiché en haut de l'écran plus de 5 minutes (ne s'efface jamais).
7. **Séries** : une vingtaine seulement, dans deux catégories écrites en arabe.
8. **Direct à l'ouverture** : parfois aucune chaîne, seulement « Favoris » (vide) ; il faut bouger la télécommande pour que tout s'affiche, puis OK pour changer de catégorie.
9. **Lenteur** : chaque catégorie met longtemps à charger quand on passe dessus (catalogue pas encore trié). Le propriétaire veut quelque chose de rapide et fluide.

Verdict du propriétaire : « Je n'aime vraiment pas ce que je vois. » Fusion dans main **non** autorisée.

## Enquête (Claude)

En cours — voir `docs/rapports/journaux/plan-4-journal.md`.

## Essais demandés au propriétaire (même soir)

| Essai | Résultat |
|---|---|
| Plein écran, attendre 15 s | Son OK, **image noire** même après 15 s. |
| Plein écran, flèche haut/bas | Change de chaîne, **l'image s'affiche** tout de suite. |
| OK sur un épisode | Bref écran noir (fraction de seconde), **retour aussitôt à la fiche**, rien ne joue. Pareil à chaque essai. |
| OK maintenu 2 s sur un épisode | Marqué « vu » : fonctionne. |
| Changer de profil puis revenir | « Mise à jour » **toujours affiché** ; les chaînes apparaissent après ~5 s. |
| Guide d'une grande chaîne connue | « Programme indisponible » pour maintenant et ensuite. |
| Séries, tout en bas | **2 rangées** d'environ 10 séries. |

Analyse de Claude (à confirmer) : l'image n'apparaît que lorsque quelque chose est dessiné par-dessus la vidéo (commandes après OK, « Connexion… » au changement de chaîne) ; le lecteur repris de l'aperçu vers le plein écran ne redessine pas sa surface. L'épisode se ferme sans doute parce que l'écran de lecture lit un état « au repos » périmé juste après son ouverture.

## Essai télé 0.4.1 (15 novembre 2026)

Verdict du propriétaire : **tous les problèmes signalés fonctionnent maintenant**, sauf le guide (« Programme indisponible »). Le guide n'est pas important pour lui : **reporté à plus tard**, sur sa décision. Fusion dans main : pas encore demandée ni accordée.
