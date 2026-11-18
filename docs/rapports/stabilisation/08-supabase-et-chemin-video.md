# Stabilisation — Zone 8 : Supabase, et le chemin de la vidéo (bilan)

*18 nov. 2026, après la 0.31.0. Le propriétaire veut « les meilleures vidéos possibles, le meilleur chemin pour qu'elles s'affichent le mieux à la télé, avec le moins de bogues ou de lags ». Les vidéos ne passent **pas** par Supabase : elles vont directement du fournisseur IPTV à la télé. Supabase ne transporte que réglages, progression, favoris et la copie du catalogue pour l'iPhone. Cette zone couvre donc les deux : le serveur, puis le chemin de la vidéo dans la télé.*

## Partie A — Supabase (mesures sur la production, lecture seule)

### Mesures

| Mesure | Résultat |
|---|---|
| Taille | `catalog_title` 59 182 lignes (28 Mo), `live_catalog_channel` 56 812 (21 Mo), le reste < 1 Mo |
| Demande la plus fréquente | `prefs_rev` (vérification des réglages) : 37 510 appels, **0,8 ms** en moyenne |
| Demandes de la télé | `profile_prefs` 19 ms, `record_progress` 5 ms, `continue_watching` 8–14 ms, `live_group_page` 6 ms |
| Demandes de la page iPhone | `org_categories` 102 ms, `live_catalog_page` 55 ms, `org_search` 39 ms |
| Travail de fond (télé → iPhone) | `publish_catalog_titles` 226 ms (une fois par jour), `publish_live_categories` 61 ms |
| Index | les grosses tables sont lues par index (178 799 lectures indexées contre 8 complètes sur `catalog_title`) |

**Conclusion : Supabase est rapide et n'est pour rien dans les lenteurs ni les saccades vidéo.**

### Constats

1. **Petits réglages du serveur** (conseils Supabase, gain modeste mais gratuit) : 3 index manquants (`active_streams.profile_id`, `households.owner_id`, `pairing_codes.household_id`), règle de `media_meta` recalculée à chaque ligne (`(select auth.uid())`), doubles règles de lecture sur `category_order` et `hidden_items`, clé primaire manquante sur `category_order`. Une migration, approuvée par le propriétaire dans l'invite Supabase.
2. **Migration 0010 « protection de la lecture »** : jamais appliquée en production, mais appliquée dans les tests (elle remplace `claim_stream`). Aucune app n'appelle `claim_stream` ni les fonctions `playback_*` ; l'écran iPhone correspondant est caché. → La ranger à part (`backend/supabase/en-attente/`, avec son test) pour que les tests vérifient exactement la production ; elle reste disponible si la fonction est voulue un jour.
3. **Sécurité** : revue complète faite en zone 0 et confirmée : chaque fonction vérifie l'appartenance au foyer, `anon` n'exécute rien, aucune règle d'écriture permanente sur le stockage hors des 4 `avatars_*`. Rien à corriger.

## Partie B — Le chemin de la vidéo dans la télé (là où se joue la qualité)

Chemin actuel : fournisseur (http) → `DefaultHttpDataSource` (Media3) → décodeur matériel → **TextureView** → écran.

### Constats

**4. La vidéo est dessinée dans une « TextureView » au lieu d'une « SurfaceView » (important).**
Choix fait en 4A pour corriger un écran noir au passage de l'aperçu au plein écran du Direct (`res/layout/player_view.xml`). Conséquences, documentées par Google/Media3 :
- chaque image est recopiée par le processeur graphique avant l'affichage : plus de travail, de chaleur, et des images sautées plus probables, surtout sur le Fire Stick et en 4K ;
- **pas de HDR** : un film 4K HDR s'affiche délavé, sans le relief de couleurs prévu ;
- **pas d'adaptation de la cadence** (point 5) ;
- cadence d'affichage moins régulière (petites saccades dans les panoramiques et le sport à 60 images/s).
→ Proposition : SurfaceView via le composant Compose officiel de Media3 (`media3-ui-compose`, `PlayerSurface` avec `SURFACE_TYPE_SURFACE_VIEW`, fait pour Compose, ce qui évite le défaut d'origine). **Par étapes** : d'abord films, épisodes et Sport (plein écran direct, pas de passage depuis un aperçu) ; puis le Direct après ton essai. Retour en arrière simple si l'écran noir revenait.

**5. Pas d'adaptation de la cadence d'image (films).**
Les films sont à 24 images/s, l'écran à 60 Hz : la télé répète les images de façon inégale, d'où le petit tremblement régulier des panoramiques. Android TV sait passer l'écran en 24 Hz (réglage « Fréquence d'images adaptée » / « Match content frame rate » de la TCL), mais seulement avec une SurfaceView et si l'app le demande (`setVideoChangeFrameRateStrategy`). → Activer avec le point 4.

**6. Pas de décodeur de secours.**
Si le décodeur matériel refuse un flux (profil vidéo inhabituel, fréquent en IPTV), la lecture échoue. Media3 peut essayer un autre décodeur (`setEnableDecoderFallback(true)`). → Activer (une ligne, sans risque).

