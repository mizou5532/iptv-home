# Journal B2 — Le direct complet

## 14 novembre 2026 — Conception proposée

- Sources lues : passation entière, feuille de route, conception générale et conception B1 ; inspection du lecteur, du parcours direct et du client Xtream existants.
- Le propriétaire confirme l'aperçu uniquement sur OK. Il précise que les copies sont dans une même catégorie et dans plusieurs catégories.
- Deux approches présentées : automatique avec corrections sur l'iPhone, ou entièrement manuel. Le propriétaire retient la première, avec regroupement prudent.
- Proposition complète : `docs/superpowers/specs/2026-11-14-b2-direct-complet-design.md`. Les autres précisions restent proposées, pas encore approuvées.
- Proposition importante à faire valider : reprise de lecture sur un autre appareil seulement après confirmation d'arrêt ; nouvelle lecture bloquée si la disponibilité ne peut pas être vérifiée, lecture déjà autorisée conservée pendant une panne de synchro.
- Relecture indépendante de conception : deux points relevés, récupération après arrêt brutal et invisibilité de la 0.2.0. Réponses proposées dans la conception : récupération explicite après arrêt physique vérifié par le propriétaire ; mise à jour de toutes les installations et confirmation avant la mise en service du partage de place. Ces choix restent soumis à validation avec la conception.
- Branche `plan-3b2-direct` créée depuis le checkout fourni, au commit `c7aa465` (fusion B1). Aucun code produit, aucune migration appliquée, aucune publication et aucune fusion dans main.
- Suite : validation de la conception, plan détaillé, construction et tests ; accords distincts avant toute action de production ou fusion.

## 14 novembre 2026 — Conception validée, plan préparé

- Le propriétaire valide explicitement la conception complète : « Oui, je valide. »
- Plan enregistré dans `docs/superpowers/plans/2026-11-14-plan-3b2-direct.md` : neuf tâches indépendamment testables, avec interfaces, chemins, tests, relectures et livraison sous accords distincts.
- Choix de réalisation : moteurs Kotlin/TypeScript avec fixtures communes, groupes corrigés persistants sans conversion des favoris, protocole de lecture B2 dédié pour éviter le nettoyage automatique dangereux des réservations anciennes, coordinateur unique et guide borné.
- Autorelecture de couverture et cohérence effectuée : réservation entre accusé et promotion, premières images et réponses périmées, révocation hors ligne, pagination >1 000 et conflits de corrections explicitement testés dans le plan.
- Méthode conservée de la passation : constructeur puis relecteur indépendant par tâche, relecture finale de branche. Pas de nouveau choix de méthode demandé au propriétaire.
- Aucun code écrit, aucun test B2 exécuté, aucune migration appliquée, aucune publication ni fusion. Les limites d'environnement connues sont indiquées dans la tâche de validation ; ne pas les confondre avec des défauts de B2.
- Suite : relecture du plan par le propriétaire avant construction, conformément à la compétence writing-plans ; maintenir les accords distincts pour production et fusion.

## 14 novembre 2026 — Construction autorisée

- Le propriétaire autorise explicitement la construction et insiste sur les documents de reprise à jour à chaque étape.
- Méthode activée : sous-agent frais par tâche, relecture indépendante avant de poursuivre ; suivi technique local dans `.superpowers/sdd/b2-direct/progress.md` (ignoré), étapes et résultats durables dans les documents versionnés.
- Démarrage tâche 1 : moteur déterministe et fixtures communes ; préparation des prérequis de test en parallèle par le contrôleur. Aucun accord de production ou de fusion donné.

## 14 novembre 2026 — Tâche 1, première implémentation et relecture

- Commit `a1db69e` : moteurs Kotlin/TypeScript et fixture partagée ; seules les qualités terminales connues sont retirées, titre original conservé, groupes corrigés prioritaires.
- Tests passants : web 78 + build, shared 79, télé 63 ; fixture de 100 000 chaînes/2 000 catégories. Le rouge a été observé avant les moteurs ; les pannes initiales de prérequis ne sont pas comptées comme rouges fonctionnels.
- Fixture racine déclarée dans les entrées Gradle pour empêcher un résultat périmé du cache ; invalidation vérifiée par modification temporaire de contenu puis restauration.
- Relecture indépendante : un écart Unicode entre blancs Kotlin et JS reste à corriger avant de valider la tâche. Correction en cours ; ne pas considérer la tâche 1 terminée.
- Environnement opérationnel pour ces tests : JDK21/SDK36 locaux, proxy Java configuré, PostgreSQL16 conteneurisé ; procédure dans `docs/rapports/environnement-cloud.md`. Dépréciation préexistante de `RefreshFailure.cause` signalée séparément. Aucun changement en production.

