# Comptes IPTV supplémentaires — 0.33.0 (code 58), publiée le 18 nov. 2026

*Demande du propriétaire : regarder sur plusieurs télés en même temps (lui et sa sœur). Le fournisseur n'autorise qu'une vidéo à la fois par compte et ne vend pas de compte à plusieurs connexions : il faut plusieurs abonnements. « La première télé prend les premiers identifiants ; si une autre regarde en même temps, les deuxièmes entrent en jeu. »*

## Fonctionnement

1. **Page iPhone → Réglages → « Comptes supplémentaires »** : ajouter un compte (nom d'utilisateur + mot de passe, même serveur que le compte principal), 4 au plus, ou le retirer. La page ne relit jamais les identifiants : elle montre seulement l'état (« En attente de la télé », « Vérifié : prêt à servir », « Refusé » avec la raison).
2. **Vérification par la télé** (le fournisseur ne parle qu'en http) : dès qu'un compte est ajouté (révision des réglages du foyer) ou à l'ouverture d'un profil, la télé vérifie qu'il est accepté, actif, et qu'il donne le même catalogue que le compte principal (mêmes catégories du Direct, mêmes chaînes dans une catégorie **jamais masquée pour tout le monde**). Résultat envoyé au serveur.
3. **Attribution au moment de regarder** : la télé réserve le premier compte libre (principal d'abord, puis les comptes « Vérifié » dans l'ordre), le garde pendant les changements de chaîne, confirme toutes les 15 s qu'elle regarde toujours, et le rend 5 s après la dernière vidéo (tout de suite si l'app passe en arrière-plan). Sans signe de vie depuis 45 s (télé débranchée), le compte redevient libre.
4. **Tous les comptes pris** : rien n'est lu, message en haut de l'écran (« Les 2 comptes IPTV sont déjà utilisés : Maha sur Télé du salon. ») ; films et épisodes affichent le message avec « Réessayer ».
5. **Sans compte supplémentaire** : rien ne change (aucune réservation, aucun refus). Serveur de la maison injoignable : le compte principal est utilisé comme avant.
6. Le catalogue est toujours lu avec le compte principal. Diagnostic : ligne « Compte IPTV : principal / n° 2 ».

## Où

- Serveur : `backend/supabase/migrations/20261119000027_comptes_supplementaires.sql` (**appliquée en production**) — table `xtream_extra_accounts`, `add_extra_account`, `remove_extra_account`, `report_extra_account`, `claim_account` (colonne `active_streams.account_position`), revérification si le compte principal change. Tests `backend/test/comptes-supplementaires.test.ts`.
- Télé : `player/AccountLeases.kt`, `player/ExtraAccountsCheck.kt`, `player/PlayerController.kt`, `IptvApp.kt`, `App.kt` (`BusyNotice`), `diag/Diagnostics.kt` ; partagé : `SyncClient` (5 appels), `XtreamUrls.withAccount`.
- Page : `web/config/src/extra-accounts.ts`, section dans `main.ts`.

## Relecture indépendante

5 défauts trouvés et corrigés avant publication : (1) une télé était refusée même sans compte supplémentaire → plus aucune réservation dans ce cas, et le compte principal accepte autant de vidéos que le fournisseur le permet ; (2) film ou épisode refusé restait sur « Connexion… » → message avec « Réessayer » ; (3) compte rendu par erreur pendant un changement de chaîne rapide → relevé fait au moment de la fermeture ; (4) réservation perdue après une longue coupure → redemandée aussitôt, sinon vidéo arrêtée avec un message (jamais deux télés sur un compte) ; (5) compte attribué mais illisible → plus de repli sur le compte principal déjà pris.

## Chiffres

Tests : 217 serveur, 362 partagés, 504 télé, 268 page, 0 échec ; analyse Android 0 erreur. APK SHA-256 `e5736585185289e86b87aab4d41f30e54ea0ebf7eddbfaec0d989cc81a794088`, 14 682 559 octets, `versionCode 58`, `minSdkVersion 25`, certificat attendu, identique par Supabase et Vercel ; page `index-Be35Gnnt.js` + `index-aipw3atI.css` (+ `demo-BT0mforq.js`), démo sans erreur ; Vercel `dpl_5EuRjkvKzWSkNrC5YdD42B9oHd6S` ; règles stockage : les 4 `avatars_*`.

## Essai à faire

1. Mettre à jour les deux télés (TCL et Fire Stick) en 0.33.0.
2. iPhone → Réglages → Comptes supplémentaires → Ajouter le 2e abonnement. Garder une télé allumée sur l'app : en moins d'une minute, recharger la page → « Vérifié : prêt à servir ».
3. Regarder une chaîne sur la TCL, puis une autre sur le Fire Stick : les deux jouent. Panneau de diagnostic (Info ×3) : « Compte IPTV : principal » sur l'une, « n° 2 » sur l'autre.
4. Une 3e vidéo (ou les deux télés sans 2e compte vérifié) : message « déjà utilisé ».

## 0.33.1 (code 59), publiée le 18 nov. 2026 — vérification complète

Demande du propriétaire : voir pour chaque compte s'il est expiré ou actif, **combien de jours il reste**, et s'il donne **les mêmes chaînes, films et séries** que le compte principal.

| Ajout | Où |
|---|---|
| Page : « Actif jusqu’au 8 décembre 2026 (dans 20 jours) », orange à 30 jours, rouge à 7, « Abonnement terminé depuis le … » ; date de la dernière vérification par la télé | `web/config/src/extra-accounts.ts` |
| Films et séries comparés (catégories) : refus « Mêmes chaînes, mais pas les mêmes films / séries / ni films ni séries » | `ExtraAccountsCheck`, raisons `films`, `series`, `films_series` |
| Chaque compte revérifié une fois par jour (au retour à l'écran et toutes les 6 h) : un compte expiré passe à « Refusé », un compte renouvelé redevient « Vérifié » | `ExtraAccountsCheck.RECHECK_EVERY_S`, `MainActivity` |
| Serveur : un compte dont la date de fin est passée n'est jamais donné à une télé, même avant sa revérification | migration `20261119000028_comptes_verification.sql` (**appliquée en production**) |

Tests : 218 serveur, 362 partagés, 506 télé, 269 page, 0 échec ; analyse Android 0 erreur. APK SHA-256 `1629eb518bb8a5a337dbf6a02a7c87982fdee8c4cb24d6c1f5568edc08f538e0`, 14 698 943 octets, `versionCode 59`, `minSdkVersion 25`, certificat attendu, identique par Supabase et Vercel ; page `index-Cpa8hzks.js` + `index-aipw3atI.css` (+ `demo-Cxq9oigt.js`), démo sans erreur ; Vercel `dpl_EXwZpwVRpXwiPbrRg24uhRR62kxX` ; règles stockage : les 4 `avatars_*`.

## 0.34.0 (code 60), publiée le 18 nov. 2026 — connexions en cours, message du fournisseur, vraie qualité des films

Demande du propriétaire (points 1, 2 et 5 de la liste des informations disponibles avec les identifiants) :

| Point | Ce qui est fait | Où |
|---|---|---|
| 1. Connexions en cours | La télé relève `active_cons` de chaque compte (principal et supplémentaires) toutes les 10 min quand l'app est à l'écran ; page : « 1 vidéo en cours sur 1 permise (relevé à 21 h 04) ». Compte tous les appareils, même hors de la maison | `XtreamApi.userInfo`, `IptvApp.refreshAccountsStatus`, `MainActivity` (boucle arrêtée en arrière-plan), page `account.ts` (`usageText`) |
| 2. Message du fournisseur | Affiché sous le compte s'il existe ; texte simple, 300 caractères ; **jamais** s'il contient l'identifiant, le mot de passe, une adresse web, un nom de domaine ou une adresse IP (dont celle du serveur) | `XtreamApi.providerMessage` |
| 5. Vraie qualité des films | Fiche du film sur la télé : « 4K · HDR10 · HEVC · 5.1 Dolby Digital Plus » d'après l'analyse du fichier par le fournisseur (`info.video` / `info.audio`) ; rien si elle manque. Base locale v5 (colonne `tech`) ; fiches déjà gardées relues une fois, et toujours lisibles hors ligne | `MediaTech`, `XtreamApi.techOf`, `4.sqm`, `MovieRepository`, `MovieDetailScreen` |

Serveur : migration `20261119000029_connexions_message.sql` (**appliquée en production**) ; `report_iptv_account` accepte toujours l'ancien appel à 4 valeurs.

Relecture indépendante : corrigé — message qui aurait pu montrer l'adresse du serveur (sans « http ») ; fiches déjà gardées qui ne s'ouvraient plus hors ligne juste après la mise à jour. Noté : une télé restée en ancienne version efface le relevé des connexions jusqu'au relevé suivant (10 min) ; sans conséquence une fois les télés à jour.

Non vérifié ici (identifiants IPTV jamais utilisés depuis l'environnement de travail) : ce que le fournisseur remplit réellement. Chaque information n'apparaît que si le fournisseur la donne.

Tests : 220 serveur, 365 partagés, 506 télé, 270 page, 0 échec ; analyse Android 0 erreur. APK SHA-256 `282f27bc1c3b4a83b3af1d267006e165c2d20f40a607dacf671d9a2fc7f84695`, 14 698 943 octets, `versionCode 60`, `minSdkVersion 25`, certificat attendu, identique par Supabase et Vercel ; page `index-BPiN35ni.js` + `index-aipw3atI.css` (+ `demo-BI_c9dmF.js`), démo sans erreur ; Vercel `dpl_54FHPFvmdgLZ4kbcTvw22kZptSfA` ; règles stockage : les 4 `avatars_*`.

## 0.46.4 / serveur maison 0.6.6 (26 nov. 2026) — un lien (adresse) par compte

Demande du propriétaire : le fournisseur donne **un lien différent par abonnement** (autre adresse de serveur, autres
identifiants, même catalogue). Avant, un compte supplémentaire empruntait toujours l'adresse du compte principal.

| Où | Ce qui change |
|---|---|
| Base (`20261126000039_comptes_adresse_propre.sql`, **appliquée en production le 26 nov.**) | colonne `xtream_extra_accounts.server` ; `add_extra_account(h, username, password, server default null)` : `http://` ajouté si absent, `/` final retiré, adresse identique au principal ou vide = `null` (celle du principal), adresse invalide → `adresse-invalide`. Les anciens appels à 3 valeurs restent valables. |
| Page (`extra-accounts.ts`, `main.ts`) | champ « Adresse du lien de ce compte (facultatif) » ; le lien complet `…/get.php?username=…&password=…` remplit aussi l'utilisateur et le mot de passe (`parseProviderLink`). La page ne relit jamais l'adresse. |
| Télé, serveur maison, partagé | `ExtraAccount.server` + `credsFor(main)` (vérification, relevé d'état, lecture) ; `withAccount` réécrit aussi l'adresse de la vidéo. |

Tests : base 276, page 297 (dont lien complet, adresse vide, adresse invalide), télé 638, partagé 438, serveur 76,
0 échec (dont vérification à l'adresse propre et réécriture d'adresse de la vidéo).

**Mise en ligne (26 nov.)** : serveur maison 0.6.6 déployé (ancien gardé dans `server.0.6.5`) ; il vérifie lui-même un
compte ajouté (révision des réglages) à son adresse propre, même si aucune télé n'est sur l'app. APK 0.46.4 (code 81)
`4dc3cd89…5c64e7` (5 534 897 octets, certificat officiel) dans `apk/iptv-home.apk`, règles `deploy_temp_*` remises à
`false` ; page `index-BCGh8kfh.js` + `version.json` 0.46.4 sur Vercel, champ visible dans la démo, 0 erreur.
Essai sur la TCL non fait : la télé était allumée sur une entrée TCL (quelqu'un regardait), on n'y a pas touché.
Essai réel à faire par le propriétaire : ajouter le 2e abonnement avec son lien → « Vérifié : prêt à servir ».
