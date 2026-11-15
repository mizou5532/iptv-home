# Rapport — Tâche 6 : lecture d'un épisode

## Ce qui est fait
**Partagé** (`ProgressQueue`) : l'expiration de 14 jours **ignore l'âge** d'une entrée dont l'estampille est invraisemblable (avant le 2026-01-01, ou à plus d'un jour dans le futur) : télé dont l'horloge était fausse au démarrage. `isPlausibleStamp`, 2 tests (1970 / veille de 2026 / +2 j conservées et envoyées ; une entrée plausible de 14 j expire toujours ; bornes exactes).

**Lecteur** (`player/`) : `Player` reçoit, avec valeurs par défaut (les faux de test existants compilent), `play(url, startMs)`, `snapshot()` (position, durée, lecture voulue, fin, erreur, prêt), `tracks()`, `selectAudio`, `selectSubtitle(null = aucun)`, `seekBy`, `setPaused`. `PlayerController.open(url, startMs = 0)` : la fermeture avant l'ouverture reste celle du contrôleur (une seule connexion). `ExoPlayerPlayer` : reprise par `setMediaItem(item, startMs)`, fin (`ended`), pistes audio/sous-titres décrites depuis `Tracks` (id « type:groupe:piste », langue, libellé, choisie), sélection par `TrackSelectionOverride`, « aucun sous-titre » par désactivation du type texte.

**Logique pure** (`series/EpisodePlayback.kt`, `EpisodePlaybackState`) :
- `nextEpisode(info, current)` : suivant de la même saison (numéro supérieur, tolère trous et désordre), sinon premier de la saison suivante non vide ; `null` après le dernier.
- `resumePosition(pos, dur)` : 0 si >= 90 % ou à <= 30 s de la fin ; durée inconnue : position gardée.
- `EpisodePlayback(profileId, controller, queue, flusher, store, clock, scope, urlFor, loadInfo, hiddenCategories, io, ...)` : `begin(request)` (ferme tout lecteur, refuse si catégorie masquée **avant** de demander la fiche, charge la fiche hors du fil principal, puis `start` sur le fil principal), `start`, `onTick(posS, durS, playing)`, `onEnded`, `onFailed`, `retry`, `cancelNext`, `playNextNow`, `stop`, `onTracks`, `chooseAudio`, `chooseSubtitle`. États : `Idle`, `Loading`, `Playing`, `NextCountdown(secondsLeft, next)`, `Finished(seriesEnded)`, `Failed(message, canRetry)`.
- Progression : toutes les 30 s de lecture, à la pause, à l'arrêt/Retour/onStop/changement de profil, à la fin (position = durée, donc « vu »), à l'erreur. Toujours pour le profil de l'instance (un exemplaire par profil), avec nom de série, affiche (nettoyée par la file) et catégorie. Jamais une position à 0, et rien n'est enregistré si aucune seconde n'a été lue (une reprise ouverte puis quittée ne touche pas la position serveur).
- Épisode suivant : décompte de 10 s annulable (« Lire maintenant », « Annuler »), puis fermeture avant ouverture ; passe d'une saison à l'autre ; dernier épisode : `Finished(seriesEnded = true)` sans décompte. « Masqué gagne » revérifié à chaque ouverture, enchaînement compris (masquée pendant le décompte : refusé).
- Pistes : choix mémorisé par profil et par code de langue (`fre`/`fra`/`fr-CA` -> `fr`), « Aucun » mémorisé (`SUBTITLES_OFF`), réappliqué une seule fois par épisode quand les pistes arrivent ; langue absente du fichier : rien ne change. `TrackPrefsStore` extrait de `LocalStore` (interface étroite, testable).
- Erreurs : messages français (« Impossible de lire cet épisode… », « Cette série est masquée… »), jamais d'adresse ni d'identifiant ; « Réessayer » reprend à la dernière position.

**Envoi de la file** (`series/ProgressFlusher.kt`) : jamais sur le fil principal (dispatcher IO), un seul envoi à la fois, demandes regroupées (1,5 s), aucune demande perdue entre deux passages. Demandé après chaque enregistrement, à l'ouverture de l'onglet Séries, à `onStart` de l'app et au retour du réseau (`registerDefaultNetworkCallback`, permission `ACCESS_NETWORK_STATE` ajoutée). Hors ligne : l'envoi bloqué est repris à la demande suivante (prochain enregistrement de 30 s, retour du réseau, prochain démarrage). `IptvApp.progressQueue` (horloge système) créée.

**Écrans** : `EpisodePlayerScreen` (surface vidéo, commandes Pause / Audio et sous-titres / Quitter par OK ou haut/bas, gauche/droite = -/+10 s, touches média, carte « Épisode suivant dans N s », fin de série, erreur avec « Réessayer » et « Retour », « Connexion… »), `TrackPanel` (audio, « Aucun » + sous-titres, piste choisie marquée). Retour : panneau, puis commandes, puis sortie vers l'écran d'origine (fiche ou accueil Séries) ; `detailPlay`/`pending` repassent à null, ce qui déclenche `playbackClosed(id)` + focus rendu à l'épisode (fiche) ou à la carte d'origine (accueil, `lastOpened`), et l'accueil relit désormais la progression (`reloadProgress`, jamais appelée avant). `SeriesPlaceholder` supprimé.

**Cycle de vie** : `MainActivity.onStop` : `playback.stop()` (position en file), fermeture du lecteur, puis `store.persistNow()` sur le thread IO, attendu au plus 2 s (`runBlocking` + timeout). Au retour, l'écran de lecture (état `Idle`) se ferme tout seul : rien ne reprend en arrière-plan.

## Tests
- `EpisodePlaybackTest` (21) : reprise, règle 90 %/30 s, enregistrement toutes les 30 s avec champs d'enrichissement, pause/arrêt, reprise quittée sans lecture, décompte puis suivant (ordre `stop`, `release`, `create`, `play`), annuler, dernier épisode, saisons (vides, désordre, trous), arrêt pour le bon profil sans reprise, `begin`, masqué (ni fiche ni lecteur), masqué pendant le décompte, préférences inconnues, erreur sans adresse + réessai, fiche absente, langue mémorisée et réappliquée, « Aucun » et autres profils intacts, langue absente, codes/titres, un seul envoi à la fois.
- `PlayerControllerTest` +1 ; `ProgressQueueTest` +2.
- **RED** : stub compilable (`TODO()`) -> `./gradlew --max-workers=2 :tv:testDebugUnitTest --tests '*EpisodePlaybackTest*' --offline` : 21 échecs `NotImplementedError`. Côté partagé : `implausibleStampsIgnoreTheFourteenDayExpiry` -> `19 tests completed, 1 failed` (AssertionError : l'entrée de 1970 avait expiré).
- **GREEN** : `cd apps && ./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest :tv:assembleDebug --offline` -> BUILD SUCCESSFUL ; 169 shared, 232 TV, 0 échec.
- Écarts honnêtes : 5 tests ont d'abord échoué après l'implémentation pour de vrais défauts de comportement (décompte non visible tout de suite, reprise quittée sans lecture ré-enregistrée, « Aucun » non réappliqué quand rien n'était coché) ou d'attentes de test (les envois partaient avant l'assertion) ; corrigés.

## Fichiers
Créés : `series/EpisodePlayback.kt`, `EpisodePlayerScreen.kt`, `TrackPanel.kt`, `ProgressFlusher.kt`, `EpisodePlaybackTest.kt`. Modifiés : `player/PlayerController.kt`, `ExoPlayerPlayer.kt`, `data/LocalStore.kt`, `App.kt`, `MainActivity.kt`, `IptvApp.kt`, `AndroidManifest.xml`, `ProgressQueue.kt` (+ tests), `PlayerControllerTest.kt`, PASSATION, journal. Supprimé : `SeriesPlaceholder.kt`.
Commits : bc0d106, 24d35b2, 35438d1 (+ documents).

## Limites / choix à relire
- La position à l'arrêt est celle du dernier tick (<= 1 s de retard) ; pas de lecture directe du lecteur à l'arrêt.
- Reprise : la règle 90 %/30 s utilise la durée de la fiche ; sans durée, la position demandée est gardée telle quelle.
- Les ticks et `onEnded` viennent de l'écran : si l'activité est détruite sans passer par l'écran, `onStop` enregistre quand même via `playback.stop()`.
- « Masqué » refusé avec des préférences encore inconnues (« réglages pas encore chargés ») : choix prudent.
- Épisode sans extension chez le fournisseur : « mkv » par défaut.
- `LiveDims` (interne au module) réutilisé pour les mesures ; aucune couleur codée en dur hors le noir pur derrière la vidéo (comme `PlayerScreen`).

## À vérifier sur la vraie télé (impossible ici)
1. Reprise à la seconde près sur un vrai MKV (le seek avant lecture fonctionne-t-il sur ces fichiers ? délai d'ouverture).
2. Lecture réelle de MKV/MP4 de la série, serveur à une seule connexion : aucune erreur « connexion refusée » à l'enchaînement fermeture/ouverture.
3. Pistes : la liste audio/sous-titres des vrais fichiers (langues `fre`/`fra`/`eng`, libellés), « Aucun », choix retenu et réappliqué à l'épisode suivant ; sous-titres affichés lisiblement à 3 m.
4. Décompte « Épisode suivant dans 10 s » : focus sur « Lire maintenant », Annuler, enchaînement saison 1 -> 2, dernier épisode de la série.
5. D-pad : OK/haut/bas pour les commandes, gauche/droite -/+10 s, touches média de la télécommande TCL, Retour (panneau -> commandes -> sortie), focus rendu à l'épisode (fiche) ou à la carte (accueil).
6. Coupure réseau pendant la lecture : la position reste en file, part au retour du réseau (callback) ; ne recule pas une position plus récente d'un autre appareil.
7. Veille / Accueil de la télé pendant la lecture : `onStop` enregistre la position, au retour l'écran revient à la fiche sans reprise automatique.
8. Écran de veille de Google TV pendant une longue lecture (`keepScreenOn` sur la vue).
9. Après lecture, « Reprendre » et « Continuer » de l'accueil Séries se mettent à jour ; changement de profil pendant la lecture : position enregistrée pour le bon profil.
10. Horloge fausse au démarrage de la télé : entrées conservées (garde 2026-01-01 / +1 jour).
