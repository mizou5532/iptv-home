# Confirmations intégrées — suite (points 1 à 4 de l'audit du 15 novembre 2026)

**Qui :** Cosmo, l'assistant personnel du propriétaire (pas l'agent de développement habituel). Travail fait à la demande explicite du propriétaire : audit en lecture seule d'abord, puis accord donné point par point. Le point 5 (protection de la lecture) est **volontairement réservé** : le propriétaire veut l'attaquer séparément, plus tard.

**Branche :** `fix/confirmations-integrees-restantes`, créée à partir de `claude/peaceful-davinci-pi93u7` @ `966295e` (la branche Films en cours). Ni `main` ni la branche Films n'ont été modifiées. **Rien n'est déployé** : la page en ligne garde le code d'avant. Sauvegarde complète d'avant travaux : chez le propriétaire (miroir du dépôt + fichiers en ligne, 15 nov. 21 h 19).

## Contexte

Le rapport du matin même (`correction-confirmation-integree-2026-11-15.md`, commit `9198d98` sur `main`) avait converti les confirmations de l'écran Catégories (Direct et Séries) et listé les `confirm()` natives restantes. L'audit du 15 nov. au soir les a confirmées : 5 endroits, identiques sur `main` et sur la branche Films. Le propriétaire a approuvé les points 1 à 4 ci-dessous et réservé le point 5.

## Ce qui a changé

Même patron partout : le texte d'avertissement existant (inchangé) s'affiche dans la page, dans un conteneur `.tools` avec un `<span class="ctext">` et des boutons « Confirmer » / « Annuler ». Aucun appel métier n'a changé : mêmes RPC, mêmes arguments, mêmes chemins d'erreur.

1. **Suppression d'une playlist** — `web/config/src/org/view.ts` (`openPlaylist`)
   Zone `#delconfirm` sous le bouton « Supprimer la playlist » (boutons `data-del="yes"` / `"no"`). « Confirmer » exécute `orgPlaylistDelete` puis revient à la liste, comme avant. Le nom de la playlist passe par `esc()` : il est maintenant inséré dans du HTML, il ne l'était pas dans la boîte native.

2. **Retrait d'un appareil** — `web/config/src/main.ts` (`showMain`, section « Mes appareils »)
   Zone `#devconfirm` sous la liste des appareils. « Confirmer » exécute `revokeDevice` puis `route()`, comme avant ; en cas d'erreur, le message passe toujours par `say()`. Le texte précise le nom de l'appareil visé (« Retirer l'appareil « X » ? »).

3. et 4. **Groupes de copies** — `web/config/src/org/groups-view.ts` (`mountGroups`)
   Zone `#copies-confirm` et mécanisme `askConfirm` / `closeConfirm` : une seule confirmation en attente à la fois, boutons `data-cf="yes"` / `"no"`.
   - « Recharger » avec des modifications non enregistrées : confirmation « Abandonner les modifications et recharger ? », puis `mountGroups` comme avant. Sans modification, rechargement immédiat, comme avant.
   - « Restaurer » une correction dont les familles sont partagées : même texte qu'avant, confirmation intégrée, puis la même préparation (`stage(...)`) qu'avant. Sans famille partagée, restauration immédiate, comme avant.
   - **Focus préservé** : `askConfirm` retient la source de la ligne qui a ouvert la confirmation ; après l'action confirmée, `paint()` rend le focus à cette même source (comportement couvert par le test existant « restaurer garde le focus… », adapté pour confirmer d'abord). Toute nouvelle préparation (`stage`) ferme une confirmation en attente.

## Tests

- `npm test` (web/config) : **146/146 verts, 12 fichiers** (la branche de base en comptait 144). Détail :
  - `org-groups-view.test.ts` : le test de restauration vérifie maintenant le flux intégré (zone visible, « Annuler » = rien de préparé, « Confirmer » = restauration, exclusions conservées) ; nouveau test « recharger avec modifications demande confirmation intégrée » ; le test de focus confirme d'abord ; le `vi.stubGlobal('confirm', …)` global a été retiré — plus aucun test ne dépend de la boîte native.
  - `org-playlists-view.test.ts` (**nouveau**) : flux complet de suppression (confirmation affichée, annulation sans effet, confirmation = suppression et retour à la liste), sur les données de démonstration.
  - Le point 2 n'a pas de harnais d'interface dans ce dépôt (comme avant) : `revokeDevice` est couvert par `flow.test.ts` ; le code passe le typage strict.
- `npm run build` (web/config, `tsc` + `vite`) : OK.
- Build de production `tools/web/construire-page.sh` : OK. Le paquet contient les trois nouvelles zones (`delconfirm`, `devconfirm`, `copies-confirm`) et **exactement une** confirmation native restante : celle du point 5.
- Aucun fichier hors `web/config` n'est touché ; les suites backend et Android n'ont pas été relancées.

## Ce qui n'a PAS été touché (point 5, réservé par le propriétaire)

- `web/config/src/org/playback-admin.ts` : `window.confirm` garde toutes les mutations de l'écran « Protection de la lecture » (confirmation d'arrêt physique). Ses tests stubent encore `confirm`. À traiter séparément, avec l'accord du propriétaire.

## Pour fusionner

La branche part de la pointe de la branche Films : la fusion dans `claude/peaceful-davinci-pi93u7` (ou dans `main` après la 4B) ne devrait toucher que les 5 fichiers de `web/config` ci-dessus, ce rapport et le pointeur dans `PASSATION.md`. Aucun déploiement n'est fait ni implied : la page en ligne ne change que par la procédure habituelle, avec l'accord du propriétaire.
