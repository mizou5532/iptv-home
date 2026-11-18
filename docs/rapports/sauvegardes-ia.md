# 0.37.0 — Copie la plus stable, sauvegardes, accès pour les IA (18 nov. 2026)

Demandes du propriétaire : idée n° 10 (« copie la plus stable »), « un maximum de sécurité de sauvegarde », et une
interface pour que les intelligences artificielles organisent la maison (« Je veux l'option 1 et l'ordre me va »).

## Ce qui a été publié

| Élément | Détail |
|---|---|
| Migration `sauvegardes` (`20261119000030`) | Appliquée. Table `household_backups` (lecture propriétaire seulement), copies de nuit (pg_cron installé, tâche `sauvegardes-nuit`, `17 8 * * *` UTC = 3 h 17 au Québec), avant changement d'identifiants IPTV, avant suppression de profil, avant restauration, avant IA, à la demande. Gardées : 30 nuits, 1 par mois sur 12 mois, 40 par autre raison (15 pour « avant restauration » et « avant IA ») — une raison ne pousse jamais dehors les copies d'une autre. Restauration par parties (profils, progression, organisation), fichier téléchargeable et restaurable. Jamais les identifiants IPTV ni les appareils. `progress_copy` pour la progression retrouvée. |
| Migration `acces_ia` (`20261119000031`) | Appliquée. `ai_access` (seule l'empreinte de la clé est gardée, 5 accès actifs au plus, coupables), `ai_log` (journal), `ai_call` (seule fonction ouverte à la clé publique ; la clé est vérifiée à chaque appel). Outils : profils, catégories, chaînes, masquages, masquer/afficher, favoris, playlists, annuler (dans l'heure, jamais par-dessus une sauvegarde manuelle ou une restauration), journal. |
| Fonction `assistant` | Déployée (v1, `verify_jwt = false`, aucun droit particulier : appelle `ai_call` avec la clé publique). Vérifiée : GET → 405, POST sans clé → 401, clé inconnue → « Clé d'accès invalide ». Tables nouvelles fermées à l'accès public (401). |
| Télé 0.37.0 (code 63) | Copie la plus stable de chaque chaîne (`CopyReliability`), catalogue vidé et relu au changement de fournisseur, progression recopiée par nom/saison/épisode (`ProgressRemapper`), ligne dans État de l'app. |
| Page iPhone | Réglages → « Sauvegardes » et « Accès pour les IA ». |

## Vérifications

- Tests : 372 partagés, 537 télé, 275 page, 236 serveur, 0 échec ; lint 0 erreur.
- APK SHA-256 `53e6d61875383b6244242de2c5663c32a8b82e794c5e6f006379b6d1048cb8b8`, 14 780 863 octets, certificat attendu, `minSdkVersion 25`, identique par le lien Vercel.
- Page `index-nB2nHH4u.js` + `index-Cv-vQ8Hk.css` (+ `demo-DLZs6pyJ.js`), identiques en ligne, bons types ; Vercel `dpl_7i4Npdyq6U6eqm68iwjWPfCXVP1P` ; démo sans erreur, les deux sections visibles.
- Règles stockage : les 4 `avatars_*` seulement.

## Limite connue

L'adresse du connecteur contient la clé (`?cle=…`) car Claude demande une adresse : elle peut apparaître dans des
journaux techniques. Elle se traite comme un mot de passe ; en cas de doute, couper l'accès sur la page et en créer un autre.

## Utiliser l'accès IA

Page iPhone → Réglages → Accès pour les IA → Créer un accès → copier l'adresse (montrée une seule fois). Dans Claude :
Réglages → Connecteurs → Ajouter un connecteur personnalisé → coller l'adresse.
