# 0.40.0 — Rapports de lenteur et allègements (19 nov. 2026)

Suite de l'enquête `enquete-lenteur-2026-11-19.md`. Le propriétaire a choisi **A + B** (C plus tard). Pendant le
travail, il a signalé un plantage en lançant le match des Canadiens depuis le Sport (après une longue lenteur).

## Ce que montrent les journaux du serveur au moment du plantage (19 nov., 00 h 45–00 h 47 UTC)

1. 00 h 45 min 35 s : l'app démarre ; 00 h 46 min 07 s : nouveau démarrage.
2. 00 h 46 min 23 s : « Regarder » (réservation du compte acceptée en 0,17 s).
3. 00 h 46 min 35 s : l'app redémarre (elle s'est fermée avant la première seconde de lecture) ; 2e essai à
   00 h 46 min 50 s : la lecture marche.
4. **À chaque démarrage, toute la liste des chaînes du Direct est retéléchargée et republiée** (deux fois en une
   minute) : la mise à jour faite n'était retenue que pour le processus.

La cause exacte du plantage n'est pas connue (aucune capture avant cette version) ; le plus probable vu la lenteur juste
avant : manque de mémoire ou blocage complet de l'app.

## A — Mesurer

| Élément | Détail |
|---|---|
| Serveur | Migration `20261120000034_rapports_lenteur` : `diagnostic_reports` (propriétaire seulement, 300 derniers), `report_diagnostic` (membres ; 30/h et 12 automatiques/jour par appareil ; 800 Ko au plus), `household_members.app_version` + `report_app_version`. |
| Surveillance (`diag/PerfMonitor.kt`, `PerfData.kt`) | Images de l'écran (durée de chaque image), blocages du fil de l'écran > 0,7 s **avec la pile** (endroit du code), délai et traitement de chaque touche, mémoire toutes les 30 s (tas, mémoire libre, ramasse-miettes, fils), réseau par type (fournisseur avec seulement `action`, serveur, sport, fiches, images — jamais d'adresse), travaux de fond et leur durée, événements (écrans, mémoire réclamée, arrière-plan). |
| Bouton | « Signaler une lenteur » dans la barre du haut de chaque section, à droite de la pastille du profil : « Rapport n° X envoyé ✓ », ou « gardé, envoyé plus tard ». |
| Automatique | Écran figé ≥ 2,5 s ou touche en retard ≥ 2 s : rapport automatique (1 par 10 min, 8 par jour). |
| Plantages | Rapport écrit sur la télé avant la fermeture (pile de l'erreur ; message seulement sans adresse, identifiants retirés), envoyé au lancement suivant. Android 11+ : fermetures précédentes (« ne répond pas » avec la pile du fil de l'écran, manque de mémoire…). |
| Page iPhone | « Mes appareils » : version de l'app de chaque appareil. |

Lire les rapports : `select id, created_at, device_name, kind, app_version, summary from diagnostic_reports order by id desc;`
puis `select data from diagnostic_reports where id = …;`.

## B — Allègements

1. **Optimiseur R8** (`isMinifyEnabled = true`, `proguard-rules.pro`) : sans renommage (piles lisibles), notre code et
   les bibliothèques réseau/serveur/sérialisation/base gardés entiers. APK 14,8 Mo → ~5,4 Mo. Vérifié : toutes les
   classes chargées par service (coroutines, Ktor, Coil, cryptographie) sont présentes. **Pas d'émulateur ici : à
   confirmer sur la télé** (démarrage, Direct, Séries, Films, Sport, lecture).
2. `android:largeHeap="true"`.
3. **Remplisseur de la recherche** : il vérifiait chaque catégorie de films et de séries une par une, plusieurs fois par
   seconde et **sans fin** (des centaines de lectures de la base par seconde, devant celles de l'écran). Maintenant : une
   lecture par section (`freshCategoryIds`), fils de priorité basse, et aucune demande au fournisseur tant qu'on
   navigue (2 s sans touche, 30 s au plus).
4. **Liste du Direct gardée entre deux lancements** (jusqu'au redémarrage de la télé, même compte) : plus de mise à jour
   complète à chaque relance de l'app.
5. **Index des versions gardé sur la télé** (`FileVersionIndexDisk`, relu si rien n'a changé), un seul calcul à la fois,
   en priorité basse.
6. Films et séries rafraîchis tous les **3 jours** (24 h avant) ; titres pour l'iPhone sans les résumés ; Sport préparé
   au démarrage seulement pour les profils qui s'ouvrent dessus.

## Tests

Serveur 266 (5 nouveaux), partagés 392, télé 591 (15 nouveaux : mesures et rapport sans secret, attente de la
télécommande, index gardé, bouton à la télécommande qui fabrique un vrai rapport), page 281 (1 nouveau).
