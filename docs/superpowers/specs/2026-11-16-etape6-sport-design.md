# Étape 6 — Sport sur la télé : conception

16 novembre 2026. Statut : **approche A choisie par le propriétaire** ; document à valider.
Conception générale : `2026-11-14-player-iptv-design.md` §6.4. Vérification des sources : `docs/rapports/source-sport.md`.

## 1. Pourquoi

Le propriétaire regarde surtout du sport en direct (Canadiens, FC Barcelone toutes compétitions, un peu de NFL et
de NBA). Son vrai problème n'est pas l'horaire : c'est **trouver la chaîne**. Aujourd'hui il « a une petite idée »,
essaie une chaîne, puis une autre, ou cherche sur internet. Pour une équipe et une compétition, ce sont presque
toujours **les mêmes 3 ou 4 chaînes**, et il **préfère toujours le français** (ses mots, 16 nov.).

## 2. Décisions

Du propriétaire : approche **A** (tout sur la télé) ; onglet Sport tel que proposé ; français d'abord ; pas de rappel
ni de voix pour l'instant. Les autres points ci-dessous sont des propositions de Claude acceptées avec l'approche.

## 3. Objectifs, hors-champ, à éviter

**Objectifs**
1. Un 4ᵉ onglet **Sport** : « Mes équipes » (matchs en cours et des 7 prochains jours des Canadiens et du Barça) puis
   « Ce soir » (tous les matchs du jour : LNH, NFL, NBA, Liga, Ligue des champions, Coupe du Roi, Supercoupe).
2. Pour chaque match, **jusqu'à 4 chaînes à essayer dans l'ordre**, français d'abord ; OK lance la première,
   Droite / Gauche passent à la suivante / précédente sans quitter le plein écran.
3. La télé **apprend** : la chaîne regardée au moins 3 minutes depuis un match est proposée en premier la fois
   suivante pour la même compétition.
4. Tout à la télécommande, chaque écran avec un test qui imite la télécommande.

**Hors-champ** : rappels, voix, choix des équipes depuis la télé (équipes fixes : Canadiens, Barça), scores en direct
détaillés, synchronisation de l'apprentissage en ligne (approche B, plus tard avec l'app ordinateur), page iPhone.

**À éviter** : proposer une chaîne masquée ; ouvrir plus d'un flux à la fois (une seule connexion chez le
fournisseur) ; bloquer l'écran quand les sites de sport ne répondent pas ; envoyer une donnée personnelle à ces sites.

## 4. Approches (rappel)

| | Approche | Retenue ? |
|---|---|---|
| **A** | La télé lit les horaires (LNH, ESPN) et retient les chaînes sur place | **Oui** (choix du propriétaire) |
| B | A + chaînes apprises enregistrées dans le compte en ligne | Plus tard (app ordinateur) |
| C | Page « Ce soir » seule + recherche | Non : ne règle pas le vrai problème |

## 5. Ce que voit le propriétaire

```
 Direct  Séries  Films  Sport  Rechercher          (profil)
 MES ÉQUIPES
 ┌───────────────────────┐ ┌───────────────────────┐
 │ EN COURS · LNH        │ │ Sam. 19 h 00 · Liga   │
 │ Canadiens – Bruins    │ │ Barcelone – Séville   │
 │ RDS · TVA Sports      │ │ (chaînes apprises)    │
 └───────────────────────┘ └───────────────────────┘
 CE SOIR
  19 h 00  LNH   Leafs – Sénateurs        Sportsnet · TVA Sports
  20 h 15  NFL   Packers – Bears
  ...
```

- OK sur un match → plein écran sur la 1ʳᵉ chaîne proposée. Bandeau quelques secondes : « Canadiens – Bruins ·
  chaîne 1 sur 3 : RDS · ◀ ▶ autre chaîne ». Droite / Gauche : chaîne suivante / précédente (le bandeau revient).
  Retour : retour à la liste, focus sur le même match.
- Aucun candidat trouvé : « Aucune chaîne trouvée pour ce match. Essaie la recherche. » (le match reste listé).
- Sites de sport injoignables : derniers horaires connus avec « Horaires du <date> (hors ligne) » ; aucun connu :
  message et bouton « Réessayer ».

## 6. Comment ça marche

### 6.1 Horaires (`apps/shared/.../sport/`, sans Android)
- LNH : `https://api-web.nhle.com/v1/schedule/<AAAA-MM-JJ>` (une semaine). Champs utiles : `id`, `startTimeUTC`,
  `gameState` (FUT, PRE, LIVE, CRIT, FINAL, OFF), équipes (`abbrev`, `placeName`, `commonName`), `tvBroadcasts`
  (`network`, `countryCode`) — **seuls les diffuseurs `countryCode == "CA"`** sont gardés.
- ESPN : `https://site.api.espn.com/apis/site/v2/sports/<ligue>/scoreboard?dates=<AAAAMMJJ>-<AAAAMMJJ>` (7 jours)
  pour `football/nfl`, `basketball/nba`, `soccer/esp.1`, `soccer/uefa.champions`, `soccer/esp.copa_del_rey`,
  `soccer/esp.super_cup`. Champs : `id`, `date`, `status.type.state` (pre, in, post), équipes (`team.id`,
  `displayName`). Les diffuseurs ESPN (américains) sont ignorés.
