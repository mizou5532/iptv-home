# Feuille de route — Player IPTV familial

Spec : `docs/superpowers/specs/2026-11-14-player-iptv-design.md`
Vue interactive (tenue à jour par Claude) : https://claude.ai/artifact/79SZ97WaDCLXmm1t4tVVNs

**Priorité (décision du propriétaire, 2026-11-14) : la télé d'abord.** L'app ordinateur passe après toutes les étapes télé.

| # | Étape | Livre | Statut |
|---|---|---|---|
| 1 | Vérifications et fondations | Dépôt, tests du fournisseur, source sportive, décisions | terminé |
| 2 | Service de synchro | Supabase : foyer, profils, identifiants, appairage, favoris, listes, masquage, progression, qui regarde | en cours |
| 3 | App télé — première version | Projet Kotlin Multiplatform + app Android TV : connexion au compte, catalogue par catégorie, direct (doublons + bascule auto), guide maintenant/ensuite, profils, favoris, installation | à venir |
| 4 | App télé — films et séries | Sections séparées style plateforme de streaming, reprise, épisodes vus, épisode suivant, « Continuer à regarder », « Ma liste » | à venir |
| 5 | App télé — recherche | Recherche instantanée, fautes et accents tolérés, alias, voix | à venir |
| 6 | App télé — sport | « Mes équipes » (LNH complet ; Barça/NFL/NBA simplifié), « Ce soir », rappels | à venir |
| 7 | App télé — personnalisation et thèmes | Masquer/réordonner, listes, tailles, thèmes sombre/clair/fun | à venir |
| 8 | App ordinateur (Windows, Mac) | Compose Desktop sur le cœur partagé, lecteur libVLC, installateurs | plus tard |
| 9 | Plus tard | Téléchargement hors ligne | plus tard |

Point ouvert pour l'étape 3 : l'appairage de la télé était prévu depuis l'app ordinateur (spec §6.1, §6.10). L'app ordinateur arrivant plus tard, il faut une autre façon de valider la télé — à décider avec le propriétaire avant de détailler l'étape 3.
