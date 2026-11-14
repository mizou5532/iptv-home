# Rapport tâche 8 — aperçu sur OK, sources, plein écran, télécommande

Statut : DONE_WITH_CONCERNS (logique testée ; aucun essai visuel/TCL exécuté).

## Réalisé (amendement appliqué : pas de PlaybackCoordinator, pas de TakeoverDialog)
- `live/LivePlaybackState.kt` : réducteur pur `reduce(state, event)` (modes Browse/Fullscreen, `selectedRowId`, `playingGroupId`, `playingSourceId`, `originSectionKey`, panneau `None/Sources`, commandes, `returnFocusRowId`, `notice`). Le champ `effect` (Play/Stop) est l'effet demandé ; le réducteur ne touche pas au lecteur.
- `live/LivePlaybackHost.kt` (fichier ajouté, absent de la liste du brief) : exécute les effets via le `PlayerController` existant. Chaque `Play` ferme d'abord le flux (avant même la résolution de la copie), une résolution tardive après remplacement/arrêt n'ouvre rien, haut/bas sérialisé (mutex), repli de section (favoris sinon première catégorie), `syncPlaying()` (masqué gagne : arrêt sans changer de source ; simple changement de groupe : la lecture continue), `shutdown()`.
- `LiveBrowseViewModel` : `playbackFor(row, sourceId)` (généralise `playbackSource`, qui garde son comportement et ses tests), `sectionRows(key)`, `groupOf(sourceId)`, `groupsRevision`. `Sections.kt` : `fallbackSectionKey`.
- UI : `LiveBrowseScreen` (3 zones 24/38/38 %, chaîne en lecture marquée par barre d'accent + « En lecture », distincte du focus), `LivePreview.kt` (vidéo 16:9, titre, guide Maintenant/Ensuite avec « Programme indisponible », boutons Sources/Arrêter), `SourcesPanel.kt` (noms originaux, catégorie, copie active, Arrêter, Fermer), `PlayerScreen.kt` (`PlayerSurface` commune ; plein écran : haut/bas, OK = commandes, `keepScreenOn` posé sur chaque vue), `ExoPlayerPlayer` (`started`, `detach` qui ne stoppe pas), `LiveFocus.kt` (`LiveButton`), `LiveDims`, `LiveRoute` (hôte, guide, dernière bonne copie après image prête, focus de retour), `LiveSessions`/`App`/`MainActivity` (un `EpgRepository` par session, fermé avec elle). Aucun nouveau jeton ; `grep "Color(0x" live/` vide.
- Textes en français. « Arrêter » atteignable dans l'aperçu, le panneau et les commandes plein écran.

## Tests
- Commande : `cd apps && ./gradlew --max-workers=2 :tv:testDebugUnitTest :tv:assembleDebug`
- Résultat final : 146 tests tv, 0 échec ; assembleDebug OK. (Référence avant : 82.) Nouveaux : `LivePlaybackStateTest` (24), `LiveNavigationTest` (19, réducteur + hôte + PlayerController avec faux lecteur), 1 test VM, 1 test Sections.
- RED réducteur : stub `reduce = state.copy(effect=null)` puis `:tv:testDebugUnitTest --tests '*LivePlaybackStateTest*'` -> « 23 tests completed, 19 failed » (attendu : aucune transition). GREEN : BUILD SUCCESSFUL.
- Hôte : tests écrits après l'implémentation (pas de rouge initial). Vérification par mutation : suppression de la fermeture avant remplacement -> `ok_sur_un_autre_groupe_ferme_l_ancien_flux_avant_d_ouvrir_le_nouveau FAILED` ; code restauré.
- Couvert : focus seul = zéro lecteur ; OK -> aperçu ; OK même groupe -> plein écran sans nouveau play ; Retour -> aperçu sans nouveau play ; OK autre groupe -> stop/release avant create/play ; deux OK rapprochés -> un seul flux ; Retour ferme panneau puis commandes puis plein écran ; changement de catégorie garde la lecture ; haut/bas sans boucle, dans la section d'origine, rapide ordonné, section disparue -> repli ; choix de source ; Arrêter ; masqué gagne ; groupe modifié sans redémarrage ; refus (identifiants/indisponible) sans flux ; résolution tardive ignorée ; libération par l'activité.

## Commits (poussés sur plan-3b2-direct)
a40f8c7 réducteur ; 4b1fd31 hôte ; 8c619a2 écran ; 83b2ae0 retour de focus + tests VM ; bde9cc9 docs (PASSATION, journal).

## Non exécuté : à vérifier sur la TCL (ou émulateur), une compilation ne le prouve pas
1. Une seule surface vidéo : l'aperçu et le plein écran composent chacun leur `PlayerView` (jamais les deux). Vérifier l'absence d'écran noir durable (un éclair noir bref au changement est possible) et que la vidéo reprend.
2. Aucun reconnect réseau au passage aperçu <-> plein écran (le flux continue, pas de tampon relancé) ; un seul flux chez le fournisseur lors d'un remplacement (OK sur autre chaîne, haut/bas rapide, choix de source).
3. `keepScreenOn` effectif en aperçu et en plein écran (pas d'économiseur d'écran, pas d'onStop).
4. Focus : après fermeture du panneau Sources (liste : retour sur le bouton Sources ; plein écran : retour aux touches), retour du plein écran sur la chaîne en lecture, fond non focalisable pendant le panneau (`focusProperties { canFocus = false }` sur le parent), Droite depuis la liste vers Sources/Arrêter, Retour à chaque niveau (priorité des `BackHandler` : panneau > plein écran > liste).
5. Plein écran : haut/bas répétés (zapping), OK affiche les commandes puis elles se referment seules après 6 s, OK long/répété.
6. Lisibilité à 3 m (24 sp lignes, 18 sp méta) avec sections à 24 % (titres tronqués par « … »), mise en page 24/38/38 avec les marges réelles.
7. Erreurs de source : message « Lecture impossible… » dans l'aperçu et en plein écran ; « Connexion… » avant l'image ; enregistrement de la dernière bonne copie seulement après `STATE_READY`.
8. Masquage depuis l'iPhone pendant la lecture : arrêt et message (délai = interrogation des préférences, ~4 s).
9. Guide : horaires locaux, barre de progression (rafraîchie seulement à chaque publication du guide, pas à la seconde), « Programme indisponible », changement de chaîne sans guide d'une autre copie (une image périmée possible entre la sélection et l'effet `observe`).

## Écarts et préoccupations
- Les dialogues « occupé / attente / échec / récupération via iPhone » du brief relèvent du TakeoverDialog, reporté (amendement) : non faits.
- `LivePlaybackHost.kt` ajouté (non listé) pour rendre l'ensemble réducteur + effets testable sans Compose ; `TakeoverDialog.kt` non créé ; `IptvTheme.kt` inchangé.
- Pas de nouvelle tentative sur OK d'une chaîne en échec (il faut Arrêter puis OK, ou choisir une autre source).
- « Sources » n'est proposé que pour la chaîne en lecture (pas avant lecture).
- Le guide d'aperçu avant lecture suit la ligne parcourue (délai 300 ms du dépôt du guide).
- Pas d'émulateur utilisé ; aucun test Compose UI.
- Seules les suites tv ont été relancées (shared/backend/web non touchés).
