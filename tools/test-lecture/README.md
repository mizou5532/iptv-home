# test-lecture — page de test de lecture dans le navigateur

Vérifie ce qu'un navigateur (Safari iPhone/Mac, Chrome/Edge Windows) arrive à lire chez le fournisseur Xtream,
et produit un rapport **sans identifiants**.

- `npm test` : tests.
- `npm run faux-xtream` : faux serveur Xtream sur `http://localhost:8899` (identifiants `utilisateur-test` / `motdepasse-test`).
- Vidéo de test pour le faux serveur (facultatif, nécessite ffmpeg) :
  `mkdir -p dev/media && ffmpeg -f lavfi -i testsrc=duration=10:size=640x360:rate=25 -f lavfi -i sine=duration=10 -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest dev/media/test.mp4`
- En local, la fonction `/api/analyse` refuse les adresses privées sauf si `AUTORISER_LOCAL=1`.
