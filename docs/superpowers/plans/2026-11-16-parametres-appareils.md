# Paramètres des appareils — Implementation Plan

> Exécution directe avec superpowers:executing-plans ; revue finale indépendante.

Goal : noms et pictogrammes par télé, style existant.
Spec : docs/superpowers/specs/2026-11-16-parametres-appareils-design.md.
Contraintes : propriétaire seulement, appareil de son foyer seulement, nom1–40 caractères, icônes tv/sofa/bed/desk/other ; migration additive, anciennes associations conservées, aucun déploiement/migration sans accord distinct.

- [x] Backend : tests rouges puis migration0016 et tests verts ; contrat README.
- [x] Web : tests éditeur, succès/erreur/annulation/rechargement ; module devices.ts + device-view.ts ; intégrer Réglages et démo, garder retrait confirmé.
- [x] TV : tests purs du nom fabricant/modèle et branchement à l’appairage ; compilation debug et tests adaptés.
- [ ] Suites web/backend, relecture indépendante, rapport/passation, sauvegarde branche ; préparer les artefacts avant demande distincte de publication.

Review focus : droits autre foyer/propriétaire, cible supprimée, erreur réseau conserve saisie, navigation pendant écriture, nom automatique n’écrase jamais un nom enregistré.
