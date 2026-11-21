# Serveur maison : préparer le catalogue à la place de la télé — document de conception

*Date : 2026-11-20 · Statut : brouillon à valider par le propriétaire*
*Approche choisie par le propriétaire : **C, « le mélange »** (voir §5).*

## 1. Intention

Les enquêtes du 19 nov. (`docs/rapports/enquete-lenteur-2026-11-19.md`, `lenteur-0-40.md`, `lenteur-0-40-1.md`)
montrent que **la lenteur et les plantages de l'app télé viennent surtout de la mémoire**. La télé (TCL, 2,4 Go ;
Fire Stick « Maha », 1,5 Go) télécharge et calcule seule un catalogue énorme : 47 000 films, 13 000 séries et
56 000 chaînes (6 500 visibles). Il en résulte 17 à 27 s de « ménage de mémoire » juste après le lancement, des
saccades, et deux fermetures par manque de mémoire (323 et 391 Mo), dont celle du match des Canadiens. Chaque appareil
refait aussi tout le travail de son côté : le fournisseur reçoit donc les mêmes demandes 4 fois.

Le propriétaire a maintenant un **serveur à la maison** : un MacBook Intel sous Ubuntu 26.04, i5-1030NG7 4 cœurs /
8 fils, 7 Go de mémoire, 203 Go libres, avec Docker. Il est branché en Wi-Fi sur le même réseau que la TCL et le Fire
Stick (192.168.2.81) et aussi joignable par Tailscale. Il est 5 à 10 fois plus rapide que la télé pour ces calculs.

**But :** le serveur fait les courses chez le fournisseur **une fois pour toute la maison** et fait **le travail
lourd**. La télé ne reçoit que ce qu'elle affiche, déjà prêt, et **n'est plus jamais à court de mémoire à cause du
catalogue**. C'est le « point C » déjà prévu dans l'enquête (« un seul appareil télécharge le catalogue, les autres le
reçoivent »).

## 2. Objectifs, non-objectifs, anti-objectifs

**Objectifs**
1. La télé ne télécharge plus le catalogue (chaînes, films, séries, guide) chez le fournisseur quand le serveur répond.
2. La télé ne fait plus les calculs lourds : regroupement des copies de chaînes, index des versions, index de
   recherche, calcul du Sport, publication de la copie du catalogue pour l'iPhone.
3. La mémoire de la télé reste basse et stable, démarrage à froid compris.
4. Le fournisseur reçoit les demandes de catalogue **une seule fois** pour toute la maison.
5. **Mêmes résultats qu'aujourd'hui** : mêmes regroupements, mêmes versions, mêmes résultats de recherche, mêmes
   matchs. Pour y arriver, le serveur utilise **le même code Kotlin** (`apps/shared`, cible `jvm()` déjà présente).

**Mesure du succès** (avec les rapports de lenteur déjà en place, `diag/PerfMonitor.kt`) : sur la TCL, après un
démarrage à froid, le Direct affiche ses catégories en moins de 3 s ; le « ménage de mémoire » reste sous 3 s au total
pendant les 2 premières minutes (contre 17 à 27 s) ; aucune fermeture par manque de mémoire pendant une semaine
d'usage normal ; une seule mise à jour du catalogue chez le fournisseur par période pour toute la maison.

**Non-objectifs** (pour ce chantier)
- Faire passer la vidéo par le serveur : la vidéo va toujours directement du fournisseur à la télé.
- Remplacer Supabase : il reste la source des profils, réglages, progression, favoris et « qui regarde ».
- Préparer les images (idée 2) et lire sur iPhone ou ordinateur (idée 3) : ce seront des chantiers à part.
- Accès depuis l'extérieur de la maison, et plusieurs maisons (l'ami) : non.
- App ordinateur : non concernée pour l'instant. Elle pourra profiter du serveur plus tard.

