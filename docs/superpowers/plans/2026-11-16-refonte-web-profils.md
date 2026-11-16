# Refonte web profils — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement task-by-task.

**Goal:** Site mobile compact ivoire/vert centré sur chaque profil.
**Architecture:** Vues DOM TypeScript dédiées, intégrées à main.ts ; moteur Organiser et éditeurs existants conservés.
**Tech Stack:** TypeScript, Vite, Vitest/jsdom, Phosphor.
**Spec:** docs/superpowers/specs/2026-11-16-refonte-web-profils-design.md

## Global Constraints

Français ; aucune production, migration ou publication ; aucun changement APK/version ; 14–16px et cibles ≥44px ; ne pas exposer les accès IPTV.

## Review Focus

Profil choisi distinct du premier ; portée Tout le monde explicite ; réponses asynchrones après navigation ; écriture refusée restaurée ; code QR et démo isolée.

### Task 1 — Profils et portée

Files : src/profile-view.ts, src/shell.ts, src/icons.ts, src/org/view.ts, src/org/demo.ts ; test/profile-view.test.ts, test/org-scope.test.ts.
Interfaces : mountProfileView(root, options), options.onOrganise(profileId) ; mountOrganiser(..., options?) avec initialScope/onScopeChange, nettoyage via unmountOrganiser().
- [x] Tests rouges : choix second profil, écriture ciblée, rollback, absence/erreur, portée initiale et retour de Tout le monde.
- [x] Implémenter vues/factories et mémoire démo sans toucher aux RPC existantes.
- [x] Tests ciblés verts et sauvegarde.

### Task 2 — Intégration et thème

Files : src/main.ts, src/ui.css, index.html ; test/site-navigation.test.ts.
- [x] Navigation Profils/Organiser/Réglages, code QR ouvre réglages ; erreurs et confirmations préservées.
- [x] Ivoire/vert compact sur tous les écrans, éditeurs inclus, mise en page responsive.
- [x] Suite web entière et build Vite réussis.

### Task 3 — Validation et passation

Files : docs/rapports/refonte-web/validation.md, PASSATION.md, feuille de route.
- [ ] Vérification navigateur mobile/bureau : bloquée (outil cua en échec d’initialisation ; relais preview indisponible). Contrôles DOM testés, rendu non vérifié.
- [x] Relecture indépendante finale et corrections nécessaires.
- [x] Sauvegarde commit/push branche dédiée, sans publication ni fusion.
