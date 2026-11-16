# Étape 5 — tâche 4 : relecture indépendante

16 novembre 2026. Périmètre : diff `e904169..e9bcc40`, brief tâche 4, rapport de réalisation, modèle et tests, contrats des sources et de l’index partagés. Aucun code produit modifié, aucune suite relancée ; les journaux ciblé et TV fournis indiquent `BUILD SUCCESSFUL`. Les résultats communiqués sont 11 tests ciblés et 331 tests TV verts. L’intégration écran/voix/Direct, le focus et la revalidation au OK appartiennent à la tâche 5.

## Verdict

**Conformité : approuvée. Qualité : approuvée avec une remarque mineure de contrat d’intégration.** Aucun défaut critique ou important relevé dans le périmètre autorisé.

## Constats

- **Critique : aucun.**
- **Important : aucun.**
- **Mineur M1 — confinement à expliciter**, `apps/tv/src/main/kotlin/home/iptv/tv/search/SearchViewModel.kt:24` et `:45`. `generation`, `work`, `index` et les copies successives de `state.value` supposent une exécution sérialisée. Le constructeur accepte pourtant un `CoroutineScope` quelconque et `setQuery` ne documente pas cette contrainte. Avec un scope multithread ou un appel concurrent de `setQuery`, ces mutations ne sont pas synchronisées ; la sûreté intrinsèque de StateFlow ne rend pas atomique le couple lecture/copie. Pour la tâche 5, fournir un scope sur le dispatcher principal et appeler `setQuery` sur ce même dispatcher ; documenter cette condition au niveau de l’API. Aucun appelant de ce modèle n’est encore intégré dans ce diff : ce point ne constitue pas une régression observée.

## Points solides vérifiés

- Préférences inconnues : aucun appel à `items` ni `details`, résultats retirés et message exact. À chaque préférence observée, les résultats sont effacés avant la reconstruction suspendue (`SearchViewModel.kt:30`).
- Annulation plus génération et comparaison des préférences avant réutilisation/publication (`:58`, `:72`, `:80`) : une ancienne opération IO ne peut publier à la place d’une requête plus récente dans le contrat sérialisé. Le test non coopératif complète utilement le test de frappe rapide.
- Anti-rebond de 120 ms ; reconstruction uniquement pour la section concernée, conservation du texte et relance sans frappe supplémentaire. Construction de l’index, recherche et résolution des détails s’exécutent sur `io` (`:71`, `:74`).
- Le filtrage emploie `visibleSearchItems` avant l’index ; les sources de tâche 2 fournissent l’union des catégories par identité. Un détail absent, d’identité différente, sans catégorie ou dont la catégorie est masquée est écarté (`:76`). La limite de 60 reste portée par l’index partagé.
- Les trois messages requis sont exacts. L’avancement incomplet reste distinct du message, limité à la section et absent sans préférences. Le passage null→préférences connues relit la valeur actuelle, sans dépendre d’une nouvelle émission de progression (`:33`).
- Les quatre filtres séries sont remplacés par une extension équivalente ; aucun changement de sens du masque ou des autres types.
- Les tests couvrent les risques principaux de la tâche ; le rapport distingue les preuves exécutées des travaux UI à venir et ne revendique aucun essai télé.

## Contrats à conserver pour la tâche 5

Les callbacks doivent lire seulement le cache local. `items` doit conserver toutes les catégories connues d’une même identité : pour le Direct, passer le plan complet des groupes connus à `liveSearchItems`, les sources visibles pour la sélection et les masques fusionnés ; ne pas perdre une copie masquée avant le calcul de l’union. Le contrôle supplémentaire du détail ne remplace pas cette union. `anchorSourceId` est distinct de cette union.

Afficher séparément `Catalogue en préparation : <done> catégories sur <total>. D'autres résultats peuvent apparaître.` à partir de `SearchUi.progress`. Garder le modèle au retour d’une fiche, le recréer par profil et annuler son scope à sa fermeture définitive. La revalidation au OK et la stabilité du focus restent à réaliser et tester dans la tâche 5.
