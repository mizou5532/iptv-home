# Tâche 7 : organiser les catégories de séries sur l'iPhone

## Réalisé
- Onglet Catégories : sélecteur « Direct / Séries » (puces). En Séries, même écran que le direct (masquer, réordonner, « Choisir » puis « Tout masquer sauf », « Tout réafficher »), branché sur les RPC de la migration 0011 avec leurs noms d'arguments exacts :
  `org_series_categories(h, p)`, `org_set_hidden(h, p, item_type='series_category', ids, hidden)`, `org_series_hide_all_except(h, p, keep)`, `org_set_series_category_order(h, p, ids)`.
- Confirmation « tout le monde » partagée avec B1 (`confirmText(..., kind)`), texte série : « Cette catégorie de séries ne sera plus téléchargée par la télé, pour tout le monde. Continuer ? » / « Ces N catégories de séries … ».
- Liste vide : « Ouvre la section Séries sur la télé une fois pour envoyer la liste. »
- `?demo` : 10 catégories de séries inventées (dont une masquée pour tout le monde) et les 3 RPC série.
- Onglet « Lecture » inchangé (toujours derrière VITE_PLAYBACK_ADMIN). « Ma liste » : non fait (optionnel). Rien sous apps/.
- Le rendu du direct est réutilisé (`renderCategoryList(ctx, kind, root)`) ; en Séries, pas d'ouverture de catégorie (pas de liste de séries côté web) et pas de compteur de chaînes.

## TDD
- RED (src remisé) : `npx vitest run test/org-series.test.ts` -> 6 failed (6) (fonctions/sélecteur absents).
- GREEN : 6 passed (arguments exacts des 4 appels, confirmation, liste vide, profil sans confirmation + « tout masquer sauf », ordre).

## Vérifications
- `npm test -w web/config` : 10 fichiers, 124/124 passent.
- `npm run build -w web/config` : OK.
- Playwright (iPhone 13, deviceScaleFactor 1) `?demo` > Catégories > Séries : 7 lignes rendues, 0 erreur console. Capture : `docs/rapports/captures/4a-tache7-series-iphone.png`.

## Fichiers
web/config/src/org/{api,categories,demo,view}.ts ; web/config/test/org-series.test.ts.

## Remarques
- `view.ts` grossit (~750 lignes) ; pas de scission faite.
- Le choix Direct/Séries est gardé le temps de la session seulement (variable de module).
- La recherche globale du haut reste « Chercher une chaîne » (live uniquement).
