# Journal B2 — Le direct complet

## 14 novembre 2026 — Conception proposée

- Sources lues : passation entière, feuille de route, conception générale et conception B1 ; inspection du lecteur, du parcours direct et du client Xtream existants.
- Le propriétaire confirme l'aperçu uniquement sur OK. Il précise que les copies sont dans une même catégorie et dans plusieurs catégories.
- Deux approches présentées : automatique avec corrections sur l'iPhone, ou entièrement manuel. Le propriétaire retient la première, avec regroupement prudent.
- Proposition complète : `docs/superpowers/specs/2026-11-14-b2-direct-complet-design.md`. Les autres précisions restent proposées, pas encore approuvées.
- Proposition importante à faire valider : reprise de lecture sur un autre appareil seulement après confirmation d'arrêt ; nouvelle lecture bloquée si la disponibilité ne peut pas être vérifiée, lecture déjà autorisée conservée pendant une panne de synchro.
- Relecture indépendante de conception : deux points relevés, récupération après arrêt brutal et invisibilité de la 0.2.0. Réponses proposées dans la conception : récupération explicite après arrêt physique vérifié par le propriétaire ; mise à jour de toutes les installations et confirmation avant la mise en service du partage de place. Ces choix restent soumis à validation avec la conception.
- Branche `plan-3b2-direct` créée depuis le checkout fourni, au commit `c7aa465` (fusion B1). Aucun code produit, aucune migration appliquée, aucune publication et aucune fusion dans main.
- Suite : validation de la conception, plan détaillé, construction et tests ; accords distincts avant toute action de production ou fusion.

## 14 novembre 2026 — Conception validée, plan préparé

- Le propriétaire valide explicitement la conception complète : « Oui, je valide. »
- Plan enregistré dans `docs/superpowers/plans/2026-11-14-plan-3b2-direct.md` : neuf tâches indépendamment testables, avec interfaces, chemins, tests, relectures et livraison sous accords distincts.
- Choix de réalisation : moteurs Kotlin/TypeScript avec fixtures communes, groupes corrigés persistants sans conversion des favoris, protocole de lecture B2 dédié pour éviter le nettoyage automatique dangereux des réservations anciennes, coordinateur unique et guide borné.
- Autorelecture de couverture et cohérence effectuée : réservation entre accusé et promotion, premières images et réponses périmées, révocation hors ligne, pagination >1 000 et conflits de corrections explicitement testés dans le plan.
- Méthode conservée de la passation : constructeur puis relecteur indépendant par tâche, relecture finale de branche. Pas de nouveau choix de méthode demandé au propriétaire.
- Aucun code écrit, aucun test B2 exécuté, aucune migration appliquée, aucune publication ni fusion. Les limites d'environnement connues sont indiquées dans la tâche de validation ; ne pas les confondre avec des défauts de B2.
- Suite : relecture du plan par le propriétaire avant construction, conformément à la compétence writing-plans ; maintenir les accords distincts pour production et fusion.

## 14 novembre 2026 — Construction autorisée

- Le propriétaire autorise explicitement la construction et insiste sur les documents de reprise à jour à chaque étape.
- Méthode activée : sous-agent frais par tâche, relecture indépendante avant de poursuivre ; suivi technique local dans `.superpowers/sdd/b2-direct/progress.md` (ignoré), étapes et résultats durables dans les documents versionnés.
- Démarrage tâche 1 : moteur déterministe et fixtures communes ; préparation des prérequis de test en parallèle par le contrôleur. Aucun accord de production ou de fusion donné.
