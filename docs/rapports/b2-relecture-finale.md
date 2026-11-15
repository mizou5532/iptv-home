# B2 — Corrections de la relecture finale de la branche

Branche `plan-3b2-direct`. Rien n'est appliqué en production, publié ni fusionné.

## Corrections

1. **Onglet « Lecture » (important).** `web/config/src/org/view.ts` : l'onglet n'est ajouté à `TABS` que si `VITE_PLAYBACK_ADMIN === '1'` à la construction (désactivé par défaut). `playback-admin.ts` et ses tests sont conservés. Test `org-tabs.test.ts` : `TABS` par défaut = copies, categories, favorites, playlists. Ajout de `src/env.d.ts` (types `import.meta.env`). `npm run build` : plus aucun appel `playback_*` dans le code applicatif ; seul subsiste le faux serveur de démonstration (chunk `demo-*.js`, données fictives) inatteignable sans l'onglet.
2. **Choix d'une copie ajoutée (important).** `LiveBrowseViewModel.playbackFor` résout désormais sur le groupe courant (`groupIndex[anchor]` de même identifiant) et non sur la ligne périmée ; `LivePlaybackHost.syncPlaying` rafraîchit `known` quand les copies du même groupe changent. `playbackSource` compare groupe et ancre plutôt que l'égalité de ligne.
   - RED : `choosing_a_copy_added_to_the_playing_group...` expected:<c> but was:<null> ; `une_copie_ajoutee_au_groupe_en_lecture...` expected:<[a1, a2, a3]> but was:<[a1, a2]>.
   - GREEN : les deux passent.
3. **Ordre global des copies.** Migration 0009 modifiée en place : `live_catalog_page(h, after_category_position, after_position, after_id, lim)` pagine et ordonne sur `(c.position, ch.position, ch.id)`, renvoie `category_position` et un curseur à trois clés ; index `(household_id, category_id, position, id)`. `groups-api.ts` dérive la position globale (rang dans cet ordre). Tests : backend `catalogue_ordonne_par_categorie_puis_chaine_et_curseur_a_trois_cles` (deux catégories, pages de 1), web `la position des copies est globale...`. `demo.ts` adapté.
4. **Préférences changées pendant la résolution.** `playbackFor` refait un seul essai avec les nouvelles préférences ; chaque essai relit la visibilité, une copie masquée n'est jamais lue. Test `preferences_changing_during_resolution_retry_once_and_never_play_a_hidden_copy` (RED : expected:<a> but was:<null>).
5. **`live_group_edit`.** Titre 1–160 caractères après trim, au plus 50 `blocked_auto_keys` de 200 caractères, sinon `correction-invalide`. Test `edition_refuse_titre_et_cles_hors_limites` (refus et bornes exactes acceptées).
6. **Docs.** Plan tâche 9 étape 4 (0009 seule, 0010 reportée), `backend/README.md` (0001–0009, 0010 non appliquée avant l'étape 8), `PASSATION.md` ligne 3B2.

## Vérification

- `npm test -w backend` : 149/149
- `npm test -w web/config` : 118/118
- `npm run build -w web/config` : OK
- `./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest :tv:assembleDebug` : shared 85/85, tv 156/156, aucun ignoré, BUILD SUCCESSFUL

## Réserves

- Le RED des points 3 et 5 (SQL) n'a pas été exécuté avant la correction (tests écrits avec le code SQL) ; les tests vérifient les bornes exactes et les refus.
- Le test de l'ordre côté TV n'a pas été modifié (la télé utilisait déjà l'ordre global).
- `live_catalog_page` change de signature : à appliquer avec 0009 non encore en production, aucun autre client ne l'appelle (vérifié par recherche).
