# Feuille de route — Player IPTV familial

Spec : `docs/superpowers/specs/2026-11-14-player-iptv-design.md`
Vue interactive (privée, tenue à jour par Claude, même contenu) : https://claude.ai/artifact/79SZ97WaDCLXmm1t4tVVNs
État détaillé et passation : [`PASSATION.md`](../../../PASSATION.md)

*Mise à jour : 16 novembre 2026. Version sur la télé : 0.5.0. Étape 5 (recherche) : conception validée et plan écrit, code à faire.*

**Priorité (décision du propriétaire) : la télé d'abord.** L'app ordinateur passe après toutes les étapes télé.

Durées estimées : une **séance** ≈ une soirée de travail d'IA (3 à 5 h), sans les essais du propriétaire.
Tâches : **courte** (< 1 h), **moyenne** (1 à 3 h), **longue** (une séance ou plus).
Reste estimé : **≈ 4 à 5 séances** pour que la télé soit complète (recherche ≈ 1, sport ≈ 2, thèmes ≈ 1 à 2), puis **≈ 4 séances** pour l'app ordinateur.

| # | Étape | Durée | Statut |
|---|---|---|---|
| 1 | Vérifications et fondations | 1 séance | ✅ terminé |
| 2 | Service de synchro (Supabase) | 1 séance | ✅ terminé |
| 3 | App télé : première version, organiser, le direct complet | faite | ✅ terminé (0.3.0 puis corrections de la 0.4.1). « Prendre la main » reporté à l'étape 8 ; guide « Programme indisponible » reporté à la demande du propriétaire |
| 4 | App télé : films et séries | faite | ✅ 4A séries (0.4.1) et 4B films (0.5.0 en ligne le 16 nov., essai télé réussi, fusionnée dans main) |
| 5 | App télé : recherche | ≈ 1 séance | 🟡 code fait et testé ; vérification finale, mise en ligne et essai télé regroupés à la fin du projet |
| 6 | App télé : sport | ≈ 2 séances | 🟡 0.7.0 publiée le 16 nov. (bouton Mise à jour) ; essais complets à la fin du projet |
| 7 | App télé : thèmes clair et fun, tailles | ≈ 1 à 2 séances | à venir |
| 8 | App ordinateur (Windows, Mac) | ≈ 3 à 4 séances | plus tard |
| S | **Stabilisation (en cours, avant toute nouvelle fonction)** : 8 zones auditées et corrigées une par une | ≈ 1 séance par zone | 🟡 zone 0 (bilan général) faite le 18 nov. : 1 294 tests verts ; rapport `docs/rapports/stabilisation/00-bilan-general.md` ; zones 1 (lecteur, 0.30.1), 2 (Direct, 0.30.2) 3 (Sport, 0.30.3) et 4 (Séries, 0.30.4) publiées, 5 (Films) sans correction ; suite : zone 6 |
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
| B2.1 · Copies d'une chaîne regroupées (choix manuel) | longue | ✅ | Moteur, corrections sur l'iPhone (onglet « Copies »), panneau Sources sur la télé. |
| B2.2 · Guide « maintenant / ensuite » | moyenne | ✅ livré, un point reporté | « Programme indisponible » reste à régler ; reporté par le propriétaire. |
| B2.3 · Aperçu de la chaîne | moyenne | ✅ | OK = aperçu, 2ᵉ OK = plein écran. |
| B2.4 · « Prendre la main » | moyenne | reporté à l'étape 8 | Une seule télé pour l'instant ; migration 0010 prête mais non appliquée. |

## Étapes suivantes (tâches prévues)

- **4. Films et séries** : accueil Séries et fiche (longue), accueil Films et fiche (longue), lecture/reprise/épisode suivant (longue), « Continuer à regarder » et « Ma liste » (moyenne), organiser films et séries depuis le téléphone (moyenne).
- **5. Recherche** : instantanée dans tout le catalogue (moyenne), fautes et surnoms (« habs ») (moyenne), vocale (courte).
- **6. Sport** : Mes équipes, LNH complet (longue) ; Barça, NFL, NBA : horaires et recherche du match (moyenne) ; page « Ce soir » (moyenne) ; rappels (courte).
- **7. Thèmes** : clair et fun (longue), choix par profil (courte), tailles des affiches et de l'aperçu (courte).
- **8. Ordinateur** : « Prendre la main » (tâche 6 du plan B2, avec la migration 0010 déjà prête), app sur le même cœur (longue), lecteur tous formats dont MKV (longue), installateurs (moyenne).
- **9. Plus tard** : bascule automatique vers une autre copie (moyenne), téléchargement hors ligne (longue).

