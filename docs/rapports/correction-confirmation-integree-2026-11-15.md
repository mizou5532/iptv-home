# Correction — confirmation intégrée à la page (15 novembre 2026)

**Qui :** Cosmo, l'assistant personnel du propriétaire (un autre agent que celui qui développe habituellement le projet). Intervenu à la demande explicite du propriétaire, le 15 novembre 2026 au soir, pendant qu'il faisait appliquer la sélection des chaînes en direct du profil « Tout le monde ».

**Commit :** `9198d98` sur `main` — « Page de configuration : confirmation intégrée à la page au lieu de confirm() native (Organiser, portée « Tout le monde ») ».

## Pourquoi

Le propriétaire a demandé à Cosmo d'appliquer, depuis le navigateur, la sélection des chaînes en direct du profil « Tout le monde » (66 catégories à garder sur 916 ; critères : Canada, Québec, France, États-Unis, sport international, nouvelles en français, enfants ; adultes, 24/7, copies en boucle et autres pays/langues masqués ; arabe gardé seulement pour le sport sur ce profil).

Blocage rencontré : dans la portée « Tout le monde », l'écran Catégories demandait ses confirmations par la boîte native `confirm()` du navigateur. Cette boîte est rejetée d'office dans l'environnement de navigateur automatisé de Cosmo : chaque tentative s'annulait avant toute écriture (état vérifié propre, 916/916 affichées, après chaque essai). Le propriétaire a choisi la correction du code plutôt que de faire le clic final lui-même.

Vérification faite avant de toucher au code : la logique métier était **correcte**. `hideAllExceptPlan` (page) et `org_hide_all_except` (serveur) font exactement « tout masquer sauf ce qui est coché », et les tests backend le couvrent (`tout_masquer_sauf_portee_profil_et_foyer`). Seule la confirmation native bloquait. Ne pas « corriger » cette logique : elle n'était pas en cause.

## Ce qui a changé (3 fichiers)

