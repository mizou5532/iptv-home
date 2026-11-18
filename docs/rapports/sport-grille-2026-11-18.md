# Sport : deuxième disposition « Grille complète » (18 nov. 2026)

Branche **`sport-grille`**, partie de `main` @ `ed030a6` (0.33.1). **Non fusionnée, non publiée** (stabilisation en
cours : le propriétaire a demandé ce travail « dans une branche à part »).

## Demande du propriétaire

L'affiche actuelle lui plaît pour le match principal, mais les autres matchs sont difficiles à voir et certains sont
cachés tout à droite de la bande du bas. Sur 6 maquettes puis 4 variantes (canevas
https://claude.ai/artifact/PT9iZ78DL5FTuk1FrY7LRs), il a choisi la **1 · Grille complète** et veut pouvoir choisir,
depuis l'iPhone, entre la disposition actuelle et la nouvelle.

## Ce qui est fait

- **Réglage** : `profiles.settings.sport_view.layout` = `"grille"` ; absent ou autre valeur = l'affiche actuelle
  (rien ne change pour les profils existants). Pas de migration.
- **Partagé** : `SportView.layout` + `SportView.grid` + `SPORT_LAYOUT_GRID` (`sync/SyncModels.kt`).
  Test `SportLayoutTest`.
- **Télé** : `sport/SportGrille.kt` → `SportGrilleScreen`, choisi dans `SportTab` d'après
  `profile.settings.sportView?.grid` (pris en compte au prochain choix du profil, comme les autres réglages du Sport).
  - En haut : le match choisi (fond aux couleurs des deux équipes selon le réglage « Fond », équipes, score,
    « ▶ Regarder sur … » et « N autres chaînes », à droite « Où regarder » : 3 chaînes probables).
  - Filtres en pilules : Tout, En direct, LNH, NFL, NBA, Soccer (seulement ceux qui changent quelque chose ;
    `gridChips`, `gridFilter`).
  - Grille de 4 colonnes de tous les matchs de la bande (16 au plus, `STRIP_MAX`), qui défile **vers le bas** ;
    bande de couleur des deux équipes en haut de chaque tuile, compétition, état, une ligne par équipe avec score.
  - Mêmes gestes que l'affiche : le focus sur une tuile met ce match en haut, OK = regarder, maintenir OK = choix de
    la chaîne (`ChannelSheet` réutilisé), retour d'une lecture = « Regarder » du même match.
  - Aides de `SportScreen.kt` passées de `private` à `internal` pour être partagées : `teamColor`, `shortName`,
    `AfficheButton`, `ChannelSheet`, `Crest`, `LiveBadge`, `LiveRed`, `scoreLine`.
  - Test `SportGrilleTest` (filtres et états des tuiles).
- **Page iPhone** : profil → Réglages du sport → **« Disposition de l'écran »** (Affiche / Grille complète), avec
  aperçu miniature de la grille (`sportview.ts` : `SportLayout`, `LAYOUTS` ; `sportview-ui.ts` : `gridPreview` ;
  `ui.css` : `.gridmini*`). Tests page : **270/270**, construction OK.

## À faire avant de publier (IMPORTANT)

1. **Le code Kotlin n'a pas été compilé** : dans l'environnement de cette séance, les dépôts Android et Gradle
   (dl.google.com, maven.google.com, repo.maven.apache.org, services.gradle.org) étaient bloqués. Lancer
   `cd apps && ./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest :tv:assembleRelease` et corriger.
2. Ajouter un test à la télécommande (Robolectric, comme `SportDpadTest`) avec un profil
   `ProfileSettings(sportView = SportView(layout = "grille"))` : grille visible, Bas depuis « Regarder » → filtres →
   tuiles, focus sur une tuile → match du haut changé, OK lance la lecture, rien hors de l'écran.
3. Juger le rendu sur la TCL (tailles prévues pour 960 × 540 dp ; match du haut 186 dp de haut, tuiles ≈ 66 dp).
4. Publication (APK + page) seulement avec l'accord du propriétaire, après la stabilisation.

## Intégrée et publiée — 0.35.0 (code 61), 18 nov. 2026

Le propriétaire a demandé d'intégrer la maquette à l'app pour pouvoir l'utiliser. Branche `sport-grille` fusionnée dans `claude/vigilant-ramanujan-572vae` (aucun conflit), puis :

1. **Compilée et testée** : le code Kotlin compilait tel quel (un avertissement sans conséquence). Tests de la branche verts.
2. **Test à la télécommande** `ui/SportGrilleDpadTest` (rendu réel, écran 960 × 540) : grille visible, Bas depuis « Regarder » → filtres → tuiles, le match du haut suit le focus, OK lance la lecture, maintenir OK ouvre le choix de la chaîne, tout reste dans l'écran ; filtre « En direct » qui disparaît : le focus n'est jamais perdu. Captures : `docs/rapports/captures/sport-grille/` (`grille-regarder.png`, `grille-tuile.png`, `page-iphone-disposition.png`) ; `-Psport.shots=<dossier>` pour les refaire.
3. **Relecture indépendante** : corrigé — un filtre disparu (fin du direct) revenait tout seul plus tard ; le focus se perdait quand le filtre ou la tuile qui l'avait disparaissait.

Tests : 367 partagés, 513 télé, 271 page, 0 échec ; analyse Android 0 erreur. APK SHA-256 `3e70f8322bb1a4be6c38e15840e522ef6b2abd8065fa27c6db69a5dc76d03662`, 14 731 711 octets, `versionCode 61`, `minSdkVersion 25`, certificat attendu, identique par Supabase et Vercel ; page `index-b_19b5P9.js` + `index-BL-AKKzD.css` (+ `demo-DYFUWU0s.js`), démo sans erreur, choix « Grille complète » vérifié ; Vercel `dpl_ChJa1ntWwjmbc33Uf1uZPiEvckta` ; règles stockage : les 4 `avatars_*`. Pas de migration serveur.

**Essai TCL** : iPhone → Profils → (profil) → Réglages du sport → Disposition de l'écran → Grille complète ; sur la télé, revenir à « Qui regarde ? » et rouvrir le profil, puis Sport. Juger la taille des tuiles et du match du haut.
