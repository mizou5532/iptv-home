# iptv-home — Player IPTV familial

Un player IPTV à nous : une app Android TV rapide pour le salon (app Windows/Mac plus tard), une page web
sur iPhone pour configurer et organiser, et une synchro (profils, favoris, progression, listes) entre tous les appareils.

**Reprendre le projet (personne ou IA) : lire d'abord [`PASSATION.md`](PASSATION.md).**

- Conception : [`docs/superpowers/specs/2026-11-14-player-iptv-design.md`](docs/superpowers/specs/2026-11-14-player-iptv-design.md)
- Feuille de route : [`docs/superpowers/plans/2026-11-14-feuille-de-route.md`](docs/superpowers/plans/2026-11-14-feuille-de-route.md)
- Outils de vérification (plan 1) : `tools/test-lecture`, `tools/source-sport`

**Règle absolue : aucun identifiant IPTV (serveur, nom d'utilisateur, mot de passe) ne doit jamais
être écrit dans ce dépôt, dans un commit, un journal ou un rapport.**

Lancer les tests : `npm install` puis `npm test`.
