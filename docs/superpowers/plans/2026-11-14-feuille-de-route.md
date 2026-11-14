# Feuille de route — Player IPTV familial

Spec : `docs/superpowers/specs/2026-11-14-player-iptv-design.md`
Vue interactive (privée, tenue à jour par Claude, même contenu) : https://claude.ai/artifact/79SZ97WaDCLXmm1t4tVVNs
État détaillé et passation : [`PASSATION.md`](../../../PASSATION.md)

*Mise à jour : 14 novembre 2026.*

**Priorité (décision du propriétaire) : la télé d'abord.** L'app ordinateur passe après toutes les étapes télé.

Durées estimées : une **séance** ≈ une soirée de travail d'IA (3 à 5 h), sans les essais du propriétaire.
Tâches : **courte** (< 1 h), **moyenne** (1 à 3 h), **longue** (une séance ou plus).
Reste estimé : **≈ 12 séances** pour que la télé soit complète, puis **≈ 4 séances** pour l'app ordinateur.

| # | Étape | Durée | Statut |
|---|---|---|---|
| 1 | Vérifications et fondations | 1 séance | ✅ terminé |
| 2 | Service de synchro (Supabase) | 1 séance | ✅ terminé |
| 3 | App télé : première version, organiser, le direct complet | ≈ 4 séances restantes | 🟡 en cours (B1 essayé et fusionné ; B2 en construction, tâches 1–2 terminées, tâche 3 en cours) |
| 4 | App télé : films et séries | ≈ 3 à 4 séances | à venir |
| 5 | App télé : recherche | ≈ 1 séance | à venir |
| 6 | App télé : sport | ≈ 2 séances | à venir |
| 7 | App télé : thèmes clair et fun, tailles | ≈ 1 à 2 séances | à venir |
| 8 | App ordinateur (Windows, Mac) | ≈ 3 à 4 séances | plus tard |
| 9 | Plus tard : bascule automatique vers une autre copie, téléchargement hors ligne | — | plus tard |

## Étape 3 en détail

| Tâche | Taille | Statut | Détail |
|---|---|---|---|
| A · Première version sur la télé | longue | ✅ | 0.1.0 installée sur la TCL : QR, 3 profils, chaînes, lecture, 2ᵉ ouverture très rapide. Plan : `plan-3a-tele-connexion-socle.md`. |
| B1.0 · Conception de B1 | courte | ✅ | `specs/2026-11-14-b1-organiser-depuis-le-telephone-design.md` |
| B1.1 · La télé dépose la liste des chaînes dans le compte en ligne | moyenne | ✅ | Noms seulement ; recherche identique télé/téléphone (accents). |
| B1.2 · « Organiser » sur le téléphone : catégories | longue | ✅ | Profil ou tout le monde, recherche, masquer, « tout masquer sauf », réordonner. |
| B1.3 · Favoris, playlists et recherche sur le téléphone | moyenne | ✅ | |
| B1.4 · La télé suit l'organisation en quelques secondes | moyenne | ✅ | Interrogation toutes les 4 s, hors ligne OK, changement de profil propre. |
| B1.5 · Nouvel écran du direct (thème sombre) | longue | ✅ | À juger sur la télé. |
| B1.6 · Mise à jour 0.2.0 sur la télé et essai | courte | ✅ | Essai réussi. Plan : `plan-3b1-organiser.md`. Branche `plan-3b1-organiser`. |
| B2.0 · Conception et plan du direct complet | courte | ✅ validés | Conception validée. Plan `2026-11-14-plan-3b2-direct.md`, neuf tâches avec tests ; construction autorisée. Branche `plan-3b2-direct`. |
| B2.1 · Copies d'une chaîne regroupées (choix manuel) | longue | planifié | Tâches 1–4 du plan B2 : moteur et corrections persistantes ✅ relus ; écran iPhone en cours, télé ensuite. |
| B2.2 · Guide « maintenant / ensuite » | moyenne | planifié | Tâche 7 du plan B2. |
| B2.3 · Aperçu de la chaîne | moyenne | planifié | Tâches 6 et 8 du plan B2. |
| B2.4 · « Prendre la main » | moyenne | planifié | Tâches 5–6 du plan B2 ; validation/livraison en tâche 9. |

## Étapes suivantes (tâches prévues)

- **4. Films et séries** : accueil Séries et fiche (longue), accueil Films et fiche (longue), lecture/reprise/épisode suivant (longue), « Continuer à regarder » et « Ma liste » (moyenne), organiser films et séries depuis le téléphone (moyenne).
- **5. Recherche** : instantanée dans tout le catalogue (moyenne), fautes et surnoms (« habs ») (moyenne), vocale (courte).
- **6. Sport** : Mes équipes, LNH complet (longue) ; Barça, NFL, NBA : horaires et recherche du match (moyenne) ; page « Ce soir » (moyenne) ; rappels (courte).
- **7. Thèmes** : clair et fun (longue), choix par profil (courte), tailles des affiches et de l'aperçu (courte).
- **8. Ordinateur** : app sur le même cœur (longue), lecteur tous formats dont MKV (longue), installateurs (moyenne).
- **9. Plus tard** : bascule automatique vers une autre copie (moyenne), téléchargement hors ligne (longue).

Appairage de la télé : QR code + page de configuration sur téléphone (spec §6.1).
