# MÉMOIRE DES IA — ce qu'on peut faire, comment, et les pièges

> **Demande du propriétaire (24 nov. 2026) : toute IA qui travaille sur ce projet laisse ici ses découvertes
> importantes** (un accès, une commande qui marche, un piège, une particularité du fournisseur…), avec la date.
> Lire ce fichier au début ; l'enrichir avant de finir. `PASSATION.md` dit **où en est** le projet ; ce fichier dit
> **comment agir**. Une copie est gardée sur le Mac dans `~/serveur-maison/MEMOIRE-IA.md` (la référence est ici).
> Ne jamais y écrire d'identifiant IPTV, de mot de passe ni de clé secrète.

---

## 1. Les accès possibles

| Quoi | Comment | Remarques |
|---|---|---|
| **Mac du propriétaire** (`MacBookeMizzler`) | Séance sur le Mac, ou séance cloud + dossier partagé `serveur-maison` (Remote Control) | En cloud : `list_computer_folders` puis `request_computer_folder` ; le propriétaire approuve. Si le dossier est « not_available » : mettre à jour l'app Claude du Mac et activer Réglages → Claude Code → Remote Control. Le lien peut tomber quand la machine cloud redémarre : redemander le dossier. |
| **Serveur maison** (Ubuntu, `192.168.2.81`) | Depuis le Mac : `ssh volcan@serveur "commande"` (clé sans mot de passe) | `sudo` impossible sans le propriétaire. Contexte : `ssh volcan@serveur "cat ~/services/CLAUDE.md"`. |
| **TV Salon** (TCL Google TV) | Depuis le Mac : `~/serveur-maison/iptv-dev/tv.sh …` (adb dans le conteneur `iptv-dev`) | Voir section 2. **Ne jamais prendre la main si quelqu'un regarde au salon sans l'accord du propriétaire.** |
| **Supabase** (production) | Connecteur MCP Supabase, projet `gisjeuukbrgjgkrmlyvs` | Lecture SQL pour diagnostiquer (ex. `profiles`, `watch_progress`). Jamais la clé `service_role`. |
| **GitHub** | `mizou5532/iptv-home` | Depuis le Mac, `gh api repos/mizou5532/iptv-home/contents/<chemin>?ref=<commit> -H "Accept: application/vnd.github.raw"` télécharge un fichier d'une branche sans toucher à la copie du propriétaire. |
| **Vercel** | CLI `vercel` connectée sur le Mac | Projet `iptv-home-config` (lien dans `~/Projets/iptv-home/web/config/dist/.vercel/project.json`). |

## 2. Contrôler la TV Salon (`tv.sh`, sur le Mac)

| Commande | Effet |
|---|---|
| `tv.sh on` | **Allume la télé** par le réseau (Wake-on-LAN depuis le serveur), attend qu'adb soit prêt, réveille l'écran. ✅ testé le 24 nov. |
| `tv.sh off` | Met la télé en veille. |
| `tv.sh connect` / `devices` / `ip` | Connexion adb, état, adresse (retrouvée par l'adresse MAC `24:3f:75:c6:cd:83`). |
| `tv.sh screen /tmp/x.png` | Capture d'écran (puis la lire avec l'outil Read). |
| `tv.sh shell "input keyevent 23"` | Touches : 19 haut, 20 bas, 21 gauche, 22 droite, 23 OK, 4 retour, 224 réveil, 223 veille. Appui long : `input keyevent --longpress 23`. |
| `tv.sh install` | Installe l'APK construit dans l'atelier (`/work/apps/tv/build/outputs/apk/release/tv-release.apk`). |

- **Allumage à distance** : possible seulement parce que la télé est réglée sur *Paramètres → Système → Alimentation et
  énergie → Modes de consommation d'énergie → « Optimisée · Mode Veille en réseau »* (réglé le 24 nov. 2026 avec
  l'accord du propriétaire ; avant : « Faible », Wi-Fi coupé en veille, rien ne pouvait la réveiller). En veille, elle
  ne répond pas au ping : seul le signal de réveil marche. Juste après le réveil, adb est « offline » quelques secondes
  (`tv.sh on` gère : déconnexion/reconnexion jusqu'à l'état `device`).
