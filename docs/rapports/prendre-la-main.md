# 0.38.0 — « Quelqu'un regarde déjà » : prendre la main ou laisser regarder (18 nov. 2026)

Demande du propriétaire : avant de lancer une vidéo (Direct, film, série, Sport) alors qu'une autre télé regarde
déjà, prévenir, dire quel appareil et ce qui est regardé, et laisser le choix : prendre la main, ou laisser l'autre
personne continuer.

## Ce qui existait avant

- Avec des **comptes supplémentaires** : quand tous étaient pris, la vidéo était refusée avec un message (« Le compte
  IPTV est déjà utilisé : Maha sur Télé du salon. »), sans possibilité de prendre la main ni de savoir ce qui était regardé.
- Avec **un seul compte** : aucune protection. Deux télés pouvaient lancer une vidéo et le fournisseur coupait l'une des deux.

## Ce qui change

- **Serveur** (migration `20261119000032_prendre_la_main`) : chaque vidéo réserve un compte, même sans compte
  supplémentaire. `claim_account(…, takeover)` : quand tout est pris, la réservation choisie est libérée et donnée
  (table `stream_takeovers`, sans accès direct). `heartbeat_stream(id, label)` met à jour ce qui est regardé.
  `stream_takeover(id)` : la télé coupée apprend qui a pris la main. `takeover_done(id)` : la télé qui prend la main
  sait quand l'autre s'est arrêtée. Les anciennes télés (0.37) restent compatibles : elles reçoivent un refus avec message.
- **Télé** :
  - Fenêtre « Quelqu'un regarde déjà » : « Maha regarde « Série · Lost · S1 É2 » sur Télé du salon. »
    Boutons **Laisser regarder** (choisi au départ, et aussi avec Retour) et **Prendre la main** (un bouton par télé
    quand plusieurs comptes sont pris).
  - Prendre la main : l'autre télé s'arrête dans les 5 secondes (signe de vie toutes les 5 s au lieu de 15) et
    affiche « Hamza a pris la main depuis Télé de la chambre : ta vidéo est arrêtée. » ; la nôtre attend son arrêt
    (8 s au plus) puis démarre, pour ne jamais avoir deux vidéos sur le même compte.
  - Ce qui est regardé est envoyé avec chaque signe de vie : « Direct · TSN 1 », « Film · Dune »,
    « Série · Lost · S1 É2 », « Sport · RDS ». Un changement de chaîne est donc visible par les autres télés.
  - Pendant le choix, aucun délai « pas d'image » ne compte : une copie n'est jamais jugée en panne pour ça.
- Code : `player/AccountLeases.kt`, `player/PlayerController.kt` (`conflict`, `takeOver`, `leave`, `waiting`),
  `player/TakeOverDialog.kt`, `shared/sync/SyncClient.kt`.

## Limite connue

Une télé qui s'éteint brutalement (prise débranchée) garde sa place 45 secondes ; « Prendre la main » la libère tout de suite.
