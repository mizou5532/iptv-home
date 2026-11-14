# Player IPTV familial — document de conception

*Date : 2026-11-14 · Statut : brouillon à valider*
*Maquettes : https://claude.ai/artifact/F7jLeEDSvJYRa69UMLh5Y4 (privées)*

## 1. Intention

Remplacer le player IPTV actuel de la maison (IPTV Player Pro sur la télé du salon) par un player à nous : **rapide, fluide, bien organisé et personnalisable**, avec **la progression, les favoris et les listes synchronisés** entre tous les appareils, et **sans abonnement à payer pour le player**.

Ce qui ne va pas aujourd'hui (dans les mots du propriétaire) :
- c'est lent et pas fluide ; parfois il abandonne avant de trouver son match ;
- les listes de chaînes, films et séries sont très basiques ;
- la recherche est mauvaise ;
- plein de petits bugs ;
- impossible d'organiser : pas de playlists sur la télé, pas de tri, pas de masquage des centaines de chaînes/catégories inutiles fournies par le fournisseur ;
- l'app est vieille et pas belle, trop rigide ;
- abonnement annuel au player, et l'activation est « par appareil ».

Sur ordinateur, il utilise IPTV Player Zero (Windows/Mac), qu'il trouve très bon : affiches pour films et séries, reprise automatique et épisode suivant enchaîné, émission en cours et suivante pendant le direct. Notre site doit le remplacer.

## 2. Pour qui, et comment ils regardent

Un foyer, trois personnes, des usages très différents :

