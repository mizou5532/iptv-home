# Tâche 5 — Réservation B2 avec arrêt confirmé

État : implémentation terminée, relecture indépendante de conformité et qualité approuvée, aucun défaut retenu.
Branche `plan-3b2-direct`, base `1f5fa4e`. Migration 0010 **locale uniquement**.
Aucun sous-agent/reviewer lancé ; aucun service distant, migration de production ou fusion.

## Livré

- Tables privées slot/request (empreinte SHA-256 native), signal Realtime sans battements.
- Douze RPC authentifiés, verrou foyer, reprise exclusivement après ACK physique ou confirmation owner explicite ; priorité ready, expiration réelle, aucune libération à 45 secondes.
- Validation profil/source/masques à claim, demande, promote, started/update/control ; distinction des installations même compte.
- Marqueur stopping lors de révocation/suppression profil ; ACK exact reste possible après révocation ; anciens ACK sans effet.
- Activation idempotente owner et refus v1 pour ce foyer, comportement legacy conservé ailleurs.
- Module téléphone séparé `playback-admin.ts`, onglet Lecture, confirmations explicites, erreurs françaises fixes, démo isolée.
- Contrat complet dans `backend/README.md` ; signatures reproduites ci-dessous pour tâche 6.

## Preuves RED/GREEN

Commandes exécutées dans `/workspace/iptv-home` avec `source /workspace/iptv-env/activate.sh`.
`iptv_backend_test` monte ce dépôt dans PostgreSQL16 Docker éphémère ; aucune connexion Supabase.

| Commande / étape | Sortie observée |
|---|---|
| `iptv_backend_test test/lecture-confirmee.test.ts` initial | **2 failed**, fonctions playback_claim/playback_enable absentes, PostgreSQL initialisé |
| même commande après réservation/ACK | **2 passed** |
| même commande après nouveaux tests contrôle/admin/legacy | **4 failed / 2 passed**, fonctions absentes et v1 autorisé |
| même commande après implémentation + correction cascade foyer | **6 passed** |
| `npm test -w web/config -- test/playback-admin.test.ts` | module absent, puis stub : **4 failed**, actions/état absents |
| même commande après module réel + demo | **4 passed** |
| backend ciblé limites supplémentaires | **11 passed**, aucune correction nécessaire |
| première `iptv_backend_test` complète | **14 failed / 120 passed**, tests qui-regarde : `missing FROM-clause entry for table claim_stream` (renommage PL/pgSQL) |
| `iptv_backend_test test/qui-regarde.test.ts test/lecture-confirmee.test.ts` après préservation corps v1 | **29 passed** |
| nouveau test expiration transactionnelle | **1 failed / 11 passed**, promote acceptait après échéance avec now() figé |
| même commande après clock_timestamp() | **12 passed** |
| dernière `iptv_backend_test` complète, 23:08 UTC | **10 files / 135 passed**, 18,55 secondes |
| `npm test -w web/config` | **8 files / 116 passed** |
| `npm run build -w web/config` | **tsc + Vite PASS**, 63 modules |
| `git diff --check 1f5fa4e..HEAD` | aucun résultat, exit 0 |

Les tests couvrent concurrence, mêmes auth.uid / clés distinctes, demandes non accusées,
priorité entre ACK et promote, ACK ancien, started ancien, update tiers, masques deux portées,
profil/source étrangers, mauvaise clé, propriétaire étranger, demande expirée, réservation stale,
annulation, révocation hors ligne simulée sans ACK, suppression profil, ACK après révocation,
publication privée, grants helper/anon et activation répétée.

Parcours réel Chromium `/usr/bin/chromium`, Playwright global, Vite local : viewport iPhone
390×844, `?demo`, route réseau refuse tout hôte externe. Onglet Lecture → activation annulée
→ activation confirmée → récupération confirmée. Résultat :
`{"mobile":"PASS","overflow":false,"blocked":0,"errors":[]}`.
Vite reste disponible sur 5173 pour la relecture. Les boutons natifs sont utilisables au clavier ;
le parcours navigateur exécuté utilisait les clics, pas une assertion de navigation clavier.

## Autorevue et limites

- Corrigé : trigger de révocation ne doit pas recréer un signal lors de la suppression du foyer.
- Corrigé : renommage d’une fonction PL/pgSQL invalide ses références qualifiées ; corps v1 préservé.
- Corrigé par test RED : expiration utilise clock_timestamp() après verrou, pas l’heure de début de transaction.
- Droits vérifiés : slot privé, hash request non sélectionnable, publication colonnes publiques seulement,
  helpers non exécutables, mutations owner exigées côté SQL (pas seulement côté UI).
- La publication PostgreSQL16 et ses colonnes ont été vérifiées localement. Le transport Supabase
  Realtime hébergé n’a pas été exercé ; polling 2 secondes reste contractuellement obligatoire.
