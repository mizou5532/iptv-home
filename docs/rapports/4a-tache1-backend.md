# Tâche 1 — Backend séries (rapport)

## Implémenté
Migration `backend/supabase/migrations/20261114000011_series.sql` (dépend de 0001-0009 seulement) :
- table `series_catalog_category` (RLS lecture membre, aucune écriture directe) + `publish_series_categories`.
- `org_series_categories`, `org_series_order` (aide interne), `org_set_hidden` étendu (`series_category`, même signature), `org_series_hide_all_except`, `org_set_series_category_order`.
- Ma liste : `org_set_series_list`, `org_reorder_series_list` (favorites `item_type='series'`).
- `profile_prefs` : repart de la version 0009 (group_copies + toutes les clés B1) + `series_order`, `series_list`.
- `watch_progress` + `series_name/series_cover/series_category` ; `record_progress` 12 arguments (sans valeurs par défaut) et ancienne signature conservée en enveloppe mince ; `horodatage-invalide` si NULL dans les deux ; `continue_watching` renvoie les 3 colonnes et exclut les catégories masquées (global ou profil).
- `set_watched(p, items, watched)`.
- Révision : les triggers existants (favorites, hidden_items, category_order) suffisent ; la publication des catégories ne touche pas `prefs_revision` (comme la copie du direct).

Choix de conception : surcharge sans valeurs par défaut (au lieu de 3 paramètres facultatifs) pour éviter l'ambiguïté (un appel à 9 arguments correspondrait aux deux signatures, et PostgREST ne départage pas par noms). Les clients passent null. Démarquer ne crée aucune ligne (une ligne watched=false à 2000-01-01 polluerait Continuer).

## TDD
RED (`npm test -w backend -- series`, avant la migration) :
    ⎯⎯⎯⎯⎯⎯⎯ Failed Tests 9 ⎯⎯⎯⎯⎯⎯⎯
     FAIL  test/series.test.ts > series > publication_categories_series_et_lecture_membre
    error: function publish_series_categories(unknown, jsonb) does not exist
     FAIL  test/series.test.ts > series > masquer_et_ordre_section_series
    error: function publish_series_categories(unknown, jsonb) does not exist
     FAIL  test/series.test.ts > series > ma_liste_series_ordre
    error: function org_set_series_list(unknown, unknown, boolean) does not exist
     FAIL  test/series.test.ts > series > profile_prefs_contient_series_order_et_series_list
     FAIL  test/series.test.ts > series > record_progress_refuse_horodatage_nul
     FAIL  test/series.test.ts > series > record_progress_enrichi_et_ancienne_signature
    error: function record_progress(unknown, unknown, unknown, unknown, unknown, unknown, unknown, unknown, unknown, unknown, unknown, unknown) does not exist
     FAIL  test/series.test.ts > series > continue_watching_exclut_categorie_masquee
    error: function record_progress(unknown, unknown, unknown, unknown, unknown, unknown, unknown, unknown, unknown, unknown, unknown, unknown) does not exist
     FAIL  test/series.test.ts > series > set_watched_marque_et_demarque_sans_remonter_la_serie
    error: function record_progress(unknown, unknown, unknown, unknown, unknown, unknown, unknown, unknown, unknown, unknown, unknown, unknown) does not exist
     FAIL  test/series.test.ts > series > etranger_refuse
    error: function publish_series_categories(unknown, jsonb) does not exist
          Tests  9 failed (9)
GREEN (même commande après) : Test Files 1 passed, Tests 9 passed (9).

## Suite complète (0010 présente)
`npm test -w backend` : 11 fichiers, 158 tests passent.

## 0011 sans 0010
Copie scratch du dossier backend (migrations 0001-0009 + 0011, sans 0010 ni son test) : 10 fichiers, 134 tests passent, donc 0011 s'applique proprement sur 0001-0009 seules (copie supprimée).

## Fichiers
- backend/supabase/migrations/20261114000011_series.sql (nouveau)
- backend/test/series.test.ts (nouveau)
- backend/README.md (tables, fonctions, règles « progression enrichie », « marquer vu », « séries masquées »)

## Concerns
- Signature : les clients de la nouvelle surcharge doivent envoyer les 12 clés (null accepté).
- PostgREST réel non testé (tests sur Postgres seul) ; appels nommés 9 et 12 clés vérifiés en SQL.

## Correctifs, tour 1 (migration 0011 modifiée en place)
- (Important) `continue_watching` résout la catégorie d'une série = dernière catégorie non nulle parmi ses lignes (renvoyée dans `series_category`) ; `set_watched` accepte `series_name/series_cover/series_category` facultatifs (coalesce à l'insertion et à la mise à jour). Série sans aucune catégorie connue : visible (documenté, README). Test sC inchangé car sans catégorie du tout ; nouveau test `continue_watching_categorie_resolue_sur_les_lignes_de_la_serie`.
- `publish_series_categories` : retour anticipé sans rien supprimer si aucune ligne valide (id nul ou vide, nom ou position absents). Test ajouté.
- `continue_watching` : `max_rows` borné à 0..200 (test).
- Tests ajoutés : hide_all_except avec p nul refusé pour un non-propriétaire ; `org_set_hidden` series_category augmente `prefs_rev` ; `set_watched` refusé à un non-membre. Note : `can_use_profile` = tout membre du foyer, donc « membre sans droit sur le profil » n'existe pas ; le cas couvert est le non-membre.
- README : GET direct de `series_catalog_category` sans risque sous 1 000 ; les 12 clés avec nulls explicites pour `record_progress` enrichi.
RED (`npm test -w backend -- series`, avant correction) :
    ⎯⎯⎯⎯⎯⎯⎯ Failed Tests 3 ⎯⎯⎯⎯⎯⎯⎯
     FAIL  test/series.test.ts > series > continue_watching_categorie_resolue_sur_les_lignes_de_la_serie
     FAIL  test/series.test.ts > series > set_watched_enrichit_et_conserve_nom_affiche_categorie
     FAIL  test/series.test.ts > series > publish_series_categories_sans_ligne_valide_ne_supprime_rien
          Tests  3 failed | 11 passed (14)
GREEN : `npm test -w backend` : 11 fichiers, 163/163.