| Profil | Usage principal | Ce qui compte |
|---|---|---|
| **Le propriétaire** | Sport en direct (Canadiens, Barça, et parfois d'autres sports) | Trouver vite la chaîne qui diffuse le match et une copie qui marche ; zapping rapide |
| **Sa sœur** | Séries, souvent en journée | Reprendre exactement où elle s'est arrêtée, épisodes vus, favoris |
| **Son frère** | Films | Pareil que la sœur, pour les films |

Parcours typique du propriétaire aujourd'hui : il ouvre le player, va dans ses favoris (les chaînes qui diffusent souvent ses matchs), essaie chaque chaîne ; le fournisseur donne souvent **plusieurs copies de la même chaîne**, alors il essaie la 1re, puis la 2e, puis la 3e. Quand il ne sait pas quelle chaîne diffuse le match, il en essaie plein. Il ne fait pas confiance au guide des programmes du fournisseur.

Appareils : **télé TCL QM85 (65QM851G, Google TV)** au salon — l'appareil principal aujourd'hui ; **un PC Windows et un MacBook** (le propriétaire) ; **des iPhone** (tous les téléphones de la maison). La sœur (et probablement le frère) a son propre ordinateur et son iPhone : elle regarderait **souvent** ses séries dessus si c'était possible. Une seule personne regarde à la fois (limite du fournisseur), mais sur n'importe quel appareil.

## 3. Objectifs, non-objectifs, anti-objectifs

**Objectifs (V1)**
1. Une app télé nettement plus rapide et fluide que l'actuelle.
2. Profils avec chacun sa progression, ses favoris, ses listes, son accueil et son thème.
3. Organisation libre du catalogue : masquer, réordonner, trier, playlists.
4. Trouver un match en quelques secondes : chaînes en double regroupées avec bascule automatique, recherche instantanée, section « Mes équipes ».
5. Séries et films : reprise, épisodes vus, épisode suivant automatique, « Continuer à regarder ».
6. Tout synchronisé entre la télé et un site utilisable sur Windows, Mac et téléphone.
7. Aucun abonnement pour le player ; hébergement gratuit ou presque.
8. Beau et moderne, avec trois thèmes au choix : **sombre** (celui des maquettes), **clair**, **fun**.

**Non-objectifs (V1)** — confirmés par le propriétaire
- Enregistrement : non.
- Regarder deux flux en même temps (multi-écran) : non.
- Contrôle parental : non.
- Téléchargement pour regarder hors ligne : **plus tard**, seulement pour films et séries.
- Pas de publication sur le Play Store / App Store : l'app est pour la famille.
- Pas d'app native iPhone / Windows / Mac : le site couvre ces appareils.

**Anti-objectifs** (ce qui ferait échouer le projet même si « ça marche »)
- Une app télé aussi lente ou saccadée que l'actuelle, ou qui le devient avec un gros catalogue.
- Une recherche qui ne trouve pas « canadiens » quand on tape « canadien » ou « canadi ».
- Perdre la progression de la sœur, ou la mélanger avec celle d'un autre profil.
- Une app rigide où l'on ne peut pas réorganiser ce qu'on voit.
- Contourner une limite imposée par le fournisseur (verrou par appareil, nombre de connexions).

## 4. Contraintes

- **Fournisseur : compte Xtream** (adresse du serveur + nom d'utilisateur + mot de passe). Le compte indique le nombre de connexions simultanées autorisées ; l'app le respecte.
- **Télé : Google TV (Android TV).** L'app s'installe hors Play Store (fichier APK, via l'app « Downloader » ou ADB). Elle doit aussi tourner sur un Fire TV Stick 4K Max (Fire OS) si un jour il en achète un. Les Fire TV sous Vega OS ne sont pas visés.
- **Navigation à la télécommande** (D-pad + OK + retour + micro) : chaque écran doit être utilisable sans souris ni écran tactile.
- **Lecture dans le navigateur** : beaucoup de serveurs IPTV sont en `http` (bloqué depuis un site `https`), n'autorisent pas les requêtes venant d'un site (CORS), et certains formats (MKV, son AC3/DTS, flux MPEG-TS) ne se lisent pas partout dans un navigateur. À vérifier avec son fournisseur avant de promettre la lecture sur le site.
- **Coût** : pas d'abonnement pour le player. Les services gratuits sont la cible ; quelques euros par mois seulement si un relais vidéo s'avère indispensable, et seulement avec son accord.
- **Connexions : une seule à la fois**, utilisable depuis n'importe quel appareil (dit par le propriétaire). Conséquences : l'aperçu et le plein écran partagent le même flux ; la bascule automatique **ferme** une source avant d'ouvrir la suivante ; « Prendre la main ? » est un cas normal, pas une exception.
- **Identifiants Xtream** : stockés une fois dans le compte du foyer, accessibles seulement aux appareils connectés à ce compte, jamais affichés à l'écran après saisie. *Mon choix :* on accepte qu'ils figurent dans les adresses de lecture (c'est le fonctionnement normal de Xtream, y compris pour « Ouvrir dans VLC ») ; pas de serveur de jetons.
- **Accès pendant le développement** — *mon choix :* les identifiants ne passent jamais par la conversation. Les vérifications avec le vrai fournisseur se font via une page de test et des versions de l'app que le propriétaire ouvre/installe lui-même, en saisissant ses identifiants sur son appareil ; le reste se développe avec des données fictives.
- **Données de démonstration** dans les maquettes (matchs, chaînes, séries) : inventées.

## 5. Approches considérées

| | Approche | Pour | Contre |
|---|---|---|---|
| **A — choisie** | App Android TV native + site web responsive + petit service de synchro | Le plus rapide et fluide sur la télé (moteur vidéo Media3/ExoPlayer, Dolby Vision/Atmos, navigation télécommande native) ; rien à installer sur ordis et téléphone | Deux interfaces à construire (télé et site) ; lecture sur le site limitée par le navigateur |
| B | Une seule app multiplateforme (Flutter) pour télé, Windows, Mac, Android | Un seul code ; lecture de tous formats sur ordi (mpv) | Moins fluide à la télécommande et vidéo moins bien gérée sur la télé — la priorité n°1 ; app à installer et mettre à jour sur chaque ordi ; iPhone exigerait quand même un site |
| C — écartée | Tout en site web, y compris sur la télé (site emballé dans une app) | Le plus simple | Exactement ce qui rend les players actuels lents ; limites vidéo du navigateur sur la télé |

Choix du propriétaire : **A**, parce que la fluidité sur la télé est la raison n°1 du projet. Une option Jellyfin/Plex/Channels a aussi été écartée en amont : bonne synchro mais intégration Xtream bricolée et pas faite pour zapper vite.

## 6. Ce que fait le produit (V1)

### 6.1 Compte du foyer et profils
- Un **compte foyer** (créé par le propriétaire) qui contient les identifiants Xtream, les profils et toutes les préférences.
- **Profils** : un par personne (au départ « Toi », « Sœur », « Frère » ; noms et couleurs modifiables), ajout/suppression. **Pas de code PIN.**
- Écran **« Qui regarde ? »** au démarrage (maquette 1). *Ma proposition :* mémoriser le dernier profil et le présélectionner.
- Chaque profil a : favoris, playlists, progression, éléments masqués « pour moi », ordre de tri, thème, équipes suivies, et un **écran d'accueil adapté à son usage** (sport en direct / séries / films ; maquettes 2 et 5).
- **Connecter la télé au compte** — *ma proposition :* la télé affiche un code court et un QR code ; on le valide depuis le site sur le téléphone. Pas de saisie de mot de passe à la télécommande.

### 6.2 Catalogue (source Xtream)
- Les identifiants sont saisis une fois (sur le site ou la télé) ; chaque appareil les récupère via le compte.
- **Catalogue gardé en local sur chaque appareil** et mis à jour en arrière-plan ; la navigation n'attend jamais le fournisseur.
- Chargement **par catégorie** : une catégorie masquée « pour tout le monde » n'est **jamais téléchargée** ni gardée en mémoire.
- Films et séries avec affiches, infos, saisons et épisodes tels que fournis par Xtream.
- Guide des programmes (EPG) : au minimum « maintenant / ensuite » par chaîne.

### 6.3 Direct (maquette 3)
- Colonne catégories, liste des chaînes, aperçu à droite (taille réglable : aucun / petit / grand), émission en cours avec progression et émission suivante.
- **Copies en double regroupées** : une seule entrée par chaîne, avec « N sources » ; réglage pour tout montrer à la place.
- **Bascule automatique** : si la source choisie ne donne pas d'image en ~2–3 s, passage à la suivante, avec un message discret (« source 1 ne répond pas → source 2 ») ; l'app retient la dernière source qui a marché.
- Appui long sur une chaîne : ajouter aux favoris / à une playlist, masquer.
- Zapping haut/bas pendant la lecture plein écran.

### 6.4 Sport : « Mes équipes » et « Ce soir » (maquette 2)
- Le profil suit des équipes (ex. Canadiens, FC Barcelone).
- **Couverture V1 :** **LNH** (Canadiens), **FC Barcelone dans toutes ses compétitions** (Liga, Ligue des champions, Coupe du Roi, Supercoupe…), **NFL**. **NBA** : souhaitable, à ajouter si la source le permet sans effort majeur. « Ce soir » couvre ces mêmes ligues.
- Son accueil affiche les **prochains matchs et ceux en cours**, l'heure, et **les chaînes qui les diffusent au Canada**, d'après une **source sportive externe fiable** plutôt que le guide du fournisseur.
- L'app **relie le nom du diffuseur** (« RDS », « TVA Sports », « TSN 2 »…) aux chaînes du fournisseur ; un clic lance le match avec la bascule automatique.
- Page **« Ce soir »** : tous les matchs du jour, pour les sports qu'il ne suit pas d'habitude.
- Rappel optionnel avant un match (« Me rappeler »).
- **Résultat de la vérification (2026-11-14, `docs/rapports/source-sport.md`)** :
  - **LNH : complet.** L'API officielle de la LNH donne les diffuseurs canadiens de chaque match (avec leur pays) → « Mes équipes » tel que décrit.
  - **FC Barcelone, NFL, NBA : version réduite.** Aucune source gratuite trouvée avec les diffuseurs canadiens (ESPN ne donne que les américains). « Mes équipes » affiche les matchs (date, heure, compétition) et lance la **recherche du match dans le guide et les noms de chaînes du fournisseur**, plus ses chaînes favorites. Une autre source (ou une table de règles pour la NFL) reste à chercher au plan 7.

### 6.4 bis Films et séries : principe d'interface (demandé par le propriétaire)
- **Films et Séries sont deux sections séparées**, chacune avec sa propre navigation et son propre accueil.
- Interface **soignée, style grande plateforme de streaming** : grandes affiches et images de fond quand le fournisseur les donne, rangées thématiques, fiches détaillées propres, hiérarchie claire. L'effort porte sur **le design** (mise en page, typo, espacements, qualité des visuels), pas sur les effets.
- **Pas d'effets lourds** : pas de bandes-annonces en lecture automatique, pas de flous ni d'animations coûteuses, pas de vidéo en fond. Transitions courtes et simples seulement. La fluidité passe avant.
- Inspiration de style uniquement : on ne copie ni le logo, ni les couleurs, ni la mise en page exacte d'une plateforme existante.

### 6.5 Séries (maquettes 5 et 6)
- Accueil séries : grand bloc **« Reprendre »** (série, saison, épisode, temps restant), rangée **« Continuer à regarder »**, **« Nouveaux épisodes dans tes séries »**, **« Ma liste »**.
- Fiche série : saisons (avec « saison vue »), épisodes cochés quand vus, épisode en cours avec sa progression, « Marquer la saison vue », « Dans ma liste ».
- **Lecture automatique de l'épisode suivant.**
- *Ma proposition :* un épisode est « vu » à partir de ~90 % ou du générique de fin ; la position est sauvegardée régulièrement pendant la lecture et à l'arrêt.

### 6.6 Films
- Section à part, avec son propre accueil (principe §6.4 bis) : grande mise en avant en haut, rangées « Continuer à regarder », « Ma liste », « Ajoutés récemment », puis rangées par catégorie du fournisseur (dans l'ordre choisi, sans les catégories masquées).
- Fiche film : grande image, titre, année, durée, genre, résumé, note si fournie ; « Lire » / « Reprendre » et « Ma liste ».
- Reprise, films vus, progression : même logique que les séries.

### 6.7 Recherche (maquette 4)
- **Résultats instantanés pendant la frappe**, sur chaînes, programmes du guide, films et séries, avec filtres (Tout / Direct / Programmes / Séries / Films).
- **Tolère fautes, accents et mots partiels** (« canadi », « canadien », « barca », « montreal »). *Ma proposition :* alias pour les surnoms courants (« habs » → Canadiens).
- **Recherche vocale** avec la télécommande Google TV.
- Les éléments masqués n'apparaissent pas.

### 6.8 Organisation et personnalisation (maquette 7)
- Masquer / réafficher catégories, chaînes, films, séries — **pour tout le monde** (le propriétaire) ou **seulement pour mon profil**.
- Réordonner les catégories et les chaînes (mon ordre / A→Z / les plus regardées).
- Créer et ordonner des **playlists**, y compris sur la télé.
- Taille de l'aperçu, taille des affiches, regroupement des doublons.
- **Thèmes : sombre, clair, fun**, choisis par profil (maquettes 8 et 9 pour le fun). Les thèmes ne changent que couleurs, polices et formes ; aucun coût de performance notable.

### 6.9 Synchronisation et « qui regarde »
- Synchronisé entre appareils : profils, favoris, playlists, progression, éléments masqués, ordres, réglages, thème, équipes suivies.
- **Pas synchronisé** : le catalogue lui-même (chaque appareil le récupère chez le fournisseur et le garde en cache).
- **Présence** : l'app sait quel appareil lit quoi. Si on lance une lecture ailleurs alors que la limite de connexions est atteinte, elle propose « Le salon regarde *X* (Sœur). Prendre la main ? » ; si oui, l'autre appareil s'arrête proprement en gardant la progression.
- Fonctionne aussi si la synchro est momentanément indisponible : la télé continue de lire, et envoie les changements quand le service revient.

### 6.10 Site web (Windows, Mac, téléphone)
- Mêmes profils et données que la télé ; mise en page adaptée à l'écran (ordinateur et téléphone).
- Parcourir, chercher, gérer favoris, listes, masquage, réglages, comptes.
- **La lecture des séries et films sur le site est centrale**, pas un bonus : la sœur regarderait souvent sur son ordinateur et son iPhone. Le lecteur du site doit donc gérer la reprise, les épisodes vus, l'épisode suivant automatique et la présence (« qui regarde »), comme sur la télé.
- Le direct sur le site est secondaire (le propriétaire regarde le sport au salon).
- **iPhone (Safari)** : lecture native seulement pour les formats que Safari accepte (HLS, MP4 H.264/HEVC + AAC) ; mpegts.js n'y fonctionne pas. Rappels de match sur iPhone seulement si le site est ajouté à l'écran d'accueil.
- **Ce que le navigateur ne peut pas lire** : bouton « Ouvrir dans VLC » en dépannage (la progression n'est alors pas suivie). Si la vérification n°1 (§9) montre qu'une grande partie des films/séries ne se lit pas dans le navigateur, on présente au propriétaire les options **avant** de construire : petit relais/conversion vidéo (coût mensuel à chiffrer) ou petite app de bureau pour les ordis (l'iPhone resterait limité).
- Sert aussi à connecter la télé (code / QR).

## 7. Performance visée

*Mes propositions, à confirmer à l'usage :*
- Ouverture de l'app jusqu'à l'accueil : < 2 s quand le catalogue est déjà en cache.
- Démarrage d'une chaîne qui marche : ~1–2 s ; bascule vers la copie suivante après ~2–3 s sans image.
- Recherche : résultats affichés en < 100 ms par lettre tapée, même avec un catalogue de dizaines de milliers d'éléments.
- Navigation dans les listes et rangées : fluide, sans saccade, sur la TCL QM85.

## 8. Choix techniques (mes décisions, approuvables)

- **App télé** : Kotlin, Jetpack Compose pour TV, lecteur **Media3 (ExoPlayer)** ; base locale SQLite (Room) avec index plein texte pour la recherche ; mise à jour de l'app depuis un lien de téléchargement que l'app vérifie elle-même.
- **Site** : application web TypeScript (React ou équivalent), installable comme « app web » sur le téléphone ; lecture via hls.js / mpegts.js quand le flux le permet.
- **Synchro** : Supabase (base Postgres, comptes, temps réel, règles d'accès par foyer), offre gratuite.
- **Relais pour le site** : petit relais gratuit (ex. Cloudflare Worker) pour les appels de catalogue Xtream que le navigateur ne peut pas faire directement. **Pas de relais vidéo** sans accord explicite (coût en bande passante).
- **Hébergement du site** : offre gratuite (Cloudflare Pages, Netlify ou Vercel).

## 9. Risques et vérifications à faire en premier

1. **Lecture dans le navigateur avec son fournisseur** (Chrome/Edge sur Windows, Safari sur Mac et **iPhone**) : `http` ou `https` ? CORS ? Formats des films et épisodes (MP4/MKV, codecs vidéo et audio) ? → **critique**, puisque la sœur regarderait surtout là ; décide de ce que le site lit lui-même vs « Ouvrir dans VLC », et si un relais vidéo serait nécessaire.
2. **Source sportive** : trouver une source gratuite et fiable avec les diffuseurs canadiens pour la LNH, le FC Barcelone (toutes compétitions) et la NFL (NBA en bonus) et tester le rapprochement avec ses noms de chaînes → décide si « Mes équipes » est en V1 tel que décrit ou en version réduite (ex. horaires + recherche dans ses chaînes).
3. **Limite de connexions** : la bascule automatique et l'aperçu ne doivent jamais ouvrir plus de connexions que le compte n'en autorise (risque de coupure ou de signalement par le fournisseur).
4. **Taille du catalogue et du guide** : certains fournisseurs ont des dizaines de milliers d'éléments et un guide très lourd → chargement par catégorie et guide limité au « maintenant / ensuite » sur la télé.
5. **Offre gratuite de Supabase** : un projet gratuit inutilisé pendant une semaine est mis en pause → l'usage quotidien suffit normalement ; prévoir un message clair si la synchro est indisponible.

## 10. Laissé au constructeur

Architecture interne du code, schéma détaillé de la base, bibliothèques précises, algorithme de recherche floue et de rapprochement des noms de chaînes, découpage en étapes, stratégie de tests, détails visuels non montrés par les maquettes (en restant dans leur esprit).
