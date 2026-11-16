# 4B — Relecture indépendante de la tâche 4

Date : 16 novembre 2026. Commit relu : `2a245471d5521ccf98ddca6e140c6a0f3de0b720`.

**Verdict : qualité approuvée, avec une remarque mineure non bloquante.** Aucun défaut critique ou important identifié.

## Conformité

La section Films reprend le moteur commun de Direct et Séries : lecture, masquage individuel, tout masquer sauf, tout réafficher et ordre, avec transmission du périmètre `p`. Les libellés sont français. Films n'ouvre ni catalogue ni lecteur ; Ma liste n'est pas ajoutée à l'iPhone. Les anciens points d'entrée Direct/Séries restent disponibles.

Les quatre appels correspondent aux signatures et arguments de la migration 0012 : `org_movie_categories(h,p)`, `org_set_hidden(h,p,item_type='vod_category',ids,hidden)`, `org_movie_hide_all_except(h,p,keep)` et `org_set_movie_category_order(h,p,ids)`. Aucun alias incompatible n'est introduit.

La généralisation par tables évite de dupliquer l'écran et conserve les garde-fous de génération des chargements. La démo Films utilise uniquement les gestionnaires locaux en mémoire.

## Constats

- **Critique :** aucun.
- **Important :** aucun.
- **Mineur — ordre de démo partagé entre profils**, `web/config/src/org/demo.ts:38` (lecture à la ligne 30). `Shelf.order` est unique et le gestionnaire d'ordre ignore `a.p`. Réordonner Films pour « Toi », puis choisir un autre profil ou « Tout le monde », montre le même ordre. Le backend conserve au contraire un ordre par périmètre, avec repli sur celui du foyer. Cette limite préexistait pour Séries et est reproduite pour Films ; elle ne touche pas les écritures réelles. Amélioration conseillée : conserver les ordres par profil et l'ordre global séparément, puis vérifier le changement de profil en démo.

## Méthode et limites

Relecture statique du brief, du rapport d'implémentation, du diff et des fichiers concernés. Contrôles ciblés hors diff : **contrat RPC** dans `backend/supabase/migrations/20261115000012_movies.sql` ; **routage local de la démo** dans `web/config/src/main.ts` (`route()` substitue `demoDb` avant de monter l'organiseur, et les gestionnaires de Films n'appellent aucun service externe). Aucun défaut supplémentaire avancé hors de ce périmètre.

Les tests et le build déjà rapportés (143 tests web, build TypeScript/Vite et essai navigateur local) n'ont pas été relancés ; ces résultats restent ceux du rapport d'implémentation, pas une exécution indépendante. Aucun accès production, publication, modification du code ou commit effectué pendant cette revue.

## Relecture ciblée du correctif `37d1e3f`

**Verdict actualisé : qualité approuvée ; remarque mineure résolue. Aucun constat critique, important ou mineur restant dans le périmètre relu.**

La table `orders` distingue désormais chaque profil et la portée globale (`null`). La lecture privilégie l'ordre propre au profil, sinon hérite de l'ordre global ; un ordre propre vide reste prioritaire. Modifier l'ordre global ne remplace donc pas un ordre personnel existant. Le test ajouté vérifie l'isolement de `p1`, le maintien de l'ordre global initial, l'héritage de `p2` et la conservation de l'ordre personnel après modification globale.

Le tri porte sur une copie de toutes les catégories : une catégorie absente de l'ordre enregistré reste présente, après les catégories explicitement ordonnées, dans l'ordre fournisseur. Les identifiants inconnus dans l'ordre enregistré sont conservés dans la table et ignorés à l'affichage, sans produire de ligne indéfinie ; les doublons sont retirés à l'écriture.

Vérification statique limitée au diff du correctif et à la preuve ajoutée au rapport d'implémentation : rouge annoncé (1 échec / 18 succès), puis 144 tests web et build réussis. Aucune suite relancée, aucune revue élargie, aucune modification hors de cet ajout au rapport.
