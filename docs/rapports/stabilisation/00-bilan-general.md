# Stabilisation — Zone 0 : bilan général (photo de départ)

*18 nov. 2026. Branche `claude/vigilant-ramanujan-572vae`, partie de `main` @ `9163d9b` (0.30.0). Aucune modification de l'app, aucune publication.*

## Démarche de stabilisation (validée par le propriétaire)

Avant toute nouvelle fonction : l'app est découpée en zones. Pour chaque zone : bilan sans toucher → rapport classé (grave / gênant / détail) → le propriétaire choisit → corrections avec un test qui reproduit chaque bogue → relecture indépendante → publication d'une version → essai sur la TCL.

Ordre prévu : **0 bilan général** (ce document) → 1 lecteur vidéo → 2 Direct → 3 Sport → 4 Séries → 5 Films → 6 démarrage, navigation, profils, thèmes, mises à jour, recherche → 7 page iPhone → 8 synchro Supabase.

## 1. Mesures de départ

| Mesure | Résultat |
|---|---|
| Tests page iPhone (`web/config`) | **265 / 265** ✅ |
| Tests backend (Postgres 16 local) | **229 / 229** ✅ |
| Tests cœur partagé (`:shared:jvmTest`) | **361 / 361** ✅ |
| Tests app télé (`:tv:testDebugUnitTest`) | **439 / 439** ✅ |
| **Total** | **1 294 tests, 0 échec, 0 ignoré** |
| Construction de la page | ✅ JS 399,5 Ko (110 Ko compressé), CSS 36,8 Ko (7,9 Ko) |
| Construction APK release | ✅ 14,55 Mo, certificat attendu `5d8877ce…15d4` |
| Code contenu dans l'APK | 6 fichiers `classes*.dex` = **≈ 38 Mo décompressés** (aucune réduction R8 : `isMinifyEnabled = false`) |
| Failles connues des bibliothèques de la page (`npm audit --omit=dev`) | **0** |
| Avertissements du compilateur Kotlin | 24 |
| Analyse Android (lint, release) | **8 erreurs, 45 avertissements, 7 conseils** (l'analyse arrête la construction si on la lance) |
| Conseils Supabase sécurité | 72 avertissements « fonction accessible aux connectés », 1 info, 1 option d'authentification |
| Conseils Supabase performance | 1 règle recalculée à chaque ligne, 2 tables à doubles règles, 3 index manquants, 1 table sans clé primaire, 2 index jamais utilisés |

Taille du code et protection par les tests (app télé) :

| Partie | Lignes de code | Tests |
|---|---|---|
| Direct (`live/`) | 2 951 | 167 |
| Séries (`series/`) | 2 819 | 91 |
| Sport (`sport/`) | 1 151 | 16 |
| Films (`movies/`) | 1 042 | 28 |
| Démarrage (`startup/`) | 804 | 15 |
| **Lecteur (`player/`)** | **704** | **5** |
| Recherche (`search/`) | 688 | 17 |
| Accueils mis en avant (`feature/`) | 714 | 7 |
| Navigation (`nav/`) | 108 | 0 |

## 2. Constats

### Importants

1. **Le lecteur vidéo est la partie la moins protégée** : 5 tests pour 704 lignes, alors qu'il sert partout (Direct, Sport, Séries, Films) et porte la règle « une seule connexion au fournisseur ». → Confirme la zone 1 en premier.
2. **L'app n'est pas « compactée » à la construction** (R8 désactivé) : ≈ 38 Mo de code embarqué, dont une grande partie jamais utilisée. L'activer rendrait l'app plus légère et plus rapide à démarrer, surtout sur le Fire Stick 4K 1re génération (appareil faible). Risque : certaines bibliothèques (Supabase, sérialisation, Media3) demandent des règles précises, sinon plantage au démarrage. À faire en zone 6, avec tests complets et essai télé.
3. **Les tests backend ne vérifient pas exactement la production** : la migration `0010_lecture_confirmee` (protection de la lecture) existe dans le dépôt et dans les tests, mais **n'a jamais été appliquée en production** (volontaire depuis B2). Elle remplace `claim_stream`. Donc la version de `claim_stream` testée n'est pas celle qui tourne. L'écran iPhone correspondant est caché (`VITE_PLAYBACK_ADMIN`). → Zone 8 : décider de la garder de côté (hors des tests principaux) ou de la retirer.

### Gênants (à examiner dans chaque zone)

4. **8 erreurs de l'analyse Android**, vérifiées une à une :
   - 3 sur la gestion des touches de la télécommande (`MainActivity.dispatchKeyEvent`) : fausse alerte connue de l'outil ;
   - 4 dans le lecteur (`ExoPlayerPlayer.kt` 52–56) : fonctions Media3 « à confirmer » utilisées sans le marquage demandé (sans effet aujourd'hui, mais peut casser à une mise à jour de Media3) ;
   - 1 dans `MediaMeta.rememberMeta` : valeur de départ lue directement (sans effet réel, la suite est bien suivie).
   → Corriger ou marquer, puis créer une « ligne de base » pour que l'analyse devienne un garde-fou utilisable à chaque construction.
5. **≈ 15 boucles qui interrogent régulièrement** (préférences toutes les 4 s, mises à jour, Sport, guide, accueil Séries/Films, horloge…). À vérifier zone par zone : qu'elles s'arrêtent bien quand on quitte l'écran ou que la télé se met en veille (réseau, chauffe et fluidité du Fire Stick).
6. **67 endroits qui attrapent « toutes les erreurs »** dans le code télé et partagé. Certains peuvent cacher un vrai problème ou empêcher l'arrêt propre d'une tâche. À relire zone par zone.
7. **Supabase, performance** : petits gains faciles (règle `media_meta_select` à écrire avec `(select auth.uid())`, fusion des doubles règles sur `category_order` et `hidden_items`, index sur `active_streams.profile_id`, `households.owner_id`, `pairing_codes.household_id`, clé primaire pour `category_order`). Volume faible aujourd'hui, donc gain modeste. → Zone 8.

### Détails

8. **Supabase, sécurité** : les 72 avertissements sont la conception voulue (toute la page et la télé passent par des fonctions). Échantillon vérifié (`hidden_for`, `global_hidden_live_categories`, `catalog_titles_by_ids`, `can_write_avatar`…) : chacune contrôle l'appartenance au foyer, et `anon` n'en exécute aucune. Connexions anonymes : voulues (télé). Revue complète en zone 8.
9. **Avertissements du compilateur** : une fonction Supabase dépréciée dans `SyncClient.kt:122` (à remplacer avant la prochaine mise à jour de la bibliothèque) ; une condition toujours vraie (`LiveBrowseScreen.kt:210`, sans effet) ; un `!!` inutile (`SportExtra.kt:87`).
10. **31 suggestions de style** (`UseKtx`) et quelques conseils Compose (`mutableIntStateOf`, `offset { }` dans `WhoIsWatchingScreen`) : gains minimes, à faire au passage.
11. **`PASSATION.md` est devenu difficile à suivre** : section 0 d'environ 330 lignes, informations périmées ailleurs (section 3 : tableau d'étapes de 4A/4B ; section 5 : « télé 0.5.0 » ; section 7 : branche `plan-4-series`). → Ménage à faire.
12. **Environnement de construction** : le premier essai Gradle a échoué, le serveur Maven ayant refusé trop de téléchargements (erreur 429). Résolu en relançant avec `-Dorg.gradle.internal.repository.max.retries=12 -Dorg.gradle.internal.repository.initial.backoff=3000`.

## 3. Limite

La fluidité réelle (saccades, lenteurs d'image, chauffe) ne se mesure pas ici : aucune télé n'est branchée à l'environnement de Claude. Les bogues que le propriétaire a remarqués sur la TCL sont l'information la plus utile pour chaque zone.

## 4. Suite

Zone 1, lecteur vidéo : relecture complète de `apps/tv/.../player/` et des écrans qui l'utilisent (`EpisodePlayback`, `EpisodePlayerScreen`, `MoviePlayerScreen`, lecture du Direct et du Sport), plus `ProgressQueue` côté partagé.
