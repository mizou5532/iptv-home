# PASSATION — iptv-home

> **À lire en premier par toute IA ou personne qui reprend le projet.**
> Ce fichier dit ce qui existe, où on en est, ce qui reste, comment travailler et ce qu'il ne faut jamais faire.
> Il est tenu à jour **à chaque étape franchie** (voir « Règle de mise à jour » en bas).

*Dernière mise à jour : 15 nov. 2026. **Étape 4A (séries) terminée** : 0.4.1 en ligne, essai télé réussi, `plan-4-series` **fusionnée dans main**. Guide des programmes reporté (décision du propriétaire). **Accord permanent du propriétaire : fusionner dans main dès qu'un essai télé est réussi** (les publications et migrations demandent toujours son accord à chaque fois). Suite : attendre sa consigne (4B films prévu ensuite ; rien n'est commencé). La section 0 ci-dessous raconte l'essai raté de la 0.4.0 et ses corrections : c'est de l'historique. Journal : `docs/rapports/journaux/plan-4-journal.md`.*

---

## 0. Reprise immédiate (à lire avant tout le reste)

**Situation (15 nov. 2026, soir).** La 0.4.0 (séries) est en ligne : migration 0011 en production, page iPhone et APK publiés, règles temporaires de stockage supprimées (0 règle sur `storage.objects`). **L'essai sur la TCL a échoué.** Le propriétaire n'aime pas ce qu'il voit. Rapport complet, avec ses essais ciblés : `docs/rapports/tele-4a-series.md`.

**Problèmes signalés (n° utilisés partout) :**
1. Direct, télécommande : passer des catégories aux chaînes demande OK ; gauche/droite font « disparaître » le focus.
2. Plein écran du direct **noir** (le son joue). L'image n'apparaît qu'après OK (commandes affichées) ou un changement de chaîne (haut/bas). Attendre 15 s ne suffit pas.
3. OK sur un épisode : bref écran noir, puis retour immédiat à la fiche, rien ne joue. OK maintenu (marquer vu) fonctionne.
4. D'autres petits problèmes non détaillés (à demander au propriétaire).
5. Guide « Programme indisponible » partout, même sur les grandes chaînes.
6. « Mise à jour » reste affiché en permanence, même après un changement de profil.
7. Séries : seulement 2 rangées d'environ 10 séries (catégories en arabe).
8. Direct à l'ouverture : parfois rien (Favoris vide) ; il faut ~5 s ou bouger la télécommande.
9. Lenteur au passage sur chaque catégorie. Le propriétaire veut rapide et fluide.

**Cause de fond :** aucune interface n'avait de test qui imite la télécommande. Les tests ne couvraient que la logique, et il n'y a ni télé ni émulateur ici (pas de `/dev/kvm`).

**Hypothèses de Claude (à confirmer) :**
- **(2)** Une SurfaceView ajoutée dans un AndroidView n'est pas redessinée quand le lecteur passe de l'aperçu au plein écran. Elle s'affiche dès que Compose dessine par-dessus. Correction prévue : TextureView (PlayerView en `surface_type=texture_view`) ou une surface unique.
- **(3)** `EpisodePlayerScreen` lit un `state` périmé (`Idle`, via `collectAsState`) juste après `begin()`, et `LaunchedEffect(state, begun)` appelle alors `onClose()`.

