# SDD ledger — plan: docs/superpowers/plans/2026-11-20-serveur-maison-etape1-direct.md

Spec : docs/superpowers/specs/2026-11-20-serveur-maison-catalogue-design.md
Branche : serveur-maison-catalogue (dépôt /Users/mizzler/Projets/iptv-home). Base au départ : aa0b01c.

## Environnement
Le Mac n'a ni Java, ni Node, ni SDK Android, ni Postgres. Toutes les commandes de construction et de tests passent par
`~/serveur-maison/iptv-dev/tester.sh '<commande>'` : copie le dépôt (rsync, sans .git/build) sur le serveur et lance
la commande dans le conteneur `iptv-dev` (JDK 21, Node 22, Postgres 16 dans /usr/lib/postgresql/16/bin, SDK Android
36 dans /opt/android-sdk, `apps/local.properties` déjà présent côté serveur). Exemple :
`~/serveur-maison/iptv-dev/tester.sh 'cd apps && ./gradlew --max-workers=2 :shared:jvmTest'`.
Les éditions et les commits se font sur le Mac.

## Pré-vol (conflits)

| Tâches | Produit → consommé | Constat |
|---|---|---|
| 1 → 11 | `groupSourceIndex(..., precomputed)` ; T5 `liveKeys(): Map<String, PrecomputedKey>` → `precomputed = repo.liveKeys()::get` | Cohérent |
| 2 → 8, 11 | `LiveRefresher` (shared/catalog) | Cohérent |
| 3 → 4 | RPC `register_home_server`, `issue_home_server_token`, `check_home_server_token`, table `home_server` | Cohérent |
| 4 → 5, 8, 10, 11 | `LiveSnapshot`, `SnapStream`, `liveSnapshotRev`, `HOME_PROTOCOL`, appels `SyncClient` | `account` = « empreinte comme `IptvApp.accountFingerprint()` (déplacée si besoin) » : ambigu |
| 5 → 11 | `replaceLive(snapshot): RefreshReport` | Cohérent |
| 6 → 7, 8 | `Config`, `Pairing.awaitHousehold`, `FileSessionManager` | Cohérent |
| 7 → 10 | format d'empreinte `sha256/<base64 SPKI>` | Cohérent (même format OkHttp) |
| 8 → 11 | route `/v1/live?since=` (304/503/200 gzip) ; publication iPhone faite par le serveur | Cohérent |
| 9 → 11 | `EpgSource` déplacée dans shared ; route `/v1/epg/{id}` → `EpgReply` | Cohérent |
| 10 → 11 | `HomeServerClient.liveSnapshot/epg`, `LiveSnapshotResult` | Cohérent |
| Chaque tâche | texte interne | T12 : gestes du propriétaire (production, box, publication) |
| Global | « écoute sur toutes les interfaces » vs spec « pas ouvert sur Internet » | Compatible : serveur derrière la box, pare-feu en T12 |

Rulings :
- Ruling: `accountFingerprint` est déplacée dans `apps/shared` à la tâche 4 (fonction pure, même résultat que la télé), et T8/T11 l'utilisent — un seul calcul partagé évite deux empreintes différentes — si faux : la télé ignorerait toutes les listes du serveur (détecté par le test `snapshotFromOtherAccountIgnored` et l'essai TCL).
- Ruling: tâches 1 à 11 exécutées ici ; la tâche 12 n'est pas lancée sans le propriétaire (migration de production, publication, gestes sur la box et la télé) — règles absolues de PASSATION — coût si faux : aucun, elle attend.
- Ruling: Tailscale/box : la réservation DHCP de la tâche 12 étape 2 est déjà réglée autrement (le serveur a une adresse fixe 192.168.2.81 et la box distribue à partir de .100) — fait le 2026-11-20 — coût si faux : aucun.

## Avancement

