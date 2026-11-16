# Étape 5b — Bouton « Mise à jour » sur la télé : conception courte

16 novembre 2026. Statut : **approuvée par le propriétaire** (« Oui, ça me va, tu peux y aller »).

## Pourquoi

Chaque nouvelle version oblige le propriétaire à passer par Downloader et à retaper l'adresse : long et pénible, alors
que les mises à jour vont être fréquentes. Il veut un bouton dans l'application, avec une pastille quand une version
est prête, qui télécharge, installe et relance.

## Ce qui est décidé

- **0.5.1 / versionCode 7** = la 0.5.0 actuelle + le bouton, construite depuis `main` (sans la recherche). Dernière
  installation par Downloader. **0.6.0 / versionCode 8** = la recherche, installée ensuite **par le bouton** (c'est le
  vrai essai du bouton). Choix de Claude, accepté par le propriétaire.
- L'application lit `https://iptv-home-config.vercel.app/version.json` au démarrage, au retour au premier plan (au plus
  une fois par heure) et toutes les 6 h tant qu'elle reste ouverte. Champs : `versionCode`, `versionName`, et nouveaux
  `apkSha256` (empreinte du fichier) et `apkSize` (taille en octets).
- Version plus récente que celle installée → bouton **« Mise à jour »** avec une pastille dans la barre du haut
  (à droite de Direct / Séries / Films). OK ouvre un panneau : « Nouvelle version x.y.z », **Mettre à jour** /
  **Plus tard**, puis la progression du téléchargement.
- Téléchargement de `https://iptv-home-config.vercel.app/iptv-home.apk` dans le cache de l'app ; refus si la taille ou
  l'empreinte ne correspondent pas à `version.json` (message « Le fichier téléchargé est abîmé. Réessaie. »).
- Installation par `PackageInstaller` (session) ; sur Android 12 et plus, `USER_ACTION_NOT_REQUIRED` pour éviter la
  confirmation quand Android l'autorise. La première fois, Android exige l'autorisation « installer des applis
  inconnues » pour IPTV Home : le panneau l'explique et ouvre le bon réglage.
- Android vérifie lui-même que la mise à jour est signée avec la même clé (sinon refus) : rien d'autre ne peut
  s'installer par ce bouton.
- Après l'installation, tentative de relance automatique (`MY_PACKAGE_REPLACED`) ; si Android la bloque, le
  propriétaire rouvre l'application d'un clic.
- Aucun changement du compte en ligne ni de la page iPhone (seul `version.json` gagne deux champs).

## Approches considérées

| | Approche | Pour | Contre |
|---|---|---|---|
| **A — retenue** | L'app télécharge l'APK publié et lance l'installateur Android | Simple, aucun service nouveau, même lien qu'aujourd'hui | Une confirmation Android possible, autorisation à donner une fois |
| B | Passer par le Google Play (piste interne) | Mises à jour automatiques | Compte développeur Google, publication à chaque fois, délai de revue |
| C | Ne mettre à jour que du contenu (pas de nouvelle app) | Aucune installation | Impossible pour du code Kotlin ; ne couvre pas le besoin |

## Tests

Logique pure (lecture de `version.json`, comparaison, vérification de taille/empreinte, états du panneau) en tests JVM ;
bouton et panneau à la télécommande (Robolectric). L'installation réelle et la relance ne peuvent être vérifiées que
sur la TCL.

## À vérifier sur la vraie télé

L'autorisation « applis inconnues », la présence ou non de la confirmation Android, la relance automatique, la durée
du téléchargement (≈ 37 Mo).
