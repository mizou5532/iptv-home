# Sport, grille complète : bannière et pastilles « face-à-face » (19 nov. 2026)

## Demande du propriétaire

La bannière de la grille complète (disposition 2 du Sport) affichait trop d'informations, dont la liste
« Où regarder » à droite, dont le propriétaire n'a pas besoin. Il voulait quelque chose de beau et sportif :
grands logos, VS, couleurs des équipes, peu de texte, un bouton « Regarder » et un bouton pour une autre source.
Cinq maquettes proposées (page « Maquettes Sport ») ; **choix : 1 · Face-à-face**.

## Ce qui change (`apps/tv/src/main/kotlin/home/iptv/tv/sport/SportGrille.kt`)

- **Bannière** (216 dp de haut) : fond coupé en diagonale, couleur des visiteurs à gauche, des locaux à droite,
  trait blanc au milieu ; grand logo (96 dp) et nom en capitales de chaque côté, classement en petit dessous.
  Au centre, sur une plaque sombre : « VS » (ou le score), l'heure (ou l'avancement du direct, ou « Terminé »),
  le compte à rebours et la compétition, puis **« ▶ Regarder »** et **« Autres sources »**.
- **Supprimé** : la colonne « Où regarder », le nom de la chaîne dans le bouton, la ligne d'aide
  « OK : regarder · maintenir OK… ». « Autres sources » (ou maintenir OK) ouvre le même choix des chaînes qu'avant.
- **Pastilles** (84 dp) : mini face-à-face, coupées en diagonale aux couleurs des deux équipes, un logo de chaque
  côté (halo sombre derrière pour rester lisible sur sa propre couleur), au centre l'heure (et le jour au-dessus si
  ce n'est pas aujourd'hui), ou le score et l'avancement en direct, ou « ● DIRECT » en mode sans résultat.
- Couleurs trop claires (blanc, argent, or) assombries automatiquement ; thèmes « sombre » et « profil » respectés.
- Le nom des équipes reste lu par l'accessibilité sur chaque pastille (sert aussi aux tests).

## Télécommande et défilement (inchangés, vérifiés)

Mêmes règles qu'avant : Bas depuis les boutons → filtres → grille ; le focus sur une pastille met ce match en haut ;
OK = regarder ; maintenir OK = choisir la chaîne ; Droite/Gauche bloquées aux bords ; Retour du choix des
chaînes → « Regarder ».

## Tests (`SportGrilleDpadTest`, 5 tests)

- existants adaptés : la bannière n'a plus « OÙ REGARDER » ; le bouton est « ▶  Regarder » ;
- nouveau : « Autres sources » ouvre le choix des chaînes sans rien lancer, Droite ne quitte pas la bannière,
  Retour revient sur « Regarder » ;
- nouveau : soirée de 16 matchs, on descend jusqu'en bas (chaque pastille focalisée entièrement à l'écran, le focus
  s'arrête sur la dernière rangée sans se perdre), puis Haut ramène à « Regarder ».

Résultat : **608 tests télé + 399 partagés verts** (dont `SportNouveautesDpadTest` adapté : il attendait « OÙ REGARDER ») ; APK de sortie construit et signé (certificat `5d8877ce…e615d4` vérifié), version inchangée (0.41.2, code 70).

Captures dans `docs/rapports/captures/sport-face-a-face/`. Pour les refaire : `./gradlew :tv:testDebugUnitTest --tests '*SportGrille*' -Psport.shots=<dossier>`.

## Reste à faire

- Essai sur la TCL avec les vrais logos ESPN (les tests n'ont que les ronds de secours).
- Publication : version à monter et APK à envoyer **avec l'accord du propriétaire**.
