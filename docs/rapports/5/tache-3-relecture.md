# Étape 5 — tâche 3 : relecture indépendante

16 novembre 2026. Relecture de `4352166..e8aeaaf`, du brief tâche 3, de la conception validée et du rapport de réalisation. Périmètre : remplisseur shared, additions de fraîcheur des dépôts et tests. Aucun branchement TV attendu avant la tâche 5. Aucun code produit ni commit modifié.

## Verdict

**Conformité : une réserve importante à corriger (I1). Qualité : bonne structure et tests pertinents, approbation après correction ciblée.** Aucun défaut critique relevé. Les contrats de masquage, de sérialisation et de recalcul pendant une demande suspendue sont respectés dans le modèle d’exécution prévu. La conservation des identifiants globaux par `categories(emptySet())` est conforme à la décision du contrôleur : il s’agit des métadonnées globales, pas des listes de titres masqués.

## Constats

### Critical

Aucun.

### Important — I1 : une catégorie rafraîchie avec succès ne peut plus être rafraîchie dans la même session

**`apps/shared/src/commonMain/kotlin/home/iptv/shared/search/CatalogFiller.kt:138` et `:144`**, avec conservation du jeu à `:90`.

Le jeu `attempted` reçoit toute catégorie avant la demande, même quand celle-ci réussit. Le choix suivant exige ensuite que cette catégorie n’appartienne pas au jeu ; aucune réussite ne l’en retire. Scénario déterministe : démarrer, laisser les listes se remplir, conserver la session active pendant plus de 24 heures (la conception prévoit notamment de continuer pendant la lecture). Les horloges des dépôts rendent alors ces listes périmées. Le recalcul publie `done = 0`, la liste de catégories peut être rafraîchie, mais les listes de titres restent exclues définitivement par `attempted`. Le catalogue reste « en préparation » et ses titres ne se mettent plus à jour jusqu’à un `stop` puis `start` ; rappeler seulement `start` ne suffit pas puisqu’il est idempotent sur le worker actif.

Cela contredit le parcours des catégories non fraîches et le commentaire de suivi des expirations à `:160`. La protection contre les répétitions doit retenir les échecs (y compris un repli périmé), sans interdire un futur rafraîchissement après expiration d’une réussite. Préserver également le comportement sans boucle après panne.

**Vérification ciblée à ajouter :** remplir, avancer l’horloge de la fixture de 25 h sans arrêter le remplisseur, laisser passer les demandes, vérifier une deuxième demande de titres par catégorie et un avancement de nouveau complet. Les tests existants d’expiration (`CatalogFillerTest.kt:159` et `:172`) arrêtent puis redémarrent le remplisseur ; ils réinitialisent donc le jeu et ne détectent pas ce défaut. Constat établi par lecture et trace du contrôle de flux ; aucun test supplémentaire exécuté par cette relecture.

### Minor

Aucun défaut supplémentaire retenu.

## Points solides

- Les masquages sont relus après attente du verrou, immédiatement avant chaque demande de contenu (`CatalogFiller.kt:142–148`) ; les dépôts conservent leur garde. Les préférences nulles empêchent également les demandes de catégories (`:114`, `:116`). Aucune liste de titres masquée n’est demandée par ce code.
- Un verrou commun couvre les lectures des écrans et celles du fond. Le compteur d’écrans en attente empêche le fond de reprendre avant les écrans déjà inscrits ; une demande déjà en vol peut finir. La pause de fond reste hors du verrou.
- L’annulation est relancée explicitement. `stop` annule le travail en vol ; le verrou continue de protéger une reprise immédiate et les demandes d’écran restent indépendantes.
- Les deux nouvelles méthodes de fraîcheur sont strictement locales et réemploient les TTL des dépôts. Le contrôle après lecture distingue correctement réussite fraîche et repli périmé ; ce dernier ne produit pas `changed` ni faux avancement.
- Le recalcul indépendant permet de publier le total du profil courant pendant une requête lente, y compris pour une catégorie nouvellement autorisée. Les identifiants complets évitent de confondre l’ancien filtre avec le catalogue global.
- Les tests utilisent les vrais dépôts et SQLite en mémoire, avec source suspendable et comptage de concurrence ; ils couvrent les cas difficiles de panne, d’annulation, de priorité écran et de profil modifié.