**Anti-objectifs** (échec même si « ça marche »)
- **Une télé qui ne marche plus quand le serveur est éteint**, en panne ou en cours de mise à jour.
- Des identifiants IPTV qui apparaissent dans un journal, un fichier lisible par d'autres, une réponse du serveur, la
  conversation ou le dépôt (règle absolue n° 1).
- Télécharger une catégorie masquée « pour tout le monde » (règle absolue n° 7).
- Des résultats différents de ceux d'aujourd'hui (regroupements, recherche, Sport).
- Contourner la limite d'une connexion vidéo du fournisseur.
- N'importe quel appareil du réseau Wi-Fi qui pourrait lire le catalogue sans être un appareil de la maison.

## 3. Contraintes

- **Fournisseur** : Xtream, en http seulement, `max_connections = 1` (pour les vidéos). L'API accepte les centres de
  données. Le serveur, lui, est sur la connexion de la maison.
- **Règles absolues de `PASSATION.md` §2**, toutes applicables. En particulier : pas de clé `service_role`, pas de
  foyer créé depuis des tests, et toute publication ou nouveau service en ligne seulement avec l'accord du
  propriétaire. Une migration de production est approuvée par lui dans l'invite Supabase.
- **Serveur** : Docker, services dans `/home/volcan/services/<nom>/`, copie des fichiers de configuration dans
  `~/serveur-maison/<nom>/` sur le Mac. `sudo` n'est pas utilisable par une IA : le propriétaire tape son mot de passe
  dans un onglet préparé. Le pare-feu (ufw) est installé mais **éteint** au 2026-11-20.
- **Wi-Fi seulement** côté serveur (pas d'Ethernet) : les réponses vers la télé doivent rester petites (compressées,
  par écran, pas un gros fichier à chaque fois).
- **Le serveur peut être éteint** (coupure de courant longue, mise à jour, panne) : la télé doit s'en rendre compte
  vite et continuer seule.
- **Le propriétaire n'est pas développeur** : l'installation sur le serveur et l'appairage doivent se faire en
  quelques gestes guidés.

## 4. Ce qui existe aujourd'hui (et ce que le chantier touche)

- `apps/shared` (Kotlin Multiplatform, cibles `jvm()` et `androidTarget()`) contient déjà le client Xtream
  (`xtream/XtreamApi.kt`), le cache SQLDelight (`catalog/`, `movies/`, `series/`), le regroupement des chaînes
  (`groups/LiveGroups.kt`), les versions (`versions/Versions.kt`), la recherche (`search/SearchEngine.kt`,
  `CatalogFiller.kt`), le Sport (`sport/`) et la synchro Supabase (`sync/SyncClient.kt`).
- La télé (`apps/tv`) lance les mises à jour (`live/LiveRefresher.kt`, `LiveSessions.kt`, `CatalogFiller` dans
  `MainActivity.kt`), garde l'index des versions (`feature/VersionIndexes`, `FileVersionIndexDisk`), fait la recherche
  (`search/SearchRuntime.kt`), le Sport (`sport/`) et la publication pour l'iPhone (`search/CatalogTitlesPublisher.kt`,
  `publishLiveCategories`, `publishSeriesCategories`).
- **Attention (relecture du 2026-11-20) :** une bonne partie de la logique à réutiliser vit encore dans `apps/tv`
  et non dans `apps/shared` : `groupSourceIndex`, `cachedAutoKey`, `categoryCountries`, `groupSearchItems`,
  `OtherTitles`, `VersionIndexes`, la plus grande partie du Sport, `CatalogTitlesPublisher`, `LiveRefresher`. **La
  première tâche de chaque étape est de déplacer ce code vers `apps/shared`, sans changer son comportement** (tests
  existants verts avant et après), pour que le serveur et la télé utilisent exactement le même code.
