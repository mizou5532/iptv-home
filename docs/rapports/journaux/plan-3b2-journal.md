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