### Tâche 1 validée après correction

- Correctif `24fb996` : ensemble de blancs explicite partagé et noms aux contrôles ambigus exclus de l’automatique, aux bords et en interne ; source isolée conservée.
- Rouge puis vert vérifiés sur les vecteurs ajoutés : 43 tests TS et 5 Kotlin passants. Aucune modification de la normalisation B1.
- Relecture ciblée indépendante : défaut corrigé, aucun nouveau défaut important. Tâche 1 terminée ; tâches 2–9 restent à réaliser.
- Tâche 2 commencée : corrections de groupes transactionnelles, droits propriétaire, pagination et réglage par profil. Migrations uniquement locales ; production inchangée.

## 14 novembre 2026 — Tâche 2 validée, écran iPhone commencé

- Commit `eb007cd` : migration 0009 locale, corrections communes au foyer, réglage group_copies par profil (vrai par défaut), révisions et RPC de pagination. Verrou foyer + révision attendue empêchent les écrasements concurrents. Favoris inchangés, sources disparues conservables.
- Rouge observé sur RPC/champ absents ; test additionnel a décelé puis fait corriger un curseur de dernière page. Résultats : 10 tests ciblés et 123 tests backend complets passants.
- Relecture indépendante : conformité et qualité approuvées, aucun défaut signalé. Cohérence/cache/retry et confirmation de restauration restent explicitement aux clients des tâches 3–4.
- Tâche 3 démarrée : écran Copies et API téléphone, démonstration et navigateur iPhone. Chromium système est disponible ; Playwright doit utiliser executablePath `/usr/bin/chromium`. Démonstration B1 ouverte avec succès et routes externes bloquées pour préserver les données de production.
- Aucune migration ni publication de production ; la livraison reste soumise aux accords distincts.

## 14 novembre 2026 — Tâche 3 validée, intégration télé commencée

- Commit `df644939` : Copies sur iPhone, API paginée/cache, confirmations, démo et tests DOM ; jsdom uniquement en développement avec lockfile cohérent.
- Test navigateur iPhone : rechercher, réunir intercatégories, nommer, enregistrer, relire, séparer, restaurer et retrouver les favoris B1 ; Tout le monde peut corriger les sources masquées conservées. Corrections partagées entre profils ; toggle séparé.
- 100 000 chaînes : 14 lignes DOM, fin de liste atteinte ; XSS rendu comme texte, panne avec cache en consultation seule. Zéro appel externe/erreur JS dans les parcours navigateur. La démo est en mémoire : son bouton Recharger relit le faux serveur ; un rechargement complet de page la réinitialise comme B1.
- Suite web 109 tests + build passants. Relecture indépendante a trouvé une perte de focus clavier ; correctif `8868e575`, rouge puis 22 tests ciblés verts, parcours clavier Chromium vert. Relecture du correctif approuvée ; compilation web relancée par contrôleur après correction : succès.
- Point mineur à reconsidérer en relecture finale : assertion du premier test de séparation sur les exclusions trop faible ; test de volume plus fort déjà présent.
- Tâche 4 commencée : client Kotlin des groupes, cache commun au foyer et lignes télé respectant l'organisation B1. Aucune publication ni migration distante.

## 14 novembre 2026 — Reprise par Claude (vérification du travail de l'autre IA)

- Branche `plan-3b2-direct` récupérée au commit `919f30a` (11 commits depuis la fusion B1 `c7aa465`).
- Suites relancées dans l'environnement de Claude, toutes vertes : backend 123/123, web 112/112 + build, shared 79/79, télé 63/63, `:tv:assembleDebug` OK.
- Contrôle de la migration 0009 : tables en RLS lecture membre, aucune écriture directe ; `live_group_edit` réservé au propriétaire (`org_assert(h,null,true)`) avec verrou du foyer et révision attendue ; `set_group_copies` limité au profil. Conforme aux règles de la passation. Production inchangée (0009 non appliquée).
- **Tâche 4 perdue** : le travail en cours de l'autre IA (GroupsClient, GroupsWatcher, corrections de LiveBrowseViewModelTest, relecture « review4 ») n'a jamais été poussé sur GitHub avant la fin de son quota. La branche ne contient aucun fichier de la tâche 4. Elle doit être refaite à partir du plan (tâche 4).
- Écart mineur : `.superpowers/sdd/b2-direct/task-3-report.md` a été forcé dans Git alors que ce dossier est ignoré ; sans conséquence.

## 14 novembre 2026 — Récupération de la tâche 4 et nouvelle règle de sauvegarde