## Contrats à conserver aux tâches 4 et 5

- Une seule instance par processus, et toutes les opérations fournisseur des écrans concernées passent par son `screenFirst`. Ce wrapper sérialise le bloc fourni : il ne relit pas lui-même les préférences et ne corrige pas un masque capturé trop tôt par l’appelant. Relire le profil et son masque dans le bloc avant l’accès au dépôt.
- Appeler `start`/`stop` depuis le même contexte de cycle de vie, typiquement Main. Ces méthodes et la référence `worker` ne constituent pas une API atomique pour plusieurs appels simultanés sur différents threads. Le scope fourni doit rester vivant entre `onStop` et la reprise.
- Les callbacks renvoient l’union du profil actuellement actif et de « Tout le monde », ou null tant que ces préférences sont inconnues ; ils doivent pouvoir être lus dans le contexte du scope.
- `changed` est une invalidation sans rejeu, pas un journal durable. Relire le cache filtré au démarrage du consommateur, après changement de profil/masque et lors des invalidations ; une requête en vol peut avoir rempli le cache pour l’ancien profil.
- La pause de 500 ms s’applique aux demandes de fond. Les écrans passent immédiatement après la demande déjà en vol et ne sont pas retardés par cette pause. Une opération `screenFirst` doit elle-même éviter plusieurs demandes concurrentes dans son bloc.
- `FillProgress(0, 0).complete` vaut vrai, y compris si les préférences sont inconnues. L’UI doit conserver son état distinct « préférences inconnues », conformément à l’API du plan.

## Validation et limites

Les résultats fournis de la réalisation sont **14 tests ciblés / 304 tests shared verts** ; aucune suite n’a été relancée pour cette relecture, conformément au mandat. Ces preuves ne couvrent ni l’expiration continue I1, ni le futur branchement TV. Le cycle de vie, l’unicité effective et l’exhaustivité des wrappers d’écran seront à vérifier à la tâche 5. Aucun changement de production, backend, schéma ou interface TV dans cette tâche.


## Relecture du correctif I1 — tour 1, `f7f0d6b`

**Verdict actualisé : I1 résolu ; tâche 3 approuvée pour son périmètre shared. Aucun constat Critical, Important ou Minor restant.** Cette conclusion remplace la réserve du verdict initial ; les contrats d’intégration ci-dessus restent applicables aux tâches 4 et 5.

Relecture limitée au diff correctif fourni et au complément du rapport de réalisation. À `CatalogFiller.kt:152`, la catégorie est retirée de `attempted` seulement si `refreshed` est vrai, après contrôle local de fraîcheur du dépôt. Une réussite reste exclue des nouvelles demandes tant que son TTL est valide par la garde `fresh`, puis redevient éligible après expiration. Une exception ou un repli périmé laisse au contraire la catégorie dans `attempted`, conservant l’absence de répétition avant le prochain démarrage. La sérialisation, les masquages et la pause ne sont pas modifiés. Aucun nouveau défaut relevé dans ce diff.

Le test `expiration_apres_succes_rafraichit_sans_redemarrer` (`CatalogFillerTest.kt:229`) reproduit précisément le scénario I1 : deux sections remplies, horloge avancée de 25 h sans `stop`, deuxième demande de chaque liste et de chaque catégorie, progression complète et concurrence maximale de 1. Les preuves fournies indiquent **15 tests / 1 échec avant correction**, puis **15/15 ciblés et 305/305 shared verts après correction**. Aucune suite relancée pour cette relecture ; aucun code produit ni commit modifié.