- Plusieurs commandes dans un seul `shell` : les mettre entre apostrophes, `tv.sh shell "'logcat -c; input keyevent 23'"`.
- **Lire l'écran en texte** : `tv.sh shell "'uiautomator dump /sdcard/ui.xml >/dev/null; cat /sdcard/ui.xml'" | grep -o 'text="[^"]*"'`.
- À l'allumage, Google TV peut afficher « Choisir un compte » (comptes Google) : ne pas y toucher, lancer l'app avec
  `tv.sh shell "am start -n home.iptv.tv/.MainActivity"`. Écran de veille ambiant : `input keyevent 224`.
- Profils : Hamza (propriétaire, langues anglais → français → québécois), Maha, Mahmoud.
- **Piège : lire un épisode/film écrit la progression du profil.** Une touche de trop sur une fiche lance la lecture.
  Avancer touche par touche avec une capture entre chaque. Si ça arrive : appui long sur l'épisode (« vu ») puis encore
  (« non vu »), puis relire quelques secondes l'épisode où en était le propriétaire ; vérifier dans `watch_progress`.
- Accueil Séries/Films : la bannière défile toute seule (« Voir la série » ouvre le titre affiché à ce moment) ; OK
  sur une carte « Reprendre » **lance la lecture** ; OK sur « Ma liste » ou une rangée ouvre la fiche.

## 3. Construire et tester (atelier `iptv-dev` sur le serveur)

- `~/serveur-maison/iptv-dev/tester.sh '<commande>'` copie **`~/Projets/iptv-home` (la copie du propriétaire)** vers
  le serveur puis lance la commande. Pour tester le travail d'une séance cloud sans toucher à sa copie : faire une copie
  de travail `/tmp/iptv-test` sur le Mac (rsync de `~/Projets/iptv-home`, y déposer les fichiers modifiés, `gh api`
  ci-dessus), puis `rsync -a --exclude '**/build/' --exclude '**/.gradle/' --exclude '**/.kotlin/' --exclude local.properties /tmp/iptv-test/apps/ volcan@serveur:dev/iptv-home/apps/`
  (ne copier que `apps/` : un rsync `--delete` du dossier entier échoue sur les `node_modules` du conteneur, propriété de root).
- Tests : `ssh volcan@serveur "docker exec -w /work iptv-dev bash -lc 'cd apps && ./gradlew --max-workers=2 --continue :shared:jvmTest :server:test :tv:testDebugUnitTest'"`.
  Compter les résultats dans `build/test-results/*/*.xml`. 3 tests Robolectric en dessin natif sont écartés dans l'atelier.
- Page web : `npm test -w web/config` et `npx tsc --noEmit -p web/config` (marche aussi dans une machine cloud).
- Le conteneur cloud n'a pas de SDK Android : construire/tester Kotlin dans l'atelier.

## 4. Publier (toujours avec l'accord du propriétaire)

Ordre : **serveur maison → APK → page + `version.json`** (la télé ne doit jamais voir une version annoncée avant le fichier).

1. **Serveur maison** : `docker exec -w /work/apps iptv-dev ./gradlew :server:installDist`, puis sur le serveur
   `cd ~/services/iptv-catalogue && mv server server.<ancienne-version> && cp -R ~/dev/iptv-home/apps/server/build/install/server server && docker compose up -d --build`
   (garder l'ancienne pour revenir en arrière). Vérifier la version : `unzip -p server/lib/server.jar home/iptv/server/MainKt.class | strings | grep 0.6.`.
   Puis mettre à jour `~/services/CLAUDE.md` (règle du serveur) et sa copie sur le Mac.
2. **APK** : `:tv:assembleRelease` dans l'atelier ; vérifier le certificat (`apksigner verify --print-certs`,
   SHA-256 attendu `5d8877ce…615d4`) et la version (`aapt dump badging`, prendre **un seul** dossier build-tools).
   Ouvrir l'écriture le temps de l'envoi :
   `alter policy deploy_temp_insert on storage.objects with check (bucket_id = 'apk' and name = 'iptv-home.apk');` (idem `deploy_temp_select` / `deploy_temp_update`),
   envoyer depuis le serveur avec `curl -X POST …/storage/v1/object/apk/iptv-home.apk -H 'x-upsert: true'` et la clé
   publishable (`docs/rapports/synchro-production.md`), puis **remettre les trois règles à `false`** et vérifier
   l'empreinte du fichier téléchargé par `https://iptv-home-config.vercel.app/iptv-home.apk`.
