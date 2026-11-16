# Refonte mobile du site de configuration

Validée dans cette conversation : thème ivoire et vert profond, seconde proposition, variante compacte acceptée puis demande explicite de commencer la refonte. Référence : dernière maquette compacte (Alex, avatar initiale, ouverture Sport). Les propositions roses et les autres thèmes sont écartés.

## Intention et choix

Site de configuration existant, pas de nouveau lecteur. Entrée par profil comme un espace personnel ; navigation basse Profils / Organiser / Réglages. Conserver TypeScript/Vite sans framework et les contrats backend. Palette ivoire #F3F1EA, vert #214837, texte presque noir, séparateurs discrets ; corps 14–16px, commandes tactiles au moins 44px, avatar 56–64px, titre profil 26px. Le sélecteur de profils reste toujours accessible. Ordinateur : largeur confortable, navigation identique, aucun débordement mobile.

Approches explorées : pastel familial, clair éditorial et sombre ; puis graphite/bleu, ivoire/vert et noir/orange. Choix utilisateur : ivoire/vert compact. Implémentation choisie : vues de profils séparées du moteur d’organisation existant, mêmes RPC et éditeurs photo/équipes, sans refonte backend.

## Parcours

- Profils : choisir une personne, voir son espace, régler l’onglet de départ Direct/Séries/Films/Sport, modifier sa photo (GIF compris), ses équipes favorites ; accéder à son organisation.
- Organiser : initialiser avec le profil sélectionné, propager les changements de portée au reste du site. Conserver Tout le monde, copies, catégories, favoris, playlists, recherche, masquage, ordre et confirmations intégrées. Catégories initiales, pas de sélection implicite dangereuse de Tout le monde.
- Réglages : association de la télé, appareils et retrait confirmé, identifiants IPTV repliés, déconnexion. Un code QR en attente ouvre directement les Réglages.
- Connexion et première configuration : même langage visuel. Chargement, erreur, succès et focus visibles.
- Démo : navigation complète avec profils et données fictives, aucune écriture de production. Photo simulée localement si possible, équipes et choix de départ en mémoire.

## Contraintes

Aucun secret ni flux journalisé, aucun test en production, aucune migration, aucun changement de l’APK/version.json. Pas de publication sans accord distinct. L’apparence ne change pas les droits : les profils restent administrés par le compte du foyer, pas d’authentification individuelle inventée.

## Validation

Tests des changements de profil et de portée, rollback d’un échec d’écriture, erreur/absence de profils, démo isolée ; suites web existantes et compilation ; inspection navigateur mobile et bureau si disponible. Tracer explicitement les limites de validation. Sauvegarder sur branche dédiée basée sur plan-7-themes @ fc0b9a3.
