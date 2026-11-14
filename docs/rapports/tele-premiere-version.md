# Télé — première version (0.1.0) : essai chez le propriétaire

Date : 14 novembre 2026 · Appareil : TCL QM85 65" (Google TV) · Version : 0.1.0 (versionCode 1)

## Mise en ligne
- Page de configuration : https://iptv-home-config.vercel.app (projet Vercel `iptv-home-config`).
- APK : `https://iptv-home-config.vercel.app/iptv-home.apk`, réécrit vers le compartiment public
  Supabase `apk` (le fichier de 35 Mo ne passe pas par l'outil de déploiement). Téléversement par
  une règle d'écriture temporaire, supprimée juste après : plus aucune règle d'écriture sur le stockage.
- Supabase : Site URL et Redirect URLs réglées par le propriétaire sur l'adresse de la page.

## Résultats de l'essai (rapportés par le propriétaire)
| Point | Résultat |
|---|---|
| Installation via Downloader | OK |
| QR code scanné, télé validée | OK |
| Profils affichés (« Qui regarde ? ») | OK, les 3 profils |
| Catégories et chaînes affichées | OK |
| Lecture d'une chaîne | OK, plusieurs chaînes essayées |
| 2ᵉ ouverture | « très très rapide » |

Aucun problème rencontré.

## Reste à vérifier plus tard (non bloquant)
- Ouverture à froid après plus d'une heure sans démarrer l'app (session conservée, pas de « Hors ligne »).
- Écoute longue sans toucher la télécommande (pas d'économiseur d'écran).
- Rafraîchissement du catalogue pendant une lecture avec une seule connexion autorisée.

## Points différés (plan 3b ou plus tard)
- Libellé quand on valide un ancien code remplacé (« n'existe pas » au lieu de « expiré »).
- Identifiants et profils demandés en parallèle au démarrage.
- Garde « un seul rafraîchissement » par processus plutôt que par écran.
- Backend : `record_progress` accepte un horodatage NULL (à corriger au début du plan 3b).
- Mise à jour de l'APK : refaire le téléversement dans le compartiment `apk` (même nom de fichier).
