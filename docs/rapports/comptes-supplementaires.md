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
