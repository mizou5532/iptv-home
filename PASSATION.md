# PASSATION — iptv-home

> **À lire en premier par toute IA ou personne qui reprend le projet.**
> Ce fichier dit ce qui existe, où on en est, ce qui reste, comment travailler et ce qu'il ne faut jamais faire.
> Il est tenu à jour **à chaque étape franchie** (voir « Règle de mise à jour » en bas).

*Dernière mise à jour : 14 novembre 2026, B1 essayé avec succès et fusionné dans `main`. Conception et plan B2 validés ; construction autorisée et commencée sur `plan-3b2-direct`. Tâche 1 : moteur de regroupement.*

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
| 3B2. Le direct complet | construction en cours | Copies regroupées (choix manuel), guide maintenant/ensuite, aperçu, « Prendre la main ». Tâche 1 démarrée, tests et relectures par tâche. |

Conception B2 : [`docs/superpowers/specs/2026-11-14-b2-direct-complet-design.md`](docs/superpowers/specs/2026-11-14-b2-direct-complet-design.md), **validée par le propriétaire**. Plan : [`docs/superpowers/plans/2026-11-14-plan-3b2-direct.md`](docs/superpowers/plans/2026-11-14-plan-3b2-direct.md), validé par le propriétaire ; construction autorisée en neuf tâches avec tests et relectures. Reprise : tâche 1 démarrée, tâches suivantes en attente. Journal : `docs/rapports/journaux/plan-3b2-journal.md`.
| 4. Films et séries | à faire | ≈ 3–4 séances |
| 5. Recherche | à faire | ≈ 1 séance |
| 6. Sport | à faire | ≈ 2 séances |
| 7. Thèmes (clair, fun) et tailles | à faire | ≈ 1–2 séances |
| 8. App ordinateur (Windows, Mac) | plus tard | ≈ 3–4 séances |
| 9. Plus tard | plus tard | **Bascule automatique vers une autre copie** (repoussée à la fin à la demande du propriétaire), téléchargement hors ligne |

Une « séance » ≈ une soirée de travail d'IA (3 à 5 h), sans compter les essais du propriétaire.

### Prochaines actions, dans l'ordre

1. ~~Recueillir l'essai de B1~~ (fait, tout OK) (questions posées au propriétaire) :
   mise à jour 0.2.0 installée sans redemander le QR ? catégories visibles dans « Organiser » ? la télé suit en < 5 s ?
   avis sur le nouvel écran (logos, flèches, Retour) ? 2ᵉ ouverture toujours rapide ? nombre réel de catégories et de chaînes ?
   Consigner dans `docs/rapports/tele-b1-organiser.md`.
2. ~~Fusionner `plan-3b1-organiser` dans `main`~~ (fait) (le propriétaire a déjà accepté les fusions précédentes ; lui confirmer).
3. Vérifier d'un jour à l'autre que les identifiants de chaînes (`stream_id`) restent stables (sinon les favoris se perdraient ; spec B1 §9.5).
4. **B2** : conception et plan validés, construction autorisée ; réaliser la tâche 1 puis les suivantes avec la méthode par sous-agents et relectures de la section 7. Les accords de production et de fusion restent distincts.
5. Correction backend en attente : `record_progress` accepte un horodatage NULL (à corriger au début de B2 ou de l'étape 4).

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

À **chaque** changement d'état (tâche commencée ou finie, mise en ligne, essai du propriétaire, décision) :
1. mettre à jour la section **3. État actuel** et la date en haut de ce fichier ;
2. mettre à jour `docs/superpowers/plans/2026-11-14-feuille-de-route.md` ;
3. consigner les décisions dans le journal ou le rapport de l'étape (`docs/rapports/`) ;
4. committer et pousser sur GitHub.

Si Claude reprend : la feuille de route interactive (lien en haut) doit refléter la même chose.