- Les appareils rejoignent le foyer par **appairage** : un utilisateur anonyme Supabase, `create_pairing_code`, puis
  validation sur la page iPhone (`approve_pairing`). Ils lisent ensuite les identifiants (`credentials`) et les
  réglages (`globalHiddenCategories`, `profilePrefs`, `householdSettings`).

## 5. Approches considérées

| | Approche | Pour | Contre |
|---|---|---|---|
| A | **Plateau tout prêt** : le serveur envoie à la télé tout le catalogue préparé (base et index) une à deux fois par jour | Écrans presque inchangés ; marche serveur éteint avec la dernière copie | La télé charge encore en mémoire les gros index (recherche, versions) : seulement la moitié du problème réglée |
| B | **Serveur au comptoir** : la télé ne garde presque rien et demande chaque écran au serveur | Mémoire de la télé minimale | Presque tous les écrans à refaire ; serveur éteint = tout doit retomber sur l'ancien chemin ; plus gros chantier |
| **C — choisie** | **Mélange** : ce qui est léger et souvent affiché arrive tout prêt et est gardé sur la télé (comme A) ; ce qui est lourd en mémoire (recherche, versions, Sport) reste sur le serveur et la télé le lui demande (comme B) | Règle le vrai problème de mémoire sans refaire toute l'app ; serveur éteint : dernière copie pour les listes, code actuel en secours pour le reste | Un peu plus de travail que A ; deux chemins (serveur et secours) pour la recherche, les versions et le Sport |

Écarté aussi : faire passer la vidéo par le serveur (aucun gain de fluidité, et un détour), remplacer Supabase (rapide
et fiable, sans raison de changer).

**Choix du propriétaire : C**, parce que la cause des lenteurs, c'est la mémoire de la télé, et que C la règle sans
tout reconstruire.

## 6. La conception

### 6.1 Vue d'ensemble

```
Fournisseur Xtream ──(API, une fois pour la maison)──▶ SERVEUR MAISON (Docker, JVM, code apps/shared)
                                                        │  cache SQLite + calculs (groupes, versions, recherche, Sport)
                                                        │  publie la copie pour l'iPhone dans Supabase
Supabase ◀──(appareil appairé : identifiants, réglages)─┤
                                                        ▼  Wi-Fi de la maison (HTTP, réponses compressées)
                                                      TÉLÉ / FIRE STICK
                                                        │  garde une copie des listes (Direct, rangées)
                                                        │  demande recherche, versions, Sport au serveur
Fournisseur Xtream ──────────(vidéo, directe, comme aujourd'hui)──────────▶ TÉLÉ
```

### 6.2 Le serveur : un « appareil » de la maison
- Nouveau module **`apps/server`** (Kotlin JVM) qui dépend de `apps/shared`. *Mon choix :* serveur HTTP **Ktor**
  (déjà utilisé comme client dans l'app), base **SQLite via les mêmes schémas SQLDelight** que la télé.
- Il rejoint le foyer **comme une télé** : utilisateur anonyme Supabase, `create_pairing_code("Serveur maison")`. Le
  code s'affiche dans le journal du conteneur et sur une petite page `http://serveur:<port>/` ; le propriétaire le
  valide sur la page iPhone, comme pour une télé. **Personne ne tape d'identifiant IPTV** : le serveur les lit avec
  `credentials()`, comme la télé.
- La session Supabase et le cache sont gardés dans un volume Docker lisible seulement par le conteneur. Les
  identifiants ne sont **jamais écrits sur le disque** par notre code : ils sont relus dans Supabase au démarrage.
  Aucune adresse de flux ni aucun identifiant n'apparaît dans les journaux (même filtre que `diag/` côté télé).
- Il lit les réglages du foyer (catégories masquées pour tout le monde, copies regroupées à la main, comptes
  supplémentaires) et suit `prefs_rev` pour réagir aux changements faits sur l'iPhone.

### 6.3 Ce que le serveur fait
- **Mises à jour du catalogue**, mêmes rythmes qu'aujourd'hui : Direct toutes les 6 h, films et séries tous les
  3 jours, Sport selon ses règles actuelles, guide « maintenant / ensuite » (voir §8). Le code de téléchargement est
  celui d'aujourd'hui (`CatalogRepository.refreshLive`, `CatalogFiller`, etc.), donc les catégories masquées pour tout
  le monde ne sont jamais demandées.
- **Calculs** : regroupement des chaînes (`LiveGroups`), index des versions (`Versions.kt`), index de recherche
  (`SearchEngine`), Sport (`SportMatching`, `SportChannels`).
- **Publication pour l'iPhone** dans Supabase (`publishCatalogTitles`, `publishLiveCategories`,
  `publishSeriesCategories`) : le serveur la fait à la place de la télé. La télé ne publie plus quand le serveur est
  actif (fin du pic de mémoire « titres envoyés à la page iPhone »).

