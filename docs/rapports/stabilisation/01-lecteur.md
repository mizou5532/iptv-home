# Stabilisation — Zone 1 : lecteur vidéo (bilan)

*18 nov. 2026. Relecture seule, aucune modification. Fichiers relus : `apps/tv/.../player/` (ExoPlayerPlayer, PlayerController, PlayerScreen, PlayerChrome), `series/EpisodePlayback.kt`, `series/EpisodePlayerScreen.kt`, `series/ProgressFlusher.kt`, `movies/MoviePlayback.kt`, `movies/MoviePlayerScreen.kt`, `live/LiveRoute.kt`, `live/LivePlaybackHost.kt` (partie lecture), `MainActivity.kt` (cycle de vie), `shared/.../progress/ProgressQueue.kt`. Media3 1.11.1.*

## Ce qui est solide

- **Une seule connexion** : `PlayerController.open` arrête et libère l'ancien lecteur avant d'en créer un ; `onStop` ferme tout (la télé qui passe en veille ou sur une autre app ne garde pas la ligne).
- **Progression** : file locale durable, « le plus récent gagne », écrite sur le disque à `onStop`, renvoyée au retour du réseau, jamais une position à 0, jamais rien sans une vraie seconde de lecture. Bon travail.
- **Aucune adresse de flux dans les journaux ni les messages** (journaux Media3 coupés, codes d'erreur seulement).
- Réponses tardives ignorées (numéro de génération), décompte d'épisode suivant annulable, masqué gagne à chaque lecture.
- Direct : bascule automatique vers une autre copie sur erreur ou après 12 s sans image.

## Constats

### Importants

**1. Direct : image figée sans réaction (jusqu'à une minute environ).**
Une fois la chaîne démarrée, rien ne surveille un flux qui s'arrête de livrer des images (`started` reste vrai, l'état « tampon » n'est jamais regardé). Media3 attend son délai de lecture (8 s) puis réessaie plusieurs fois (6 tentatives en direct) avant de signaler l'erreur qui déclenche la bascule. Pendant ce temps l'image reste gelée sans message.
→ Proposition : si le lecteur reste en « tampon » plus de ~8 s après avoir démarré, afficher « Reconnexion… », rouvrir la même copie une fois, puis basculer sur la copie suivante comme aujourd'hui.

**2. Épisode ou film marqué « vu » si le flux s'arrête avant la fin.**
Vérifié dans Media3 1.11.1 : `DefaultHttpDataSource.readInternal` rend « fin du fichier » quand le serveur ferme la connexion, sans erreur. Si le fournisseur coupe un film au milieu (ce que font certains serveurs Xtream), le lecteur passe à « fin » : `onEnded` enregistre alors la position = durée (donc **vu**), et pour une série **lance le décompte de l'épisode suivant**.
→ Proposition : une fin à plus de ~3 min de la durée connue est traitée comme une coupure (reprise automatique à la position, voir point 3), jamais comme « vu ».

**3. Films et séries : aucune reprise automatique après une coupure.**
Toute erreur (réseau qui saute, connexion du fournisseur fermée après une longue pause…) affiche aussitôt « Impossible de lire… », et il faut appuyer sur « Réessayer ».
→ Proposition : 2 tentatives silencieuses (« Reconnexion… ») à la même position avant d'afficher le message.

### Gênants

**4. Avance/recul rapide : une demande au fournisseur par appui.**
Gauche/droite en plein écran appellent `seekBy(±10 s)` à chaque appui, y compris la répétition d'une touche maintenue. Chaque saut dans un MKV ouvre une nouvelle demande chez le fournisseur (une seule connexion permise) : maintenir la touche peut donner un écran noir ou une erreur.
→ Proposition : cumuler les appuis (afficher la cible « +1:30 »), et ne sauter qu'une fois la touche relâchée depuis ~0,6 s.

**5. Film : le message « Ce film est masqué » n'apparaît jamais pendant la lecture.**
`MoviePlayback.onTick` appelle `stop()` (état « Idle », ce qui ferme l'écran) avant `fail(...)`. Le film s'arrête bien, mais l'écran revient à la fiche sans explication.

**6. Film : les touches Lecture et Pause séparées sont ignorées.**
Les séries réagissent à Lecture/Pause, Lecture et Pause ; les films seulement à Lecture/Pause. Selon la télécommande, la touche peut ne rien faire.

**7. Marquages Media3 manquants** (4 erreurs de l'analyse Android, `ExoPlayerPlayer.kt` 52–56) : sans effet aujourd'hui, mais une mise à jour de Media3 pourrait casser la construction ou le comportement sans prévenir.

**8. Zapping : un lecteur neuf à chaque chaîne.**
Chaque changement détruit puis recrée le lecteur et son décodeur vidéo, ce qui coûte quelques centaines de millisecondes, plus sur le Fire Stick. Réutiliser le même lecteur accélérerait le zapping, mais touche à la garantie « une seule connexion ». → **À mesurer sur la TCL avant de décider**, à faire en zone 2 (Direct) si le zapping te paraît lent.

### Détails

**9. Seulement 5 tests pour le lecteur.** Chaque correction ci-dessus viendra avec ses tests ; on y ajoutera les cas de base (fin, erreur, pause, reprise).
**10. Code en double entre séries et films** (choix des pistes, enregistrement de la position). Pas de bogue aujourd'hui ; à regrouper seulement si on y retouche (risque de régression sinon).

## Ordre de correction proposé

1, 2 et 3 ensemble (même mécanisme : surveillance et reprise), puis 4, puis 5, 6, 7 et 9. Le point 8 se décide en zone 2 après ton avis sur la vitesse du zapping.
Une seule version publiée à la fin de la zone, puis essai sur la TCL : couper le Wi-Fi 5 s pendant un film et pendant une chaîne, maintenir → pendant un film, mettre en pause 10 min puis reprendre.

## Corrections faites et publiées — 0.30.1 (code 51), 18 nov. 2026

Choix du propriétaire : « corrige tout cela, sauf le point 8 ».

| Point | Correction | Où |
|---|---|---|
| 1 | Direct : 8 s de « tampon » après le démarrage → « Image interrompue : reconnexion… », même copie rouverte une fois (même adresse, lecteur neuf après fermeture de l'ancien) ; figée de nouveau moins de 60 s après → bascule vers la copie suivante comme pour une erreur | `LivePlaybackHost.stalled`, `LiveEvent.Reconnecting`, garde dans `LiveRoute`, `ExoPlayerPlayer.buffering` |
| 2 | Fin à plus de 3 min de la durée **annoncée par le lecteur** (pas celle du fournisseur, parfois fausse) = coupure : jamais « vu », jamais de décompte ; reprise à la position. Plus de crédit : position gardée + « Impossible de lire… » avec Réessayer | `isEarlyEnd`, `EpisodePlayback.onEnded`, `MoviePlayback.onEnded` |
| 3 | 2 reconnexions silencieuses (« Reconnexion… ») avant le message ; crédit rendu après 60 s de lecture ; pause gardée ; « Réessayer » reprend à la position exacte (plus de retour à 0 après 90 %) | `recover()` dans les deux lectures |
| 4 | ←/→ regroupés : cible affichée « ▶▶ 12:34 (+1:30) », un seul saut 0,7 s après le dernier appui | `player/Recovery.kt` (`SeekBatch`) |
| 5 | Film masqué pendant la lecture : le message reste à l'écran | `MoviePlayback.onTick` |
| 6 | Films : touches Lecture et Pause séparées | `MoviePlayerScreen` |
| 7 | Marquage `UnstableApi` ; 2 fausses alertes justifiées (`RestrictedApi`, `StateFlowValueCalledInComposition`) : **analyse Android 0 erreur** (8 avant) | `ExoPlayerPlayer`, `MainActivity`, `MediaMeta` |
| 9 | 19 tests ajoutés (lecture, reconnexion, sauts, direct figé) | `RecoveryTest`, `EpisodePlaybackTest`, `MoviePlaybackTest`, `LiveNavigationTest` |

Relecture indépendante : 3 défauts trouvés dans la première version et corrigés (coupure sans crédit encore marquée « vue » ; « Réessayer » d'un film après un échec de chargement reprenait à la position du film précédent ; message précis « masquée » remplacé par le message général), plus 2 améliorations (durée du fichier seulement ; crédit rendu au temps, pas à la position).

Tests : 229 serveur et 265 page (inchangés), 361 partagés, 458 télé, 0 échec. APK SHA-256 `7a2e485d2245b1850dff506bdad0054f21526a47ce85e34e79882028456f6edf`, 14 567 871 octets, `minSdkVersion 25`, certificat attendu, identique par Supabase et le lien Vercel ; page inchangée (`index-dre1hJSS.js`) ; Vercel `dpl_8zdmAfTeoAKjLfiCp5mRxwMECzGG` ; règles stockage : les 4 `avatars_*` (règles temporaires retirées).

**Essai à faire sur la TCL** : couper le Wi-Fi ~5 s pendant un film puis pendant une chaîne ; maintenir → pendant un film ; pause 10 min puis reprise ; touches Lecture/Pause dans un film.
