# 4B — Tâche 4 : organisation des films sur l'iPhone

Date : 15 novembre 2026. Branche : `plan-4-films-web` (partie de `plan-4-films`). Seuls `web/config` et la documentation sont touchés ; rien dans `apps/`.

## Ce qui a été fait

- L'onglet **Catégories** de la page iPhone propose maintenant **Direct / Séries / Films**.
- Films utilise le même écran que les deux autres : masquer ou réafficher une catégorie, réordonner, « tout masquer sauf ce que j'ai coché », « tout réafficher », pour tout le monde ou pour un profil.
- Pour « tout le monde », la confirmation parle de films : « Cette catégorie de films ne sera plus téléchargée par la télé, pour tout le monde. Continuer ? » (et « Ces N catégories de films… » au pluriel). Pour un profil, aucune confirmation, comme avant.
- Liste vide : « Ouvre la section Films sur la télé une fois pour envoyer la liste. »
- Comme pour Séries : pas de compteur de chaînes, et toucher une catégorie n'ouvre rien. Ma liste se gère sur la télé seulement ; aucun lecteur ni catalogue de films sur l'iPhone.
- `?demo` : 12 catégories de films inventées (`m1` à `m12`, dont une masquée pour tout le monde), entièrement en mémoire, séparées du direct et des séries.
- Direct et Séries inchangés. L'onglet « Lecture » reste caché sans `VITE_PLAYBACK_ADMIN`.

## Comment (sans dupliquer le moteur)

L'ancien code choisissait entre deux cas (« séries ou non »). Il est remplacé par deux tableaux à trois entrées ; l'écran lui-même n'a qu'un seul chemin.

| Fichier | Changement |
|---|---|
| `src/org/categories.ts` | `CategoryKind = 'live' \| 'series' \| 'movies'` ; tableau `CATEGORY_KINDS` (libellé, complément « de séries » / « de films », compteur de chaînes oui/non, message de liste vide). `confirmText` et `EMPTY_TEXT` en découlent. |
| `src/org/api.ts` | Tableau `CATEGORY_RPC` (noms des RPC et type de masque par section) et trois fonctions génériques `orgKindCategories`, `orgKindHideAllExcept`, `orgKindSetOrder`. Les anciens noms (`orgCategories`, `orgSeriesCategories`, …) restent, comme simples raccourcis ; ajout de `orgMovieCategories`, `orgMovieHideAllExcept`, `orgSetMovieCategoryOrder`. `orgSetHidden` accepte `vod_category`. |
| `src/org/view.ts` | Le sélecteur est construit depuis `CATEGORY_KINDS` ; `renderCategoryList` appelle les fonctions génériques avec la section. Plus aucun test « séries ou non ». |
| `src/org/demo.ts` | Les fausses catégories de séries et de films partagent une même fabrique (`shelf` / `shelfHandlers`). |

RPC utilisées, noms et arguments exacts du contrat (migration 0012) :

- `org_movie_categories { h, p }`
- `org_set_hidden { h, p, item_type: 'vod_category', ids, hidden }`
- `org_movie_hide_all_except { h, p, keep }`
- `org_set_movie_category_order { h, p, ids }`

## Preuve rouge puis vert

Tests écrits d'abord : `test/org-movies.test.ts` (18 tests) et un test ajouté dans `test/org-tabs.test.ts`.

**Rouge** (avant le code) : les 18 tests films échouent, par exemple
`TypeError: orgMovieCategories is not a function`,
`expected 'Cette catégorie ne sera plus téléchar…' to be 'Cette catégorie de films ne sera plus…'`,
`expected [ [ 'live', 'Direct' ], …(1) ] to deeply equal [ [ 'live', 'Direct' ], …(2) ]`.
Les 124 tests existants restaient verts.

**Vert** (après le code) : `npm test -w web/config` → **11 fichiers, 143 tests réussis** (124 d'origine + 18 films + 1 onglets). `npm run build -w web/config` (vérification TypeScript puis Vite) réussit.

Ce que couvrent les tests films :

- sélecteur : trois boutons dans l'ordre, libellé d'accessibilité, bascule Films → Séries → Direct avec la bonne RPC et les bonnes lignes ;
- arguments exacts des quatre RPC ; lecture des lignes ; messages d'erreur du serveur traduits ;
- masquer pour tout le monde : confirmation au texte exact, refus = aucun appel, accord = appel `vod_category` ;
- masquer puis « tout réafficher » pour un profil, sans confirmation ;
- « tout masquer sauf » pour un profil (sans confirmation) et pour tout le monde (confirmation avec le nombre) ;
- ordre enregistré avec la RPC films, jamais celle du direct ou des séries ;
- liste vide ; erreur au chargement ; erreur à l'écriture (message affiché, liste rechargée, case remise) ;
- ni compteur ni ouverture de catégorie ;
- démo : catégories locales, masquage par profil, ordre, « tout masquer sauf », sans effet sur le direct ni les séries.

## Vérification dans un vrai navigateur

`dist` servi en local par `vite preview`, Chromium piloté par Playwright, écran iPhone 390 × 844, `deviceScaleFactor` 1, adresse `/?demo` :

- sélecteur affiché : Direct, Séries, Films ; Films sélectionné ;
- 11 lignes de films dans la fenêtre (liste de 12), sans compteur de chaînes ; compteur « 11 affichées sur 12 · Toi » ;
- masquer « Action » : ligne grisée, « 10 affichées sur 12 » ; réordonner puis Terminer : « Ordre enregistré. », nouvel ordre `m2, m1, m3` ;
- retour sur Séries (10 lignes) et Direct (« 5 chaînes ») : toujours bons ;
- **0 erreur dans la console**, 0 requête hors de la machine locale, pas de défilement horizontal.

## Limites

- Aucun essai contre le vrai serveur (interdit par les règles) : la conformité au contrat repose sur les noms et arguments lus dans la migration 0012 et vérifiés par les tests. La migration 0012 doit être appliquée en production avant que Films fonctionne sur la vraie page ; d'ici là, choisir Films affichera un message d'erreur, sans gêner Direct ni Séries.
- La liste reste vide tant que la télé n'a pas ouvert la section Films une première fois (tâche 3).
- Rien n'est publié, aucune version n'est changée.
- `PASSATION.md` n'est pas modifié ici, pour ne pas entrer en conflit avec l'agent qui travaille sur `plan-4-films` : à mettre à jour lors de la fusion.
- La revue indépendante de la tâche reste à faire par le coordinateur.

## Correction après relecture indépendante (reprise Codex)

La démo partageait l'ordre Films entre tous les profils. Test de régression `ordre_par_profil_avec_heritage_de_l_ordre_global` ajouté avant correction : rouge (1 échec, 18 autres tests films verts), puis correction du stockage de l'ordre par portée/profil et héritage de l'ordre global. Les identifiants absents restent à la fin dans l'ordre fournisseur. Suite web entière : 144 tests verts ; compilation TypeScript/Vite réussie. Logs locaux `/workspace/iptv-work/4b/demo-order-{red,green}.log`. Aucun appel production. Le même helper sert aux Séries.
