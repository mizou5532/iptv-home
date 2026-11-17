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
