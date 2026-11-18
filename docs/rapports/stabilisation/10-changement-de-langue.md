# Changement de langue bloqué sur « Chargement… » (18 nov. 2026)

*Signalement du propriétaire : sur une série regroupée en plusieurs langues (ex. Les Simpsons, ouverte en anglais selon sa préférence), choisir une autre version (français) dans « Version : … » affiche « Chargement… » pour toujours. Même chose sur toutes les séries essayées ; les films ont le même mécanisme.*

## Cause

Choisir une version remplace la fiche ouverte par celle de l'autre version (`App.kt`, `SeriesTab` et `MovieTab`). Une nouvelle fiche (ViewModel) était bien créée, mais l'écran de fiche restait le même composant : son démarrage (`LaunchedEffect(Unit) { vm.start() }`) ne se fait qu'à la première ouverture. La fiche de l'autre version n'était donc **jamais démarrée** : rien n'était demandé au fournisseur et l'état restait « chargement ». Vérifié par un test : sans correctif, une seule demande de fiche (`s1-1`), jamais celle de l'autre version.

## Correction — 0.32.2 (code 58)

| # | Correction | Où |
|---|---|---|
| 1 | Chaque version ouverte a sa propre fiche complète (`key(action)` / `key(target)`) : changer de version repart de zéro (chargement, saison, focus) | `App.kt` (`SeriesTab`, `MovieTab`) |
| 2 | Le démarrage de la fiche suit la fiche (`LaunchedEffect(vm)`) : par sécurité, même si l'écran était réutilisé | `SeriesDetailScreen.kt`, `MovieDetailScreen.kt` |

Test : `VersionSwitchDpadTest` (séries : passer à l'autre langue puis revenir ; films : passer à l'autre langue), échoue sans correctif, passe avec. Le banc d'essai (`HomeRig.versions`) peut maintenant fournir des versions.

**Essai TCL** : ouvrir Les Simpsons (s'ouvre en anglais), « Version : … » → Français : la fiche française s'affiche en quelques secondes avec ses saisons ; revenir à l'anglais ; même essai sur un film à plusieurs langues.
