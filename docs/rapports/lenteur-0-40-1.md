# 0.40.1 — Lecture des premiers rapports, icône de rapport, Sport gardé (19 nov. 2026)

## Ce que disent les 4 rapports envoyés depuis la TV Salon (TCL, Android 14, 2,4 Go de mémoire)

- **La télécommande n'est pas en cause** : touches traitées en quelques millisecondes ; quelques retards de 0,3 s
  seulement au tout début (rapport 2, juste après le lancement, pendant la mise à jour du Direct).
- **Plantage des Canadiens (00 h 46 UTC) confirmé** dans les fermetures précédentes de la télé, avec l'app à 323 Mo ;
  **une autre fermeture par manque de mémoire** à 23 h 29 UTC (391 Mo).
- **Le vrai problème : la mémoire.** Rapport 3 (42 s après le lancement, Films) : le « ménage de mémoire » d'Android a
  tourné 36 fois pour 17 s au total ; rapport 4 (2 min après le lancement) : 45 fois, 27 s au total, 79 fils.
  Pendant ce ménage, l'écran saccade (blocage de 1,2 s et images de 1,5 s en dessinant des cartes avec images).
- Au même moment tournaient en parallèle : mise à jour du Direct (14,5 s, 119 demandes au fournisseur), index des
  versions des films, calcul du Sport (34 matchs, 4,8 s) — sur un catalogue de 47 152 films, 12 919 séries,
  6 487 chaînes.

## Changements

1. **Icône ronde sans texte** (taille de la photo du profil) à droite de la pastille du profil, au lieu du bouton
   « Signaler une lenteur ». « ! » pour signaler, « … » pendant l'envoi, « ✓ » ensuite ; le message (« Rapport n° X
   envoyé ✓ ») s'affiche 6 s à la place du texte de la barre, sans prendre de place.
2. **Sport gardé entre deux onglets** : l'écran Sport était recréé à chaque retour sur l'onglet (horaires, chaînes et
   calcul de tous les matchs refaits). Il est maintenant créé à la première visite puis réutilisé tant que le profil
   reste ouvert (`App.kt`, test `SportKeptDpadTest`, qui échouait avant : « créé 2 fois »).

## Prochaine étape proposée

Alléger la mémoire au démarrage : ne pas lancer en même temps la mise à jour du Direct, l'index des versions et le
Sport ; garder moins du catalogue en mémoire (47 000 films) ; puis C (un seul appareil télécharge le catalogue).
