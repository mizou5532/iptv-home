# Étape 5 — Relecture indépendante de la tâche 1

16 novembre 2026. Périmètre : moteur partagé, surnoms et tests de la tâche 1, comparaison avec le brief et la conception validée §6.1. Aucune modification du code ni relance des suites. Les résultats annoncés (16 tests ciblés, 280 partagés, maximum mesuré 40,846 ms) sont des résultats rapportés, pas une nouvelle exécution de cette relecture.

## Verdict

Qualité générale satisfaisante, mais conformité incomplète : la distance OSA ne respecte pas toutes les combinaisons de deux fautes promises à partir de huit lettres, contrairement au Damerau général demandé par le plan. Aucun défaut Critical ; un défaut Important à corriger ou à faire explicitement accepter par le propriétaire avant de déclarer la tâche conforme. La mention de cette restriction dans le rapport ne constitue pas une validation du changement de contrat.

## Constats

- **Critical : aucun.**
- **Minor : aucun défaut supplémentaire démontré.**
- **Important — `apps/shared/src/commonMain/kotlin/home/iptv/shared/search/SearchEngine.kt:95` : certaines combinaisons de deux fautes sont rejetées.** La récurrence implémente OSA : elle ne permet pas de rééditer une portion déjà transposée. Exemple précis : titre `abcdefabc sport`, requête `abcdefca sport`. Le premier mot tapé a huit lettres ; `abcdefca → abcdefac → abcdefabc` demande une transposition puis une insertion, donc deux opérations de Damerau, mais OSA donne 3. Le mot n'étant pas le dernier, la comparaison sur préfixe ne masque pas ce rejet. Une vérification Python ponctuelle de la récurrence OSA confirme 3 ; aucune suite Gradle n'a été relancée. Le rapport d'implémentation indique honnêtement cette restriction. Impact limité aux fautes qui se chevauchent, pas aux deux substitutions déjà couvertes ni aux transpositions ordinaires ; néanmoins des résultats respectant le budget explicite de deux fautes deviennent introuvables, d'où la gravité Important au regard du contrat. Ajouter un test de ce cas si le Damerau général reste le contrat, puis adopter la distance générale bornée ou faire valider explicitement la restriction OSA.

## Points solides et conformité

- Normalisation commune accents/casse, séparateurs attendus, recherche sans ordre des mots et tous les mots obligatoires.
- Index inversé réel : mots distincts vers indices des éléments, regroupement par première lettre et longueur. Les groupes de première lettre sont tous examinés : une faute initiale n'est pas exclue. Aucun calcul de distance par titre.
- Seuils correctement codés : 0 avant quatre lettres, 1 de quatre à sept, 2 à partir de huit ; distance sur préfixe uniquement pour le dernier mot. Le filtrage de longueur reste compatible avec ces règles.
- Les surnoms étendent les variantes par union, après normalisation symétrique des clés et valeurs ; les mots de la requête restent intersectés.
- Classement conforme à la conception validée : titre égal, puis tous les mots en début de mot, puis contenu, puis faute ; le pire type de correspondance gouverne les requêtes multiples. Un mot exact situé plus loin dans le titre relève bien du niveau « début de mot » : la spécification ne privilégie pas le début du titre. Départage déterministe par longueur, texte normalisé, texte original et identifiant.
- Déduplication par identifiant avec réunion des catégories, limites nulles/négatives rejetées et plafond de 60 même si l'appelant demande davantage. Aucun filtrage de masquage implicitement supposé dans ce moteur : les sources des tâches suivantes doivent fournir les éléments autorisés.
- Tests substantiels : les exemples requis, la faute initiale, les surnoms accentués, les catégories réunies et la distinction dernier mot/autres mots sont exercés. `montrael` a bien huit lettres ; le rejet à sept lettres de deux substitutions et l'acceptation à huit lettres de deux substitutions sont explicitement vérifiés.
- Le test de performance construit effectivement 50 000 titres, un vocabulaire enrichi déterministe, vingt requêtes variées et trois échauffements, avec construction hors mesure. Il établit la cible sur ce corpus JVM ; il ne prétend pas mesurer la TCL. Le parcours reste proportionnel au vocabulaire distinct et le tri aux candidats : des catalogues avec beaucoup plus de mots uniques exigeraient une mesure complémentaire, sans constituer ici un défaut démontré.

## Couverture à améliorer sans blocage

`apps/shared/src/jvmTest/kotlin/home/iptv/shared/search/SearchEngineTest.kt:30` : le classement ne distingue pas explicitement les niveaux contenu et faute, et ne démontre pas à lui seul le départage alphabétique. La lecture du code confirme leur mise en œuvre ; quelques assertions avec un contenu court face à un préfixe long, puis une faute courte face à un contenu long, protégeraient mieux la règle contre une régression.

## Relecture de la correction 1 — dc7f948

**Important précédent : résolu. Verdict actuel : conforme au périmètre de la tâche 1 ; aucun Critical, Important ou Minor fonctionnel restant identifié.** Relecture ciblée du diff de correction et des preuves ajoutées au rapport d'implémentation ; aucune suite relancée, aucune modification de code.

La fonction `withinDistance` applique désormais la récurrence générale Lowrance-Wagner : matrice initialisée avec sentinelles, dernière ligne de chaque caractère mise à jour après la ligne complète et dernière colonne correspondante enregistrée avant son éventuelle actualisation. Le terme de transposition utilise bien ces deux positions antérieures et les coûts d'insertion/suppression intermédiaires. Il autorise donc les éditions qui se chevauchent, auparavant rejetées par OSA.

La comparaison de mot entier prend la cellule finale. La comparaison du dernier mot prend le minimum sur les préfixes de longueur comprise entre longueur de requête moins budget et longueur de requête plus budget, tronquée à la longueur disponible. Cette restriction est correcte : la différence de longueur est une borne inférieure du nombre d'éditions, même avec transpositions. Il n'y a plus de bande de calcul ni d'arrêt anticipé risquant d'éliminer une transposition générale. Les filtres de longueur et les variantes de surnoms du moteur restent inchangés et compatibles avec ce calcul.

Les deux régressions ajoutées couvrent le défaut sur mot entier et préfixe. Le cas préfixe `abcdefcaxy` vers `abcdefabcxyz` conserve le suffixe nécessaire pour empêcher qu'un préfixe plus court passe par une simple suppression. Le test supplémentaire de classement exerce désormais les quatre niveaux exact, préfixe, contenu et faute ; l'amélioration suggérée sur ces niveaux est donc également traitée.

Preuves rapportées après correction : RED 19 tests dont les deux régressions échouent, GREEN ciblé 19/19, suite partagée 283/283 ; vingt requêtes du corpus 50 000 titres sous 100 ms. Maximum annoncé 89,659 ms : cible respectée sur ce corpus JVM, avec une marge réduite. La matrice complète augmente le coût par paire de mots par rapport à OSA ; cela ne constitue pas un échec mesuré du contrat et ne remet pas en cause l'index inversé par mots distincts. Aucune mesure TCL revendiquée. Aucun doute concret supplémentaire ne justifie une nouvelle exécution des suites dans cette relecture.