**État du travail (15 nov. 2026, tard — quota de Claude épuisé, passation à l'autre IA) :**

| Agent | Problèmes | Branche | État |
|---|---|---|---|
| A | 1, 2, 3, 8, 9, plus des tests d'interface et un diagnostic | `plan-4-series` | ✅ **Terminé** (`3178869`), rapport `docs/rapports/tele-4a-enquete.md`. 1, 3, 8 et 9 ont leur cause confirmée par des tests et sont corrigés. 2 (écran noir) est corrigé par TextureView mais **non vérifié** sans la télé. A a aussi corrigé un piège de focus Direct↔Séries, un titre de guide en Latin-1 qui vidait le guide (`e080595`), et une carte « Chargement… » de séries désormais focalisable, sans plafond de 20 par rangée (`87e14a4`). |
| B | 5 (guide), 6 (« Mise à jour ») | `fix-epg-refresh` | ✅ **Terminé et fusionné** dans `plan-4-series` (`4a292bf`). Rapport : `docs/rapports/tele-4a-guide-maj.md`. (6) causes confirmées et corrigées ; (5) formats et secours ajoutés, cause exacte à confirmer sur la télé (calque de diagnostic, ligne « Guide »). |
| C | 7 (rangées de séries) | `fix-series-rows` | ✅ **Terminé et fusionné** dans `plan-4-series` (`e3b3e78`). Rapport : `docs/rapports/tele-4a-series-rangees.md`. Même cause de fond que A (une rangée non chargée n'avait rien de focalisable). En plus : chargement anticipé de 2 rangées sous l'écran, requêtes en vol gardées, nouvel essai d'une rangée en échec, mémoire bornée, lecture Xtream tolérante, « Mise à jour » de l'onglet Séries corrigé. |

On a demandé à B et C de se rebaser sur `origin/plan-4-series` (après les commits de A) avant de finir ; c'est peut-être fait, peut-être pas.

**Tests à la fin de A :** 174 partagés, 258 télé (dont 13 tests d'écran Robolectric), 167 backend, 124 web, tous verts.
**Attention Gradle :** Maven Central renvoyait 404/429. L'agent A a utilisé un script d'initialisation Gradle **hors dépôt** qui met le miroir Google de Maven Central en premier. Exemple : fichier `~/.gradle/init.d/miroir.gradle` contenant `allprojects { repositories { maven { url "https://maven-central.storage-download.googleapis.com/maven2/" } } }` et, pour les plugins, `settingsEvaluated { it.pluginManagement.repositories { maven { url "https://maven-central.storage-download.googleapis.com/maven2/" }; gradlePluginPortal(); google() } }`. Ne pas le committer.

**Écran de diagnostic (ajouté par A) :** appuyer 3 fois en 2 s sur Info, Menu ou 0 ; ou haut haut bas bas gauche droite gauche droite en 6 s. Il affiche l'état du lecteur, si une vue est attachée, si la première image est rendue, la taille de la vidéo et le dernier code d'erreur. Jamais d'adresse ni d'identifiant.

**Mise à jour (15 nov., encore plus tard) :** A, B, C fusionnés ; relecture indépendante faite (`docs/rapports/4a1-relecture.md`) ; **ses 5 points importants I1–I5 sont corrigés par Claude**, chacun avec un test rouge puis vert (détail en bas de ce rapport). Un piège de plus trouvé et corrigé : dans les lecteurs, **Retour était avalé** et il fallait appuyer deux fois (`Modifier.backKey`). Suites complètes : **193 partagés, 285 télé, 167 backend, 124 web, 0 échec**. **0.4.1 (versionCode 5) construite et signée, certificat vérifié** (`apps/tv/build/outputs/apk/release/tv-release.apk`, à reconstruire avec `:tv:assembleRelease` dans une nouvelle session : le fichier n'est pas dans le dépôt). **0.4.1 PUBLIÉE** avec l'accord du propriétaire : APK dans le compartiment `apk` (vérifié identique au fichier signé, aussi par le lien `https://iptv-home-config.vercel.app/iptv-home.apk`) et `version.json` 0.4.1 sur Vercel. Règles temporaires supprimées et vérifiées (0 règle sur `storage.objects`). Les étapes 1 à 6 sont faites ; reste 7 (essai télé) et 8.

**Si tu reprends (autre IA), dans l'ordre :**
1. `git fetch origin`. Comparer `git log origin/plan-4-series..origin/fix-epg-refresh` et `..origin/fix-series-rows`. Lire les rapports de B et C s'ils existent. S'ils sont absents, reprendre : (5) guide « Programme indisponible » : suivre l'appel EPG Xtream, le stream_id passé, le décodage base64, les horodatages et le fuseau, et les exceptions avalées ; (6) vérifier que `d75accc` suffit, avec un test qui reproduit « refreshing » bloqué ; (7) vérifier que toutes les catégories de séries non masquées s'affichent en rangées et se chargent en descendant.
2. Pour chaque problème pas encore résolu : trouver la cause, écrire un test rouge, corriger, test vert. Pas de correctif à l'aveugle.
3. Fusionner `fix-epg-refresh` et `fix-series-rows` dans `plan-4-series` (résoudre les conflits sans écraser), puis relancer **toutes** les suites :
   - `cd apps && ./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest :tv:assembleDebug --rerun-tasks -q`
   - `npm test -w backend`
   - `npm test -w web/config`
4. Faire une relecture indépendante de l'ensemble des correctifs.
5. Version **0.4.1** (versionCode 5), `version.json`, `:tv:assembleRelease`, vérifier le certificat (SHA-256 en section 6).
6. **Demander l'accord du propriétaire**, puis publier l'APK seul (procédure `docs/rapports/deploiement-page.md` : règle d'écriture temporaire sur le compartiment `apk`, envoi, **suppression de la règle**, vérifier qu'il reste 0 règle). Pas de nouvelle migration prévue.
7. Le propriétaire essaie avec le lien `https://iptv-home-config.vercel.app/iptv-home.apk` (application Downloader sur la télé). Lui expliquer comment ouvrir l'écran de diagnostic s'il existe.
8. Ne fusionner dans main qu'après un essai réussi et son accord.

**Leçons à garder :** chaque écran doit avoir un test qui imite la télécommande. Les demandes d'autorisation de Supabase expirent si le propriétaire n'est pas devant : le prévenir avant. Pour Vercel, déployer les fichiers avec `projectSettings` `{framework:null, buildCommand:"", installCommand:"", outputDirectory:"."}`. Sinon le déploiement échoue sur `npm install`. Le `vercel.json` du dépôt contient désormais la réécriture `/assets/:file`.

---

## 1. Le projet en 30 secondes

Un **player IPTV familial**, fait maison, pour remplacer IPTV Player Pro sur la télé du salon :
rapide, fluide, bien organisé, personnalisable, **sans abonnement de player**, avec profils, favoris,
listes et progression **synchronisés** entre appareils.

- **Propriétaire** (il parle français, pas développeur) : sport en direct — Canadiens/LNH, FC Barcelone toutes compétitions, NFL, NBA en bonus.
- **Sa sœur** : séries (la progression est essentielle). **Son frère** : films.
- **Appareil principal** : télé **TCL QM85 65" (65QM851G), Google TV**. Plus tard : app **Windows/Mac**. **Pas d'app iPhone** (refus de l'abonnement Apple de 99 $/an). Les iPhone servent seulement à **configurer et organiser** via une page web.
- **Fournisseur IPTV** : API **Xtream Codes**, en **http seulement**, ~75 % de films en MKV, **une seule connexion à la fois** (`max_connections = 1`), refuse la vidéo aux adresses IP de serveurs (datacenter) mais accepte l'API.
- **Non-objectifs** : enregistrement, multi-écran, contrôle parental. Téléchargement hors ligne : à la toute fin.

Documents de référence :
- Conception générale : [`docs/superpowers/specs/2026-11-14-player-iptv-design.md`](docs/superpowers/specs/2026-11-14-player-iptv-design.md)
- Conception de B1 (organiser depuis le téléphone) : [`docs/superpowers/specs/2026-11-14-b1-organiser-depuis-le-telephone-design.md`](docs/superpowers/specs/2026-11-14-b1-organiser-depuis-le-telephone-design.md)
- Feuille de route (texte, source de vérité sur GitHub) : [`docs/superpowers/plans/2026-11-14-feuille-de-route.md`](docs/superpowers/plans/2026-11-14-feuille-de-route.md)
- Feuille de route interactive (privée, tenue par Claude, miroir de la précédente) : https://claude.ai/artifact/79SZ97WaDCLXmm1t4tVVNs

---

## 2. Règles absolues (ne jamais enfreindre)

1. **Aucun identifiant IPTV** (adresse du serveur, nom d'utilisateur, mot de passe) dans le dépôt, un commit, un journal, un rapport, une capture, un message d'erreur ou une conversation. Le propriétaire les saisit lui-même sur la page de configuration. Les adresses de flux contiennent ces identifiants : ne jamais les journaliser ni les afficher.
2. **Jamais la clé `service_role` de Supabase** (ni la lire, ni l'écrire, ni l'utiliser). La clé « publishable » est publique par conception.
3. **Ne jamais créer de foyer ni s'inscrire sur le vrai projet Supabase depuis des tests** : il n'accepte qu'un foyer, celui du propriétaire. Les tests backend tournent sur un Postgres local.
4. **Publier ou créer un service en ligne = accord explicite du propriétaire d'abord** (déploiement, nouveau projet, etc.). Appliquer une migration à la production : il l'approuve dans l'invite Supabase.
5. **Ne jamais régénérer la clé de signature** de l'app télé (`apps/tv/signature/`) : sinon la mise à jour refuse de s'installer par-dessus et il faut tout réappairer.
6. Ne pas mettre de nom, courriel ou détail personnel du propriétaire dans des requêtes vers des services externes.
7. Une catégorie masquée « pour tout le monde » ne doit **jamais être téléchargée** chez le fournisseur.
8. Textes visibles en **français**. Le propriétaire n'est pas développeur : lui parler simplement, sans jargon.

---

## 3. État actuel (où on en est)

| Étape | Statut | Détail |
|---|---|---|
| 1. Vérifications et fondations | ✅ terminé | Lecture navigateur impossible (http + MKV) ; serveurs refusés par le fournisseur → décision : app télé native + app ordinateur plus tard. Rapports : `docs/rapports/lecture-navigateur.md`, `source-sport.md`. |
| 2. Service de synchro (Supabase) | ✅ terminé, en production | Migrations 0001–0007. `docs/rapports/synchro-production.md`, `plan-2-decisions.md`. |
| 3A. App télé : connexion et socle | ✅ terminé, **installée sur la TCL (0.1.0)** | QR + code, « Qui regarde ? », chaînes en direct, lecture plein écran. Essai réussi : `docs/rapports/tele-premiere-version.md`. |
| 3B1. Organiser depuis le téléphone + nouvel écran du direct | ✅ terminé, **0.2.0 installée, essai réussi** | Fusionné dans `main`. Rapport : `docs/rapports/tele-b1-organiser.md`. |
| 3B2. Le direct complet | ✅ 0.3.0 en ligne, fusionné dans main | Guide, copies et aperçu livrés ; essai TCL à faire. « Prendre la main » reporté à l'étape 8. Migration 0010 locale non appliquée. |
| 4A. Séries | construite sur `plan-4-series`, livraison en attente d'accord | Tâches 1–7 terminées et relues (rapports `docs/rapports/4a-*.md`). Tâche 8 : relecture finale + correctifs faits, 0.4.0 construite ; reste mise en ligne (accord), essai TCL, fusion. |

Conception : `docs/superpowers/specs/2026-11-15-etape4a-series-design.md`, section 9 comprise. Plan : `docs/superpowers/plans/2026-11-15-plan-4a-series.md`. Corrections prioritaires : `docs/rapports/4a-tache3-corrections-a-faire.md`. Aucun nouveau déploiement, aucune migration de production ni fusion autorisés.
| 4. Films et séries | à faire | ≈ 3–4 séances |
| 5. Recherche | à faire | ≈ 1 séance |
| 6. Sport | à faire | ≈ 2 séances |
| 7. Thèmes (clair, fun) et tailles | à faire | ≈ 1–2 séances |
| 8. App ordinateur (Windows, Mac) | plus tard | ≈ 3–4 séances |
| 9. Plus tard | plus tard | **Bascule automatique vers une autre copie** (repoussée à la fin à la demande du propriétaire), téléchargement hors ligne |

Une « séance » ≈ une soirée de travail d'IA (3 à 5 h), sans compter les essais du propriétaire.

### Limite connue de l'accueil Séries

« Nouveaux épisodes dans tes séries » = séries dont le dernier épisode vu est terminé et dont la fiche connaît la suite (vérifié au plus une fois par jour, 20 séries) ; une série de Ma liste sans aucune progression n'y figure pas.

Limite connue : une série inconnue partout (aucune progression, jamais listée dans le cache, jamais vue) est demandée une fois au fournisseur, seule façon d'apprendre sa catégorie ; toute série dont une catégorie est connue et masquée n'est jamais demandée.

### Prochaines actions, dans l'ordre

1. Relecture de la tâche 6 (lecture, reprise, progression, épisode suivant, audio/sous-titres) ; essai sur la vraie télé : liste dans `docs/rapports/4a-tache6-lecture.md`.
2. Claude reprend les tâches 7–8.


---

## 4. Architecture et carte du dépôt

```
apps/                 Projet Gradle Kotlin Multiplatform (wrapper 8.14.3, Kotlin 2.3.21, AGP 8.13.2, JDK 21)
  shared/             Cœur partagé (jvm + android) : Xtream (XtreamApi, XtreamUrls), synchro Supabase
                      (sync/SyncClient.kt, supabase-kt 3.6.0), cache SQLDelight (catalog/), publication de la
                      copie du catalogue (CatalogPublisher), searchNorm (text/)
  tv/                 App Android TV (Compose for TV, Media3 ExoPlayer, Coil 3) : startup/ (QR, Qui regarde ?),
                      live/ (écran du direct, sections, LiveSessions, PrefsWatcher), player/, theme/ (jetons IptvTheme)
  tv/signature/       Clé de signature (PKCS12) + mot de passe (README.md) — VERSIONNÉE EXPRÈS, ne jamais régénérer
backend/              Supabase : migrations SQL (supabase/migrations/0001…0008), tests Vitest sur Postgres 16 local
                      (test/db.ts démarre un Postgres jetable), README.md = CONTRAT du backend (tables, fonctions, règles)
web/config/           Page de configuration + onglet « Organiser » (Vite + TypeScript, sans framework, Vitest) ;
                      ?demo = données inventées
tools/android/        installer-sdk.sh : installe le SDK Android dans $HOME/android-sdk
tools/web/            construire-page.sh : construit les fichiers de la page pour la mise en ligne
tools/test-lecture, tools/source-sport : outils du plan 1
docs/superpowers/specs   Conceptions validées par le propriétaire
docs/superpowers/plans   Feuille de route + plans détaillés par étape (tâches, tests, contraintes)
docs/rapports/           Rapports, décisions, procédures, journaux de construction
docs/installer-sur-la-tele.md   Guide d'installation et de mise à jour pour le propriétaire
```

Flux principal : la télé (à la maison) parle au fournisseur Xtream (seule à pouvoir le faire), garde le catalogue
en cache local (SQLDelight), en **publie une copie légère** (noms seulement) dans Supabase ; l'iPhone lit cette
copie et écrit les préférences via des fonctions `org_*` ; la télé relit `profile_prefs` quand `prefs_rev` change
(interrogé toutes les 4 s quand l'écran du direct est ouvert).

---

## 5. Services en ligne (production)

| Service | Détail |
|---|---|
| **GitHub** | Dépôt privé `mizou5532/iptv-home`. Branches : `main` (étapes 1, 2, 3A), `plan-3b1-organiser` (B1, en ligne mais pas fusionnée). |
| **Supabase** | Projet `gisjeuukbrgjgkrmlyvs` (région Canada, gratuit). URL `https://gisjeuukbrgjgkrmlyvs.supabase.co`. Clé publishable : voir `docs/rapports/synchro-production.md`. Migrations appliquées : foyer_profils, identifiants_xtream, appairage, preferences, progression, qui_regarde, correctifs, **organiser** (0008). Connexions anonymes activées (pour la télé). Site URL / Redirect URLs = la page Vercel. Compartiments publics : `apk` (l'APK) et `web` (fichiers JS/CSS de la page). **Aucune règle d'écriture** sur `storage.objects` (on en crée une temporaire pour chaque envoi, puis on la supprime). |
| **Vercel** | Équipe du propriétaire, projet `iptv-home-config`, adresse **https://iptv-home-config.vercel.app**. L'app Vercel n'a **pas** accès au dépôt GitHub : on déploie des fichiers. Procédure : `docs/rapports/deploiement-page.md`. Ancien projet de test `iptv-test-lecture` : le propriétaire doit le supprimer (lien déjà donné). |
| **Télé** | App `home.iptv.tv`, installée par **Downloader** depuis `iptv-home-config.vercel.app/iptv-home.apk`. Version en ligne : **0.2.0 (versionCode 2)**. |

Accès : ces comptes appartiennent au propriétaire. Une autre IA doit obtenir ses propres accès (connecteurs
Supabase/Vercel/GitHub) ou demander au propriétaire de faire les gestes en ligne.

---

## 6. Construire et tester

Environnement utilisé : Linux, Node 22, JDK 21, Postgres 16 (`/usr/lib/postgresql/16/bin`) pour les tests backend.

```bash
npm install                                   # à la racine (espaces de travail npm)
npm test -w backend                           # 113 tests, Postgres local jetable
npm test -w web/config && npm run build -w web/config   # 47 tests
tools/android/installer-sdk.sh                # une fois : SDK Android + apps/local.properties
cd apps && ./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest :tv:assembleRelease
```

- `--max-workers=2` : le réseau de l'environnement de Claude limitait les téléchargements parallèles (erreurs 429).
- Noms de tests Kotlin en ASCII (les accents faisaient planter).
- APK signé : `apps/tv/build/outputs/apk/release/tv-release.apk` ; vérifier avec `apksigner verify --print-certs`
  (SHA-256 du certificat attendu : `5d8877ce9c75eebe8d4a0cfb20427cf13dbf443402fc5c055608358d76e615d4`).
- **Publier une nouvelle version télé** : augmenter `versionCode`/`versionName` dans `apps/tv/build.gradle.kts`,
  construire, envoyer l'APK dans le compartiment `apk` (même nom), mettre à jour `web/config/public/version.json`,
  redéployer la page. Le propriétaire met à jour par Downloader (même adresse).

---

## 7. Méthode de travail utilisée

**Sauvegarde prioritaire (nouvelle instruction du propriétaire)** : après chaque sous-étape verte, correctif de relecture ou mise à jour de documents, faire un commit et un push sur la branche courante `plan-4-series`, au besoin avec un message `wip`. Jamais plus de 30 minutes sans push ; viser des points de sauvegarde plus courts. Avant une longue commande ou un sous-agent, pousser ce qui est fait. Toujours `git pull --rebase origin plan-4-series` avant chaque push, car Claude travaille aussi sur la branche ; conserver les changements locaux et résoudre les conflits sans écraser son travail. En cas de quota proche, pousser immédiatement et noter l'état dans le journal. Une tâche n'est pas terminée tant que sa sauvegarde distante et ses documents ne sont pas à jour.

- **Conception → plan → construction**. Pour chaque étape : conception écrite et validée par le propriétaire
  (`docs/superpowers/specs/`), plan détaillé en tâches avec tests (`docs/superpowers/plans/`), puis construction.
- Construction « pilotée par sous-agents » : une tâche à la fois, tests d'abord (TDD), **relecture indépendante
  après chaque tâche**, corrections, puis **relecture finale de toute la branche** avant de livrer.
- Une branche par étape (`plan-…`), fusion dans `main` avec l'accord du propriétaire.
- Commits en français, style conventionnel (`feat(tv): …`, `fix(web): …`).
- Les décisions prises en cours de route sont notées dans les journaux : `docs/rapports/journaux/`.
- Le propriétaire aime : voir l'avancement (feuille de route avec durées), des explications simples,
  qu'on avance sans lui quand c'est possible, et qu'on lui demande seulement ce qui exige sa présence.

---

## 8. Décisions importantes (résumé)

- Télé d'abord ; app ordinateur à l'étape 8 ; pas d'iPhone ni de site de lecture.
- Appairage : QR code + code à 6 caractères, configuration sur la page iPhone (option A).
- Thèmes : B1 en **sombre seulement**, construit sur des jetons (`IptvTheme`) ; clair et fun à l'étape 7.
- Bascule automatique vers une autre copie : **à la toute fin** (étape 9). En B2, copies regroupées avec choix manuel.
- Organisation : c'est le **compte du propriétaire** qui organise tous les profils (pas de comptes séparés pour l'instant).
- « Masqué gagne » ; ordre des catégories = profil, sinon « tout le monde », sinon fournisseur ; nouvelles catégories à la fin.
- Identité d'une chaîne = `stream_id` Xtream (risque si le fournisseur renumérote).
- Supabase coupe les réponses à 1 000 lignes : les grosses réponses sont renvoyées en un seul bloc jsonb/tableau.
- Clé de signature versionnée dans le dépôt privé (pour que les mises à jour s'installent par-dessus).
- Détail complet : `docs/rapports/plan-2-decisions.md` et les journaux `docs/rapports/journaux/`.

---

## 9. Points connus reportés (non bloquants)

Voir la fin des journaux `docs/rapports/journaux/plan-3a-journal.md` et `plan-3b1-journal.md` (lignes « minor » / « deferred »). Les principaux :
- Nombre de chaînes affiché seulement pour la section ouverte (écran télé).
- Pas de bouton « revenir à l'ordre de tout le monde » pour un profil qui a son propre ordre.
- `view.ts` de la page fait ~720 lignes (à découper avant de le modifier beaucoup).
- Une catégorie réaffichée « pour tout le monde » ne revient sur la télé qu'au démarrage suivant.
- `org_favorites` renvoie des lignes (limite 1 000 favoris).
- Recherche serveur sans index trigramme (à ajouter si le catalogue dépasse ~50 000 chaînes).

---

## 10. Règle de mise à jour (pour toute IA)

**Règle de sauvegarde fréquente (demande du propriétaire)** : il change souvent d'IA quand un quota se termine. Pousser sur GitHub après **chaque** sous-étape verte, correction ou décision (`git pull --rebase` avant), jamais plus de 30 minutes sans push ; noter dans le journal de l'étape ce qui est fait, en cours et la suite, pour qu'une autre IA reprenne sans rien perdre.


À **chaque** changement d'état (tâche commencée ou finie, mise en ligne, essai du propriétaire, décision) :
1. mettre à jour la section **3. État actuel** et la date en haut de ce fichier ;
2. mettre à jour `docs/superpowers/plans/2026-11-14-feuille-de-route.md` ;
3. consigner les décisions dans le journal ou le rapport de l'étape (`docs/rapports/`) ;
4. committer et pousser sur GitHub.

Si Claude reprend : la feuille de route interactive (lien en haut) doit refléter la même chose.

### Environnement cloud B2

Procédure de reprise sur la machine actuelle : [`docs/rapports/environnement-cloud.md`](docs/rapports/environnement-cloud.md). JDK21 complet et SDK36 prêts ; backend testé dans PostgreSQL16 conteneurisé (113 tests passants), sans accès à la production. Les helpers hors dépôt nécessitent l’instantané de cet environnement ; Git seul ne les restaure pas.