## Base de référence (atelier, avant tâche 1, HEAD aa0b01c)
- backend : 266/266 (≈10 min). shared:jvmTest : vert. tv:testDebugUnitTest : 589/589.
- Ruling: les 3 tests en dessin natif Robolectric (SportGrilleDpadTest, SportNouveautesDpadTest, SeasonScreenTest) sont écartés dans l'atelier (init script ~/.gradle/init.d/atelier-tests.gradle du conteneur, hors dépôt) — ils font planter la JVM ici (JniConstants: Class not found java/nio/ShortBuffer), passent ailleurs, et l'étape 1 ne touche ni au Sport ni aux saisons — coût si faux : une régression sur ces 3 écrans passerait inaperçue jusqu'à l'essai TCL.

- Tâche 1 : lancée (base aa0b01c), implémenteur sonnet.
Task 1: complete (commits aa0b01c..0076bdb, review clean ; mineurs notés : cas autoKey précalculé nul non testé, chemin enabled=false non testé dans shared)
- Tâche 2 : lancée (base 0076bdb).
Task 2: complete (commits 0076bdb..cf19416, review clean)
- Tâche 3 : lancée (base cf19416).
Task 3: minor (deferred): longueurs url/pin coupées au lieu d'être refusées ; version nulle stockée '' ; tests de refus anon sans message ; pas de test inter-foyers ni issue par anon.
Task 3: fix round 1/5 lancé — Important : retirer le serveur (revoke_device) ne le désinscrit pas.
Task 3: fix round 1/5 (1 addressed, 0 open; commits 632abd3..73c2a34)
Task 3: minor (deferred): jeton d'une télé retirée non effacé (redevient valide si le même utilisateur revient).
Task 3: complete (commits cf19416..73c2a34, review clean après 1 correction)
- Tâche 4 : lancée (base 73c2a34).
Task 4: minor (deferred): vecteurs SHA-256 55/63/64 octets non épinglés ; rev dépend de l'ordre des champs et des listes (l'appelant doit trier) ; HomeServerInfo.toString montre le pin (public).
Task 4: complete (commits 73c2a34..35e3928, review clean)
- Tâche 5 : lancée (base 35e3928).
Ruling: 5.sqm migre du schéma 5 au 6 (le schéma était déjà en 5), comme le plan l'entendait — nom de fichier imposé par le plan, version suivante — coût si faux : aucun.
Task 5: minor (deferred): liste reçue avec catégories mais zéro chaîne efface toutes les chaînes (même faiblesse que refreshLive) ; noms complets en ligne au lieu d'imports.
Task 5: complete (commits 35e3928..8f572b8, review clean)
- Tâche 6 : lancée (base 8f572b8).
Task 6: minor (deferred): pairingRoute en HTTP clair (OK sur le réseau local seulement) ; LAN_URL lu mais pas encore utilisé ; dépendances ktor déclarées pour les tâches suivantes.
Ruling: le code d'appairage expiré est renouvelé et onCode appelé une fois par code — un code mort sur la page bloquerait le propriétaire — coût si faux : aucun.
Ruling: session.json illisible → copie .corrupt et arrêt bruyant (pas de nouvel utilisateur anonyme silencieux) — sinon le serveur perdrait son appairage sans prévenir — coût si faux : redémarrage manuel.
Task 6: fix round 1/5 lancé (3 importants : code expiré, erreur passagère, session corrompue).
Task 6: fix round 1/5 (3 addressed, 0 open; commits 9f59fc7..f476ea6)
Task 6: minor (deferred, à trier en revue finale) : fichier .tmp resté après un plantage → saveSession échoue → session non gardée → nouvel utilisateur anonyme au redémarrage ; erreur de lecture passagère traitée comme corruption ; échec de la copie .corrupt ignoré ; exitProcess(1) + restart unless-stopped → boucle de redémarrage (à documenter en T12).
Task 6: complete (commits 8f572b8..f476ea6, review clean après 1 correction)
- Tâche 7 : lancée (base f476ea6).
Task 7: minor (deferred): pas de single-flight (N requêtes même jeton = N appels) ; id du foyer écrit dans stdout ; « / » révèle « déjà appairé » ; limite par IP à prévoir plus tard ; le client (T10) doit ignorer le nom d'hôte et ne se fier qu'au pin.
Task 7: fix round 1/5 lancé (2 importants : éviction du cache par faux jetons, refus qui atteindraient le gestionnaire).
Task 7: fix round 1/5 (2 addressed, 0 open; commits 2b469f5..33cc8ed ; tests 20/20 selon l'implémenteur)
Task 7: minor (deferred): test refusedCallsNeverReachHandler sans témoin positif, compteur non @Volatile ; maxEntries=0 ferait planter (inaccessible).
Task 7: complete (commits f476ea6..33cc8ed, review clean après 1 correction)
- Tâche 8 : lancée (base 33cc8ed).
Ruling: tâche 8 — changement de compte IPTV : vider et servir 503 plutôt que l'ancien compte — la spec interdit de mélanger deux catalogues — coût si faux : Direct indisponible depuis le serveur jusqu'à la mise à jour suivante (la télé garde sa copie).
Task 8: minor (deferred): base SQLite plus récente que le schéma réécrite sans refus ; planification sur l'horloge murale ; droits du fichier de liste par défaut (aucun secret dedans).
Task 8: fix round 1/5 lancé (3 importants : nouvel essai 5 min après échec, révision des réglages consommée trop tôt, changement de compte vu dans le sondage de 30 s).
Task 8: fix round 1/5 (3 addressed, 1 new open — un échec déclenché par le masquage relance toutes les 30 s au lieu de 5 min ; commits bb8467d..9ece4a4). Branchement account() vérifié par le contrôleur (Main.kt:97, même fonction).
Task 8: fix round 2/5 lancé.
Task 8: fix round 2/5 (1 addressed, 0 open; commits 9ece4a4..614d4bf)
Task 8: minor (deferred): failedAccountRefreshWaitsFiveMinutes devrait vérifier que la liste porte le nouveau compte.
Task 8: complete (commits 33cc8ed..614d4bf, review clean après 2 corrections)
- Tâche 9 : lancée (base 614d4bf).
Task 9: minor (deferred): pas de single-flight pour le guide ; full=1 ne filtre pas end<=start et perd le repli « nowPlaying » des panneaux à heures décalées ; tests full=1 et « erreur puis succès » manquants.
Task 9: complete (commits 614d4bf..37ced0c, review clean)
- Tâche 10 : lancée (base 37ced0c).
Task 10: minor (deferred): client/pin en deux champs volatiles (M1) ; fenêtre de 60 s non atomique (M3) ; PerfData classe le serveur maison en « fournisseur/autre » (M4, à traiter en T11) ; 503 au démarrage → Absent 60 s (M5, accepté).
Ruling: jeton serveur stocké en clair dans les SharedPreferences privées, comme les identifiants IPTV (allowBackup=false) — cohérent avec l'existant, la spec disait « comme les identifiants » — coût si faux : une appli root pourrait le lire ; il ne donne accès qu'à la liste des chaînes.
Ruling: corps de la liste du Direct autorisé jusqu'à 20 s (en-têtes et appels Supabase dans 1,5 s) — téléchargement en arrière-plan de quelques centaines de Ko sur un Wi-Fi lent — coût si faux : rafraîchissement lent, jamais l'écran.
Task 10: fix round 1/5 lancé (I1 délais non bornés, I2 erreur de guide coupe tout le serveur).
Task 10: fix round 1/5 (2 addressed, 0 open; commits 1b3ec45..389eeb1)
Task 10: minor (deferred): test de délai à l'horloge réelle (risque d'instabilité) ; chemin NonCancellable du jeton peut dépasser 1,5 s (dépend des délais du client Supabase) ; body.string() non annulable ; serverVersion partagé ; epg 4xx/502 n'appelle pas succeeded().
Task 10: complete (commits 37ced0c..389eeb1, review clean après 1 correction)
- Tâche 11 : lancée (base 389eeb1).
Task 11: ARRÊTÉE à la demande du propriétaire (quota). Début non testé sauvegardé dans docs/rapports/serveur-maison/tache-11-debut-non-teste.patch ; dépôt remis à 389eeb1 pour apps/tv. À reprendre depuis la fiche task-11-brief.md.
