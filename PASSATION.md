# PASSATION — iptv-home

> **À lire en premier par toute IA ou personne qui reprend le projet.**
> Ce fichier dit ce qui existe, où on en est, ce qui reste, comment travailler et ce qu'il ne faut jamais faire.
> Il est tenu à jour **à chaque étape franchie** (voir « Règle de mise à jour » en bas).

*Dernière mise à jour : 16 nov. 2026. Refonte web mobile ivoire/vert compacte implémentée sur `refonte-web-profils`, basée sur `plan-7-themes` @ fc0b9a3 (0.10.0). Tests web et build verts ; aperçus séparés démo et compte réel en ligne, rendu navigateur non vérifié, site habituel inchangé. Voir le bloc prioritaire ci-dessous.*

> **Note du 15 nov. 2026 (Cosmo, assistant personnel du propriétaire — pas l'agent de développement habituel) :** correction « confirmation intégrée à la page au lieu de confirm() native » poussée sur `main` (commit `9198d98`), tests web 124/124 verts, **déployée le 15 nov. au soir par remplacement de contenu dans Supabase et sélection des 66 catégories du profil « Tout le monde » appliquée et vérifiée (66 affichées sur 916)** — la page ne se déploie pas depuis GitHub, voir le rapport pour la méthode et le piège de cache rencontré. Détail complet, fichiers touchés et confirm() restantes : [`docs/rapports/correction-confirmation-integree-2026-11-15.md`](docs/rapports/correction-confirmation-integree-2026-11-15.md).

> **Note du 15 nov. 2026 au soir (Cosmo, suite) :** points 1 à 4 de cet audit convertis en confirmations intégrées (suppression de playlist, retrait d'appareil, rechargement et restauration dans les Copies), sur la branche **`fix/confirmations-integrees-restantes`** partie de `claude/peaceful-davinci-pi93u7` @ `966295e`. Tests web **146/146 verts**, build de production OK. **Non déployé, non fusionné.** Le point 5 (`playback-admin.ts`, arrêt physique) est réservé par le propriétaire pour plus tard. Détail : [`docs/rapports/confirmations-integrees-restantes-2026-11-15.md`](docs/rapports/confirmations-integrees-restantes-2026-11-15.md).

> **Note du 15 nov. 2026, plus tard (Cosmo, point 5) :** la confirmation d'arrêt physique de l'écran « Protection de la lecture » (`playback-admin.ts`) est elle aussi convertie en confirmation intégrée, sur la branche **`fix/confirmation-integree-playback`** partie de `fix/confirmations-integrees-restantes`. Textes d'avertissement inchangés, aucune écriture avant confirmation explicite. Tests web **146/146 verts** ; le paquet de production ne contient **plus aucune** confirmation native. **Non déployé, non fusionné.** Détail : [`docs/rapports/confirmation-integree-playback-2026-11-15.md`](docs/rapports/confirmation-integree-playback-2026-11-15.md).

---

## 0. Reprise immédiate (à lire avant tout le reste)

**REFONTE WEB — CHECKPOINT PRIORITAIRE, 16 NOV. 2026 (CODEX).**

**Correctif icônes après essai iPhone** : capture du propriétaire montrant des carrés à la place de tous les pictogrammes. Cause retrouvée dans le bundle : URLs SVG inlinées par Vite avec apostrophes non encodées, interpolées dans `url('…')`, ce qui casse le masque CSS. Les SVG de la bibliothèque Phosphor sont désormais importés `?raw` et affichés directement, sans masque ni data URI. Test rouge puis vert sur tous les pictogrammes ; **174 tests web verts (19 fichiers)** et build compte réel OK. Paquet `/workspace/iptv-work/refonte-web-live-icons-fix`, mise à jour de l’aperçu à effectuer. Le site habituel reste inchangé. Le propriétaire a choisi la maquette mobile ivoire/vert (deuxième thème sobre), demandé une échelle plus compacte, validé le résultat, puis demandé de commencer la refonte. Ce travail concerne uniquement le site de configuration, pas l’esthétique de la télé.

- Branche dédiée `refonte-web-profils`, depuis `plan-7-themes` @ `fc0b9a3`. Aucune fusion main ni remplacement du site habituel.
- **ESSAI AVEC LE COMPTE RÉEL demandé ensuite par le propriétaire** : il veut essayer toutes ses informations tout en gardant une sauvegarde/branche séparée. Sauvegarde du code précédent créée et poussée : tag `sauvegarde-site-avant-refonte-2026-11-16` → `fc0b9a3`. Site habituel toujours sur `dpl_99EPgT7nXsaa7i1CGgAjWL7gmFCj`, vérifié après publication.
- **Nouvel aperçu connecté** : compilation normale `VITE_DEMO_ONLY=0`, 173 tests web verts ; déploiement staging READY `dpl_ApNYhSB7trKLL6wjxz8i9KnjUwQX`, URL `https://iptv-home-config-abg1jtuth-hamzahlal86-9766s-projects.vercel.app`. Lien Vercel temporaire transmis au propriétaire, jeton non versionné. Connexion par son compte habituel, mêmes RPC et stockage existants. **Les changements enregistrés dans cet aperçu s’appliquent au vrai compte et à la télé**, ce qui a été expliqué au propriétaire. L’IA ne s’est pas connectée et n’a lu aucune donnée de compte. HTML + 4 assets HTTP200 et identiques au paquet local `/workspace/iptv-work/refonte-web-live`. Aucun test backend en production, aucune migration ni copie de données. Rendu réel à essayer par le propriétaire ; aucune réussite visuelle revendiquée.
- **Aperçu de test autorisé puis publié** : `VITE_DEMO_ONLY=1`, 173 tests web verts, aucun client Supabase même sans `?demo`. Déploiement **staging/preview** `dpl_AM3Y9TZuLHq8LuTCW3cNZZRJErit`, état READY : `https://iptv-home-config-fq936star-hamzahlal86-9766s-projects.vercel.app`. Accès protégé Vercel : lien de partage temporaire transmis au propriétaire (ne pas versionner son jeton). Regénérer via `get_access_to_vercel_url` si expiré. HTML et 4 assets HTTP200, contenus des assets comparés identiques au paquet. Domaine public `iptv-home-config.vercel.app` toujours sur `dpl_99EPgT7nXsaa7i1CGgAjWL7gmFCj`. Aucun changement Supabase/APK ni fusion main. Rendu/interactions dans navigateur encore à faire par le propriétaire ; ce déploiement n’est pas une validation visuelle.
- Code : navigation basse **Profils / Organiser / Réglages** ; sélecteur puis espace personnel, photo/GIF et équipes via éditeurs existants, écran de départ par profil ; Organiser reprend la portée choisie, avec Tout le monde toujours explicite ; Réglages garde association, appareils/retrait confirmé, accès IPTV repliés, déconnexion. QR en attente ouvre Réglages. Palette ivoire/vert, taille compacte et cibles tactiles conservées.
- **172 tests web verts (18 fichiers)** et compilation TypeScript/Vite réussie. Relecture indépendante effectuée : réponses RPC après navigation, écriture de home pendant aller-retour, profil disparu et contraste initiales corrigés avec régressions rouges puis vertes. Contrats backend et tests existants conservés.
- **Rendu réel mobile/bureau NON vérifié** : cua ne démarre pas (`the gaas-browser node_repl sandbox cannot be disabled`), `sites-preview` échoue à ouvrir le relais 4173. Vite local tourne sur 4173 ; HTTP200 seul ne prouve pas le rendu. `web/config/design-qa.md` indique `final result: blocked`. À ouvrir/essayer dans un navigateur disponible avant toute publication.
- Démo `?demo` : profils/écran de départ/équipes/organisation en mémoire ; aucun client Supabase instancié. Photo : message explicite en démo, envoi disponible une fois connecté. Association et opérations de compte désactivées en démo.
- Documentation : conception et plan `docs/superpowers/{specs,plans}/2026-11-16-refonte-web-profils*`, rapport `docs/rapports/refonte-web/validation.md`. Aucun changement APK/version.json, aucune migration, aucun secret utilisé ou loggé. Publication demande toujours accord distinct après validation.


**ESTHÉTIQUE (ÉTAPE 7) — 16 NOV. 2026 (CLAUDE). CE BLOC FAIT FOI SUR TOUS LES SUIVANTS.**

- Le propriétaire trouvait l'app « très moche ». Maquettes : https://claude.ai/artifact/4ipZ2hMZMKtgcsPsoZYumy. **Ses choix** : style 1 « Verre » partout ; pour sa sœur, même gabarit avec fond clair rose pâle ; lecteur films/séries **A (ligne fine)** ; lecteur direct **A (carte compacte)** ; guide comme sur la maquette du style 1.
- **Fait (branche `plan-7-themes`, empilée sur `plan-6-sport`)** : jetons refaits (`theme/IptvTheme.kt` : `darkTokens`, `lightTokens`, `tokensFor(color, theme)`, `VideoTheme` pour les lecteurs) ; chaque profil applique `profiles.color` et `profiles.theme` (`clair` = clair, sinon sombre) ; barre du haut en capsule ; catégories sur 2 lignes ; `player/PlayerChrome.kt` (ligne fine, carte compacte) ; bloc d'accueil Films avec affiche. 315 partagés, 362 télé verts.
- **0.8.0 / code 10 signée** : SHA-256 `f89d83f44606e9e1b99b7bf502e43de1079e3615a099b10bf68e417f827fcfb7`, 37 537 882 octets. **0.8.0 PUBLIÉE le 16 nov.** (accord du propriétaire) : APK vérifié identique par le lien Vercel, `version.json` 0.8.0 (Vercel `dpl_CoJD72P68HkSXMPU93CGDG5Nsone`), règles temporaires supprimées (**0 règle** sur `storage.objects`). Couleurs des profils appliquées dans `profiles` : Hamza `#8FB4FF` sombre, **Maha (la sœur) `#F4B6C8` clair**, Mahmoud `#FFC46B` sombre.
- **Autocollant de Maha fourni par le propriétaire** : `docs/autocollants/maha.webp` (personnage chibi aux cheveux blonds-roses). À intégrer à l'étape suivante (autocollant par profil, coin de l'accueil, jamais sur la vidéo).
- **0.10.0 PUBLIÉE (16 nov.) — Sport « Mes équipes » (maquette 4 choisie)** : une carte par équipe favorite (écusson ESPN, couleur, match en direct avec score et période, ou prochain match, chaînes proposées) puis « Aussi ce soir » (deux écussons, VS ou score). Données : horaire LNH officiel (diffuseurs canadiens) + tableaux ESPN, maintenant avec logos, scores, période et **diffuseurs ESPN (américains) comme sources de plus** après les françaises (demande du propriétaire). Équipes favorites par profil (choix A du propriétaire) : `profiles.settings.sport_teams` (`SportTeam` : ligue, id ESPN, nom, sigle, logo, couleur), choisies sur la page iPhone (bouton « Équipes » dans « Mes profils ») ; sans choix : Canadiens et Barça (`DEFAULT_TEAMS`). Ligues de soccer en plus (Premier League, Ligue 1, Serie A, Bundesliga, Ligue Europa, MLS) lues seulement si une favorite y joue. ESPN refuse les lectures depuis la page (CORS) : catalogue des équipes intégré (`web/config/src/teams-data.json`, généré par `tools/sport/equipes-espn.py`, chargé à la demande). Le dépôt `mizou5532/appli-sportive` (son appli sportive) a servi de référence (mêmes sources gratuites, sans clé) ; rien n'y a été modifié. 318 partagés, 373 télé, 160 page. APK SHA-256 `7562584ca279a4cfa0f7c899bd5145ce54e62c67a84ca7a46142bfaec490d21f` ; page `app-IMEQXBTH.js`, `app-HPP2GIJK.css`, `teams-data-AF7EGGM4.js` ; Vercel `dpl_99EPgT7nXsaa7i1CGgAjWL7gmFCj` ; règles stockage : les 4 `avatars_*`.
- **Maquettes Sport (16 nov.)** : 5 dispositions publiées dans l'artefact des maquettes (1 Ce soir à la Prime Video, 2 Guide horaire, 3 Tableau des scores + sources, 4 Mes équipes, 5 Mur en direct), écussons et équipes inventés. En attente du choix. Les vrais logos et le programme des matchs demanderont un service de données sportives (à choisir).
- **0.9.5 PUBLIÉE (16 nov.)** : **photo de profil animée (GIF)** : la page envoie le GIF tel quel (5 Mo au plus, pas de recadrage, aperçu animé), même nom `avatars/<id>.webp` ; compartiment `web` + `image/gif` (migration `20261116000015_photos_gif`, appliquée). Télé : `coil-gif` (décodeur animé du système dès Android 9). **Bannière épinglée (Maha) simplifiée** à la demande du propriétaire : fond, titre, où elle en est et « Reprendre » seulement (plus d'affiche à droite, ni résumé, ni « Tous les épisodes »). 373 télé, 156 page. APK SHA-256 `bf888a4ec183185043d130f0615ded8017a0bafccf4dcc20d0c3f503d65fa7c6` ; page `app-U32HQP25.js` + `app-FMIRJPMI.css` ; Vercel `dpl_EYv83dtkCXPnSmhLVBTKh4FNDvN2` ; règles stockage : les 4 `avatars_*` seulement.
- **0.9.4 PUBLIÉE (16 nov.)** : **photo de profil depuis la page iPhone** (carte « Mes profils » → « Photo » : choisir, placer au doigt, zoomer, aperçu « Qui regarde ? » style Doux, Enregistrer / Retirer). Image 512 px, WebP ou JPEG (Safari), envoyée à `web/avatars/<id>.webp` puis `profiles.settings.avatar_v` (heure) et `avatar` (true/false). La télé lit `avatar_v` (adresse `?v=…`, donc pas d'ancienne photo en cache) et ne charge rien si `avatar = false` ; elle relit les profils à son lancement ou au retour sur « Qui regarde ? » après redémarrage. Migration `20261116000014_photos_profil` appliquée en ligne : fonction `can_write_avatar` + **4 règles PERMANENTES** sur `storage.objects` (`avatars_select/insert/update/delete`, membres du foyer, nom exact `avatars/<profil de son foyer>.webp`) ; le compartiment `web` accepte aussi `image/jpeg`. **Nouvelle règle de publication : il doit rester exactement ces 4 règles `avatars_*` (aucune `deploy_temp_*`).** Code : `web/config/src/avatar.ts` (logique testée), `avatar-ui.ts` (éditeur). Tests : 179 serveur, 155 page, 316 partagés, 373 télé. APK SHA-256 `01101074922bbe061c278065933f5b20975cc06ddf572d0edef4ee47e6428c73` ; page `app-3MOJKVMC.js` + `app-COG6RL6P.css` ; Vercel `dpl_FxpB2hoS5ZL2sBvXi3BTAAHRQP1z`.
- **0.9.3 PUBLIÉE (16 nov.)** : la bannière ne restait « pas toujours très bien » en haut → **défilement rangée par rangée** sur les accueils Séries et Films (`feature/RowSnap.kt` : la liste verticale ne fait plus le défilement « juste assez » de Compose ; la rangée qui prend le focus se cale en haut, la bannière ramène tout en haut ; les rangées horizontales gardent leur défilement). **Série épinglée** par profil : `profiles.settings.pinned_series` (+ `pinned_series_category`), lu par la télé (`Profile.settings`) ; la bannière Séries ne montre qu'elle (pas de file, pas de défilement, affiche à droite, titre sur 2 lignes, résumé sur 3, barre de progression). Réglé pour Maha : My Dress-Up Darling (série 45692, catégorie 519). Pas encore de réglage sur la page iPhone ni sur la télé (changer = mettre à jour `settings`). 316 partagés, 373 télé. APK SHA-256 `f9a785c156245cefe2873c9ca7ea8bb3cce90d24c868a250ff8a84243058d700` vérifié par le lien Vercel ; Vercel `dpl_aNnQA3M6FczG7dr6rW5g5wx8uWMU` ; 0 règle sur `storage.objects`.
- **0.9.2 PUBLIÉE (16 nov.)** : essai TCL de la 0.9.0 : la bannière était coupée ou cachée au-dessus de l'écran (elle arrive après l'ouverture et la liste restait accrochée à la rangée visible) → place « resume » toujours réservée en tête de liste, retour en haut quand la bannière prend le focus, premier focus sur la bannière des Films. **La TCL affiche 960 × 540 dp** (pas 1280 × 720) : bannière à 44 % de la hauteur (220-300 dp), texte qui s'arrête avant la file, cartes « Reprendre » 208 dp, fiches compactes (affiche 176 × 250, boutons petits, épisodes en lignes de 54 dp). « Qui regarde ? » au style **Doux** (maquette 4 choisie) : fond pastel clair, ronds et étoile qui flottent, bulle de couleur qui tourne derrière chaque photo (animations coupées dans les tests via `LocalFeatureAutoRotate`). 372 télé. APK SHA-256 `4b659825fb63ec53574f8eaafb2b1078f627cf4302479d9514c661c24c66396c` vérifié par le lien Vercel ; Vercel `dpl_7ktsBaQ54JEDXMJ1f1Bb5Rt2p8RF` ; 0 règle sur `storage.objects`.
- **0.9.1 PUBLIÉE (16 nov.)** : bouton « ⟳ Redémarrer l'application » sous les profils de « Qui regarde ? » (`startup/AppRestart.kt` : relance l'activité dans une tâche neuve puis arrête le processus) ; vérification forcée des mises à jour à chaque affichage de « Qui regarde ? » (avant : au plus une fois par heure au retour). Test `RestartDpadTest`. 372 télé. APK SHA-256 `3ee71a208be3dc6d687780dccfda2ee7557ab27b93ff2b70b7a068656281a92f` vérifié par le lien Vercel ; Vercel `dpl_5R8SjWF7PMAv6eKbCXoCUh5PjcZy` ; 0 règle sur `storage.objects`. **Maquettes « Qui regarde ? » (16 nov.)** : 5 styles publiés dans l'artefact des maquettes (1 Aurore, 2 Cinéma, 3 Cartes, 4 Doux, 5 Néon) ; en attente du choix du propriétaire.
- **0.9.0 PUBLIÉE (16 nov.)** : choix du propriétaire sur les maquettes avec photos : **Accueil B** (bannière qui défile toutes les 10 s, « À l'affiche ensuite », rangée « Reprendre » en cartes paysage) et **Fiche B** (affiche à gauche, épisodes avec vignettes à droite) pour Séries et Films, aux couleurs du profil. Images de fond du fournisseur (`backdrop_path`) gardées en cache (migration SQLDelight `3.sqm`, schéma 4). Détails : `docs/superpowers/specs/2026-11-16-etape7-accueil-fiche-design.md`. Captures d'écran Robolectric impossibles ici (le moteur graphique natif plante avec Java 21) : rendu à vérifier sur la télé. 316 partagés, 371 télé. APK SHA-256 `22b5d6d32f53c3343d5720bc2951e93b9ed7986c758acbc2d5787a66be48e137` (certificat attendu) vérifié par le lien Vercel ; Vercel `dpl_4J7fDd5NRyP3Z4o2Rhx8cf7oebkW` ; 0 règle sur `storage.objects`.
- **0.8.3 PUBLIÉE (16 nov.)** : barre du haut, le bouton « Changer de profil » est retiré ; la pastille du profil (photo + nom, `ProfilePill` dans `LiveTopBar.kt`) prend le focus et ramène à « Qui regarde ? ». Test `ProfilePillDpadTest`. 315 partagés, 365 télé. (`MovieDpadTest.arrows_ok…` a échoué une fois par lenteur puis passé 3 fois : à surveiller.) APK SHA-256 `36e01b5d48a5ab9cfc0288f449eb8107666ed3f8350e52a7ae7d33a8b628c07b` vérifié par le lien Vercel ; Vercel `dpl_2wC1S5WADfkcDabem6oMrmfCxz6E` ; page inchangée ; 0 règle sur `storage.objects`.
- **0.8.2 PUBLIÉE (16 nov.)** : onglet d'ouverture par profil. `profiles.home` vaut `direct`, `series`, `films` ou `sport` (migration `20261116000013_onglet_ouverture`, appliquée en ligne ; « mixte » devenu `direct`, défaut `direct`, non nul). La télé ouvre l'onglet de `profile.home` (`openingTab`) au lieu du dernier onglet visité. Page iPhone, onglet Configuration : carte « Mes profils », une rangée Direct / Séries / Films / Sport par profil, enregistrée dès le choix. Réglé en ligne : Hamza Sport, Maha Séries, Mahmoud Sport. Tests : 315 partagés, 364 télé, 176 serveur, 150 page. APK SHA-256 `c17abb1ae1792117d81ede6c4b682cf03540f32755ca4b650e47f61e54c2449e` (certificat attendu) vérifié par le lien Vercel ; page `assets/app-OFCHOQQT.js` + `app-6UUSUI3V.css` ; Vercel `dpl_46yBSBZqhyGtvgAk26cdE5FiHiVz` ; 0 règle sur `storage.objects`.
- **0.8.1 PUBLIÉE (16 nov.)** : image de profil. La télé charge `web/avatars/<id du profil>.webp` (public) sur « Qui regarde ? » et dans la barre du haut ; sans image, la lettre comme avant. Image de Maha (visage recadré de son autocollant, `docs/autocollants/maha-avatar.webp`) envoyée pour son profil `45609b4f-…`. Le compartiment `web` accepte maintenant aussi `image/webp`. APK SHA-256 `6a71be01493b14619c576aeb014e98193e231c2664dfbf189552ac5febfc1466` vérifié en ligne ; Vercel `dpl_H2rUihKGtsPAygbr6V1eCgAeXNyY` ; 0 règle sur `storage.objects`.
- **Maquettes Séries pour Maha** (trois dispositions, en bas de la page de maquettes) : **pas retenues pour l'instant**, le propriétaire réfléchit.
- **Pas encore fait (demandé par le propriétaire)** : autocollant par profil ; choix des couleurs et création de thèmes depuis la page iPhone ; aperçu d'image en avançant dans un film ; liste latérale dans le lecteur du direct (non retenue).

**SPORT (ÉTAPE 6) — 16 NOV. 2026 (CLAUDE). CE BLOC FAIT FOI SUR TOUS LES SUIVANTS.**

- Conception : `docs/superpowers/specs/2026-11-16-etape6-sport-design.md` (approche A choisie par le propriétaire : tout sur la télé). Branche `plan-6-sport`, empilée sur `plan-5-recherche`.
- **Fait** : onglet **Sport** (« Mes équipes » Canadiens + Barça sur 7 jours, « Ce soir » LNH/NFL/NBA/Liga/LDC/Coupe/Supercoupe). Horaires lus par la télé (api-web.nhle.com, site.api.espn.com), cache local hors ligne. Chaînes à essayer : LNH = diffuseurs canadiens officiels (table des codes réseau, nom exact préféré) puis apprises ; autres = apprises puis repli sport ; français d'abord ; au plus 4 affichées ; dans le lecteur, Droite/Gauche parcourent aussi toutes les chaînes « sport » visibles. Une chaîne regardée 3 min depuis un match est apprise (par profil et compétition). Recherche masquée sur l'onglet Sport.
- Code : `apps/shared/.../sport/` (Sport.kt, SportChannels.kt, tests sur les vraies réponses), `apps/tv/.../sport/` (SportData.kt, SportScreen.kt), branchement dans `App.kt` / `MainActivity.kt`, `HomeTab.SPORT`.
- Tests : 315 partagés, 360 télé, 0 échec. **0.7.0 / code 9 construite et signée** (certificat attendu) ; APK SHA-256 `6be0e57f60b71e59194257cb0608a1018944d73d485eb0702a209e5db42a6a55`, 37 488 730 octets. **Publiée le 16 nov. avec l'accord du propriétaire** : APK vérifié identique par le lien Vercel, `version.json` 0.7.0 (Vercel `dpl_J6GQigxbJ7xAeDnSpcQrKJw7zr1L`), 0 règle sur `storage.objects`. Installée par le propriétaire (16 nov.) : « ça semble faire du sens, ça a l'air de fonctionner » ; il attend un vrai soir de match pour juger. Il trouve **toute l'application « vraiment très moche »** : l'étape 7 (esthétique, thèmes, tailles) est la suivante.
- À vérifier sur la télé : noms réels des chaînes du fournisseur face à la table des codes (RDS, TVA Sports, Sportsnet, TSN…), passage d'une chaîne à l'autre, lisibilité du bandeau.

**MISE À JOUR PAR BOUTON — 16 NOV. 2026 (CLAUDE). CE BLOC FAIT FOI SUR TOUS LES SUIVANTS.**

- **Étape 5b ajoutée par le propriétaire** : bouton « Mise à jour » dans la barre du haut (conception `docs/superpowers/specs/2026-11-16-etape5b-mise-a-jour-design.md`, code `apps/tv/src/main/kotlin/home/iptv/tv/update/`, branche `plan-5b-mise-a-jour`). L'app lit `version.json` (au démarrage, au retour au plus 1 fois/heure, toutes les 6 h), affiche une pastille, télécharge l'APK, vérifie `apkSize` et `apkSha256`, installe par PackageInstaller.
- **0.5.1 (code 7)** = 0.5.0 + bouton : publiée et **installée par le propriétaire avec Downloader** (dernière fois).
- **0.6.0 (code 8)** = recherche + bouton (branche `plan-5-recherche`) : 305 partagés et 359 télé verts, signée (certificat attendu), **publiée le 16 nov.** APK SHA-256 `6048c52536e1701df0cac2a33e1556b5a4d3712c06d0852e301554e8786a663a`, 37 374 042 octets, identique par le lien Vercel ; `version.json` 0.6.0 en ligne (Vercel `dpl_CueFr42L95Kuw22rJKzmJEfpe9s9`). 0 règle sur `storage.objects`.
- **Publier une version désormais** : construire et vérifier l'APK, l'envoyer dans le compartiment `apk` (règle temporaire puis suppression), **vérifier le fichier en ligne**, et **en dernier** publier `version.json` avec `versionCode`, `versionName`, `apkSha256` et `apkSize` exacts (sinon le bouton refuse le fichier). Le propriétaire clique ensuite « Mise à jour » sur la télé.
- **Essai du bouton réussi (16 nov.)** : le propriétaire est passé de 0.5.1 à 0.6.0 par le bouton « sans problème ». **La télé est en 0.6.0.** Les essais complets de la recherche restent prévus à la fin du projet. Prochaine étape : 6 (sport), conception en cours.
- Rien n'est fusionné dans `main` depuis `a220b2d` : `main` = 0.5.0 + confirmations de Cosmo. Fusion après les essais.

**ÉTAT AU 16 NOV. 2026 (CLAUDE) — CE BLOC FAIT FOI SUR TOUS LES SUIVANTS.**

- **Recherche (étape 5) : code terminé**, branche `plan-5-recherche` (poussée). Tâches 1 à 5 du plan faites ; la tâche 5 a été finie par Claude après l'arrêt de l'autre IA :
  - recherche **branchée dans l'application réelle** (avant, seulement dans les tests) : `IptvApp.searchRuntime` (un remplisseur par processus), `MainActivity` (démarrage à `onStart`, arrêt à `onStop`, suit profil et section), `tv/search/SearchSourcesTv.kt` (sources réelles, cache local seulement), `RepoSeriesCatalog`/`RepoMovieCatalog` passent par `CatalogFiller.screenFirst` (les écrans passent devant le remplissage) ;
  - **défaut corrigé** : une chaîne dont une copie était dans une catégorie masquée n'était jamais trouvée ; seules les copies visibles comptent maintenant (comme dans la liste du Direct) ;
  - **focus** : le Direct derrière la recherche ne réclame plus le focus (`LiveBrowseScreen.focusSuspended`) ; résultats gardés quand les réglages sont relus avec les mêmes masques ; « Recherche… » au lieu d'un « Rien trouvé » prématuré ; seul Haut ou la frappe ramène au champ ;
  - **vitesse** : moteur environ 4 fois plus rapide (≈ 20 ms pour 50 000 titres ici ; avant, 127 ms faisait échouer le test) ;
  - **pas de recherche vocale** : le propriétaire n'en veut pas pour l'instant (peut-être bien plus tard). Le paramètre `voice` existe mais reste `null`.
  - Tests : **305 partagés, 346 télé, 0 échec**, APK debug construit. Tests web 146 verts.
- **Tâche 6 (relecture globale, 0.6.0, mise en ligne, essai télé) : REPORTÉE À LA FIN DU PROJET** à la demande du propriétaire (« après les thèmes »). Ne pas publier la recherche avant. Version toujours 0.5.0 / code 6 dans le dépôt.
- **Branches empilées** : la recherche n'est **pas** fusionnée dans `main` (accord permanent = fusion après essai télé réussi). Les étapes suivantes partent de `plan-5-recherche` (nouvelle branche par étape, empilée), puis tout sera essayé et fusionné à la fin.
- **Confirmations intégrées de Cosmo** (branches `fix/confirmations-integrees-restantes` et `fix/confirmation-integree-playback`) : relues, fusionnées dans `main` (`a220b2d`) et **page publiée** le 16 nov. (`assets/app-XYWM6X6I.js`, Vercel `dpl_AUCnZF2JPjzdaYx65fwX4EhFpPwb`, script en ligne identique, `?demo` sans erreur, règle temporaire supprimée : 0 règle sur `storage.objects`).
- **Prochaine étape : 6 (sport)**, puis 7 (thèmes), puis la grande vérification finale (recherche + sport + thèmes) et une seule mise en ligne.
- **Machine de Claude** : `/home/user/iptv-home`, SDK dans `/root/android-sdk`. Les instructions `/workspace` ci-dessous concernent l'autre IA.

**PAUSE DEMANDÉE PAR LE PROPRIÉTAIRE — REPRISE PRIORITAIRE ÉTAPE5.** Sauvegarder puis arrêter pour quota. Branche **plan-5-recherche**. Tâches1–4 terminées et relues : moteur/sources/remplissage/modèle ;305shared et331TV officiels verts à leurs checkpoints. Tâche5 INCOMPLÈTE : premier palier4f0e474 écran/fiches9Dpad verts ; modifications ultérieures sauvegardées en **WIP non validé, peut ne pas compiler**. SearchRuntimeTest référence une classe et des constructeurs encore non implémentés. Ne pas annoncer recherche prête ni0.6.0 : version toujours0.5.0/code6.

**Reprendre ici** : lire docs/rapports/5/tache-5-integration.md et plan5. Finir diagnostic résultats Direct/focus : événements clavier arrivent, OK ne déclenche pas encore la sélection attendue ; ChannelRow réutilisé, vérifier focus réel et callback currentResult. Tests SearchFocusDpadTest : dernier lancement4échecs, logs task5-key-debug.log. Un test isolé supposait Alpha avant Beta mais le classement place Beta (plus court) en premier ; correction de test en cours. Supprimer tout diagnostic temporaire avant livraison (SEARCH*println retirés à la sauvegarde).

**Reste tâche5** : Directrévélation horsviewport+masque,focus dynamique ; sourceDirect complète et invalidationscache/groupes ; singletonremplisseur IptvApp+onStop/start/profil+verrou foreground accueils ET fiches ; voix Android/manifest/rejetretourstardif. TestsWIP SearchRuntimeTest pour cette intégration non faite. Puis suitescomplètes et revueindépendante tâche5. Tâche6 non commencée : revueglobale,version0.6.0/code7,APKrelease signé/certificat,accord distinctpublication,essaiTCL puisfusionmain. Aucun déploiement ni environnement cloud republié.

Outils : source /workspace/iptv-env/activate.sh, Gradle depuis apps ; testsAndroidofficiels fonctionnent désormais (ancien403 résolu). Logs/briefs locaux /workspace/iptv-work/5 ; rapports durables docs/rapports/5. Aucun secretIPTV/service_role, aucun testproduction. Dernier checkpointvertUI4f0e474 ; WIPultérieur conservé, ne pas le jeter. Prochaine IA : vérifier compilation et lire gitstatus avant reprendre ; ne pas refaire tâches1–4.


**ÉTAPE 5 — RECHERCHE : À RÉALISER (16 nov. 2026). CE BLOC FAIT FOI ; LES BLOCS 4B CI-DESSOUS SONT DE L'HISTORIQUE.**

- **Décisions du propriétaire** : la recherche trouve dans toutes les catégories gardées, jamais dans les masquées ; elle donne les résultats **de la section où l'on se trouve** (Direct → chaînes, Séries → séries, Films → films). **Choix A** : OK sur une chaîne trouvée ferme la recherche et ramène dans la liste du Direct, sur la chaîne, avec l'aperçu. Il demande de **ne pas redemander la validation** de la conception ni du plan.
- **Conception validée** : `docs/superpowers/specs/2026-11-16-etape5-recherche-design.md`.
- **Plan en 6 tâches avec tests** : `docs/superpowers/plans/2026-11-16-plan-5-recherche.md` (moteur, sources filtrées, remplissage en arrière-plan, modèle d'écran, écran + voix + Direct, validation et 0.6.0). Lire ses « Global Constraints » et « Review Focus ».
- **État de réalisation** : branche `plan-5-recherche` créée depuis `main` (`598cf85`) ; tâche1 moteur implémenté (`57385b1`), correctif `dc7f948` relu/approuvé.283shared verts ; tâche2 sources filtrées terminée et relue (`86cc57e`),290shared verts ; tâche3 remplissage implémentée (`e8aeaaf`),304shared verts, correctif `f7f0d6b` relu/approuvé,305shared verts. Tâche4 modèle implémentée (`e9bcc40`),11tests ciblés/331TV officiels verts, relecture approuvée. Tâche5 en cours : premier palier écran/fiches `4f0e474` poussé,9 nouveaux tests Dpad verts. Direct complet, source/invalidation, remplisseur processus/cycle de vie et voix restent à finir. Rapport vivant `docs/rapports/5/tache-5-integration.md`. Tâche6 validation/version/déploiement à faire.320 tests TV officiels de référence verts, APKdebug construit. Conception et plan déjà validés, aucune confirmation à redemander. Suivre le plan tâche par tâche (tests d'abord, relecture indépendante, commit + push après chaque tâche, journal `docs/rapports/journaux/plan-5-journal.md` à créer).
- **Machine Codex de cette reprise** : `/workspace/iptv-home`, activer `source /workspace/iptv-env/activate.sh`, Gradle depuis `apps`, un seul processus de build à la fois. Le16nov accès HTTP200 confirmé à gcp.mirrors.maven.org, ancien blocage403 disparu ; suite Android officielle à retenter après tâche1.
- **Machine** : sur une machine neuve, `tools/android/installer-sdk.sh` installe le SDK ; Gradle depuis `apps` avec `--max-workers=2` ; si Maven Central répond 429, relancer la même commande. Postgres 16 requis pour `npm test -w backend`.
- **Mise en ligne** : 0.6.0 / code 7, APK + `version.json` seulement (pas de migration), **avec l'accord du propriétaire**, puis essai TCL, puis fusion dans `main` (accord permanent après essai réussi).
- **En production aujourd'hui** : télé 0.5.0, migrations jusqu'à `movies` (0012), 0010 non appliquée, 0 règle sur `storage.objects`.
- **À essayer encore par le propriétaire (4B)** : Reprendre/Recommencer, Ma liste, Marquer vu/non vu, audio et sous-titres, masquer une catégorie de films depuis l'iPhone.


**REPRISE DU 16 NOV. 2026 PAR CLAUDE — CE BLOC FAIT FOI SUR TOUS LES BLOCS SUIVANTS (qui sont de l'historique).**

- **Branche** : `claude/peaceful-davinci-pi93u7` = `plan-4-films` (jusqu'à `4f16c20`) fusionnée avec `main` (`755d997`, correction « confirmation intégrée » de la page). Le conflit de `web/config/src/org/view.ts` est résolu en gardant les deux : confirmation intégrée + RPC par type (`orgKindHideAllExcept`). Les deux tests Films de confirmation suivent la confirmation intégrée. `plan-4-films` n'est plus la branche à jour.
- **Machine actuelle : `/home/user/iptv-home`** (pas `/workspace`). `tools/android/installer-sdk.sh` installe le SDK dans `/root/android-sdk`. Gradle : `cd apps && ./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest :tv:assembleDebug`. Maven Central renvoie parfois 429 au premier essai : **relancer la même commande** (les fichiers déjà téléchargés restent en cache ; 2 essais ont suffi). Robolectric se télécharge sans blocage ici. Postgres 16 présent : `npm test -w backend` fonctionne.
- **Tâche 3 corrigée** (commit `38ccb37`) : I1 accueil et fiche n'attendent plus la synchro ; I2 une référence dont la catégorie du cache est masquée est écartée de l'accueil ; I3 le Retour à l'accueil relit tout de suite la copie durable et la file, et une ancienne réponse du serveur n'écrase plus rien ; I4 le statut vu appris du serveur est gardé en mémoire et en copie durable. 7 tests rouges puis verts.
- **Tests réellement exécutés ici** : 264 partagés, **318 télé (suite officielle complète, dont les 5 `MovieDpadTest`, 8 Live, 3 Séries, 1 barre)**, 172 backend, 144 web, build web et APK debug réussis. Les harnais « sans Android » de `docs/rapports/4b/` ne servent plus sur cette machine.
- **Relecture indépendante faite** (rapport `docs/rapports/4b/tache-5-validation.md`) : I1–I4 confirmés résolus, aucun défaut critique ; N1 (réponse Ma liste tardive qui annulait un ajout) et les mineurs M2, M3 (fiche), M5, M6 **corrigés** avec tests ; M1, M4 et le focus de l'accueil (M3) reportés/à contrôler sur la télé.
- **Tests exécutés après corrections** : 264 partagés, **320 télé** (suite officielle complète, tests de télécommande compris), 172 backend, 144 web, build web réussi.
- **Version 0.5.0 / code 6 prête** : `:tv:assembleRelease` réussi, certificat vérifié (SHA-256 attendu, section 6). L'APK n'est pas dans le dépôt : le reconstruire dans une nouvelle session.
- **0.5.0 MISE EN LIGNE le 16 nov. 2026 avec l'accord du propriétaire** (« Oui, mets en ligne ») :
  - migration `movies` (0012) appliquée en production par `apply_migration` ; les 15 fonctions comparées une à une au fichier du dépôt (empreintes identiques), `anon` ne peut en exécuter aucune, RLS active sur `movie_catalog_category`, advisors sécurité sans nouveauté de niveau ERROR. 0010 toujours non appliquée ;
  - page iPhone : `assets/app-XEFLPVLI.js`, `app-SAQZZUTD.css`, `demo-OVLARHBR.js` envoyés dans le compartiment `web` (vérifiés identiques), `index.html` + `version.json` 0.5.0 + `vercel.json` déployés sur Vercel (`dpl_HTFiKJKenhJDHySTo6xygqZGeevy`). Essai `?demo` sur iPhone simulé : onglet Films visible, 0 erreur. **Les noms de fichiers sont de nouveau synchronisés avec leur contenu** (le remplacement de contenu fait par Cosmo n'a plus cours ; les anciens `app-ALUE2QLD.js` / `app-WPUQFUB4.css` restent dans le compartiment, inutilisés) ;
  - APK 0.5.0 dans le compartiment `apk`, vérifié identique au fichier signé, aussi par `https://iptv-home-config.vercel.app/iptv-home.apk` (SHA-256 `dfcd88db…fb810`) ;
  - 4 règles temporaires `deploy_temp_*` créées puis supprimées : **0 règle sur `storage.objects`** (vérifié).
- **Essai TCL 0.5.0 du 16 nov. : réussi sur ce qui a été essayé.** Le propriétaire a vu l'onglet Films, les catégories, les films, une fiche, et a lancé une lecture : « ça semblait bien fonctionner ». **Pas encore essayés par lui** : Reprendre/Recommencer, Ma liste, Marquer vu/non vu, pistes audio/sous-titres, masquage d'une catégorie depuis l'iPhone, focus de l'accueil après « Chargement… » (M3). À lui redemander à l'occasion ; aucun problème signalé.
- **Fusion dans `main` faite** (accord permanent après essai réussi) : `main` = `claude/peaceful-davinci-pi93u7`. Étape 4B terminée.
- **PROCHAINE ACTION : attendre la consigne du propriétaire** pour la suite (étape 5 Recherche, d'après la feuille de route). Rien de commencé.
- **Retour arrière possible** : la migration est additive et la 0.4.1 reste compatible ; l'APK 0.4.1 se reconstruit depuis `main`.

**PAUSE EXPLICITE DEMANDÉE PAR LE PROPRIÉTAIRE — CHECKPOINT DE REPRISE PRIORITAIRE.** Il a demandé de tout sauvegarder/pousser, mettre la passation à jour et **s'arrêter ici** à cause du quota. Aucune correction supplémentaire à lancer dans cette pause. Branche `plan-4-films`.

- **Code produit dernier checkpoint : `476e5ff`**. Accueil, fiche, lecture et onglet Films intégrés ; progression typée, cache et backend en place ; organisation iPhone faite. Le code compile et l'APK **debug** est construit. Version toujours **0.4.1 / code5** : ne pas annoncer une 0.5.0 livrée.
- **Tests effectivement verts** :264 shared,284 logique TV (harnais sans Android),172 backend,144 web,78 test-lecture,24 source-sport ; build web et APK debug réussis. Les **5 tests Dpad Films sont seulement compilés, pas exécutés**. Suite TV officielle bloquée avant exécution par image Robolectric redirigée vers `gcp.mirrors.maven.org` puis HTTP403. Les succès de la machine Claude `/home/user` ne s'appliquent pas à cette machine `/workspace`.
- **Tâches1,2,4 terminées et relues. Tâche3 implémentée mais PAS approuvée** : la relecture vient de trouver des défauts importants, à corriger en premier à la prochaine reprise. Rapport `docs/rapports/4b/tache-3-relecture.md` (relecture interrompue sur demande de pause, lire ses limites).
- **Quatre points signalés (non corrigés), suivre les IDs du rapport** : **I1** catégories accueil et fiche locale toujours retardées par les RPC progression/liste (séparer publication locale de la synchronisation) ; **I2** accueil ne confronte la catégorie persistée non vide à `catalog.cachedCategory`, donc une catégorie contradictoire connue masquée peut rester visible dans Reprendre/Ma liste ; **I3** retour accueil ne relit/publie pas immédiatement les mutations de l'état durable et la file avant attente serveur ; **I4** le statut vu appris par le RPC de fiche n'est pas conservé dans l'état durable : après Recommencer, reload peut le perdre face à une position locale en attente, alors que le test actuel préremplit déjà le cache vu. Tests rouges/verts à écrire avec RPC suspendus, vrai parcours fiche et catégories contradictoires ; relecture du correctif ensuite.
- **À faire ensuite** : finir revue/corrections tâche3 ; résoudre l'accès Robolectric et exécuter toute la suite Android et Dpad ; revue globale de branche ; tâche5 version0.5.0/code6, APK release signé avec clé existante/certificat attendu vérifié, rapport final ; demander accord distinct pour migration0012 + page + APK. **Aucune publication, aucun changement de version, aucune migration production, aucune fusion de cette reprise dans main.** Main contient seulement les checkpoints précédemment validés/autorisés.
- **Pour repartir** : lire `docs/rapports/4b/prompt-reprise.md`, rapports tâches2/3 et leurs relectures, plan et conception déjà approuvés. `source /workspace/iptv-env/activate.sh`, Gradle depuis apps, un seul à la fois. Harnais reproductibles suivis dans docs/rapports/4b. Logs détaillés locaux dans `/workspace/iptv-work/4b/`; données importantes et résultats copiés dans rapports Git.
- **Préservation** : ancien stash `reprise-4b-local-avant-synchronisation` et archives recovery conservés ; ne pas réappliquer tout le stash sur le catalogue récent. Le travail actuel est committé/poussé, vérifier `git status` et l'historique avant toute nouvelle modification.


**ÉTAT COURANT APRÈS REPRISE CODEX — fait foi sur le checkpoint Claude historique ci-dessous.** Branche `plan-4-films`, derniers commits produit `8c1a005` (progression partagée), `943f550` (isolation séries TV), `6863bf8` (protection fiche cache). Tous poussés et vérifiés sur le distant.

| Tâche | État courant |
|---|---|
| 1 Backend | Terminée et relue ; 172 tests backend validés précédemment, migration0012 locale seulement. |
| 2 Catalogue/progression Kotlin | **Implémentée et relue sans réserve** après correction I1 ; 262 tests shared verts, 267 tests de logique TV verts via harnais sans Android, APK debug construit. Suite Robolectric officielle encore bloquée403 : elle n'est pas revendiquée verte. Rapport `docs/rapports/4b/tache-2-kotlin.md`, revue `tache-2-relecture.md`. |
| 3 Écrans Films télé | **En cours** : accueil/état local implémentés au checkpoint `5d7c687` (272 tests de logique TV verts, dont5 Films). Fiche/actions implémentées au checkpoint `9ccb4f8` :263 shared et274 tests de logique TV verts. Lecture implémentée au checkpoint `68f99f0` :276 tests de logique TV verts. Écrans et intégration implémentés au checkpoint `c2c206e` : compilation du produit et de tous les tests Kotlin,276 tests de logique TV et assembleDebug réussis. Quatre tests Dpad compilés mais non exécutés (Robolectric403). Dernier checkpoint `35b9b85` :263 shared/282 logique TV verts, APKdebug compilé. Les3 points de l’auto-revue sont corrigés (`476e5ff`) : données locales immédiates, héros local sans date, repli Retour robuste.264 shared et284 tests de logique TV verts,5 Dpad compilés mais non exécutés. Relecture indépendante tâche3 en cours ; ne pas la déclarer approuvée avant son rapport. Rapport vivant `docs/rapports/4b/tache-3-tele.md`. Le parcours Films n’est pas encore intégré à l’application. |
| 4 iPhone | Implémentée, relue et corrigée (ordre démo par profil, commit37d1e3f) ;144 tests web verts, build OK. |
| 5 Validation/livraison | Pas commencée : revue globale, suite Android complète, version0.5.0/code6 et APK signé restent à faire avant demande distincte de déploiement. |

**Environnement réel ici : /workspace.** `source /workspace/iptv-env/activate.sh` puis Gradle depuis apps. Gcp.mirrors.maven.org reste refusé HTTP403 dans cette machine ; le succès sur /home/user de Claude n'est pas transposable. Ne pas lancer deux Gradle simultanés. Tests UI à écrire/compiler, mais pas à annoncer exécutés sans image Robolectric. Harnais de logique reproductible conservé dans `docs/rapports/4b/harnais-tests-sans-android.gradle` (chemin de cache /workspace à adapter ailleurs) ; ses limites et les6 erreurs Stub! de l'essai initial sont dans le rapport tâche2.

**Reprise sûre** : vieux travail local archivé/stash `reprise-4b-local-avant-synchronisation`, ne pas réappliquer tout le stash car le catalogue distant est plus récent. Conception/plan autorisés, ne pas redemander validation. Ancien état rouge256/8 désormais résolu. Après chaque checkpoint, tenir ce bloc, journal, rapport et plan à jour puis commit/push. Aucun déploiement, migration production ni republication cloud à cette reprise.

**ÉTAT EXACT AU 16 NOVEMBRE 2026, 01:10 UTC (écrit par Claude avant de rendre la main ; fait foi sur le bloc suivant).** Branche `plan-4-films`.

| Tâche du plan 4B | État |
|---|---|
| 1 Backend (migration 0012, locale) | ✅ faite et relue (`7d2791f`), 172 tests backend |
| 2 Catalogue et progression Kotlin | 🟡 **à moitié faite** (détail ci-dessous) |
| 3 Écrans Films sur la télé | ❌ pas commencée |
| 4 Organisation Films sur l'iPhone | ✅ faite, vérifiée par Claude, **fusionnée dans `plan-4-films`** : 143 tests web, build OK, rapport `docs/rapports/4b/tache-4-iphone.md`. Reste : relecture indépendante (facultative, diff petit). |
| 5 Validation, 0.5.0, accord de mise en ligne | ❌ pas commencée |

**Tâche 2, précisément :**
- ✅ `0d57f54` : client Xtream des films (`movieCategories`, `movies(categoryId)`, `movieInfo`), avec tests.
- ✅ `efaec8f` : cache SQLDelight des films (`Movies.sq`, migration `2.sqm`, schéma 2 → 3) et `MovieRepository`, avec tests.
- 🟡 Dernier commit `wip(4b): progression typée film/épisode EN COURS` : l'agent a été coupé par la limite **au milieu**. Il venait d'écrire les **tests rouges** (`ProgressClientTest`, `ProgressQueueCodecTest`, `ProgressQueueTest`) et de commencer `ProgressClient.kt`. **Ce commit n'est pas vérifié : il peut ne pas compiler et ses tests sont censés échouer** tant que l'implémentation manque.
- ❌ Reste dans la tâche 2 : `ProgressEntry.itemType` (défaut `"episode"`) + `movieName/movieCover/movieCategory` ; identité de file profil + type + id (remplacer, lire, envoyer, supprimer) ; anciens JSON sans type lus comme épisodes ; `SupabaseProgressClient.record` qui route `movie` vers `record_movie_progress` ; `progress/MovieClient.kt` + `SupabaseMovieClient` ; `ProfilePrefs.movieOrder/movieList` ; dans `apps/tv/MainActivity.kt`, filtre `itemType == "episode"` sur `dropQueued` des séries (avec test) ; rapport `docs/rapports/4b/tache-2-kotlin.md` ; cases du plan ; relecture indépendante.
- Références avant la tâche 2 : 193 tests partagés, 285 tests télé, tous verts.

**Environnement de Claude (différent de `/workspace/...`)** : dépôt dans `/home/user/iptv-home` ; `cd apps && ./gradlew --max-workers=1 :shared:jvmTest :tv:testDebugUnitTest :tv:assembleDebug -q` fonctionne, Robolectric compris. Maven Central répond parfois 429 : réessayer, au besoin une fois avec `--refresh-dependencies`. `apksigner` : `/root/android-sdk/build-tools/36.0.0/apksigner`.

**Rappels de livraison (appris en 4A)** : tout nouvel écran a un test Robolectric qui imite la télécommande ; poser `Modifier.backKey` sur le fond focalisable d'un lecteur ; ne jamais mettre `focusProperties { canFocus }` sur un conteneur (utiliser `focusBlockedWhen`) ; la page iPhone avec « Films » ne se publie qu'en même temps que la migration 0012 (sinon erreur en choisissant Films) ; appliquer 0012 avec `apply_migration`, jamais `db push` (0010 reste locale) ; les demandes d'autorisation Supabase expirent si le propriétaire n'est pas devant son ordinateur ; après un envoi de fichier, supprimer les règles temporaires et vérifier qu'il en reste 0.

**Reprise actuelle — 4B Films (15 novembre 2026).** Le propriétaire a autorisé toute la réalisation (« Oui, tu peux y aller »), et demande des traces exhaustives pour une autre IA ainsi qu'une sauvegarde sur `main`. **Ne pas redemander la validation de conception/plan.** Branche de réalisation `plan-4-films`.

- **Choix validé** : même fonctionnement que Séries ; ajouts récents limités aux catégories déjà chargées. Accueil, fiche, lecture/reprise, Ma liste et catégories sur iPhone. Conception : `docs/superpowers/specs/2026-11-15-etape4b-films-design.md`. Plan à cinq tâches : `docs/superpowers/plans/2026-11-15-plan-4b-films.md`.
- **Fait et testé** : tâche 1 backend implémentée (`7d2791f`), migration additive `20261115000012_movies.sql`, 5 nouveaux tests d'intégration, **172 tests backend verts** (12 fichiers), anciennes séries incluses. Contrat RPC dans `backend/README.md`. Rapport durable : `docs/rapports/4b/tache-1-backend.md`. Relecture indépendante **approuvée**, rapport `docs/rapports/4b/tache-1-relecture.md`.
- **En cours** : build Android de référence terminé en échec réseau : 193 tests shared verts, tests TV non exécutés. Le téléchargement Robolectric depuis repo1.maven.org redirige vers gcp.mirrors.maven.org, refusé HTTP403. Journal `/workspace/iptv-env/android-baseline.log`. SDK/JDK prêts ; tâche2 autorisée à utiliser Gradle pour les tests shared. Ne pas lancer un deuxième Gradle simultanément. Politique réseau restricted/enforced confirmée ; demande au propriétaire pour ajouter `gcp.mirrors.maven.org` envoyée, réponse non encore reçue.
- **À faire dans l'ordre** : terminer relecture tâche 1 et corriger si nécessaire ; tâche 2 **en cours** catalogue films/cache/progression typée Kotlin ; tâche 3 parcours télé complet + tests télécommande ; tâche 4 organisation iPhone ; tâche 5 revue globale, suites complètes, APK signé 0.5.0/code6 proposé et certificat vérifié, puis accord distinct de déploiement. Aucun écran Films n'existe encore.
- **Piège d'intégration trouvé** : `MainActivity.kt`/seriesDetail/dropQueued filtre actuellement profil+itemId seulement. Ajouter `entry.itemType == "episode"` en tâche 2, sinon une action épisode peut effacer une progression film de même id. Les regroupements et anciennes files doivent distinguer profil+type+id ; ancien JSON sans type = épisode.
- **Outils de cette machine** : `source /workspace/iptv-env/activate.sh` (JDK21 complet, SDK36, caches Gradle). Hors dépôt : dépend de la machine, voir `docs/rapports/4b/reprise.md` pour provenance/commandes. Les anciennes instructions `/workspace/iptv-env` de B2 ne sont pas la preuve que ces outils existaient dans cette nouvelle machine.
- **Checkpoint distant confirmé** : `main` contient backend + rapports + conception/plan via fusion `7b0eb49`. Branche `plan-4-films` remise au même commit pour continuer. Autorisation de sauvegarde sur main reçue explicitement ; la publication reste distincte.
- **Sauvegarde** : commit et push après chaque sous-étape ; mettre à jour ce bloc, le journal et le plan avant fin de quota. Le propriétaire demande aussi `main` : intégrer uniquement les checkpoints vérifiés, et signaler le travail restant. Demande distincte de toute publication.
- **Aucune production touchée** : migration 0012 locale seulement, 0010 non déployée, aucun APK/page publié, aucun environnement cloud republié. Ne jamais tester contre Supabase production ni lire service_role. La 4A est terminée en 0.4.1 ; les instructions 4A ci-dessous sont historiques.

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
| 4A. Séries | ✅ terminée | 0.4.1 publiée, essai télé réussi, fusionnée dans main. Guide reporté. |
| 4B. Films | ✅ terminée | 0.5.0 en ligne le 16 nov., essai télé réussi (accueil, catégories, fiche, lecture), fusionnée dans main. Quelques fonctions restent à essayer par le propriétaire, voir section 0. |

Conception : `docs/superpowers/specs/2026-11-15-etape4a-series-design.md`, section 9 comprise. Plan : `docs/superpowers/plans/2026-11-15-plan-4a-series.md`. Corrections prioritaires : `docs/rapports/4a-tache3-corrections-a-faire.md`. Aucun nouveau déploiement, aucune migration de production ni fusion autorisés.
| 4. Films et séries | à faire | ≈ 3–4 séances |
| 5. Recherche | code fait et testé (branche `plan-5-recherche`), essais et mise en ligne à la fin du projet | voir section 0 |
| 6. Sport | 0.7.0 publiée (branche `plan-6-sport`), essais à la fin du projet | voir section 0 |
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
| **Télé** | App `home.iptv.tv`, installée par **Downloader** depuis `iptv-home-config.vercel.app/iptv-home.apk`. Version en ligne : **0.5.0 (versionCode 6)**. |

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

### Actualisation réseau (reprise)

Le propriétaire ne connaissait pas ce domaine ; explication donnée : serveur de téléchargement de l'Android simulé des tests, pas un service à intégrer à l'application. Ajout `gcp.mirrors.maven.org` préparé et sauvegardé dans le nouveau brouillon réseau, état backend saved/requires_publish=true. Aucune publication effectuée ; scripts, dépôts, secrets et autres champs non réappliqués. Le brouillon seul n'active pas le réseau. Attendre un changement réel et retester le téléchargement. L'instruction utilisateur de ne rien publier reste en vigueur.
