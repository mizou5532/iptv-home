# Tâche 3 — Films télé

## Checkpoint accueil / état (16 novembre 2026)

Ajout du contrat MovieCatalog, adaptateur du dépôt déjà testé, état local distinct par profil pour progression et Ma liste, publication quotidienne des catégories, accueil à la demande. Aucune demande de contenu avant préférences connues ; revalidation du masque après réponse ; références sans catégorie rétablies par cache seul. Les récents (20) viennent de la requête paginée récemment ajoutée du dépôt, jamais d'un catalogue global ; dates invalides exclues. Catégories toutes accessibles, mémoire de rangées évincée (24 / 40000 affiches hors rangées actives).

Vérification : nouvelles classes absentes au premier passage de compilation (rouge), puis compilation et harness JavaExec externe `/workspace/iptv-work/4b/task-3-plain-tests.gradle` : **272 tests TV JVM verts**, dont 5 Films (4 rangées + 1 accueil masquage/préférences). Journal `/workspace/iptv-work/4b/task3-home.log`. Le harness ne contourne aucun test produit ; il exécute seulement les tests sans runtime Android.

À poursuivre : fiche/actions, lecture, écrans et intégration, tests D-pad à compiler et exécuter si le téléchargement Robolectric redevient possible. Le parcours Films n'est pas encore intégré à l'application. Aucun changement de version, publication, production ou configuration réseau.
