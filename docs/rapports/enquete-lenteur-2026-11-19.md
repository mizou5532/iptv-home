# Enquête « l'app est lente » (19 nov. 2026)

Signalement du propriétaire (0.39.0) : navigation souvent très lente, Sport très lent, chargements longs ; « des fois ça
marche bien, la plupart du temps c'est lent ». Il a aussi donné le projet à un ami (sa propre télé, ses propres
identifiants). Demande : tout examiner, y compris plusieurs personnes connectées en même temps, puis proposer.

Aucun code modifié pendant l'enquête. Aucune donnée sensible lue (identifiants : seulement la date de dernière
modification).

## 1. Ce qui est vérifié (faits)

| Sujet | Constat |
|---|---|
| Serveur de la maison | Rapide : 30 à 80 ms en moyenne par demande, ~4 000 demandes par jour, aucune rafale d'erreurs. Pas la cause. |
| Ami | **Aucun effet chez le propriétaire** : le serveur n'accepte qu'une seule maison (1 maison), aucun appareil ajouté depuis le 17 nov., identifiants IPTV inchangés depuis le 14 nov. Sa télé (Sony BRAVIA) a seulement téléchargé l'APK le 18 nov. à 19 h 32 ; aucune demande en boucle. Chez lui, l'app ne peut pas créer sa propre maison sur ce serveur. |
| Appareils de la maison | 4 : TV Salon (TCL Smart TV Pro), Maha (Fire TV Stick 4K 1re génération, 1,5 Go de mémoire), deux téléphones Samsung (noms « Macbook hamza », « Ordi windows »). Les versions installées ne sont pas connues du serveur. |
| Abonnement IPTV | 1 vidéo à la fois (`max_connections = 1`). |
| Taille du catalogue | 56 812 chaînes chez le fournisseur, dont 50 325 dans 811 catégories masquées pour tout le monde (jamais téléchargées par la télé : vérifié dans le code) ; 6 487 chaînes visibles (105 catégories) ; films 149 catégories visibles (295 masquées), séries 87 (297 masquées). |
| Construction de l'app | Publiée **sans l'optimiseur R8** (`isMinifyEnabled = false`) : Compose est nettement plus lent sans lui. Pas de `largeHeap`. |
| Mesures (serveur rapide, 4 cœurs) | Regroupement des versions : 20 000 titres 0,11 s, 60 000 titres 0,36 s ; index de recherche : 0,05 s / 0,19 s. Une télé est 5 à 10 fois plus lente : **2 à 4 s de calcul plein** par regroupement sur un gros catalogue. |

## 2. Plusieurs personnes en même temps

- **Serveur** : non, il est très loin d'être chargé.
- **Fournisseur, vidéos** : l'abonnement n'accepte qu'une vidéo. Deux vidéos en même temps → le fournisseur coupe ou
  refuse → « Connexion… » interminable, reconnexions : ça ressemble à de la lenteur. Depuis la 0.38, la fenêtre
  « Quelqu'un regarde déjà » l'évite, **seulement si tous les appareils sont en 0.38 ou plus**.
- **Fournisseur, catalogue** : chaque appareil télécharge tout seul, avec le même compte et la même connexion internet :
  chaînes (105 demandes toutes les 6 h), films et séries (~236 catégories **retéléchargées entièrement toutes les 24 h**),
  Sport (~20 catégories), guide des programmes (chaque ligne visible, chaque minute). 3 ou 4 appareils = 3 ou 4 fois.
  Beaucoup de fournisseurs ralentissent (ou refusent un moment) un compte qui fait trop de demandes : « des fois rapide,
  des fois lent » correspond à ce cas. À confirmer par des mesures.
- **Internet de la maison** : une vidéo 4K (25 Mb/s) + des téléchargements de catalogue sur les autres appareils
  partagent la même ligne.

## 3. Travail en arrière-plan sur la télé (pendant qu'on navigue)

1. **Remplissage de la recherche** : toutes les catégories de films et de séries visibles, retéléchargées et réécrites
   en base toutes les 24 h, par appareil. Pendant ce temps : regroupement des versions refait au plus une fois par
   minute (2 à 4 s de calcul par type sur la télé) et index de recherche.
2. **Mise à jour des chaînes du Direct** : 105 catégories, toutes les 6 h par lancement de l'app.
3. **Sport préparé dès l'allumage** (0.39) : pour tous les profils qui ont la section Sport, donc tous par défaut.
4. **Titres envoyés à la page iPhone** (une fois par jour) : lit tous les films **avec leurs résumés** en mémoire d'un
   coup (pic de mémoire).
5. Vérification du compte fournisseur (10 min), recherche de mise à jour (6 h), guide (chaque minute par ligne).
6. **Démarrage à froid** : Android TV ferme souvent les apps en arrière-plan (mémoire limitée). À chaque relance, tout
   ce qui est en mémoire est recalculé (regroupement des chaînes, versions, recherche, Sport) : les premières minutes
   sont lentes. 38 démarrages de l'app le 18 nov. (dont 3 mises à jour).

## 4. Ce qu'on ne sait pas encore (et qui manque pour conclure)

Aucune mesure n'est prise **sur la télé au moment où ça rame** : processeur occupé ou non, mémoire presque pleine,
blocages du fil de l'écran (et à quel endroit du code), délai des touches de la télécommande, réponses lentes du
fournisseur, travail de fond en cours. Sans ça, on corrige à l'aveugle.

## 5. Ce qu'on peut mettre en place (proposition)

**A. Mesurer (indispensable)** : bouton « Signaler une lenteur » (barre du haut de chaque section) + surveillance
automatique (blocage de l'écran de plus de 1 s avec l'endroit exact du code, images saccadées, délai des touches,
mémoire, réponses lentes par type — sans jamais d'adresse ni d'identifiant —, travail de fond en cours, version,
appareil). Envoi sur le serveur ; envoi automatique en cas de gros blocage (limité). Version de chaque appareil visible.

**B. Gains probables tout de suite**
1. Activer l'optimiseur R8 (gain connu pour Compose : navigation plus fluide) — avec tests de démarrage.
2. `largeHeap` (plus de mémoire pour l'app) et cache d'images ajusté.
3. Travail de fond « poli » : priorité basse, mis en pause pendant qu'on navigue, un seul calcul lourd à la fois.
4. Garder sur la télé les calculs entre deux lancements (versions, recherche) : démarrages à froid rapides.
5. Films et séries rafraîchis moins souvent (tous les 3 jours, ou seulement ce qui change) ; titres pour l'iPhone sans
   les résumés ; Sport préparé seulement pour le profil qui s'ouvre dessus.

**C. Plusieurs appareils** : vérifier que tous sont à jour (version affichée sur la page iPhone) ; plus tard, un seul
appareil télécharge le catalogue et les autres le reçoivent du serveur (moins de demandes au fournisseur).

**D. Ami** : pour qu'il ait sa propre installation, il faut soit son propre serveur (copie du projet), soit permettre
plusieurs maisons sur le même serveur (gros chantier de sécurité). À décider plus tard.
