# Étape 5 — tâche 3 : remplissage du catalogue

16 novembre 2026. Périmètre : shared seulement, sans branchement au cycle de vie TV, sans publication ni migration.

## Résultat et interfaces

`CatalogFiller` fournit les interfaces du plan : `SearchSection`, `FillProgress`, `progress`, `changed`, `start`, `stop`, `screenFirst`. Une seule instance devra être partagée par le processus à la tâche 5. `start` met à jour les callbacks et ne crée pas une seconde boucle si elle tourne déjà. `stop` annule aussi la demande de fond en vol ; les demandes d'écran sont indépendantes et gardent le même verrou.

Les callbacks de masquage doivent lire les préférences du profil **actuellement actif**, union des masquages personnels et de Tout le monde. `null` interdit toute lecture du dépôt susceptible d'interroger le fournisseur dans cette section, y compris sa liste de catégories. Le remplisseur relit les callbacks après l'attente du verrou, juste avant chaque opération. Les listes de titres utilisent les méthodes existantes des dépôts et leurs protections de masquage.

Les métadonnées de catégories sont globales dans l'API fournisseur : le remplisseur garde tous leurs identifiants via `categories(emptySet())`, puis filtre selon le profil courant pour le choix des demandes et le calcul des totaux. Cela permet d'afficher le bon total si un nouveau profil autorise une catégorie précédemment masquée, même pendant une demande lente. Cette conservation d'identifiants n'autorise aucune demande de titres masqués. L'avancement est recalculé toutes les 500 ms, y compris pendant une demande suspendue ou après la fin du remplissage.

Les seules additions aux dépôts sont deux lectures locales publiques, sans réseau ni changement SQL : `areCategoriesFresh()` et `isCategoryFresh(categoryId)`. Elles emploient les horloges et TTL existants. Une réponse vide valide est fraîche ; un repli silencieux vers un cache périmé reste périmé et ne produit pas de signal de réussite. Une catégorie ou une liste de catégories en échec n'est pas redemandée dans la même session ; `stop` puis `start` ouvre une nouvelle session de tentatives.

`screenFirst` sérialise les opérations fournisseur avec le remplissage. Les écrans en attente passent avant sa demande suivante ; celle déjà en vol peut terminer. La pause de fond de 500 ms reste hors du verrou et ne bloque pas l'écran. `changed` est une invalidation sans rejeu, émise après un rafraîchissement réellement frais et hors du verrou des demandes ; la tâche 4 doit toujours relire les sources filtrées.

## Preuves exécutées

Environnement : `/workspace/iptv-home`, `source /workspace/iptv-env/activate.sh`, répertoire `apps`, un seul Gradle à la fois. Commande ciblée :

```sh
./gradlew --max-workers=2 :shared:jvmTest --tests '*CatalogFillerTest*'
```

- Premier lancement : compilation rouge, API absente. Avec les signatures minimales sans comportement : 10 tests, 8 échecs attendus ; implémentation initiale : 10/10 verts.
- Cas supplémentaires de relecture : 13 tests, 1 échec sur le repli périmé de la liste des catégories. Le premier correctif supprimait les répétitions mais permettait encore une demande de titres : 13 tests, 1 échec conservé.
- Ajout du profil changé pendant une demande : 14 tests, 2 échecs ; correction du recalcul indépendant et de l'arrêt sur liste périmée : 14/14 verts.
- Ajout d'une catégorie nouvellement autorisée pendant la demande : 14 tests, 1 échec ; conservation des identifiants globaux : **14/14 verts** sur le code final.

Les neuf cas nommés dans le brief sont présents. Cinq autres couvrent : cache de titres périmé servi après panne ; liste de catégories périmée servie après panne ; écran en attente derrière une demande déjà en vol, suivi d'un masquage inconnu ; annulation en vol puis reprise ; changement de profil pendant une demande lente avec une catégorie nouvellement autorisée.

```sh
./gradlew --max-workers=2 :shared:jvmTest
```

Suite complète exécutée une fois après la première version à 14 tests, puis une fois sur le code final après le correctif des identifiants : **304 tests, 0 échec, 0 erreur, 0 ignoré, 24 suites**, dernier lancement `BUILD SUCCESSFUL` en 7 s. Comptes lus dans les XML SQLDelight/Kotlin du répertoire `shared/build/test-results/jvmTest`. Avertissement préexistant de dépréciation dans `SyncClient.kt`, aucun nouvel avertissement attribué à cette tâche.

`git diff --check` passe. Les journaux détaillés locaux sont dans `/workspace/iptv-work/5/task-3-*.log` ; ils ne sont pas nécessaires à la reprise depuis Git.

## Auto-relecture et limites

Contrôlés : filtrage avant la demande après attente d'un écran, absence d'appel si préférences inconnues, priorité initiale Films, sérialisation y compris écran et annulation, fraîcheur après réponse et repli, échecs sans boucle, reprise sans redemander le cache frais, totaux du nouveau profil pendant une requête en vol. Aucun défaut bloquant relevé à l'auto-relecture ; relecture indépendante à orchestrer par le contrôleur.

Les tests emploient les vrais dépôts et une vraie base SQLite en mémoire avec des sources factices comptées, suspendables et capables d'échouer. Ils ne prouvent pas le comportement d'un fournisseur réel ni le cycle `onStop` TV. L'unicité par processus, les wrappers `screenFirst` des écrans et le cycle de vie seront intégrés à la tâche 5. Les préférences sont interrogées par callbacks : leur mise à jour se reflète au plus tard au prochain sondage de 500 ms (et immédiatement avant chaque demande), sans notifier explicitement le remplisseur. Aucun test TV/backend/web exécuté par cette tâche ; le contrôleur gère la validation transversale.

## Correction de relecture I1 — expiration continue

La relecture indépendante a relevé que `attempted` conservait les catégories réussies : après expiration des 24 h de TTL, une session restée active ne pouvait plus les rafraîchir. Le correctif retire une catégorie de ce jeu seulement si la lecture du dépôt est effectivement fraîche après la demande. Les erreurs et replis périmés restent donc bloqués jusqu'au prochain `stop`/`start`, sans répétition en boucle.

Test ajouté : `expiration_apres_succes_rafraichit_sans_redemarrer`. Il remplit les deux sections, avance l'horloge des vrais dépôts de 25 h sans arrêter le remplisseur, vérifie deux demandes de chaque liste de catégories et de chaque catégorie de titres, l'avancement de nouveau complet et une concurrence maximale de 1.

Preuves exécutées avec l'environnement et les commandes ci-dessus :

- Ciblé rouge : **15 tests, 1 échec**, `s:1 expected:<2> but was:<1>` (`task-3-I1-red.log`).
- Ciblé après correctif : **15/15 verts**, `BUILD SUCCESSFUL` en 5 s (`task-3-I1-green.log`). Les cas de panne et de repli périmé précédents restent verts.
- Une suite shared complète sur le code final : **305 tests, 0 échec, 0 erreur, 0 ignoré, 24 suites**, `BUILD SUCCESSFUL` en 7 s (`task-3-I1-shared.log`). Aucun échec de performance rencontré.

Auto-relecture ciblée : aucune modification des masquages, du verrou, de la pause ou des interfaces publiques. La suppression du blocage intervient après vérification de fraîcheur seulement ; le worker reste unique et séquentiel. `git diff --check` passe. Aucun push ni test TV/backend/web exécuté pour ce correctif.
