# Tâche 5 — intégration télé (en cours)

## Premier palier : écran et fiches

9 nouveaux tests Robolectric `SearchDpadTest` : ouverture Direct/Séries/Films, Bas/Haut champ/résultats, retour et focus Rechercher, absence du service vocal, états vides focalisables, fiche série/film puis retour au même résultat. Premier lancement rouge : 9/9 échouent, bouton absent (`task5-red.log`, hors dépôt). Une erreur initiale du test (extension de focus inexistante) a été corrigée avant ce rouge. Première implémentation : 8/9 ; le 9e utilisait le helper de sous-chaîne « Parler », qui trouvait aussi l'aide. Assertion corrigée pour rechercher exactement le bouton. Relance : 9/9 verts.

Commandes : `source /workspace/iptv-env/activate.sh; cd apps; ./gradlew --max-workers=2 :tv:testDebugUnitTest --tests '*SearchDpadTest*'`.

L'écran conserve le ViewModel par section/profil, la requête exacte et l'identifiant sélectionné pendant les fiches. Les calculs/lectures du modèle utilisent IO ; sa portée et `setQuery` restent sur Main. Les cartes Poster sont réutilisées ; pas de lecteur réécrit. Bouton optionnel pour garder la compatibilité des écrans isolés existants.

Préparation Direct incluse : cible de révélation, sélection de catégorie, restauration après défilement et consommation avant aperçu. Cette partie doit encore être exercée par les tests dédiés (dont une chaîne hors premier viewport) ; elle n'est pas déclarée terminée.

## À faire

- Révélation Direct réelle, catégorie masquée pendant le choix et focus lors d'un catalogue qui change.
- Source Direct complète et invalidations catalogue/groupes.
- Remplisseur unique IptvApp, cycle de vie et priorité des demandes accueil/fiche.
- Reconnaissance vocale déléguée, visibilité Android et rejet des réponses tardives.
- Suites complètes shared/TV + assembleDebug ; relecture indépendante par le contrôleur.

Aucune version changée, aucun push/publication/migration ni donnée fournisseur réelle utilisée.