### 6.4 Ce que la télé reçoit tout prêt (partie « plateau »)
- **Direct** : la liste des catégories visibles, et pour chacune les chaînes **déjà regroupées**, avec leurs copies
  (identifiants de flux, noms, logos, `epg_channel_id`). La télé les écrit dans son cache SQLDelight actuel. Les écrans
  du Direct continuent de lire ce cache.
- **Films et séries** : catégories et rangées (titres, affiches, année…), dans le même cache qu'aujourd'hui.
- **La télé garde tout le catalogue visible sur son disque** (les ~46 000 films et ~12 000 séries visibles, pas
  seulement la vitrine), reçu du serveur au lieu du fournisseur. *Décision de Claude, à la relecture :* le problème
  de mémoire vient des index gardés en mémoire et des calculs en parallèle, pas de la base sur le disque. Garder tout
  sur le disque permet au secours (§6.7) d'être complet : recherche hors vitrine (`OtherTitles`), copies 4K/Dolby des
  versions, « Ma liste » et « Reprendre » continuent de marcher serveur éteint. Le serveur envoie seulement **ce qui a
  changé** depuis la dernière révision (quelques Mo compressés au premier remplissage, peu ensuite), et la télé écrit
  en priorité basse, jamais pendant qu'on navigue (même règle que le remplisseur actuel).
- Chaque section porte un **numéro de révision**. La télé demande « quoi de neuf depuis la révision N ? » et ne
  télécharge que si quelque chose a changé. Les réponses sont compressées (gzip) et découpées par section.
- **Le foyer d'un côté, le profil de l'autre** : le serveur applique ce qui vaut pour tout le monde (masqué pour tout
  le monde, copies regroupées à la main). La télé continue d'appliquer ce qui est propre au profil (favoris, ordre,
  masqué pour moi, playlists), comme aujourd'hui. C'est léger.
- Les adresses de lecture restent construites **sur la télé** (`XtreamUrls`) avec ses identifiants : le serveur ne
  renvoie jamais d'adresse de flux.

### 6.5 Ce que la télé demande au serveur (partie « comptoir »)
- **Recherche** : la télé envoie le texte tapé (et le profil) ; le serveur répond les résultats déjà classés (mêmes
  règles qu'aujourd'hui : accents, fautes, mots partiels, surnoms, éléments masqués exclus).
- **Versions** (langues, 4K, regroupement des versions d'un même titre) : demandées pour les titres affichés (une
  rangée, une fiche), ou incluses directement dans les rangées envoyées. *Choix laissé au constructeur.*
