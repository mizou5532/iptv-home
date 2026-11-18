# Navigation, état de l'app, alerte de panne, recherche par acteur — 0.36.0 (code 62), publiée le 18 nov. 2026

Demandes du propriétaire : suggestions 8, 9 et 11, et deux soucis de télécommande.

| Point | Ce qui est fait | Où |
|---|---|---|
| Recherche : la liste « descendait » | Cause : la liste des résultats suivait le titre affiché en haut ; quand une nouvelle lettre (ou le catalogue qui se remplit) faisait passer de meilleurs résultats devant, elle descendait avec lui et cachait les premiers. Désormais, tant qu'on est au clavier, la liste revient toujours en haut. Test qui échouait avant et passe après | `search/SearchScreen.kt`, `SearchFocusDpadTest` |
| Sélection qui va trop vite | Flèche maintenue : la télé répète l'appui ~20 fois/s ; ramené à ~10 déplacements/s. Un appui simple n'est jamais retardé. (Conséquence : avance/recul en maintenant une flèche dans un film, deux fois moins rapide.) | `nav/KeyRepeatGate.kt`, `MainActivity.dispatchKeyEvent` |
| 8 · État de l'app | Bouton « État de l'app » sur « Qui regarde ? » : internet, vitesse (3 Mo mesurés : assez pour la 4K ou non), serveur de la maison, fournisseur (répond, abonnement, jours restants, vidéos en cours, message), comptes supplémentaires, chaînes du Direct, version. Pastilles vert / orange / rouge, « Refaire le bilan » | `health/Health.kt`, `HealthScreen.kt`, `IptvApp.healthCheck` |
| 9 · Alerte de panne | 3 vidéos qui échouent en 2 min sans réussite entre-temps → vérification : plus d'internet → « vérifie le Wi-Fi ou le modem » ; fournisseur muet alors qu'internet marche → « ce n'est ni ta télé ni ta connexion ». Une chaîne morte seule ne déclenche rien ; au plus un message toutes les 5 min ; annulé si une vidéo démarre | `health/ProviderWatch.kt`, `ExoPlayerPlayer` (`PlaybackSignals`), `App.kt` |
| 11 · Recherche par acteur ou réalisateur | Dans Films et Séries, un nom tapé (4 lettres et plus, après une demi-seconde de pause) : la fonction serveur `media-meta` trouve la personne sur TMDB et sa filmographie ; la télé ajoute sous les résultats les titres de son catalogue (même titre, même année à un an près), avec « Avec Denzel Washington » ou « Réalisé par … ». Masqué gagne | `media-meta` (`person`, `personPick`), `shared/search/PersonSearch.kt`, `SearchViewModel` |

Relecture indépendante : corrigés — panne TMDB passagère gardée comme « personne introuvable » ; alerte qui pouvait s'afficher sur une vidéo qui venait de démarrer ; vérification relancée à chaque chaîne morte ; mesure de vitesse qui aurait pu lire tout le fichier.

Tests : 221 serveur, 369 partagés, 529 télé, 271 page, 0 échec ; analyse Android 0 erreur. Fonction `media-meta` version 8 déployée (accès toujours réservé aux membres, vérifié : 401 sans session). APK SHA-256 `76a7a622cff9d3daef32ac4c1150705549303f37fd9e61e37b0f314cda99ed0e`, 14 764 479 octets, `versionCode 62`, `minSdkVersion 25`, certificat attendu ; page inchangée ; Vercel `dpl_BQSFvZfwmPbhgAGX7ZYw8xwMzoYq` ; règles stockage : les 4 `avatars_*`.

**Essai TCL** : chercher un film en tapant lentement 6-7 lettres (la liste reste en haut) ; maintenir une flèche dans le Direct (défilement plus lent et suivable) ; « Qui regarde ? » → Bas → « État de l'app » ; dans Films → Rechercher, taper un acteur (« denzel », « di caprio »).
