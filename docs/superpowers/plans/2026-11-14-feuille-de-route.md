# Feuille de route — Player IPTV familial

Spec : `docs/superpowers/specs/2026-11-14-player-iptv-design.md`

Le projet est découpé en plans indépendants, chacun livrant quelque chose de testable. Chaque plan est écrit en détail juste avant d'être lancé, en tenant compte de ce que les précédents ont appris.

| # | Plan | Livre | Dépend de |
|---|---|---|---|
| 1 | **Vérifications + fondations** (terminé) | Dépôt, page de test de lecture, sonde serveur, évaluation des sources sportives, rapports et décisions (LNH complet ; site → app ordinateur) | — |
| 2 | Service de synchro (détaillé) | Supabase : compte foyer, profils, favoris, playlists, progression, masquage, réglages, présence ; règles d’accès ; appairage télé par code | 1 |
| 3 | App télé — socle et Direct | Projet Kotlin Multiplatform (cœur partagé) + app Android TV (Compose for TV + Media3), cache catalogue par catégorie, Direct avec doublons regroupés + bascule auto, EPG maintenant/ensuite, favoris, profils, thème sombre | 1, 2 |
| 4 | App télé — Films et Séries | Sections séparées style plateforme de streaming, fiches, reprise, épisodes vus, épisode suivant, « Continuer à regarder », « Ma liste » | 3 |
| 5 | Recherche | Index plein texte local, tolérance fautes/accents, alias, recherche vocale | 3, 4 |
| 6 | App ordinateur (Windows, Mac) | Compose Desktop sur le cœur partagé, lecteur libVLC (tous formats), films/séries avec reprise, présence, connexion de la télé, installateurs | 2, 3, 4 |
| 7 | Sport | « Mes équipes », « Ce soir », rapprochement diffuseurs → chaînes, rappels (étendue selon rapport du plan 1) | 1, 3 |
| 8 | Personnalisation et thèmes | Réordonner/masquer (tous / mon profil), playlists, tailles, thèmes clair et fun, sur télé et ordinateur | 3, 6 |
| 9 | Plus tard | Téléchargement hors ligne films/séries | — |