- **Sport** : la grille des matchs avec leurs chaînes déjà associées. La télé n'a plus à calculer.
- **Mémoire des chaînes du Sport, partagée par la maison** (*décision du propriétaire, 2026-11-20*). Aujourd'hui,
  chaque télé garde seule, par profil, les chaînes « apprises » et « préférées » pour chaque ligue ou équipe
  (`SportStore`, SharedPreferences, dans `apps/tv/.../sport/SportData.kt`). Elles passent **dans Supabase, par
  profil** (nouvelle table, migration à approuver par le propriétaire), comme les votes Sport déjà synchronisés. Ainsi,
  ce qu'une télé apprend profite à toutes, et cette mémoire reste disponible serveur éteint. Au premier lancement de
  la nouvelle version, chaque télé envoie sa mémoire locale. Deux télés qui ont appris des choses différentes sont
  **fusionnées** : la règle de fusion (la plus récente, ou la plus utilisée) est laissée au constructeur, mais rien
  ne doit être perdu sans raison. Le serveur lit cette mémoire pour classer les chaînes, et la télé l'utilise en
  secours.
- **Fiches et vérifications du fournisseur** : les fiches (`get_vod_info`, `get_series_info`, épisodes compris) et la
  vérification quotidienne des « nouveaux épisodes » (20 séries) passent par le serveur, qui les garde en cache pour
  toute la maison (étape 2). *Décision de Claude, à la relecture :* la vérification du compte toutes les 10 min
  (`userInfo`, `active_cons`) **reste sur chaque télé** : elle est minuscule et sert à « qui regarde ». La vérification
  des comptes supplémentaires (`ExtraAccountsCheck`) passe sur le serveur à l'étape 2.
- Si une demande « comptoir » ne répond pas dans le délai prévu (*mon choix : 1,5 s*), la télé bascule sur le secours
  (§6.7) pour cette fonction.

### 6.6 Trouver le serveur et s'y fier
- Au démarrage, le serveur enregistre son **adresse sur le réseau de la maison** dans Supabase (nouvelle donnée du
  foyer, par exemple `home_server(url, version, last_seen)` ; migration à approuver par le propriétaire). La télé la
  lit : aucun réglage à faire sur la télé. *Secours :* découverte sur le réseau local (mDNS) si l'adresse a changé.
- **Seuls les appareils de la maison** peuvent interroger le serveur, et **le jeton de session Supabase de la télé
  ne lui est jamais envoyé**. *Décision de Claude, à la relecture :* ce jeton permet de lire les identifiants IPTV dans
  Supabase. S'il passait en clair sur le Wi-Fi, quelqu'un qui écoute le réseau (un invité qui a le mot de passe du
  Wi-Fi, par exemple) pourrait les obtenir, ce qui enfreint la règle absolue n° 1. À la place :
  1. **Jeton propre au serveur, par appareil** : la télé obtient un jeton « serveur maison » par une fonction Supabase
     réservée aux membres du foyer (migration à approuver par le propriétaire). Ce jeton ne donne accès qu'au
     catalogue du serveur, jamais à Supabase ni aux identifiants. Il est révoqué quand l'appareil est retiré du foyer.
  2. **Liaison chiffrée (TLS)** avec un certificat créé par le serveur. Son empreinte est publiée dans la ligne
     `home_server` de Supabase, et la télé n'accepte que ce certificat-là. Personne sur le Wi-Fi ne peut lire ni
     imiter le serveur.
  Un autre appareil du Wi-Fi ne reçoit rien. Le serveur ne renvoie jamais d'identifiant ni d'adresse de flux.
- Le serveur n'écoute que sur le réseau de la maison (et Tailscale) : il n'est **pas** ouvert sur Internet. Quand le
  pare-feu sera allumé, seul son port sera autorisé depuis le réseau local.

### 6.7 Quand le serveur ne répond pas (secours)
- La télé garde **sa dernière copie** des listes (Direct, rangées) : la navigation marche sans le serveur.
- Pour la recherche, les versions et le Sport, le **code actuel de la télé** reprend le travail. Il n'est pas
  supprimé : il devient la roue de secours, lancé seulement si le serveur est absent.
- Si le serveur reste absent longtemps (*mon choix : plus de 24 h*, ou aucune copie encore reçue), la télé refait ses
  mises à jour elle-même, comme aujourd'hui.
