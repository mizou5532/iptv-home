# Rapport — lecture dans le navigateur

Page de test : https://iptv-test-lecture-hamzahlal86-9766s-projects.vercel.app (plan 1, tâche 8).

## Rapport n°1 — Mac, Chrome (2026-11-14)

- Protocole de la page : https · Protocole du serveur du fournisseur : **http**
- Accès direct à l'API depuis le navigateur : **contenu-mixte** (bloqué)
- Connexions simultanées autorisées : **1** · Formats de sortie : m3u8, ts, rtmp
- Catalogue : **56 808 chaînes, 184 786 films, 50 201 séries**
- Extensions des films : **mkv 138 147 (75 %)**, mp4 43 662 (24 %), avi 2 608, m4v 191, ts 177, flv 1
- Extensions des épisodes (2 séries échantillonnées) : mkv 14

| Type | Résultat |
|---|---|
| Direct (3 chaînes × natif / hls.js / mpegts.js) | 0/9 — contenu-mixte |
| Films (mkv, mp4, avi) | 0/6 — contenu-mixte |
| Épisodes (mkv) | 0/4 — contenu-mixte |

**0 lecture réussie sur 19.**

## Analyse

1. **Le fournisseur n'est qu'en `http`.** Tout navigateur bloque une vidéo `http` dans une page `https` (contenu mixte), quel que soit le format. Le résultat sera le même sur Safari (Mac, iPhone) et Edge : les deux autres tests ne sont pas nécessaires.
2. **Même avec un relais https, 75 % des films et (d'après l'échantillon) les épisodes sont en MKV.** Safari ne lit pas le MKV ; Chrome seulement selon les codecs. Un simple relais ne suffit donc pas : il faut aussi **reconditionner** le MKV (sans réencoder l'image, en convertissant au besoin le son).
3. **L'API du fournisseur répond depuis un serveur en ligne** (l'analyse a tourné sur Vercel) : un relais hébergé est techniquement possible.
4. **Taille du catalogue** : ~290 000 éléments. Côté site, la liste des films ne tient pas dans une réponse Vercel (limite 4,5 Mo) ; côté télé, le chargement par catégorie et le masquage sont indispensables.

## Rapports n°2 et n°3 — sonde depuis un serveur (2026-11-14)

Sonde 1 (Vercel, Washington) puis sonde 2 (Vercel, **Montréal**, nouvel essai en se présentant comme **VLC**), télé éteinte (confirmé par le propriétaire) :

| Type | Sonde 1 | Sonde 2 (navigateur → VLC) |
|---|---|---|
| Direct (ts) | 511 | 511 → 511 |
| Films (mkv, mkv, mp4) | 404 | 404 → 404 |
| Épisodes (mkv, mkv) | 404 | 404 → 404 |

Aucun message d'erreur renvoyé. **L'API du catalogue répond depuis un serveur, mais la vidéo est refusée à une adresse de centre de données**, quel que soit le pays (États-Unis, Canada) ou le lecteur annoncé. Le serveur relais hébergé (option 1 telle que choisie) n'est donc **pas faisable** avec ce fournisseur. Codecs des MKV : non mesurés (aucun octet reçu).

## Décision

Relais hébergé impossible (rapports 2 et 3). Le propriétaire choisit une **app ordinateur Windows et Mac** (lecture directe depuis la maison, tous formats). Pas d'iPhone (refus de l'abonnement Apple à 99 $/an ; on regarde à la maison sur la télé ou l'ordinateur), pas de site web en V1. Spec §6.10 et §8 mises à jour.
