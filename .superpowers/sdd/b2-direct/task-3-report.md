# Tâche 3 B2 — Copies sur téléphone, API et démonstration

Statut : DONE. Base `9b04f24`, branche `plan-3b2-direct`. Périmètre limité à la tâche 3 ; aucune production, migration, publication ou push. PASSATION, roadmap, journal et plan laissés au contrôleur.

## Changements

- `groups-api.ts` : interfaces demandées `loadGroupSnapshot`, `loadGroupSources`, `saveGroups`, `setGroupCopies`, adaptation explicite snake_case SQL / camelCase moteur. Cache mémoire par client et foyer, remplacé uniquement après une lecture paginée entièrement réussie.
- `groups-view.ts` : module indépendant ; `view.ts` reçoit seulement la route Copies et masque sa recherche B1 quand la recherche locale Copies est active. Catégories reste l'onglet initial B1.
- Recherche locale sur noms/catégories normalisés une fois, sélection multisource conservée entre recherches, liste fenêtrée (14 lignes, rangées de 96 px, zone de 420 px), nom original/catégorie/nom du groupe/état corrigé ou automatique. Aperçu des modifications avant Enregistrer ; Annuler n'écrit rien. Nom du groupe modifiable.
- Réunir fige aussi les restes des familles automatiques affectées. Une correction existante garde son UUID lorsqu'elle est remplacée ; une séparation crée les nouveaux groupes. Sources disparues et exclusions des corrections survivantes conservées lors d'une édition partielle. Séparer répartit les exclusions sans produit cartésien ; les références anciennes sont conservées une fois et chaque enfant protège sa famille.
- Restaurer retire uniquement la correction choisie ; une confirmation explicite signale les autres corrections partageant ses familles. Leurs exclusions restent identiques.
- En cas de conflit : message Recharger, aucun écrasement. En cas d'erreur : jamais de message Enregistré. Cache ancien/incomplet affiché avec avertissement et toutes écritures désactivées ; Réessayer/Recharger disponible. Boutons Annuler/Recharger désactivés pendant la sauvegarde pour ne pas changer le brouillon en vol.
- Éditeur sur la **copie figée complète**, sources masquées incluses et sélectionnables : aucune requête fournisseur. Marquage gris et « Masquée sur la télé » lorsque les métadonnées sont disponibles. Les corrections communes sont accessibles en Tout le monde ; seul le réglage `group_copies` demande un profil. Clarification explicite du contrôleur appliquée et testée après un premier filtrage trop strict.
- `demoDb` implémente pages catalogue/corrections, édition atomique, révisions/conflits, erreurs de correction invalide, préférences/visibilité et réglage profil. Données inventées, sans réseau. Favoris B1 et identifiants des sources intacts.
- `jsdom` et `@types/jsdom` ajoutés uniquement en développement pour les tests DOM réels des boutons, messages, XSS et fenêtre de rendu ; lockfile racine cohérent. Aucune dépendance runtime supplémentaire.

## Contrats utilisés

- `live_group_page({h,after_id,lim:500})` → `{rev,rules_version,groups,next_id}`.
- `live_catalog_page({h,after_position,after_id,lim:1000})` → `{catalog_rev,channels,next_cursor}` ; les deux composantes du curseur sont reprises ensemble.
- À chaque page, comparaison de `rev` / `catalog_rev`. Au changement, résultat partiel jeté et reprise première page, **trois tentatives maximum**, puis erreur Réessayer. Cache complet précédent conservé après erreur réseau ou incohérence.
- `live_group_edit({h,expected_rev,replacement:{remove_ids,groups:[{id,title,source_ids,blocked_auto_keys}]}})` → `{rev}`. Les identifiants d'origine restent ceux du catalogue/favoris.
- `set_group_copies({p,enabled})` ; valeur par défaut lue via `profile_prefs` (true en absence du champ).
- Visibilité informative du profil via `profile_prefs`; en Tout le monde, catégories globalement masquées via `org_categories`. Ces informations ne filtrent jamais les sources dans l'éditeur de corrections.
- Les erreurs affichées sont génériques/françaises : aucun message serveur brut ou identifiant sensible.

## RED / GREEN et sorties

Référence avant modification :

```
npm test -w web/config
Test Files 5 passed (5)
Tests 90 passed (90)
```

Premier RED, tests demandés écrits avant les nouvelles fonctions :

```
npm test -w web/config -- test/org-groups-api.test.ts test/org-groups-view.test.ts
Failed Suites 2
Cannot find module '../src/org/groups-api'
Failed to resolve import '../src/org/groups-view'
```

