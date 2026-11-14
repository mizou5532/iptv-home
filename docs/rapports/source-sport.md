# Rapport — source des diffuseurs sportifs

Période : 2026-11-14 → 2026-11-27 (14 jours). Sources : API publique d'ESPN (un appel par jour et par ligue) et API officielle de la LNH (api-web.nhle.com).

### Canadiens — API officielle LNH

- Matchs : 7
- Avec au moins un diffuseur : 100 %
- Avec au moins un diffuseur canadien : 86 %

| Date (UTC) | Match | Diffuseurs |
|---|---|---|
| 2026-11-15 00:00 | Avalanche at Canadiens | SNE, TVAS, ALT2, KTVD |
| 2026-11-17 00:30 | Canadiens at Rangers | TNT, truTV, HBO MAX, SN+, TVAS |
| 2026-11-19 00:30 | Canadiens at Devils | Prime, MSGSN |
| 2026-11-20 00:00 | Canadiens at Hurricanes | TSN2, RDS, HHN, CARNHL |
| 2026-11-22 00:00 | Flyers at Canadiens | SNE, NHLN, TVAS, NBCSP |
| 2026-11-24 00:30 | Kings at Canadiens | SN+, TVAS, ABTV  |
| 2026-11-26 02:30 | Canadiens at Avalanche | TSN2, RDS, ALT, KTVD |

### Canadiens — ESPN

- Matchs : 7
- Avec au moins un diffuseur : 100 %
- Avec au moins un diffuseur canadien : 0 %

| Date (UTC) | Match | Diffuseurs |
|---|---|---|
| 2026-11-15 00:00 | Colorado Avalanche at Montreal Canadiens | ESPN+, Altitude 2 Sports, KTVD-TV (My20) |
| 2026-11-17 00:30 | Montreal Canadiens at New York Rangers | TNT, truTV |
| 2026-11-19 00:30 | Montreal Canadiens at New Jersey Devils | ESPN+, MSGSN |
| 2026-11-20 00:00 | Montreal Canadiens at Carolina Hurricanes | ESPN+, CARNHL |
| 2026-11-22 00:00 | Philadelphia Flyers at Montreal Canadiens | NHL Net, NBC Sports Phil |
| 2026-11-24 00:30 | Los Angeles Kings at Montreal Canadiens | ESPN+, ABTV |
| 2026-11-26 02:30 | Montreal Canadiens at Colorado Avalanche | ESPN+, Altitude Sports, KTVD-TV (My20) |

### Toute la LNH — API officielle LNH

- Matchs : 103
- Avec au moins un diffuseur : 100 %
- Avec au moins un diffuseur canadien : 56 %

### FC Barcelone — ESPN (Liga, Ligue des champions, Coupe du Roi, Supercoupe)

- Matchs : 2
- Avec au moins un diffuseur : 100 %
- Avec au moins un diffuseur canadien : 0 %

| Date (UTC) | Match | Diffuseurs |
|---|---|---|
| 2026-11-22 13:00 | Villarreal at Barcelona | ESPN Deportes, ESPN+ |
| 2026-11-25 17:45 | Barcelona at Sabah FK | Paramount+ |

### NFL — ESPN

- Matchs : 31
- Avec au moins un diffuseur : 100 %
- Avec au moins un diffuseur canadien : 0 %

### NBA — ESPN

- Matchs : 98
- Avec au moins un diffuseur : 86 %
- Avec au moins un diffuseur canadien : 6 %

## Décision

La règle du plan (≥ 80 % des matchs des Canadiens **et** du Barça avec un diffuseur canadien) n'est remplie que pour la LNH. Décision par ligue :

- **LNH — « Mes équipes » complet, comme dans la spec §6.4.** L'API officielle de la LNH donne les diffuseurs canadiens (SN, SNE, SN+, TVAS, TSN2, RDS…) pour 6 matchs des Canadiens sur 7. Le 7ᵉ (« Prime ») est un match du lundi diffusé au Canada sur Prime Video : l'API indique le pays de chaque diffuseur (`countryCode: "CA"`), c'est ce champ qu'il faudra utiliser au plan 7 plutôt qu'une liste de noms. Avec lui, la couverture est complète.
- **FC Barcelone — version réduite.** ESPN ne donne que des diffuseurs américains (ESPN Deportes, ESPN+, Paramount+), aucune source gratuite avec les diffuseurs canadiens n'a été trouvée. « Mes équipes » affichera les matchs du Barça (date, heure, compétition, via ESPN) et, au lieu des chaînes, lancera la **recherche du match dans le guide et les noms de chaînes du fournisseur**, plus ses chaînes favorites. Une autre source reste à chercher au plan 7.
- **NFL — version réduite pour l'instant.** ESPN : 0 % de diffuseurs canadiens. Piste pour le plan 7 : au Canada, les droits de la NFL sont très concentrés, une petite table de règles maintenue à la main pourrait suffire. À confirmer.
- **NBA (bonus) — version réduite.** 6 % seulement via ESPN.

Conséquence pour la spec : §6.4 reste valable pour la LNH ; pour le Barça, la NFL et la NBA, « Mes équipes » donne les horaires et une recherche du match dans les chaînes du fournisseur.