- L’arrêt matériel réel sera testé par les clients/télé aux tâches suivantes. La déclaration owner
  constitue une preuve humaine explicite, et ne peut être vérifiée automatiquement par SQL.
- Les requêtes expirées sont conservées pour ce protocole ; pas de purge automatique ajoutée.
- La démo présente un scénario fictif de récupération après activation afin de rendre le bouton testable.
- PASSATION/feuille de route et relecture indépendante restent au contrôleur comme demandé.

## Commits et sauvegardes

Chaque commit ci-dessous a été précédé du commit du travail local, suivi de
`git pull --rebase origin plan-3b2-direct` puis `git push origin HEAD:plan-3b2-direct`.
Aucun stash ni écrasement ; branche distante à jour à chaque checkpoint.

- `2d73925` tests contrat WIP, avant longue implémentation.
- `c8e9c6c` réservation/ACK/promotion initiale (2 verts).
- `5073d90` contrôle/récupération/révocation/legacy (6 verts).
- `55b34c3` module téléphone et démo (4 web verts).
- `5b1435e` limites SQL (11 verts), avant suites longues.
- `4c7ca4d` correction legacy (29 ciblés verts).
- `0f809b4` contrat README exact.
- `ed150ad` expiration après attente (12 verts), avant suite finale.
- Le commit contenant ce rapport et le journal final complète la sauvegarde (SHA communiqué au contrôleur après push).

## Interfaces exactes RPC / JSON

| RPC | Retour exact |
|---|---|
| `playback_claim(h uuid,p uuid,device_key text,device_name text,source_id text,label text)` | `{status:"ok",generation:uuid}` ; `{status:"setup_required"}` ; ou `{status:"occupied",generation:uuid,occupant:{device_name:text\|null,profile_id:uuid\|null,label:text\|null},stale:boolean}` |
| `playback_control(h uuid,generation uuid,device_key text,beat boolean default false)` | `{status:"keep"}` ou `{status:"stop"}` ou `{status:"stop",request_id:uuid}` |
| `playback_request_stop(h uuid,generation uuid,p uuid,device_key text,device_name text,source_id text,label text)` | UUID de la demande |
| `playback_poll_request(request_id uuid,device_key text)` | `{status:"waiting"\|"ready"\|"cancelled"}` |
| `playback_cancel_request(request_id uuid,device_key text)` | void |
| `playback_stopped(h uuid,generation uuid,device_key text,request_id uuid default null)` | void |
| `playback_promote(request_id uuid,device_key text)` | `{status:"ok",generation:uuid}` (nouvelle génération) |
| `playback_started(h uuid,generation uuid,device_key text)` | void |
| `playback_update(h uuid,generation uuid,device_key text,source_id text,label text)` | void |
| `playback_enable(h uuid)` | void, propriétaire seulement |
| `playback_confirm_physical_stop(request_id uuid)` | void, propriétaire seulement |
| `playback_admin_state(h uuid)` | `{enabled:boolean,slot:null\|{generation:uuid,state:"empty"\|"reserved"\|"playing"\|"stopping",device_name:text\|null,label:text\|null,stale:boolean},requests:[{id:uuid,generation:uuid,device_name:text,label:text,expires_at:ISO8601,state:"waiting"}]}` ; propriétaire seulement |

`generation` dans la réponse `occupied` sert à cibler `playback_request_stop`. `occupant`
ne fournit pas de nom de profil : utiliser `profile_id` avec les profils du foyer déjà lus.
Une place vide peut garder les anciens champs descriptifs ; le client affiche l’occupant
uniquement lorsque l’état est différent de `empty`. `requests` de l’admin contient seulement
les demandes waiting non expirées de la génération courante, triées par expiration.
`poll_request` présente une demande expirée ou d’ancienne génération comme `cancelled`
sans supprimer la ligne. Une promotion consommée passe également la demande à `cancelled`.


Toutes les valeurs `void` utilisent la réponse habituelle PostgREST des fonctions void.
Toutes les fonctions sont exécutables par `authenticated` uniquement. Les fonctions enable,
confirm_physical_stop et admin_state exigent en plus le propriétaire. Les autres requièrent
un membre du foyer ; stopped autorise uniquement l’accusé de l’installation titulaire exacte
après révocation. Chaque opération titulaire/demandeur vérifie auth.uid ET l’empreinte de clé.

Séquence client : claim ok → ouvrir → started après première image ; control toutes les 2s ;
stop/refus → fermer/libérer physiquement → stopped ; demandeur poll ready → promote ok → ouvrir.
Pas d’ouverture avant promote, pas d’expiration de place sans preuve. occupied inclut generation
pour request_stop. update garde la génération et exige fermeture du flux précédent avant remplacement.