Ce premier RED est une absence des modules demandés (aucune panne d'environnement). Après implémentation, la sélection du test a dû relire les checkbox après chaque rendu DOM : cliquer une ancienne liste de nœuds détachés ne représente pas une deuxième interaction réelle. Les six scénarios initiaux passent avec cette correction du pilote de test.

Cycles RED supplémentaires avec assertions fonctionnelles, avant leurs corrections :

- Nom corrigé visible après rechargement : attendu « Nom corrigé fictif », reçu seulement nom original/état → ajout du nom du groupe dans la ligne.
- Séparation volumineuse : attendu ≤ 2 000 références pour 1 000 sources, reçu 1 000 000 → répartition linéaire des clés entre enfants sans perdre les exclusions.
- Copie masquée et groupe commun interprofils : attendu écran Copies en Tout le monde, reçu invitation à choisir un profil → accès commun rétabli et retrait du filtrage des sources masquées.

Dernier ciblé : **19 tests passants** (5 API + 14 DOM). Couvre aussi pagination/révision/reprise, trois lectures incohérentes, cache après panne, formes SQL exactes, restauration confirmée, sources disparues, visibilité indisponible, XSS et 100 000 sources.

Validation finale après la dernière modification fonctionnelle :

```
npm test -w web/config
Test Files 7 passed (7)
Tests 109 passed (109)

npm run build -w web/config
TypeScript : succès
Vite : 62 modules transformed, built in 108ms
index JS : 265.54 kB / gzip 70.71 kB

git diff --check
[aucune sortie, succès]
```

## Navigateur iPhone et preuves locales ignorées

Chromium système via Playwright, appareil `iPhone 13`, Vite local port 5173, `?demo`. Toutes routes hors localhost/127.0.0.1 bloquées ; aucun compte ni service réel.

- Recherche → sélection de deux catégories Sports/Cinéma → réunir → renommer → aperçu → enregistrer → recharger → séparer → enregistrer → recharger → restaurer → Favoris : **PASS**, cinq favoris B1 présents, zéro appel externe et zéro erreur JavaScript.
- Tout le monde → recherche Québec → sources masquées sélectionnables → correction → profil Frère retrouve Corrigé → désactivation du regroupement de ce seul profil : **PASS**.
- Injection locale de 100 000 chaînes inventées : chargement/rendu **437 ms** sur ce conteneur ; **14 lignes DOM**, dernière source atteinte après défilement. Nom `<img onerror=...>` visible comme texte, zéro élément img, aucune exécution. Après panne simulée : cache toujours présent, checkbox désactivées et avertissement explicite. Largeur page = viewport = 391 px.

Artefacts laissés **ignorés, non commités**, dans `.superpowers/sdd/b2-direct/browser-task3/` : `check.cjs`, `volume.cjs`, `log.json`, `volume-log.json`, captures `iphone-preview.png`, `iphone-restored.png`, `iphone-favorites.png`, `iphone-common-hidden.png`, `iphone-volume-cache.png`.

## Limites et transmission

- Cache complet conservé en mémoire de la page, pas de persistance locale supplémentaire. La démo se réinitialise à un rechargement intégral du document (comportement B1 existant) ; Recharger dans Copies relit le faux serveur en mémoire.
- Les sources disparues restent dans les corrections et ne s'effacent pas lors d'une édition partielle ; la liste des sources représente la copie publiée actuelle.
- Tout le monde dispose des métadonnées de catégories masquées ; les flags de chaînes masquées sont disponibles dans la lecture d'un profil. L'édition n'en dépend pas pour sélectionner les sources.
- Recherche par sous-chaîne sur index normalisé en mémoire ; pas de nouvel index serveur ni de dépendance runtime.
- Pas de relecture indépendante ni de sous-agent, conformément à l'instruction du contrôleur pour cette exécution. Contrôle personnel des contrats, chemins d'erreur, conservation des identifiants/exclusions et volume effectué. Relecture par le contrôleur attendue après livraison.
- Commit de livraison : `feat(web): corriger les copies des chaines depuis le telephone` ; identifiant renvoyé au contrôleur et consultable par `git log -1 --format=%H` sur cette branche. Aucun push.

## Correctif de relecture — round 1/5

Base du correctif : `df644939`. Finding Important du contrôleur : `paint()` remplaçait le contrôle focalisé ; sélection, séparation/restauration et défilement renvoyaient le focus sur BODY. Défaut reproduit avant correction.

Modification limitée à la gestion du focus du rendu fenêtré : avant remplacement DOM, mémoriser l'identifiant stable de la source et le type de contrôle actif. Après rendu, focaliser le même contrôle de cette source avec `preventScroll`. Si le bouton Restaurer disparaît, revenir à la case de la même source. Si la source sort de la fenêtre, focaliser le conteneur de liste existant pour reprendre localement avec Tab, sans remonter au début de page. Aucun changement aux contrats RPC ou aux transformations de corrections.

Tests ajoutés avant correction : sélection garde le focus et permet le contrôle suivant ; Restaurer garde la même source lorsque son bouton disparaît ; défilement conserve le contrôle encore rendu ou revient au conteneur hors fenêtre.

RED :

```
npm test -w web/config -- test/org-groups-view.test.ts
Test Files 1 failed (1)
Tests 3 failed | 14 passed (17)
selection garde le focus sur sa source et permet le controle suivant : reçu BODY, attendu INPUT
restaurer garde le focus dans la meme source si le bouton disparait : reçu BODY, attendu INPUT
defilement garde le controle actif ou revient a la liste hors fenetre : reçu BODY, attendu INPUT
```

GREEN après correction puis formatage :

```
npm test -w web/config -- test/org-groups-view.test.ts test/org-groups-api.test.ts
Test Files 2 passed (2)
Tests 22 passed (22)
git diff --check
[aucune sortie, succès]
```

Contrôle Chromium/iPhone ciblé, touches réelles Playwright, `?demo`, routes externes bloquées :

```
node .superpowers/sdd/b2-direct/browser-task3/keyboard-round1.cjs
PASS keyboard: Space retains checkbox -> Tab reaches separate -> Enter retains separate after stage
-> Tab reaches new restore -> Removed restore falls back to source checkbox
-> Tab continues after restore -> Tab reaches next source -> Window exit focuses local list
-> Tab resumes in new window -> Space selects next window source; external=0 errors=0
```

Script, capture `iphone-keyboard-round1.png` et `keyboard-round1-log.json` laissés localement dans `browser-task3/`, ignorés et non commités. Suites globales/build non relancés conformément à la demande du contrôleur (déjà verts avant ce correctif ciblé). Finding Minor de séparation laissé hors périmètre pour la relecture finale. Commit correctif distinct : `fix(web): conserver le focus dans les copies fenetrees`. Aucun push ni modification des documents du contrôleur.