- Équipes suivies : Canadiens = LNH `MTL` ; Barça = ESPN `team.id == "83"`.
- Lecture tolérante (réponses de référence dans `tools/source-sport/fixtures/`) ; une réponse illisible ne remplace
  jamais un horaire en cache. Cache disque, rafraîchi à l'ouverture de l'onglet puis toutes les 30 min tant qu'il est
  affiché ; un match « post »/FINAL disparaît 3 h après son début + durée typique (LNH/NBA 3 h, soccer 2 h 15, NFL 3 h 30).
- Heures affichées dans le fuseau de la télé.

### 6.2 Chaînes à essayer (`apps/shared/.../sport/`)
Clé d'apprentissage = **compétition** (`nhl`, `nfl`, `nba`, `esp.1`, `uefa.champions`, …), par profil.
Ordre, sans doublon, au plus 4 **proposées** (affichées sous le match) :
1. LNH : **diffuseurs officiels** d'abord (fiables, propres à chaque match), puis les apprises. Autres compétitions :
   **apprises** d'abord (la plus récemment regardée en tête), puis le repli. *(Décision de Claude : une chaîne apprise
   sur un match des Canadiens ne passe jamais devant le vrai diffuseur d'un autre match.)*
2. **Diffuseurs officiels** (LNH seulement) : chaque code réseau est traduit en noms de chaînes par une table
   (ex. `RDS`→« RDS », `RDS2`→« RDS 2 », `TVAS`→« TVA Sports », `TVAS2`→« TVA Sports 2 », `SN`→« Sportsnet »,
   `SNE`/`SNO`/`SNW`/`SNP`→« Sportsnet East/Ontario/West/Pacific », `SN1`→« Sportsnet One », `SN+`→« Sportsnet+ »,
   `TSN1`…`TSN5`→« TSN 1 »…, `CBC`→« CBC », `CITY`→« Citytv », `Prime`→« Prime Video ») puis cherché avec le moteur
   de l'étape 5 parmi les **groupes de chaînes visibles** du Direct (masquées exclues).
3. **Repli** : chaînes visibles des catégories dont le nom contient « sport », trouvées par le nom de la compétition
   et des équipes, puis les favoris.
4. **Au-delà des 4 proposées**, Droite continue dans **toutes les chaînes des catégories « sport » visibles**
   (français d'abord, ordre du Direct), puis Gauche revient en arrière. C'est le geste habituel du propriétaire
   (essayer une chaîne puis une autre), en plus rapide, et la chaîne où il reste est apprise : c'est ce qui permet
   d'apprendre dès le premier match du Barça. *(Décision de Claude.)*
5. **Guide des programmes : non utilisé** (toujours « Programme indisponible », reporté par le propriétaire).
Dans chaque niveau, **français d'abord** : une chaîne est « française » si son nom ou sa catégorie contient un mot
`fr`, `french`, `francais`, `quebec`, ou si c'est RDS / TVA Sports.

### 6.3 Apprentissage
Clé = profil + compétition (pas l'équipe : ce sont les mêmes chaînes pour toute la compétition, et l'ordre LNH ci-dessus
évite l'effet de bord). Une chaîne lancée depuis un match (proposée ou atteinte avec Droite) et regardée **3 minutes**
sans interruption est enregistrée pour la compétition
(profil, compétition → liste de groupes, la plus récente en tête, 6 au plus). Stockage local (préférences Android).
Une chaîne devenue masquée n'est plus proposée.

### 6.4 Écran et lecture (`apps/tv/.../sport/`)
- `HomeTab.SPORT`, onglet de la barre ; passer à Sport ferme le lecteur du Direct (comme Séries/Films).
- Lecture : le lecteur unique existant (`PlayerController`), adresse de la chaîne résolue comme pour le Direct
  (meilleure copie visible du groupe) ; Droite / Gauche changent de candidat (un seul flux ouvert à la fois).
- Le remplisseur de la recherche reçoit la priorité « Direct » quand Sport est affiché (aucune série/film).

## 7. Tests attendus
- Lecture LNH/ESPN sur les vraies réponses de `tools/source-sport/fixtures/` ; réponses vides ou abîmées.
- Diffuseurs CA seulement ; table des codes ; candidats : ordre appris > officiels > repli, français d'abord, max 4,
  jamais masqué, sans doublon.
- Apprentissage à 3 min ; pas en dessous ; plus récent en tête ; limite 6.
- Cache : hors ligne, réponse illisible, matchs terminés retirés.
- Télécommande : onglet Sport, rangées, OK → lecture, Droite/Gauche changent de chaîne, Retour garde le focus,
  état « aucune chaîne », hors ligne.

## 8. À vérifier sur la vraie télé
Noms réels des chaînes du fournisseur face à la table des codes, temps de passage d'une chaîne à l'autre, lisibilité
du bandeau.

## 9. Livraison
Branche `plan-6-sport` empilée sur `plan-5-recherche`. Version **0.7.0 / code 9**, publiée par le bouton « Mise à
jour » avec l'accord du propriétaire. Fusion dans `main` avec le reste après les essais de fin de projet.