Appairage de la télé : QR code + page de configuration sur téléphone (spec §6.1).

## Étape 4 en détail (terminée)

| Partie | Statut | Détail |
|---|---|---|
| 4A · Séries | ✅ | 0.4.0 puis 0.4.1 ; essai télé réussi ; fusionnée dans main. Plan : `2026-11-15-plan-4a-series.md`. |
| 4B · Films | ✅ | 0.5.0 en ligne le 16 nov. (migration 0012, page iPhone, APK) ; essai télé réussi sur accueil, catégories, fiche et lecture ; fusionnée dans main. Plan : `2026-11-15-plan-4b-films.md`. Rapport : `../../rapports/4b/tache-5-validation.md`. |

À essayer encore par le propriétaire, sans urgence : Reprendre et Recommencer, Ma liste, Marquer vu / non vu, audio et sous-titres, masquer une catégorie de films depuis l'iPhone.

## Étape 5 — Recherche (conception validée, plan prêt, code à faire)

Conception : `../specs/2026-11-16-etape5-recherche-design.md`. Plan : `2026-11-16-plan-5-recherche.md`.

| Tâche | Taille | Statut |
|---|---|---|
| 1 · Moteur de recherche (accents, partiels, fautes, surnoms) | moyenne | ✅ |
| 2 · Ce qui est cherché, sans rien de masqué | courte | ✅ |
| 3 · Remplissage du catalogue films et séries en arrière-plan | moyenne | ✅ |
| 4 · Modèle de l'écran de recherche | courte | ✅ |
| 5 · Écran, bouton Rechercher, retour sur la chaîne dans le Direct (voix retirée à la demande du propriétaire) | longue | ✅ |
| 6 · Vérifications, 0.6.0, mise en ligne (accord) et essai télé | moyenne | reporté à la fin du projet |

Décisions du propriétaire : résultats de la section où l'on se trouve ; toutes les catégories gardées ; OK sur une chaîne = retour dans la liste du Direct avec l'aperçu.


### Refonte mobile du site de configuration — 16 novembre 2026

Demande du propriétaire : profils comme espaces personnels, thème ivoire/vert compact validé. Implémentation sur `refonte-web-profils` depuis `plan-7-themes` (0.10.0). Profils / Organiser / Réglages, éditeurs et organisation existants conservés. 172 tests web verts et build OK ; relecture indépendante corrigée. Validation visuelle bloquée par l’outil navigateur, aucune publication. Rapport : `docs/rapports/refonte-web/validation.md`.

Aperçu démo séparé publié avec accord explicite (16 nov.), déploiement Vercel `dpl_AM3Y9TZuLHq8LuTCW3cNZZRJErit`, lien temporaire transmis pour essai iPhone. Site de production inchangé.

Essai avec compte réel demandé ensuite : nouvel aperçu staging `dpl_ApNYhSB7trKLL6wjxz8i9KnjUwQX`, connexion normale par le propriétaire. Code précédent sauvegardé par tag `sauvegarde-site-avant-refonte-2026-11-16`, site habituel inchangé.

Refonte validée par le propriétaire après essai iPhone de l’aperçu corrigé ; intégrée à main à sa demande explicite. Branche et sauvegarde conservées. Site habituel non remplacé.

Refonte désormais publiée sur l’adresse habituelle : production `dpl_QcG61J9Z2TvPr52jWUVYtTyeD2Sw`. Sauvegarde conservée, métadonnées et APK0.10.0 inchangés.

Paramètres appareils : noms/pictogrammes et récupération des modèles déjà associés réalisés sur parametres-appareils,189 backend/179 web/320 shared/14 démarrage verts ; APK0.11.0 signé prêt. Migration et publication distinctes à autoriser avant activation. Dernière connexion abandonnée ; statut de lecture hors de cette étape.
