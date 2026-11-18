# Migrations en attente (jamais appliquées en production)

- `20261114000010_lecture_confirmee.sql` : « protection de la lecture » (B2). Écrite et testée en novembre 2026, jamais
  appliquée en production ; aucune app ne l'utilise (l'écran iPhone est caché derrière `VITE_PLAYBACK_ADMIN=1`).
  Rangée ici le 18 nov. 2026 (stabilisation, zone 8) pour que les tests de `test/` vérifient exactement la production.
  Son test est dans `../test-en-attente/lecture-confirmee.test.ts` (hors de la suite normale).
  Pour la reprendre : la renommer avec une date postérieure à la dernière migration, la remettre dans `migrations/`,
  remettre son test dans `test/`, et l'appliquer avec l'accord du propriétaire.