3. **Page** : mettre à jour `web/config/public/version.json` (code, nom, SHA-256, taille). Sur le Mac, dans la copie
   de travail : `npm ci` (sinon `tslib` manque et la construction échoue), `vite build --outDir /tmp/iptv-web-dist --emptyOutDir`,
   copier `vercel.json` et `.vercel/project.json`, puis `vercel deploy --prod --yes` depuis ce dossier. Vérifier la
   page (`?demo` dans un navigateur, 0 erreur) et `version.json` en ligne.

## 5. Comportements de l'app à connaître

- **Changer de langue sur une fiche** (bouton « Version : … ») : la fiche de l'autre version s'ouvre (réparé en 0.46.2,
  `key(target)` dans `App.kt`). La **langue de la version passe avant les langues du profil** pour la piste audio, à la
  télé (`chooseTracks(…, versionAudio)`) comme sur la page iPhone (serveur maison 0.6.5, `StreamRelay` lit les pistes
  avec `ffprobe` puis `-map 0:<n>`).
- Les versions d'un même titre partagent la reprise (même saison/épisode) : une progression faite en QC apparaît en FR.

## 6. Particularités du fournisseur IPTV (constatées)

- « FR - South Park (1997) (US) » : deux pistes audio, **anglais en premier**, français en second. « QC - South Park » :
  une seule saison chez le fournisseur.
- « Le Robot sauvage », version « Anglais » : durée annoncée 1 min (donnée du fournisseur, probablement un mauvais fichier).
- Les titres portent souvent la langue en préfixe (« FR - », « QC - », « EN - », « D+ - », « 4K-OSN+ - »).

## 7. Pièges de l'outil (Claude Code en mode automatique)

- Le contrôle de sécurité refuse parfois : les gros blocs encodés (base64), `curl -k`, les longues chaînes de commandes
  qui éteignent/rallument des appareils. Découper en petites commandes lisibles, écrire les fichiers avec Write/Edit.
- Commandes lancées sur le Mac : `timeout` n'existe pas (macOS) ; le shell est zsh.

---

## Journal des découvertes (ajouter en haut, avec la date)

- **2026-11-26** : le fournisseur donne **un lien (adresse de serveur) par abonnement**, même catalogue. Les comptes
  supplémentaires ont maintenant leur propre adresse (`xtream_extra_accounts.server`, vide = celle du principal ;
  `ExtraAccount.credsFor(main)` partout). Migration `20261126000039` appliquée en production via le connecteur
  Supabase (`apply_migration`, le propriétaire approuve). Le Mac se déconnecte quand il se met en veille : la séance
  cloud ne le voit plus du tout dans `list_computer_folders` ; demander au propriétaire de le réveiller.
- Tests de la base : Postgres 16 est présent dans la machine cloud (`/usr/lib/postgresql/16/bin`) :
  `npx vitest run --root backend` marche sans le serveur (≈ 1 min).

- **2026-11-24** (Claude, séance « changement de langue ») : télé réglée en « Mode Veille en réseau » ; `tv.sh on/off`
  ajoutés et testés ; procédures de publication et de test depuis une séance cloud écrites ci-dessus ; bogue
  « Chargement… sans fin » au changement de langue corrigé (0.46.2 → 0.46.3, serveur 0.6.5).
