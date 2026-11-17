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