**7. Démarrage d'une chaîne : 2,5 s de réserve avant d'afficher.**
Réglage par défaut de Media3. Une réserve de 1,5 s rendrait le zapping un peu plus vif, au prix d'un risque d'arrêt plus élevé sur une connexion faible. Tu trouves le zapping correct : **pas de changement proposé**.

**8. Connexions au fournisseur non réutilisées.**
Chaque saut (avance, recul, reprise) ouvre une nouvelle connexion. Une bibliothèque réseau qui réutilise la connexion (OkHttp, déjà dans l'app pour d'autres usages) rendrait les sauts et reprises un peu plus rapides. Gain modeste. → Optionnel, après 4 à 6.

**9. Son DTS des films MKV.**
Beaucoup de films IPTV en MKV ont une piste audio DTS. Si la TCL ou le Fire Stick ne décodent pas le DTS, cette piste est écartée (autre langue choisie, ou film muet s'il n'y a qu'elle). Corriger demande d'ajouter un décodeur logiciel (FFmpeg) à l'app : gros travail. → Seulement si tu constates des films sans son.

## Ordre proposé

1. Serveur : 1 et 2 (migration approuvée par toi dans l'invite Supabase).
2. Vidéo, étape 1 : 6, puis 4 et 5 pour films, épisodes et Sport ; version publiée ; **essai sur la TCL** (un film 4K HDR, un film 24 images avec « Fréquence d'images adaptée » activée dans les réglages de la TCL, un match).
3. Vidéo, étape 2 (après ton essai) : 4 pour le Direct ; puis 8 si tu veux.

## Corrections faites et publiées — 0.32.0 (code 56), 18 nov. 2026

Choix du propriétaire : le serveur et l'étape 1 de la vidéo (« j'aimerais quand même qu'on fasse toutes ces corrections »).

| Point | Correction | Où |
|---|---|---|
| 1 | Migration `reglages_serveur` **appliquée en production** : 3 index, règle `media_meta` lue une fois par demande, règles d'écriture de `category_order` et `hidden_items` découpées (mêmes conditions). Plus aucun avertissement de performance Supabase. Pas de clé primaire sur `category_order` : `profile_id` peut être vide, la contrainte d'unicité `nulls not distinct` joue déjà ce rôle | `backend/supabase/migrations/20261118000026_reglages_serveur.sql` |
| 2 | Migration 0010 rangée dans `backend/supabase/en-attente/` (README) et son test dans `backend/test-en-attente/` (hors suite) : les 205 tests du serveur vérifient exactement la production | `backend/vitest.config.ts` |
| 4 | Films, épisodes et Sport : vidéo dans une **SurfaceView** (`VideoSurface`, `media3-ui-compose` `ContentFrame`), écran maintenu allumé pendant la lecture. Le Direct garde la TextureView (étape 2, après essai) | `player/PlayerScreen.kt` |
| 5 | Films et épisodes : l'écran passe à la cadence de la vidéo (`preferredDisplayModeId`, même définition, fréquence égale ou multiple entier), mode d'origine rétabli en quittant ; pas pour le Direct ni le Sport (clignotement au zapping) ; Android 6+ (Fire Stick compris) | `player/FrameRateMatch.kt` |
| 6 | Décodeur de secours (`setEnableDecoderFallback(true)`) | `player/ExoPlayerPlayer.kt` |
| 9 | **Non fait** : le décodeur DTS tout prêt (Jellyfin `media3-ffmpeg-decoder`) s'arrête à Media3 1.9 ; l'app est en 1.11 : risque de plantage à la lecture, invérifiable ici. À reprendre si un film est muet | — |

Relecture indépendante : rien de sérieux (écran allumé lors des changements d'épisode et des reconnexions, mode d'écran rétabli dans tous les cas, compatibilité Fire OS vérifiés). Compromis noté : avec le décodeur de secours, un flux 4K HEVC que le Fire Stick ne décode pas en matériel peut être lu en logiciel (saccadé) au lieu d'afficher une erreur.

Tests : 205 serveur, 361 partagés, 485 télé (7 nouveaux : `FrameRateMatchTest`, `VideoSurfaceTest`), 0 échec ; analyse Android 0 erreur. APK SHA-256 `fe1b93098138f5f2b0976e52e6a1206a3dde5480e42c773d46d884b7cfb40411`, 14 649 791 octets, `minSdkVersion 25`, certificat attendu, identique par Supabase et Vercel ; page inchangée ; Vercel `dpl_5wyyy7wVrowFUQLifx4RFv3uTYe5` ; règles stockage : les 4 `avatars_*`.

**Essai TCL** (avant l'étape 2) : (a) activer « Fréquence d'images adaptée » / « Match content frame rate » dans les réglages Écran de la TCL ; (b) lancer un film : l'écran peut devenir noir 1 à 2 s (passage en 24 Hz, normal), puis le mouvement doit être plus régulier ; en sortir : retour à l'affichage habituel ; (c) un film 4K HDR si le fournisseur en a ; (d) un match dans le Sport : image fluide ; (e) **vérifier qu'aucun écran noir avec le son seul n'apparaît** (le défaut de 0.4.0) en films, épisodes et Sport.