1. **`web/config/src/org/view.ts`**
   - Nouvel état `pending` dans l'écran Catégories : une action qui exigeait `confirm()` affiche maintenant le texte d'avertissement existant (`confirmText` / `hideChannelConfirm`, **inchangés**) dans la barre d'outils, avec des boutons « Confirmer » (`data-act="yes"`) et « Annuler » (`data-act="no"`).
   - « Tout masquer sauf ce que j'ai coché » (Direct **et** Séries, portée « Tout le monde », ou quand rien n'est coché) : passe par cette confirmation intégrée, puis exécute exactement le même appel qu'avant (`org_hide_all_except` / `org_series_hide_all_except`).
   - Case « Afficher » d'une catégorie décochée en portée « Tout le monde » : la case se recoche immédiatement, la confirmation intégrée s'affiche ; « Confirmer » applique `org_set_hidden` comme avant, « Annuler » ne change rien.
   - Masquer une chaîne pour tout le monde (liste des chaînes d'une catégorie) : même traitement, confirmation affichée dans la zone de message de la liste, boutons « Confirmer » / « Annuler » (`data-cf="1"` / `data-cf="0"`).
2. **`web/config/src/ui.css`** : une seule règle ajoutée, `.tools .ctext` (mise en forme du texte de confirmation dans la barre d'outils).
3. **`web/config/test/org-series.test.ts`** : le test `masquer_pour_tout_le_monde_demande_confirmation` vérifie maintenant la confirmation intégrée (texte affiché dans les outils, « Annuler » = aucune écriture, « Confirmer » = `org_set_hidden` appelé avec les mêmes arguments qu'avant). Aucun autre test modifié.

## Tests

- `npm test -w web/config` : **124/124 verts** (10 fichiers).
- `npm run build -w web/config` (`tsc` + `vite`) : OK.

## Ce qui n'a PAS été touché (confirm() natives restantes, volontairement hors portée)

- `web/config/src/org/view.ts` : suppression d'une playlist.
- `web/config/src/main.ts` : retrait d'un appareil.
- `web/config/src/org/groups-view.ts` : abandon de modifications, restauration de groupe.
- `web/config/src/org/playback-admin.ts` : confirmation d'arrêt physique (écran désactivé par défaut).

Si un automatisme doit un jour passer par ces écrans, il faudra leur appliquer le même traitement ; pour un humain, les boîtes natives fonctionnent normalement.

## Suite : la sélection des chaînes

## Déploiement : FAIT le 15 novembre 2026, par remplacement de contenu dans Supabase

Rappel : **cette page ne se déploie pas depuis GitHub** (procédure habituelle : `docs/rapports/deploiement-page.md`). Le propriétaire n'avait plus de quota chez ses autres IA et ne pouvait pas déployer lui-même ; il a donc approuvé une mise en ligne simplifiée, faite par Cosmo le 15 novembre au soir :

1. Construction du paquet de production avec `tools/web/construire-page.sh` : `assets/app-H22EIYHL.js` + `assets/app-SAQZZUTD.css` (+ `chunk-UNZNHO7B.js`, `demo-NNEQHQVA.js`).
2. Dans le compartiment Supabase public `web`, dossier `assets`, le **contenu** des fichiers en ligne a été remplacé sous leurs **noms actuels** (ceux que `index.html` sur Vercel référence déjà) : le nouveau JS sous le nom `app-ALUE2QLD.js`, le nouveau CSS sous `app-WPUQFUB4.css`, plus `chunk-UNZNHO7B.js` (contenu identique, même empreinte) et `demo-NNEQHQVA.js` (nouveau nom). Aucun autre fichier touché, aucune règle/policy modifiée. (Détail pratique : le téléversement groupé de l'interface Supabase crée des doublons « (1) » au lieu de remplacer, et son option « Replace file » n'a pas fonctionné pour l'outil automatisé ; le remplacement a été fait fichier par fichier : supprimer le fichier cible, retéléverser aussitôt le nouveau sous le même nom, vérifier.)
3. Vercel n'a pas été touché : `index.html` référence les mêmes noms, il charge donc le nouveau contenu.

**Conséquence à connaître pour le prochain vrai déploiement :** les noms `app-ALUE2QLD.js` / `app-WPUQFUB4.css` dans le compartiment contiennent le code du commit `9198d98`, pas le code de leur empreinte d'origine. Un déploiement normal depuis `main` (script + envoi des fichiers à empreinte + `index.html`) resynchronisera tout proprement. Une copie des anciens fichiers remplacés est conservée par Cosmo (espace de travail, dossier `iptv-deploy/backup-live/`) si un retour arrière était nécessaire entre-temps.

**Piège rencontré (cache) :** après le remplacement, le profil par défaut du navigateur automatisé de Cosmo a continué de servir l'ancienne copie du fichier (confirmations natives) malgré des onglets neufs et des rechargements forcés, alors que le serveur servait déjà la nouvelle version (vérifiée octet par octet par téléchargement direct). La sonde n'a réussi que dans un **profil de navigateur neuf** (cache vide). Si une ancienne version semble persister quelque part, penser au cache du navigateur avant de douter du déploiement.

## Suite : la sélection des chaînes — APPLIQUÉE le 15 novembre 2026

La sélection du profil « Tout le monde » (Direct) a été **appliquée et vérifiée** le 15 novembre 2026 vers 19 h 32 (heure de Montréal), juste après la mise en ligne : sonde d'abord (confirmation affichée dans la page : « Ces 915 catégories ne seront plus téléchargées par la télé, pour tout le monde. Continuer ? », annulée), puis les 66 catégories ci-dessous cochées (« 66 cochées sur 916 »), confirmation dans la page (« Ces 850 catégories ne seront plus téléchargées… »), et après rechargement : **« 66 affichées sur 916 · Tout le monde »**, avec par exemple CA| GENERAL FR et FR| SPORT HD/4K visibles et cochées. Liste appliquée (approuvée par le propriétaire, cas limites inclus) :

Canada (13) : CA| GENERAL FR, CA| CINEMA FR, CA| KIDS FR, CA| SPORTS FR, CA| MUSIC FR, CA| DOCUMENTARY FR, CA| NEWS EN, CA| GENERAL EN, CA| CINEMA EN, CA| SPORTS EN, CA| KIDS EN, CA| DOCUMENTARY EN, CA| ROGERS SUPER SPORTS PACK EN.
France (12) : FR| GÉNÉRAL HD/4K, FR| CINÉMA HD/4K, FR| ENFANTS HD/4K, FR| LIGUE1+ BE, FR| LIGUE1+ ◉, FR| L'EQUIPE LIVE, FR| SPORT HD/4K, FR| CANAL+ LIVE HD, FR| MUSIQUE HD/4K, FR| DOCUMENTAIRE HD/4K, FR| CARIBBEAN HD/4K, FR| CANAL+ AFRICA.
États-Unis (11) : US| AT&T RAW 60fps, US| ENTERTAINMENT HD/RAW 60fps, US| NEWS HD/RAW 60fps, US| MOVIES HD/RAW 60fps, US| SPORT HD/RAW 60fps, US| KIDS HD/RAW 60fps, US| NBC HD/RAW 60fps, US| FOX HD/RAW 60fps, US| ABC HD/RAW 60fps, US| CBS HD/RAW 60fps, US| CW HD/RAW 60fps.
Sport international (21) : UK| NOW TV SPORT HD/RAW 60fps, UK| TNT SPORT HD VIP, UK| SPORT HD, ES| VODAFONE SPORTS RAW, PT| DESPORTO, BR| ESPN SPORT HD, ASIA| SPORTS, NZ| SKY SPORTS HD, DE| SPORT HD/4K, NL| SPORT HD/4K, BE| SPORT HD/RAW, IT| SPORT HD/4K, CH| BLUE SPORT DIRECT RAW, PL| SPORTOWE HD/RAW, GR| NOVA/SPORTS VIP, AT| CANAL+ ONLINE SPORT, AL| SPORT, TR| BEIN SPORTS GOLD, KU| KURDISH SPORT, AR| BEIN SPORTS 8K & HD, AR| ARABIC SPORT 4K.
Cas limites (9) : BE| GENERAL HD/RAW, BE| MOVIES, BE| KIDS, BE| DOCUMENTARY, CH| SWITZERLAND HD/4K, AFR| AFRICA VIP HD/4K, AFR| MAURITIUS, AFR| CANAL+ VIP HD/4K, CR| HAITI.

Reste aussi à faire, plus tard et seulement si le propriétaire le demande : la même sélection pour le profil « Hamza », avec sa règle différente pour l'arabe (garder les chaînes du Maroc et toutes les chaînes sportives arabes ; le reste de l'arabe ne l'intéresse pas).

