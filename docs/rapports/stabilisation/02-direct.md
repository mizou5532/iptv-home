# Stabilisation — Zone 2 : le Direct (bilan)

*18 nov. 2026, après la 0.30.1. Relecture seule. Fichiers relus : `apps/tv/.../live/` (LiveSessions, PrefsWatcher, GroupsWatcher, LiveRefresher, LiveBrowseViewModel, LiveBrowseScreen, LiveRoute, LivePlaybackHost, LivePlaybackState, LiveTopBar, epg/EpgRepository), `shared/.../catalog/CatalogRepository.refreshLive`, `App.HomeForProfile`.*

## Ce qui est solide

- **Masqué gagne** partout : avant chaque lecture, la visibilité est relue ; une catégorie masquée pour tout le monde n'est jamais téléchargée (`refreshLive` ne demande que les catégories voulues).
- **Fluidité de la liste** : tous les calculs lourds (groupes de copies sur ~20 000 chaînes, lecture du cache) se font hors du fil principal ; listes paresseuses avec clés ; mémoire des 24 dernières sections ; une section n'est lue qu'après 150 ms d'arrêt du focus ; zapping haut/bas attendu 250 ms.
- **Guide** : 2 demandes à la fois au plus, cache de 100 chaînes, rien en arrière-plan, guide complet pas redemandé avant 1 h s'il est vide.
- **Une seule session par profil**, fermée proprement (portées annulées, lecteur fermé en quittant le Direct).
- 167 tests sur cette partie.

## Constats

### Importants

**1. La liste des chaînes n'est mise à jour qu'au lancement de l'app.**
`LiveSessions.catalogDone` : une fois la première mise à jour faite, plus aucune tant que l'app n'est pas fermée par Android. Sur une Google TV, l'app reste souvent en mémoire pendant la veille, parfois des jours. Conséquences : nouvelles chaînes du fournisseur (dont les chaînes d'événements sportifs du jour) absentes du Direct ; une catégorie réaffichée « pour tout le monde » sur l'iPhone ne revient qu'au redémarrage (limite déjà notée en section 9 de la passation).
→ Proposition : relancer la mise à jour en arrière-plan (a) au retour de veille si la dernière date de plus de 6 h, (b) quand l'iPhone réaffiche une catégorie masquée pour tout le monde. Toujours une seule à la fois, toujours sans les catégories masquées.

### Gênants

**2. Les réglages sont vérifiés toutes les 4 s, même pendant un film.**
`PrefsWatcher` interroge Supabase toutes les 4 s tant que l'app est à l'écran, quel que soit l'onglet : environ 900 demandes par heure, aussi pendant 2 h de film. Petites demandes, mais inutiles et elles occupent le réseau du Fire Stick pendant la lecture.
→ Proposition : 4 s quand on est sur la liste du Direct (pour voir tout de suite ce qu'on organise sur l'iPhone), 30 s ailleurs et pendant une lecture plein écran.

**3. Zapping : un lecteur neuf à chaque chaîne** (point 8 de la zone 1). Décision selon ton avis : si le changement de chaîne te paraît lent sur la TCL ou le Fire Stick, on garde le même lecteur d'une chaîne à l'autre (en vérifiant que le fournisseur ne voit jamais deux connexions). Sinon, on ne touche à rien (le plus sûr).

### Détails

**4. L'horloge de la barre du haut peut retarder jusqu'à 20 s** (relue toutes les 20 s, pas au changement de minute). → la relire pile au changement de minute.

## Ordre de correction proposé

1, puis 2, puis 4 ; 3 seulement si tu le demandes. Tests ajoutés pour chaque point, relecture indépendante, une version publiée.

Essai TCL ensuite : laisser la télé en veille une nuit, rallumer → la liste se met à jour toute seule (« Mise à jour » en haut) ; réafficher une catégorie depuis l'iPhone → elle apparaît sur la télé en moins d'une minute.

## Corrections faites et publiées — 0.30.2 (code 52), 18 nov. 2026

Choix du propriétaire : « seulement la 1, 2 et 4 » ; le changement de chaîne ne lui paraît pas lent → point 3 abandonné (lecteur neuf à chaque chaîne conservé).

| Point | Correction | Où |
|---|---|---|
| 1 | Liste remise à jour en arrière-plan : au retour de veille si la dernière a 6 h ou plus ; à l'ouverture d'un profil si elle est vieille ; quand une catégorie masquée pour tout le monde lors de la dernière mise à jour ne l'est plus (vérifié à chaque nouvelle révision des réglages, revérifié au tour suivant si le réseau manque). Toujours par la mise à jour unique partagée, qui relit d'abord la liste des masquées (règle 7). Rien ne démarre en arrière-plan. Profil dont le Direct n'est pas ouvert : faite à son ouverture | `LiveSessions` (`resume`, `onHouseholdRevision`, `refreshAgain`), `LiveBrowseViewModel.refreshNow`, `LiveRefresher.lastHidden` |
| 2 | Réglages vérifiés toutes les 4 s quand la liste du Direct est à l'écran (vérification immédiate à son apparition), 30 s ailleurs (Séries, Films, Sport, plein écran) | `PrefsWatcher(period)`, `pokeNow`, `LiveSession.setFastPoll`, `LiveRoute(onListVisible)` |
| 4 | Horloge relue pile au changement de minute et au retour de veille | `LiveTopBar.msToNextMinute` |

Relecture indépendante : rien ne contrevient aux règles (masquées jamais téléchargées, une mise à jour à la fois, rien en veille) ; corrigé : vérification lancée juste avant la veille qui pouvait déclencher une mise à jour en arrière-plan, demande en attente perdue face à celle du démarrage, accès concurrents, catégorie réaffichée oubliée si le réseau coupait, horloge au réveil. Changement voulu : Séries et Films voient un réglage de l'iPhone en 30 s au plus (au lieu de 4 s).

Tests : 361 partagés, 466 télé (8 nouveaux), 0 échec ; `SearchDpadTest.clavier_qwerty_ecrit_efface_et_accents` a échoué une fois dans la suite complète puis réussi 3 fois sur 3 seul (sensible au minutage, à surveiller en zone 6). Analyse Android : 0 erreur. APK SHA-256 `5ed11a5beced58884f47c723f1c7dce027b6e38ecf09ba11bf8a0ff0f307a8f0`, 14 567 871 octets, `minSdkVersion 25`, certificat attendu, identique par Supabase et Vercel ; page inchangée ; Vercel `dpl_7qaovdxpmaSATWtymcD8yevuWmMo` ; règles stockage : les 4 `avatars_*`.

**Essai TCL** : veille d'une nuit puis rallumage → « Mise à jour » en haut du Direct ; réafficher une catégorie depuis l'iPhone (télé sur la liste du Direct) → elle apparaît en moins d'une minute.
