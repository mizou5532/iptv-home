# Tâche 3 (progression) — corrections demandées par la relecture (à faire)

Code concerné : `apps/shared/src/commonMain/kotlin/home/iptv/shared/progress/ProgressQueue.kt`, `ProgressClient.kt`, `apps/tv/.../data/LocalStore.kt` (commit `02a8a1f`). Montrer chaque test en échec avant de le faire passer.

## Importantes
1. **Une erreur permanente ne doit pas bloquer la file.** Classer les échecs : transitoires (arrêter et réessayer plus tard) = IOException, délais, HTTP 408/429/401/≥500, absence de session ; permanents = autre 4xx, Postgres 42501 / P0001 → passer l'entrée et continuer. Compteur `attempts` par entrée : abandon après 5 échecs permanents ou après 14 jours. `flush` renvoie `FlushResult(sent, dropped, blocked)`. Tests : un `acces-refuse` en tête ne bloque pas les suivantes ; abandon au 5ᵉ échec.
2. **Modifications de la file atomiques** : `ProgressStore.update(transform)` (`synchronized` sur Android) utilisé par `enqueue` et par le retrait dans `flush`. Test : un `enqueue` pendant un `flush` n'est jamais perdu.
3. **File bornée** : 300 entrées maximum (les plus anciennes sautent) ; écriture `apply()`, et `persistNow()` (`commit()`) exposé pour `onStop` (branché en tâche 6).
4. **Persistance réelle et testée** : encodage/décodage dans `commonMain` (`ProgressQueueCodec`), décodage entrée par entrée (une mauvaise entrée est ignorée, pas toute la file) ; JSON illisible conservé sous une clé de secours au lieu d'être écrasé. Tests jvm : aller-retour avec Instant et nulls, entrée corrompue, clé inconnue, champ facultatif absent.

## Petites
- Lors d'un remplacement d'une entrée non envoyée, garder les champs d'enrichissement (et série/saison/épisode) de l'ancienne quand la nouvelle les a à null.
- Horodatage strictement croissant pour un même épisode : `max(horloge, précédent + 1 ms)`.
- `seriesCover` : ne garder que schéma + hôte + chemin (pas de paramètres ni d'identifiants) ; test.
- Tests manquants : `record` et `publishSeriesCategories` sans session ; `set_watched(false)` ; `flush` sur file vide.
- `lastTab` : constantes plutôt qu'une chaîne libre.

Vérification : `cd apps && ./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest :tv:assembleDebug`.
