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

## 14 novembre 2026 — Tâche 5 terminée et relue, tâche 6 démarrée

- Sauvegardes poussées à chaque étape verte : `2d73925`, `c8e9c6c`, `5073d90`, `55b34c3`, `5b1435e`, `4c7ca4d`, `0f809b4`, `ed150ad`, puis validation `3ba6c36`. Aucun intervalle de travail long laissé sans push ; pull --rebase avant chacun.
- Dernier code `ed150ad` corrige une expiration après attente transactionnelle avec clock_timestamp ; régression legacy de renommage PL/pgSQL corrigée en gardant le corps v1 et en ajoutant sa garde B2.
- Preuves finales : backend 135/135 dont12 B2, web116/116 + build, navigateur mobile activation/annulation/récupération verts sans réseau externe. Relecture indépendante (conformité et qualité) approuvée, aucun finding retenu.
- Douze RPC et JSON exacts dans backend/README.md ; rapport durable docs/rapports/b2-reservation-lecture.md (déplacé hors scratch dans `4c8f4fa`). Clé installation représentée par empreinte privée, contrôle même auth.uid/deux clés, présence périmée non libérée sans preuve. Migration0010 locale seulement.
- Tâche6 commence : client Kotlin, événements lecteur et coordinateur ; ajout de branchement minimal dans LiveRoute/App pour éviter que l'ancien chemin de lecture contourne la réservation. L'aperçu et les panneaux complets restent tâche8, hors de cette reprise.
- Arrêt matériel réel et transport Supabase Realtime hébergé non testés ; aucune prétention d'essai physique. Aucune production/mise en ligne/fusion.
- Complément intégration après review5, base 636fba1 : RED confirmé localement (23:18 UTC), 2 échecs / 12 verts. Publication B1 chunk0 nonfinal retire s2 de la copie : control d'une lecture s2 déjà autorisée renvoie stop au lieu de keep ; started après claim rejette source-inconnue dans la même fenêtre. Cause : playback_allowed exige la présence instantanée alors que fingerprintNULL marque une reconstruction partielle. Aucun correctif au checkpoint RED ; migrations de production inchangées.
- Correctif intégration GREEN (23:20 UTC) : 23/23 tests B2. Migration0010 seulement : catégorie validée mémorisée dans la place ; control/started tolèrent une source temporairement absente seulement si cette catégorie existe encore avec fingerprintNULL. Validation STABLE dans un même instantané ; masques chaîne/catégorie globaux/profil lus sans jointure à une source absente. claim/request_stop/promote/update restent stricts ; révocation, suppression profil/catégorie, fin de publication sans source arrêtent toujours. Nouvelles signatures publiques inchangées. Suite complète encore à relancer.
- Contrat README complété pour la fenêtre de publication, signatures/JSON inchangés ; test supplémentaire déplacement de catégorie observé par control/started et helper privé non appelable : 24/24 verts (23:21 UTC). Sauvegarde avant suite backend complète.

## 14 novembre 2026 — Vérification par Claude après la 2ᵉ reprise

- Branche au commit `f974c0d` : la tâche 4 a bien été récupérée (`c05336f`, `67fcd6a`) et la tâche 5 terminée et relue (migration 0010 **locale**). La tâche 6 n'a pas de code sur GitHub (seulement annoncée dans le journal).
- Suites relancées par Claude, toutes vertes : backend 147/147, web 116/116 + build, shared 85/85, télé 82/82, `:tv:assembleDebug` OK.
- Production inchangée : migrations 0009 et 0010 non appliquées, rien publié, rien fusionné.
- B2 : tâches 1–5 terminées sur 9 ; restent 6 (coordinateur et prise de main sur la télé), 7 (guide), 8 (aperçu, choix des copies, plein écran), 9 (vérifications, mise en ligne 0.3.0, essai).

## 14 novembre 2026 — Reprise par Claude : tâche 6 reportée, tâche 7 en cours

- Décision du propriétaire : tâche 6 (« Prendre la main ») reportée à l'étape 8 ; plan amendé (section « Amendement » en fin de plan). Livraison 0.3.0 avec la migration 0009 seulement, 0010 reste locale, onglet « Lecture » masqué.
- Tâche 7 (guide) lancée par Claude (sous-agent constructeur), base aa7ab78. Règle de sauvegarde : push après chaque sous-étape verte.
- **Si tu reprends ici (IA ou personne)** : lire PASSATION.md, puis ce journal du bas vers le haut ; la tâche en cours est la première non cochée du plan B2 (hors tâche 6). Vérifier `git log` : chaque sous-étape terminée y est poussée.
- Tâche 7 construite : `aa7ab78` (17 tests verts, rouge montré d'abord). Relecture indépendante en cours. Écarts à juger : interface `EpgSource`, horloge monotone injectable, nouvel essai après 60 s en cas d'erreur.
- Tâche 7 relue : approuvée. Petite série de corrections en cours (test du nouvel essai après 60 s, horloge sûre après mise en veille, pas de clignotement « indisponible »).
- Tâche 7 terminée (`aa7ab78`, correctifs `e309f93`, 19 tests). Tâche 8 (aperçu, choix des copies, plein écran) lancée.
- Tâche 8 construite (a40f8c7 réducteur pur, 4b1fd31 hôte de lecture, 8c619a2 écran, 83b2ae0 retour de focus) : télé 146 tests verts, `:tv:assembleDebug` OK. Relecture indépendante à faire. Rien vérifié sur la TCL : voir `docs/rapports/b2-tache8-apercu.md` pour la liste des essais visuels restants.
- Tâche 8 : relecture indépendante en cours.
- Tâche 8 relue : 2 corrections demandées (ne jamais rouvrir une chaîne quand la télé passe en veille pendant le chargement ; la télécommande doit rester active en plein écran après le panneau Sources) + petites améliorations. Correction en cours.
- Tâche 8 terminée (`a40f8c7`…`f935bc2`, 153 tests télé). Relecture d'ensemble de la branche B2 (tâches 1–8) lancée, puis tâche 9 (préparation 0.3.0).
- Relecture d'ensemble : 2 corrections avant la 0.3.0 (masquer l'onglet « Lecture » qui dépend de 0010 ; une copie ajoutée pendant la lecture coupait la chaîne) + petites corrections (même ordre de copies téléphone/télé, limites de longueur, documents). Vérifié : 0009 seule suffit, la télé 0.2.0 continue de marcher. Corrections en cours.
- Corrections de la relecture d'ensemble faites (`9b04478`, `23f0d36`, `6c78f64`) ; Claude a tout relancé : backend 149, web 118, shared et télé verts. Script de mise en ligne : onglet « Lecture » désactivé à la construction. **Version 0.3.0 (versionCode 3) construite et signée avec la bonne clé. En attente de l'accord du propriétaire** pour appliquer la migration 0009 en production et publier la page et l'APK.
- **0.3.0 en ligne** (15 nov.) : migration 0009 appliquée en production (0010 non appliquée), APK 0.3.0 (versionCode 3, même certificat) déposé et vérifié octet pour octet, page publiée (onglet « Lecture » absent), aucune erreur navigateur. Règles d'écriture temporaires supprimées (0 restante). **Attend l'essai du propriétaire sur la TCL.**
