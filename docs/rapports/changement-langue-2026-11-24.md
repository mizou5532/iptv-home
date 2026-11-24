# Changer de langue sur une fiche (Séries et Films) — 24 nov. 2026

Signalé par le propriétaire le 23 nov. au soir : profil Hamza (langues : anglais, puis français, puis québécois),
fiche **South Park**, bouton « Version : Anglais » → « Français (France) » : écran « Chargement… » sans fin.

## Reproduit sur la TV Salon (ADB, 0.46.1)

Accueil Séries → fiche South Park → Version → Français (France) : « Chargement… » toujours affiché après 20 s
(capture prise). Même mécanisme pour les films.

## Cause 1 : la nouvelle fiche n'était jamais démarrée

`App.kt` remplace la fiche quand on choisit une version (`pending = v.id to v.categoryId`, films :
`detail = …`). La nouvelle fiche (`SeriesDetailViewModel` / `MovieDetailViewModel`) est bien créée
(`remember(target)`), mais l'écran reste le même dans Compose et ne la démarre qu'avec
`LaunchedEffect(Unit) { vm.start() }`, qui ne se relance jamais. La fiche reste donc dans son état initial
(`loading = true`). Preuve par test : la fiche française n'est même jamais demandée (`infoCalls` ne contient que
la version anglaise).

Correction :
- `App.kt` : chaque fiche (séries et films) est enveloppée dans `key(target)` : une autre version reconstruit
  tout l'écran (démarrage, focus, défilement repartent de zéro).
- `SeriesDetailScreen.kt`, `MovieDetailScreen.kt` : `LaunchedEffect(vm) { vm.start() }` (défense en plus).

## Cause 2 (trouvée pendant l'essai) : la version française jouait en anglais

Le fichier « FR - South Park (1997) (US) » contient deux pistes audio (anglais puis français). La règle des pistes
(`chooseTracks`) appliquait les langues du profil (anglais d'abord) : l'épisode partait en **anglais** même sur la
version française choisie.

Nouvelle règle (séries et films) : **la langue annoncée par la version jouée passe en premier** quand le fichier
a cette piste (« FR - … » → français, « QC - … » → français, « EN - … » → anglais). Sinon, la règle d'avant :
langues du profil (iPhone), puis dernier choix à la télécommande, puis langues par défaut. La langue de la version
vient de l'index des versions (`HomeVersionStore`, sinon `VersionIndexes`), sinon du préfixe du titre
(`versionTag`). Fichiers : `Versions.kt` (`chooseTracks(…, versionAudio)`), `EpisodePlayback.kt`,
`MoviePlayback.kt`, `MainActivity.kt`.

## Tests

- Nouveau `VersionSwitchDpadTest` (télécommande simulée) : séries (anglais → français → anglais) et films
  (anglais → français). **Échouait avant la correction** (attente dépassée sur la fiche française), passe après.
- `EpisodePlaybackTest` / `MoviePlaybackTest` : profil réglé en anglais + version française → piste française.
- `TrackChoiceTest` : langue de la version d'abord ; absente du fichier → règle d'avant.
- Suites complètes dans l'atelier `iptv-dev` : **télé 636/636**, **partagé 436/436**, 0 échec.

## Essais sur la TV Salon (0.46.2, code 79, installée par ADB, non publiée)

APK SHA-256 `f2813c17e7d1a3a32d6cc67d0812ba2f0b48bdbc29fa2de3909cbcb96b48ed13`, 5 534 897 octets, certificat
officiel `5d8877ce…615d4`.

| Essai | Résultat |
|---|---|
| South Park : Anglais → Français (France) | fiche FR en < 4 s, progression reprise (S1 · É6) |
| South Park : Anglais → Québec → Anglais → France | les 3 changements instantanés |
| Lecture S1 É6 version FR (depuis la fiche et depuis Reprendre) | audio **Français** choisi tout seul (avant : Anglais) |
| Film Le Robot sauvage : Top Blu-ray → Français → Anglais | fiches instantanées, lecture FR OK |

Incident pendant l'essai : une touche de trop a lancé quelques secondes de S1 É3 (version FR) ; corrigé avec
l'app (appui long « vu » puis « non vu ») puis relecture de S1 É6 : la reprise du profil Hamza est revenue à
S1 · É6 (≈ 5 min), vérifié dans `watch_progress`.

## Reste à faire / remarques

- **Publication** de la 0.46.2 (stockage `apk` + `version.json` + Vercel) : **pas faite**, attend l'accord du
  propriétaire. Seule la TV Salon a la 0.46.2 (par ADB).
- **Page iPhone « ▶ Regarder »** : même défaut d'audio. Le relais du serveur maison (`StreamRelay.kt`,
  `-map 0:a:0?`) prend toujours la première piste du fichier, donc l'anglais pour « FR - South Park ». Correction
  à faire côté serveur maison (choisir la piste selon la langue de la version), avec redéploiement de
  `iptv-catalogue` : demande l'accord du propriétaire.
- Mineur : sur la fiche, les versions QC et FR affichent toutes deux « Version : Français » (la liste, elle,
  distingue Québec et France). La fiche de « FR - South Park » n'a parfois pas d'affiche. Fournisseur : la version
  « Anglais » du Robot sauvage est annoncée à 1 min (fichier probablement défectueux chez lui).
