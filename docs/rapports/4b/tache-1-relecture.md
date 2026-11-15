# Revue indépendante — tâche 1, backend Films

Base : `2cf8885` ; tête annoncée : `7d2791f`.

**Conformité au brief : conforme. Qualité de la tâche : APPROVED.** Aucun problème Critical, Important ou Minor suffisamment étayé n'a été identifié dans le périmètre examiné.

## Périmètre et méthode

Lecture du brief, du rapport et du diff fourni une seule fois. La sortie initiale ayant tronqué une partie de la migration, seule cette partie manquante a été lue dans le fichier source. Inspection ciblée des anciennes définitions de progression et de `org_assert` pour vérifier deux risques concrets : conservation des nouvelles métadonnées par les anciennes RPC et contrôle foyer/profil des fonctions SECURITY DEFINER. Aucune suite réexécutée, aucune modification du code ou de Git, aucun accès production.

## Conformité et points forts

- Toutes les RPC demandées et les nouvelles clés de `profile_prefs` sont présentes, avec les signatures attendues et un contrat README explicite.
- Migration additive ; les anciennes signatures sont préservées. L'ancienne écriture `record_progress` met à jour une liste explicite de colonnes et conserve ainsi les colonnes `movie_*` existantes.
- Métadonnées films séparées des séries dans les favoris et la progression. Suppression de liste, lecture, marquage et progression ciblent explicitement `item_type = 'movie'`.
- `continue_watching` inclut désormais le type dans son regroupement, ce qui évite la collision film/épisode de même identifiant ; sa projection correspond à l'enrichissement du type composite.
- Les fonctions publiques contrôlent le foyer/profil ; les changements globaux d'organisation restent réservés au propriétaire via `org_assert`. Le catalogue active RLS, autorise seulement la lecture membre et refuse les écritures directes ; le helper d'ordre n'est pas exposé.
- Les catégories masquées sont exclues avant la limite de continuation ; les limites sont bornées. Les références de liste et la lecture unitaire d'un film masqué restent disponibles conformément au contrat documenté, avec filtrage d'affichage à la charge du client.
- Écriture de progression atomique avec priorité à l'horodatage récent, plafond futur, métadonnées conservées quand nulles et statut vu collant. Marquage manuel sans remontée de date, remise à zéro pour non vu et date ancienne pour un nouveau film marqué vu.
- Organisation de liste sérialisée par verrou transactionnel par profil ; ordre dédoublonné, éléments omis conservés et champs historiques de préférences maintenus.
- Les tests fournis couvrent les nouvelles interfaces, des refus d'accès, l'isolation des foyers/profils, la collision typée, l'ancienne signature, les métadonnées, les masques avant limite et le marquage manuel. Le changement du test séries remplace correctement une valeur désormais valide par une valeur réellement invalide.
- Aucun identifiant IPTV réel, adresse de flux, secret privilégié ou publication n'apparaît dans les changements examinés. Les messages applicatifs ajoutés sont français.

## Preuves et limites de vérification

Le rapport annonce un rouge fonctionnel de 5 tests avant migration, puis 5 tests Films réussis et une suite complète de 172 tests sur 12 fichiers réussie. Ces résultats sont des preuves rapportées, pas une exécution indépendante de cette revue ; conformément au mandat, aucune suite n'a été relancée. Les fichiers de logs et l'identité effective du commit n'ont pas été revérifiés.

La conformité est évaluée contre le brief fourni et les contraintes globales. Le document de conception référencé par le brief n'a pas été relu. Les comportements client (affiches nettoyées, catégorie inconnue indisponible, masqué gagne à l'affichage et à la lecture), le cache, les files de synchronisation et les essais matériels relèvent des tâches suivantes et ne sont pas vérifiables dans ce diff backend.