- Le panneau de diagnostic de la télé et les rapports de lenteur indiquent « serveur : présent / absent / version ».
- Au retour du serveur, la télé reprend le chemin serveur sans relance.

### 6.8 Installation sur le serveur
- Conteneur Docker (*mon choix : image Java 21 « JRE » officielle, mémoire Java limitée à environ 2 Go*), redémarrage
  automatique (`unless-stopped`), dossier `/home/volcan/services/iptv-catalogue/` (copie dans
  `~/serveur-maison/iptv-catalogue/`).
- Le programme est construit sur le Mac (Gradle) puis copié sur le serveur. Une mise à jour = copier la nouvelle
  version et relancer le conteneur, sans perdre l'appairage ni le cache.
- Après installation : mise à jour de `~/services/CLAUDE.md` sur le serveur et de `PASSATION.md` dans ce dépôt.

### 6.9 Étapes (chacune publiée et essayée sur la TCL avant la suivante)
1. **Socle + Direct + guide** : module serveur, appairage, adresse dans Supabase, sécurité par jeton, mise à jour du
   Direct et regroupement côté serveur, réception côté télé, secours. C'est là que le démarrage souffre le plus.
2. **Films et séries + versions** : rangées prêtes, versions servies, publication iPhone déplacée sur le serveur.
3. **Recherche** sur le serveur.
4. **Sport** sur le serveur, avec la mémoire des chaînes partagée dans Supabase.

Chaque étape : tests (serveur, partagés, télé), relecture indépendante, version de l'app publiée, essai TCL,
comparaison des rapports de lenteur avant et après.

## 7. Décisions prises par le propriétaire
- Approche **C**.
- Tous les appareils (TCL, Fire Stick « Maha ») sont **dans la même maison** que le serveur.
- Si le serveur est éteint, la télé doit continuer de marcher (proposé par Claude, non contesté).
- Travail par étapes, avec essai sur la télé à chaque étape.
- La mémoire des chaînes du Sport est **partagée** entre les télés de la maison (§6.5).

### Décisions prises par Claude (à vérifier par le propriétaire)
- Serveur Ktor + SQLite (mêmes schémas SQLDelight), module `apps/server`, conteneur Java 21 limité à environ 2 Go.
- Appairage du serveur comme une télé (QR / code validé sur la page iPhone) : aucun identifiant tapé.
- Jeton propre au serveur par appareil et liaison chiffrée : le jeton Supabase de la télé ne circule jamais sur le
  Wi-Fi.
- La télé garde tout le catalogue visible sur son disque, mis à jour par différences depuis le serveur.
- Fiches, « nouveaux épisodes » et comptes supplémentaires passent par le serveur ; la vérification du compte toutes
  les 10 min reste sur chaque télé.
- Délai de 1,5 s avant le secours pour une demande au serveur ; mises à jour refaites par la télé après 24 h sans
  serveur.
- Étape 1 = Direct et guide, parce que c'est là que le démarrage souffre le plus.

## 8. Laissé au constructeur
- Format exact des réponses (JSON compressé ou binaire), découpage et pagination.
- Versions incluses dans les rangées ou demandées à part.
- **Guide des programmes** : aujourd'hui demandé « par ligne visible, chaque minute ». Le serveur peut le récupérer
  pour toutes les chaînes visibles et répondre « maintenant / ensuite » pour une liste de chaînes. Le niveau de détail
  et le rythme sont au choix du constructeur, à condition de ne pas demander plus au fournisseur qu'aujourd'hui.
- Organisation interne du module `apps/server`, tests, journaux (sans aucun secret).
- Détection d'un serveur « lent » (et pas seulement absent).
- Comptes supplémentaires (`extraAccounts`) : le serveur utilise le compte principal pour le catalogue (mêmes
  numéros chez le même fournisseur), sauf contre-indication trouvée dans le code.
