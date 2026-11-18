# Stabilisation — Zone 4 : les Séries (bilan)

*18 nov. 2026, après la 0.30.3. Relecture seule. Fichiers relus : `apps/tv/.../series/` (SeriesHomeViewModel, SeriesHomeScreen, SeriesDetailViewModel, SeriesRows/HomeData), `shared/.../series/SeriesRepository.kt`, `shared/.../versions/Versions.kt` (`collapseVersions`), fonctions serveur `record_progress` / `set_watched`. La lecture d'un épisode (EpisodePlayback, EpisodePlayerScreen) a été corrigée en zone 1.*

## Ce qui est solide

- **Cache du fournisseur** : catégories et séries gardées 1 jour, fiches 6 h ; une réponse vide ne remplace jamais un cache rempli ; le cache périmé sert si le fournisseur ne répond pas.
- **Une seule demande à la fois au fournisseur** ; rangées chargées seulement près de l'écran, 2 d'avance, mémoire bornée (24 rangées, 40 000 affiches).
- **Masqué gagne** partout, y compris avant de demander une fiche ; rien n'est demandé tant que les préférences sont inconnues.
- **« Vu » et position** : le serveur garde toujours la plus récente (`client_updated_at`) ; une ancienne position encore en route ne défait pas un « vu » (vérifié dans `record_progress`).
- Messages clairs quand quelque chose manque (hors ligne, tout masqué, catalogue indisponible).

## Constats

### Gênants

**1. Saccades possibles en faisant défiler l'accueil (probable, à confirmer sur la TCL).**
Chaque fois qu'une rangée ou une fiche arrive, l'accueil est entièrement recalculé (y compris le regroupement des versions de toutes les rangées en mémoire, jusqu'à des dizaines de milliers d'affiches) **sous un verrou**. Or l'écran prend ce même verrou à chaque rangée qui apparaît en défilant (`loadCategory`, `cancelCategory`) : le fil de l'écran attend la fin du calcul. Sur le Fire Stick surtout, ça peut donner de petits à-coups en descendant dans les catégories pendant le chargement. Le même schéma existe dans l'accueil Films (zone 5).
→ Proposition : l'écran n'attend plus jamais le calcul (il note la rangée et repart), et les rangées déjà regroupées sont gardées tant que rien ne change (seule la nouvelle rangée est calculée).

**2. Le catalogue attend Supabase à l'ouverture de l'onglet.**
À l'ouverture : progression gardée → **progression du serveur (Supabase)** → seulement ensuite les catégories du fournisseur → fiches. Si Supabase est lent (ou la connexion internet hésitante), les rangées de catégories attendent pour rien : les deux sont indépendants.
→ Proposition : demander la progression du serveur et les catégories en même temps.

### Détails

**3.** Je n'ai pas trouvé d'autre problème : pas de plantage possible repéré, pas de demande en arrière-plan (tout passe par l'écran ouvert), mémoire bornée.

## Ordre de correction proposé

2 (petit, sûr), puis 1. Tests pour chaque point, relecture indépendante, une version publiée.

Essai TCL ensuite : ouvrir Séries et descendre vite dans les catégories pendant le chargement (fluidité), surtout sur le Fire Stick.

## Corrections faites et publiées — 0.30.4 (code 54), 18 nov. 2026

Choix du propriétaire : « corrige », et il avait remarqué les à-coups → corrigé aussi dans **Films** (même mécanisme).

| Point | Correction | Où |
|---|---|---|
| 1 | L'écran ne prend plus jamais le verrou de l'accueil : une rangée qui apparaît ou disparaît est notée dans une file (une à la fois, dans l'ordre) traitée hors du fil de l'écran. Les rangées déjà regroupées par versions sont gardées (seule une rangée nouvelle ou changée est recalculée). Séries **et Films** | `SeriesHomeViewModel` et `MovieHomeViewModel` (`screenEvents`, `collapsed`) |
| 2 | Progression du serveur (Supabase) et catégories du fournisseur demandées en même temps ; aucun message « tout est masqué / impossible » tant que la progression n'est pas arrivée | `SeriesHomeViewModel.start`, `rebuildLocked` |

Relecture indépendante : aucun problème sérieux (ordre des événements, mémoire des rangées, une seule demande au fournisseur vérifiés) ; corrigés : message hâtif possible à l'ouverture, rangée de films demandée pile à la fin d'un chargement oubliée jusqu'au mouvement suivant.

Tests : 361 partagés, 474 télé (3 nouveaux : catalogue sans attendre Supabase, rangée qui n'attend jamais le recalcul, Séries et Films), 0 échec ; analyse Android 0 erreur. APK SHA-256 `82855b6cc8d4f7eae4c00fb18a64828e77e05dd08890f948e9df57caeb64929e`, 14 584 255 octets, `minSdkVersion 25`, certificat attendu, identique par Supabase et Vercel ; page inchangée ; Vercel `dpl_CxjoVLUrnqLDuV5JHsXj83k2U794` ; règles stockage : les 4 `avatars_*` (vérifié après un redémarrage de la machine de travail pendant la publication).

**Essai TCL / Fire Stick** : ouvrir Séries puis Films et descendre vite dans les catégories pendant le chargement.