- Le constat de Claude dans f12d331 était exact pour GitHub : la tâche 4 n'y était pas encore. Les fichiers existaient toujours dans cet environnement, déjà commités et avec les deux correctifs de relecture approuvés. Aucun stash ; seul le complément de plan du contrôleur restait non commité.
- Ce complément a été commité ; `git fetch origin` puis `git pull --rebase origin plan-3b2-direct` ont intégré f12d331 sans conflit. Code apps inchangé par rebase (comparaison ancienne/nouvelle arborescence). Nouveaux SHA : tâche4 `c05336f`, correctif `67fcd6a`, complément plan `7e91baf`. Push immédiat effectué avant commande longue.
- Relance `source /workspace/iptv-env/activate.sh`, puis depuis apps `./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest :tv:assembleDebug` : succès 7 s, 85 tests shared et 82 TV, aucun échec/erreur/ignoré ; sorties inchangées en partie réutilisées par Gradle. Relecture indépendante et relecture ciblée des correctifs déjà approuvées, artefacts locaux conservés.
- Nouvelle règle prioritaire : push après chaque sous-étape verte/correctif/document, avant tâche longue ou sous-agent, jamais plus de 30 min sans push ; toujours pull --rebase avant push. Les commits wip sont autorisés pour éviter une nouvelle perte.
- Ordre de cette reprise : terminer 4 (fait), construire 5 localement, puis 6 ; 7 seulement si quota ; ne pas commencer 8–9. Aucun accord de production, mise en ligne ou fusion main.
- Tâche5 démarrée : protocole d'arrêt confirmé. Le plan précise les champs de lecture souhaitée dans une demande de prise de main, la distinction des installations partageant auth.uid via empreinte privée de clé aléatoire, et le RPC propriétaire de lecture d'état administratif. Comportement utilisateur inchangé.

### Tâche 5 — checkpoint protocole initial
- RED local PostgreSQL16 : 2 tests échouent sur playback_claim/playback_enable absents (22:56 UTC).
- GREEN local : 2 tests passent (22:57 UTC), concurrence même compte, activation répétée, arrêt confirmé et priorité du demandeur.
- WIP : migration 0010 locale, API encore partielle ; contrôles/révocation/legacy et administration téléphone restent à construire. Empreinte privée SHA256, publication demandes limitée aux colonnes publiques.
- Checkpoint suivant : 4 nouveaux tests RED (API absentes / v1 encore autorisé), puis 6 tests GREEN après contrôles, récupération owner, révocation/profil supprimé, revalidation masques et blocage legacy. Correction du trigger lors de suppression du foyer (ne pas recréer un signal pour un foyer disparu). UI, contrat détaillé et tests limites restent incomplets.
- UI téléphone : module séparé `playback-admin`, onglet Lecture, RPC adminState avant toute action ; confirmations activation/récupération, rendu texte sûr, démo en mémoire. RED : 4 tests échouent sur comportement absent ; GREEN : 4/4 (23:01 UTC). Test navigateur, suite complète, limites SQL et contrat restent à finir.
- Vérification limites SQL : 11/11 verts (23:02 UTC), expiration waiting/ready, réservation périmée, masques catégorie/chaîne aux deux portées, source absente, foyer étranger, révocation réelle avec ACK restreint, privilèges et colonnes Realtime. Aucun changement de comportement nécessaire pour ces cas supplémentaires.
- Suites : web 116/116 + build verts ; Playwright Chromium mobile 390×844 `?demo` (réseau externe bloqué) : annulation activation, activation, récupération confirmée passent, aucun débordement/erreur navigateur/requête externe.
- La première suite backend a détecté 14 régressions v1 (`missing FROM-clause entry for table claim_stream`) dues au renommage PL/pgSQL. Correction : garder le corps et le nom v1, ajouter seulement le garde `protocole-obsolete` sous verrou ; tests legacy inchangés.
- Autorevue expiration : nouveau test RED démontre qu’une transaction commencée avant l’échéance pouvait promouvoir après celle-ci (`now()` figé). Horloge B2 remplacée par `clock_timestamp()` ; GREEN 12/12. Le corps legacy garde son horloge historique.
- Tâche 5 implémentée, prête pour relecture indépendante contrôleur : suite backend finale **135/135** (10 fichiers, dont 12 B2), web **116/116** + build ; parcours mobile démo vert. Contrat exact README et rapport `.superpowers/sdd/b2-direct/task-5-report.md`. Aucun déploiement ni migration distante. Transport Realtime hébergé et lecteur matériel restent aux validations ultérieures ; PostgreSQL publication/RLS testés localement.
