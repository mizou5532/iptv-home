# Plan 1 — Vérifications et fondations : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mettre en place le dépôt et répondre, avec des données réelles, aux deux questions qui décident de la suite : (1) ce que le navigateur (Safari iPhone/Mac, Chrome/Edge Windows) peut lire chez le fournisseur Xtream du propriétaire ; (2) si une source sportive gratuite donne les diffuseurs canadiens des matchs suivis.

**Architecture:** Un dépôt npm à espaces de travail. `tools/test-lecture` : page web Vite + TypeScript que le propriétaire ouvre sur chaque appareil, avec une fonction serveur Vercel (`api/analyse`) qui interroge l'API Xtream côté serveur et ne renvoie qu'un résumé ; la page tente ensuite de lire un échantillon de flux un par un et produit un rapport **sans identifiants** à copier. `tools/source-sport` : script Node qui interroge l'API publique d'ESPN pour les ligues suivies, relie les diffuseurs aux noms de chaînes et produit un rapport.

**Tech Stack:** Node 22, TypeScript (strict), Vitest, Vite, hls.js, mpegts.js, fonctions Vercel (Node).

**Spec:** `docs/superpowers/specs/2026-11-14-player-iptv-design.md` (§4 contraintes, §6.4 sport, §6.10 site, §9 vérifications 1 et 2). Feuille de route : `docs/superpowers/plans/2026-11-14-feuille-de-route.md`.

## Global Constraints

- Les identifiants Xtream ne passent **jamais** par la conversation, un commit, un journal (log) ou un rapport. Le propriétaire les saisit lui-même sur son appareil ; ils restent en mémoire dans la page (pas de `localStorage`).
- **Une seule connexion à la fois** chez le fournisseur : la page lit un échantillon à la fois et arrête/détache complètement chaque lecture avant la suivante.
- **Pas de relais vidéo.** La fonction serveur ne fait que des appels d'API JSON et ne renvoie qu'un résumé.
- Hébergement gratuit uniquement. Tout déploiement ou création de compte se fait **après accord explicite du propriétaire**.
- Interface et rapports en **français**.
- Navigateurs à tester : **Safari sur iPhone**, **Safari sur Mac**, **Chrome ou Edge sur Windows**.
- Sport — couverture : **LNH** (Canadiens), **FC Barcelone dans toutes ses compétitions** (Liga, Ligue des champions, Coupe du Roi, Supercoupe), **NFL** ; **NBA** en bonus. Diffuseurs recherchés : **canadiens**.

## Review Focus

1. **Adresse de serveur saisie de mille façons** (`monserveur.com:8080`, `http://monserveur.com:8080/`, `http://x:8080/player_api.php`, espaces autour) → toujours normalisée vers `http://monserveur.com:8080`. Test dans la tâche 1.
2. **Identifiants avec caractères spéciaux** (`@`, `&`, `#`, `/`, espace) → encodés dans la requête d'API et dans le chemin des flux. Test dans la tâche 1.
3. **Le rapport ne contient jamais les identifiants**, même encodés dans une URL ou dans un message d'erreur. Test dans la tâche 2.
4. **Extensions de fichier absentes ou en majuscules** (`""`, `null`, `"MKV"`) → comptées sous `inconnu` / `mkv`, pas d'erreur. Test dans la tâche 2.
5. **Noms de chaînes du fournisseur avec préfixes et mentions de qualité** (`CA| RDS FHD`, `RDS 2 HD`, `TVA Sports ᴴᴰ`, `TSN+`) → « RDS » trouve les copies de RDS mais **pas** RDS 2 ni RDS Info. Test dans la tâche 6.

---

## Fichiers

```
README.md                                  présentation du projet (FR)
.gitignore
package.json                               espaces de travail npm : tools/*
tsconfig.base.json                         options TypeScript partagées (strict)
tools/test-lecture/
  package.json  tsconfig.json  vite.config.ts  vercel.json  index.html
  src/xtream.ts        URLs Xtream (serveur, API, flux)
  src/sanitize.ts      retrait des identifiants d'un texte
  src/summary.ts       résumé du catalogue + choix des échantillons
  src/playback.ts      classification des échecs + tentative de lecture
  src/report.ts        rapport Markdown
  src/main.ts          interface de la page
  api/analyse.ts       fonction serveur Vercel
  api/guard.ts         validation des requêtes de la fonction
  dev/faux-xtream.ts   faux serveur Xtream local pour développer sans vrai compte
  test/*.test.ts
tools/source-sport/
  package.json  tsconfig.json
  src/normalize.ts     normalisation des noms de chaînes
  src/match.ts         diffuseur → chaînes du fournisseur
  src/espn.ts          lecture des calendriers ESPN
  src/evaluate.ts      script d'évaluation → rapport
  fixtures/*.json      réponses ESPN enregistrées
  test/*.test.ts
docs/rapports/
  lecture-navigateur.md   rapports collés par le propriétaire + décision
  source-sport.md         rapport d'évaluation + décision
```

---

### Task 1: Socle du dépôt et URLs Xtream

**Files:**
- Create: `README.md`, `.gitignore` (node_modules, dist, .vercel, .env*), `package.json`, `tsconfig.base.json`
- Create: `tools/test-lecture/package.json`, `tools/test-lecture/tsconfig.json`, `tools/test-lecture/vite.config.ts`
- Create: `tools/test-lecture/src/xtream.ts`
- Test: `tools/test-lecture/test/xtream.test.ts`

**Interfaces:**
- Produces:
  - `type XtreamCreds = { server: string; username: string; password: string }`
  - `type StreamKind = 'live' | 'movie' | 'series'`
  - `normalizeServer(input: string): string` — sans barre finale, sans `/player_api.php`, ajoute `http://` si aucun protocole
  - `apiUrl(creds: XtreamCreds, action?: string, params?: Record<string, string>): string` — `{server}/player_api.php?username=…&password=…[&action=…&…]`, valeurs encodées
  - `streamUrl(creds: XtreamCreds, kind: StreamKind, id: string, ext: string): string` — `{server}/{live|movie|series}/{user}/{pass}/{id}.{ext}`, user et pass encodés avec `encodeURIComponent`

- [ ] **Step 1: Créer le socle** — `package.json` racine avec `"private": true`, `"workspaces": ["tools/*"]`, script `"test": "npm test --workspaces --if-present"` ; `tools/test-lecture/package.json` avec `vite`, `vitest`, `typescript`, `hls.js`, `mpegts.js`, `@vercel/node`, scripts `dev`, `build`, `test` (`vitest run`). `README.md` : 10 lignes en français (but du projet, lien vers la spec et la feuille de route, règle « jamais d'identifiants dans le dépôt »). `npm install` à la racine.

- [ ] **Step 2: Écrire les tests**

```ts
import { describe, it, expect } from 'vitest';
import { normalizeServer, apiUrl, streamUrl } from '../src/xtream';

const c = { server: 'http://tv.exemple.com:8080', username: 'jean@x', password: 'a&b#c/d e' };

describe('normalizeServer', () => {
  it.each([
    ['tv.exemple.com:8080', 'http://tv.exemple.com:8080'],
    ['http://tv.exemple.com:8080/', 'http://tv.exemple.com:8080'],
    ['  http://tv.exemple.com:8080/player_api.php ', 'http://tv.exemple.com:8080'],
    ['https://tv.exemple.com', 'https://tv.exemple.com'],
  ])('%s', (input, out) => expect(normalizeServer(input)).toBe(out));
});

describe('apiUrl', () => {
  it('encode les identifiants et ajoute action et paramètres', () => {
    expect(apiUrl(c, 'get_series_info', { series_id: '12' })).toBe(
      'http://tv.exemple.com:8080/player_api.php?username=jean%40x&password=a%26b%23c%2Fd%20e&action=get_series_info&series_id=12');
  });
  it('sans action', () => {
    expect(apiUrl(c)).toBe('http://tv.exemple.com:8080/player_api.php?username=jean%40x&password=a%26b%23c%2Fd%20e');
  });
});

describe('streamUrl', () => {
  it('film', () => {
    expect(streamUrl(c, 'movie', '991', 'mkv')).toBe('http://tv.exemple.com:8080/movie/jean%40x/a%26b%23c%2Fd%20e/991.mkv');
  });
  it('direct', () => {
    expect(streamUrl(c, 'live', '7', 'm3u8')).toBe('http://tv.exemple.com:8080/live/jean%40x/a%26b%23c%2Fd%20e/7.m3u8');
  });
});
```

- [ ] **Step 3: Vérifier l'échec** — Run: `npm test -w tools/test-lecture` — Expected: FAIL (module `../src/xtream` introuvable).
- [ ] **Step 4: Implémenter `src/xtream.ts`** — `apiUrl` construit la chaîne de requête à la main avec `encodeURIComponent` (pas `URLSearchParams`, qui encode l'espace en `+`).
- [ ] **Step 5: Vérifier le succès** — Run: `npm test -w tools/test-lecture` — Expected: PASS.
- [ ] **Step 6: Commit** — `git add -A && git commit -m "feat(test-lecture): socle du dépôt et URLs Xtream"`

---

### Task 2: Retrait des identifiants et résumé du catalogue

**Files:**
- Create: `tools/test-lecture/src/sanitize.ts`, `tools/test-lecture/src/summary.ts`
- Test: `tools/test-lecture/test/sanitize.test.ts`, `tools/test-lecture/test/summary.test.ts`

**Interfaces:**
- Consumes: `XtreamCreds`, `StreamKind` (tâche 1)
- Produces:
  - `sanitize(text: string, creds: Pick<XtreamCreds, 'username' | 'password'>): string` — remplace le nom d'utilisateur et le mot de passe, en clair **et** sous forme `encodeURIComponent`, par `***`
  - `interface Sample { kind: StreamKind; id: string; ext: string; label: string }`
  - `interface CatalogSummary { serverProtocol: 'http' | 'https'; maxConnections: number | null; allowedOutputFormats: string[]; counts: { live: number; movies: number; series: number }; movieExtensions: Record<string, number>; episodeExtensions: Record<string, number>; samples: Sample[] }`
  - `interface RawCatalog { serverUrl: string; userInfo: unknown; live: unknown[]; movies: unknown[]; series: unknown[]; seriesInfos: unknown[] }`
  - `summarize(raw: RawCatalog): CatalogSummary`

Règles de `summarize` (données Xtream : `user_info.max_connections` est une chaîne numérique, `user_info.allowed_output_formats` un tableau ; films `{stream_id, name, container_extension}` ; direct `{stream_id, name}` ; `get_series_info` → `{ info: {name}, episodes: { "1": [{ id, title, container_extension }] } }`) :
- extension normalisée en minuscules ; vide/absente → `inconnu` ;
- échantillons direct : les 3 premiers, `ext: 'm3u8'` ;
- échantillons films : jusqu'à 2 par extension, 6 au total, dans l'ordre du catalogue ;
- échantillons épisodes : jusqu'à 2 par série parmi `seriesInfos`, 4 au total, en préférant des extensions différentes ;
- `episodeExtensions` compte les épisodes de toutes les `seriesInfos` ;
- `serverProtocol` d'après `serverUrl`.

- [ ] **Step 1: Écrire les tests**

```ts
// sanitize.test.ts
it('retire identifiants en clair et encodés', () => {
  const t = 'GET http://x/movie/jean%40x/a%26b%23c/1.mkv par jean@x avec a&b#c';
  expect(sanitize(t, { username: 'jean@x', password: 'a&b#c' }))
    .toBe('GET http://x/movie/***/***/1.mkv par *** avec ***');
});

// summary.test.ts — avec un petit catalogue écrit dans le test
it('compte les extensions, tolère vide/majuscules', () => {
  const s = summarize(raw({ movies: [m(1,'mp4'), m(2,'MKV'), m(3,''), m(4,'mkv'), m(5,'mkv')] }));
  expect(s.movieExtensions).toEqual({ mp4: 1, mkv: 3, inconnu: 1 });
  expect(s.samples.filter(x => x.kind === 'movie').map(x => x.id)).toEqual(['1', '2', '4', '3']);
});
it('lit max_connections et le protocole', () => {
  const s = summarize(raw({ userInfo: { user_info: { max_connections: '1', allowed_output_formats: ['m3u8','ts'] } }, serverUrl: 'http://tv.exemple.com:8080' }));
  expect(s.maxConnections).toBe(1);
  expect(s.allowedOutputFormats).toEqual(['m3u8', 'ts']);
  expect(s.serverProtocol).toBe('http');
});
it('3 échantillons direct en m3u8, 4 épisodes max', () => { /* 5 chaînes, 3 séries de 3 épisodes → 3 live, 4 episodes */ });
```
(`raw()` et `m()` sont de petites fonctions d'aide du fichier de test qui remplissent les champs manquants.)

- [ ] **Step 2: Vérifier l'échec** — Run: `npm test -w tools/test-lecture` — Expected: FAIL.
- [ ] **Step 3: Implémenter `sanitize` et `summarize`** — `sanitize` ignore une valeur vide (ne remplace pas la chaîne vide).
- [ ] **Step 4: Vérifier le succès** — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -am "feat(test-lecture): résumé du catalogue et retrait des identifiants"` (ajouter les nouveaux fichiers avant).

---

### Task 3: Fonction serveur `api/analyse` et faux serveur Xtream

**Files:**
- Create: `tools/test-lecture/api/guard.ts`, `tools/test-lecture/api/analyse.ts`, `tools/test-lecture/vercel.json`
- Create: `tools/test-lecture/dev/faux-xtream.ts`
- Test: `tools/test-lecture/test/guard.test.ts`

**Interfaces:**
- Consumes: `normalizeServer`, `apiUrl` (tâche 1), `summarize`, `RawCatalog` (tâche 2)
- Produces:
  - `validateRequest(body: unknown): { ok: true; creds: XtreamCreds } | { ok: false; status: 400; error: string }`
  - `POST /api/analyse` body `{ server, username, password }` → `200 CatalogSummary` | `400 { error }` | `502 { error: 'fournisseur-injoignable' | 'fournisseur-refuse' }`
  - `dev/faux-xtream.ts` : serveur HTTP local sur le port `8899` qui répond à `/player_api.php` (user_info, `get_live_streams`, `get_vod_streams`, `get_series`, `get_series_info`) avec un petit catalogue fictif, et sert `/movie/…/{id}.mp4` depuis `dev/media/test.mp4` s'il existe.

Règles de `validateRequest` : trois chaînes non vides ; `server` normalisé ; protocole `http:` ou `https:` uniquement ; refuser `localhost`, les IP `127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16`, `::1`, `fc00::/7` (la fonction ne doit pas servir de relais vers un réseau privé) ; **sauf** si la variable d'environnement `AUTORISER_LOCAL=1` (développement avec le faux serveur).

Règles de `api/analyse.ts` : appels séquentiels côté serveur (user_info, `get_live_streams`, `get_vod_streams`, `get_series`, puis `get_series_info` pour les 2 premières séries) avec 20 s max chacun ; **ne jamais journaliser** le corps de la requête ni les URL ; renvoyer uniquement `summarize(...)`. `vercel.json` : `maxDuration` 60 s pour `api/analyse.ts`.

- [ ] **Step 1: Écrire les tests de `validateRequest`**

```ts
it.each(['http://localhost:8080', 'http://127.0.0.1', 'http://192.168.1.10:8080', 'http://10.0.0.5', 'ftp://tv.exemple.com'])
  ('refuse %s', (server) => expect(validateRequest({ server, username: 'u', password: 'p' }).ok).toBe(false));
it('accepte un serveur public et le normalise', () => {
  const r = validateRequest({ server: 'tv.exemple.com:8080/', username: 'u', password: 'p' });
  expect(r).toEqual({ ok: true, creds: { server: 'http://tv.exemple.com:8080', username: 'u', password: 'p' } });
});
it('refuse un corps incomplet', () => expect(validateRequest({ server: 'x.com' }).ok).toBe(false));
```

- [ ] **Step 2: Vérifier l'échec** — Expected: FAIL.
- [ ] **Step 3: Implémenter `guard.ts`, `analyse.ts`, `vercel.json`, `dev/faux-xtream.ts`** — le faux serveur utilise `node:http` ; ajouter le script `"faux-xtream": "tsx dev/faux-xtream.ts"` (dépendance `tsx`). Si `ffmpeg` est disponible, documenter dans le README de l'outil la commande qui génère `dev/media/test.mp4` (10 s, H.264/AAC) ; `dev/media/` est dans `.gitignore`.
- [ ] **Step 4: Vérifier** — Run: `npm test -w tools/test-lecture` — Expected: PASS. Puis `npm run faux-xtream -w tools/test-lecture` et dans un autre terminal `AUTORISER_LOCAL=1 npx vercel dev` n'est **pas** requis : appeler directement le gestionnaire depuis un petit test d'intégration `test/analyse.test.ts` qui démarre le faux serveur et vérifie que `counts.movies` vaut le nombre de films du faux catalogue.
- [ ] **Step 5: Commit** — `git commit -m "feat(test-lecture): fonction d'analyse du catalogue et faux serveur Xtream"`

---

### Task 4: Tentatives de lecture et rapport

**Files:**
- Create: `tools/test-lecture/src/playback.ts`, `tools/test-lecture/src/report.ts`
- Test: `tools/test-lecture/test/playback.test.ts`, `tools/test-lecture/test/report.test.ts`

**Interfaces:**
- Consumes: `Sample`, `CatalogSummary` (tâche 2), `sanitize` (tâche 2), `streamUrl` (tâche 1)
- Produces:
  - `type Engine = 'natif' | 'hls.js' | 'mpegts.js'`
  - `type FailureReason = 'contenu-mixte' | 'format' | 'cors-ou-reseau' | 'delai' | 'inconnu'`
  - `interface PlayResult { sample: Sample; engine: Engine; ok: boolean; msToFirstFrame?: number; reason?: FailureReason; detail?: string }`
  - `classifyFailure(ctx: { pageProtocol: string; url: string; mediaErrorCode?: number; engineNetworkError?: boolean; timedOut?: boolean }): FailureReason`
  - `enginesFor(sample: Sample, support: { hls: boolean; mpegts: boolean }): { engine: Engine; ext: string }[]` — direct : `natif`/`m3u8`, puis `hls.js`/`m3u8` si `support.hls`, puis `mpegts.js`/`ts` si `support.mpegts` ; film/épisode : `natif` avec l'extension de l'échantillon
  - `tryPlay(video: HTMLVideoElement, url: string, engine: Engine, timeoutMs = 10000): Promise<Omit<PlayResult, 'sample' | 'engine'>>` — succès dès que `currentTime > 0.3` ; **détache et arrête toujours** la lecture avant de résoudre (`hls.destroy()`, `player.destroy()`, `video.removeAttribute('src'); video.load()`)
  - `interface TestRun { date: string; userAgent: string; pageProtocol: string; directApi: 'ok' | FailureReason; summary: CatalogSummary | { error: string }; results: PlayResult[] }`
  - `buildReport(run: TestRun, creds: Pick<XtreamCreds, 'username' | 'password'>): string` — Markdown en français, passé par `sanitize`

Ordre de `classifyFailure` : page `https:` et URL `http:` → `contenu-mixte` ; `mediaErrorCode` 3 ou 4 → `format` ; `timedOut` → `delai` ; `engineNetworkError` → `cors-ou-reseau` ; sinon `inconnu`.

Contenu du rapport, dans cet ordre : appareil/navigateur (`userAgent`), date, protocole de la page et du serveur, accès direct à l'API depuis le navigateur, connexions autorisées et formats de sortie, nombres de chaînes/films/séries, extensions des films et des épisodes, puis un tableau `| Type | Extension | Moteur | Résultat | Raison | Délai |`, puis une ligne de conclusion : `X lectures réussies sur Y`.

- [ ] **Step 1: Écrire les tests** (`classifyFailure`, `enginesFor`, `buildReport` ; `tryPlay` se teste à la main à la tâche 5)

```ts
it('contenu mixte prioritaire', () =>
  expect(classifyFailure({ pageProtocol: 'https:', url: 'http://x/1.mp4', mediaErrorCode: 4 })).toBe('contenu-mixte'));
it('format', () => expect(classifyFailure({ pageProtocol: 'http:', url: 'http://x/1.mkv', mediaErrorCode: 4 })).toBe('format'));
it('délai', () => expect(classifyFailure({ pageProtocol: 'https:', url: 'https://x/1', timedOut: true })).toBe('delai'));
it('moteurs du direct selon le support', () =>
  expect(enginesFor({ kind: 'live', id: '7', ext: 'm3u8', label: 'RDS' }, { hls: true, mpegts: false }))
    .toEqual([{ engine: 'natif', ext: 'm3u8' }, { engine: 'hls.js', ext: 'm3u8' }]));
it('le rapport ne contient pas les identifiants', () => {
  const r = buildReport(runAvecDetail('échec http://x/movie/jean%40x/secret/1.mkv'), { username: 'jean@x', password: 'secret' });
  expect(r).not.toContain('secret'); expect(r).not.toContain('jean'); expect(r).toContain('1 lectures réussies sur 2');
});
```

- [ ] **Step 2: Vérifier l'échec** — Expected: FAIL.
- [ ] **Step 3: Implémenter `playback.ts` et `report.ts`** — `tryPlay` : `natif` = `video.src = url` ; `hls.js` = `new Hls()` + `loadSource` + `attachMedia`, erreur fatale `NETWORK_ERROR` → `engineNetworkError` ; `mpegts.js` = `mpegts.createPlayer({ type: 'mpegts', isLive: true, url })`. Toujours `video.muted = true` (lecture auto autorisée sur iPhone) et `playsInline`.
- [ ] **Step 4: Vérifier le succès** — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(test-lecture): tentatives de lecture et rapport"`

---

### Task 5: La page de test

**Files:**
- Create: `tools/test-lecture/index.html`, `tools/test-lecture/src/main.ts`

**Interfaces:**
- Consumes: tout ce qui précède.

Comportement (textes en français) :
1. Formulaire : « Adresse du serveur », « Nom d'utilisateur », « Mot de passe » (type password), bouton « Lancer le test ». Mention sous le bouton : « Tes identifiants restent dans cette page et ne sont envoyés qu'à ton fournisseur, via notre fonction d'analyse. Ils n'apparaissent pas dans le rapport. »
2. Étape « accès direct » : `fetch(apiUrl(creds))` depuis le navigateur → `directApi` = `ok`, ou la raison via `classifyFailure` (exception `TypeError` → `cors-ou-reseau`).
3. Étape « analyse » : `POST /api/analyse` → `CatalogSummary` affiché (nombres, extensions, connexions autorisées).
4. Étape « lecture » : pour chaque échantillon et chaque moteur de `enginesFor`, **un seul à la fois**, `tryPlay` dans une petite vidéo visible ; liste de progression « ✓ / ✗ raison ».
5. Fin : rapport (`buildReport`) dans une zone de texte + bouton « Copier le rapport ».
6. Mise en page lisible sur iPhone (une colonne, champs 16 px minimum pour éviter le zoom Safari).

- [ ] **Step 1: Implémenter `index.html` et `src/main.ts`**
- [ ] **Step 2: Vérifier en local avec le faux serveur** — Run: `npm run faux-xtream -w tools/test-lecture` puis `AUTORISER_LOCAL=1 npx vercel dev --cwd tools/test-lecture` (ou `vite` + appel direct si Vercel CLI indisponible) ; saisir `http://localhost:8899`, `utilisateur-test`, `motdepasse-test` (les identifiants qu'accepte le faux serveur). Expected : les trois étapes se déroulent, le rapport s'affiche et ne contient ni `utilisateur-test` ni `motdepasse-test`, et la lecture du `test.mp4` réussit en `natif` si le fichier existe.
- [ ] **Step 3: `npm run build -w tools/test-lecture`** — Expected : build sans erreur TypeScript.
- [ ] **Step 4: Commit** — `git commit -m "feat(test-lecture): page de test de lecture"`

---

### Task 6: Normalisation des noms de chaînes et rapprochement des diffuseurs

**Files:**
- Create: `tools/source-sport/package.json`, `tools/source-sport/tsconfig.json`, `tools/source-sport/src/normalize.ts`, `tools/source-sport/src/match.ts`
- Test: `tools/source-sport/test/normalize.test.ts`, `tools/source-sport/test/match.test.ts`

**Interfaces:**
- Produces:
  - `normalizeChannelName(name: string): string` — NFKD + suppression des accents, minuscules ; `+` → ` plus` ; suppression d'un préfixe pays (`ca|`, `ca:`, `ca -`, `[ca]`, `(ca)` et tout code de 2–3 lettres suivi de `|` ou `:`) ; suppression des mentions `hd fhd uhd sd 4k hevc h265 1080p 720p 50fps 60fps backup bk` et du contenu entre parenthèses ou crochets ; tout autre caractère non alphanumérique → espace ; espaces réduits
  - `BROADCASTER_ALIASES: Record<string, string[]>` — clé = nom de diffuseur normalisé **sans espaces** ; valeur = noms de chaînes normalisés acceptés
  - `matchBroadcaster(broadcaster: string, channels: string[]): string[]` — renvoie les noms **d'origine** des chaînes qui correspondent, dans l'ordre reçu ; sans alias connu, égalité des noms normalisés
  - `isCanadianBroadcaster(broadcaster: string): boolean` — vrai si la clé normalisée sans espaces est dans `CANADIAN_KEYS`

Alias de départ (à compléter avec ce que montre la tâche 7) :
`rds → [rds]`, `rds2 → [rds 2]`, `rdsinfo → [rds info]`, `tvas | tvasports → [tva sports]`, `tvas2 | tvasports2 → [tva sports 2]`, `tsn → [tsn, tsn 1, tsn 2, tsn 3, tsn 4, tsn 5]`, `tsn1…tsn5 → [tsn N]`, `tsnplus → [tsn plus]`, `sn | sportsnet → [sportsnet, sportsnet ontario, sportsnet east, sportsnet west, sportsnet pacific]`, `sn1 | sportsnetone → [sportsnet one]`, `sn360 | sportsnet360 → [sportsnet 360]`, `snnow | sportsnetplus → [sportsnet plus]`, `cbc → [cbc]`, `citytv → [citytv]`, `dazn → [dazn, dazn 1, dazn 2]`, `primevideo → [prime video]`.
`CANADIAN_KEYS` = toutes les clés ci-dessus sauf `primevideo`.

- [ ] **Step 1: Écrire les tests**

```ts
it.each([
  ['CA| RDS FHD', 'rds'], ['RDS 2 HD', 'rds 2'], ['TVA Sports ᴴᴰ', 'tva sports'],
  ['[CA] TSN 1 (backup)', 'tsn 1'], ['Réseau des Sports', 'reseau des sports'],
  ['TSN+', 'tsn plus'], ['beIN Sports 4K', 'bein sports'],
])('normalise %s', (a, b) => expect(normalizeChannelName(a)).toBe(b));

const chaines = ['CA| RDS FHD', 'RDS 2 HD', 'RDS Info', 'RDS HD', 'TVA Sports ᴴᴰ', 'TVA Sports 2', 'TSN 1', 'TSN 4 FHD', 'TSN+', 'ESPN', 'ESPN 2'];
it('RDS sans RDS 2 ni RDS Info', () => expect(matchBroadcaster('RDS', chaines)).toEqual(['CA| RDS FHD', 'RDS HD']));
it('TVAS', () => expect(matchBroadcaster('TVAS', chaines)).toEqual(['TVA Sports ᴴᴰ']));
it('TSN famille', () => expect(matchBroadcaster('TSN', chaines)).toEqual(['TSN 1', 'TSN 4 FHD']));
it('TSN+', () => expect(matchBroadcaster('TSN+', chaines)).toEqual(['TSN+']));
it('sans alias : égalité', () => expect(matchBroadcaster('ESPN', chaines)).toEqual(['ESPN']));
it('inconnu', () => expect(matchBroadcaster('Fox', chaines)).toEqual([]));
it('canadien', () => { expect(isCanadianBroadcaster('TVA Sports')).toBe(true); expect(isCanadianBroadcaster('ESPN')).toBe(false); });
```

- [ ] **Step 2: Vérifier l'échec** — Run: `npm test -w tools/source-sport` — Expected: FAIL.
- [ ] **Step 3: Implémenter `normalize.ts` et `match.ts`**
- [ ] **Step 4: Vérifier le succès** — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(source-sport): normalisation des chaînes et rapprochement des diffuseurs"`

---

### Task 7: Calendriers ESPN et rapport d'évaluation sportive

**Files:**
- Create: `tools/source-sport/src/espn.ts`, `tools/source-sport/src/evaluate.ts`, `tools/source-sport/fixtures/*.json`
- Test: `tools/source-sport/test/espn.test.ts`
- Create: `docs/rapports/source-sport.md`

**Interfaces:**
- Consumes: `matchBroadcaster`, `isCanadianBroadcaster`, `normalizeChannelName` (tâche 6)
- Produces:
  - `type LeagueKey = 'nhl' | 'nfl' | 'nba' | 'esp.1' | 'uefa.champions' | 'esp.copa_del_rey' | 'esp.super_cup'`
  - `LEAGUE_PATHS: Record<LeagueKey, string>` — `hockey/nhl`, `football/nfl`, `basketball/nba`, `soccer/esp.1`, `soccer/uefa.champions`, `soccer/esp.copa_del_rey`, `soccer/esp.super_cup`
  - `interface Game { league: LeagueKey; start: string; name: string; teams: string[]; broadcasts: string[] }`
  - `scoreboardUrl(league: LeagueKey, from: string, to: string): string` — `https://site.api.espn.com/apis/site/v2/sports/{path}/scoreboard?dates={from}-{to}` (dates `AAAAMMJJ`)
  - `parseScoreboard(league: LeagueKey, json: unknown): Game[]` — `broadcasts` = union sans doublons de `competitions[0].broadcasts[].names[]` et de `competitions[0].geoBroadcasts[].media.shortName`
  - `evaluate.ts` (CLI `npm run evaluer -w tools/source-sport`) : 14 prochains jours ; matchs des **Canadiens** (`nhl`, équipe contenant « Canadiens »), du **FC Barcelone** (4 compétitions soccer, équipe contenant « Barcelona »), toute la **NFL**, toute la **NBA** ; imprime en Markdown, par groupe : nombre de matchs, % avec au moins un diffuseur, % avec au moins un diffuseur canadien, puis la liste des matchs Canadiens et Barça avec leurs diffuseurs

- [ ] **Step 1: Enregistrer de vraies réponses** — `curl -s "<scoreboardUrl(nhl, aujourd'hui, +14 j)>" > fixtures/nhl.json`, idem `esp.1` et `nfl`. Les ouvrir et noter, pour le premier match de chaque fichier, son nom et ses diffuseurs. Si l'API ne répond pas depuis l'environnement, le noter dans le rapport et s'arrêter à cette tâche (décision reportée).
- [ ] **Step 2: Écrire les tests** — pour chaque fixture : `parseScoreboard(...).length === json.events.length`, et le premier `Game` a `name` et `broadcasts` égaux aux valeurs notées à l'étape 1 (copiées dans le test) ; un événement sans `broadcasts` ni `geoBroadcasts` donne `broadcasts: []`.
- [ ] **Step 3: Vérifier l'échec** — Expected: FAIL.
- [ ] **Step 4: Implémenter `espn.ts` et `evaluate.ts`**
- [ ] **Step 5: Vérifier le succès** — Run: `npm test -w tools/source-sport` — Expected: PASS.
- [ ] **Step 6: Lancer l'évaluation et écrire le rapport** — Run: `npm run evaluer -w tools/source-sport > docs/rapports/source-sport.md`. Ajouter en fin de fichier une section **Décision** selon la règle : si **≥ 80 %** des matchs des Canadiens **et** du Barça ont au moins un diffuseur canadien → « Mes équipes » comme dans la spec §6.4 ; sinon → version réduite (horaires depuis ESPN + recherche du match dans le guide et les noms de chaînes du fournisseur) et noter qu'une autre source est à chercher. Indiquer aussi la couverture NFL/NBA.
- [ ] **Step 7: Commit** — `git add tools/source-sport docs/rapports/source-sport.md && git commit -m "feat(source-sport): évaluation de la source ESPN et décision"`

---

### Task 8: Déploiement de la page de test et campagne de tests du propriétaire

**Files:**
- Create: `docs/rapports/lecture-navigateur.md`
- Modify: `docs/superpowers/specs/2026-11-14-player-iptv-design.md` (§6.10 et §9 : résultats et décision)

- [ ] **Step 1: Demander l'accord du propriétaire** pour déployer `tools/test-lecture` sur un hébergement gratuit en https (Vercel, sur **son** compte). Sans accord : s'arrêter ici et le signaler.
- [ ] **Step 2: Déployer** (racine du projet Vercel : `tools/test-lecture`). `AUTORISER_LOCAL` n'est **pas** défini en production. Vérifier que `POST /api/analyse` avec `{ "server": "localhost" … }` renvoie `400`.
- [ ] **Step 3: Envoyer au propriétaire** l'adresse de la page et la consigne : l'ouvrir sur **iPhone (Safari)**, **Mac (Safari)** et **Windows (Chrome ou Edge)**, **quand personne ne regarde la télé** (une seule connexion), lancer le test, coller les trois rapports dans la conversation.
- [ ] **Step 4: Enregistrer les rapports** dans `docs/rapports/lecture-navigateur.md` (vérifier à nouveau qu'aucun identifiant n'y figure) et écrire la section **Décision** :
  - films/épisodes lus en `natif` sur les trois appareils pour l'essentiel du catalogue → le site lit lui-même, « Ouvrir dans VLC » en dépannage ;
  - échecs `contenu-mixte` → le fournisseur est en `http` : présenter au propriétaire les options (relais vidéo et son coût, petite app de bureau) **avant** le plan 6 ;
  - échecs `format` sur une part importante (ex. MKV) → idem, en chiffrant la conversion vidéo.
- [ ] **Step 5: Mettre à jour la spec** §6.10 et §9 avec les résultats et la décision ; mettre à jour la feuille de route si l'ordre change.
- [ ] **Step 6: Commit** — `git add docs && git commit -m "docs: résultats des vérifications et décisions"`

---

## Addendum (2026-11-14) — sonde depuis un serveur

Le rapport n°1 (`docs/rapports/lecture-navigateur.md`) montre un fournisseur en `http` uniquement et 75 % de MKV ; le propriétaire a choisi un **serveur relais** (spec §6.10). Avant de le construire, deux questions : le fournisseur sert-il la **vidéo** à l'adresse d'un serveur (pas seulement l'API) ? Quels **codecs** sont dans les fichiers (reconditionnement simple ou conversion du son) ?

### Task 9: Détection des codecs dans les premiers octets

**Files:** Create `tools/test-lecture/src/codecs.ts` · Test `tools/test-lecture/test/codecs.test.ts`

**Interfaces — Produces:**
- `interface CodecInfo { container: 'mkv' | 'mp4' | 'ts' | 'inconnu'; video: string[]; audio: string[] }`
- `detectCodecs(head: Uint8Array): CodecInfo` — MKV : signature EBML `1A 45 DF A3` puis identifiants ASCII `V_…` / `A_…` (ex. `V_MPEG4/ISO/AVC` → `h264`, `V_MPEGH/ISO/HEVC` → `hevc`, `A_AAC` → `aac`, `A_AC3` → `ac3`, `A_EAC3` → `eac3`, `A_DTS` → `dts`, `A_OPUS` → `opus`) ; MP4 : boîte `ftyp` puis codes `avc1`, `hvc1`/`hev1`, `mp4a`, `ac-3`, `ec-3` ; TS : octet `0x47` tous les 188 octets, type de flux dans la PMT (`0x1B` h264, `0x24` hevc, `0x0F` aac, `0x81` ac3, `0x87` eac3) ; sans doublons.

- [ ] Tests : un en-tête MKV fabriqué contenant `V_MPEG4/ISO/AVC` et `A_EAC3` → `{ container: 'mkv', video: ['h264'], audio: ['eac3'] }` ; un en-tête MP4 avec `ftyp`, `hvc1`, `mp4a` → `{ container: 'mp4', video: ['hevc'], audio: ['aac'] }` ; un paquet TS avec une PMT (h264 + aac) → `{ container: 'ts', video: ['h264'], audio: ['aac'] }` ; octets quelconques → `{ container: 'inconnu', video: [], audio: [] }`.
- [ ] RED, implémentation, GREEN, commit.

### Task 10: Fonction `/api/sonde` et section du rapport

**Files:** Create `tools/test-lecture/api/sonde.ts` · Modify `src/main.ts`, `src/report.ts` · Test `test/sonde.test.ts`, `test/report.test.ts`

**Interfaces:**
- Consumes : `validateRequest`, `assertPublicUrl` (tâche 3), `streamUrl` (tâche 1), `detectCodecs` (tâche 9), `Sample` (tâche 2)
- Produces :
  - `interface ProbeResult { sample: Sample; httpStatus: number | null; contentType: string; bytes: number; codecs: CodecInfo; error?: string }`
  - `probe(creds, samples: Sample[], opts?: { checkUrl?; maxBytes?: number; pauseMs?: number }): Promise<ProbeResult[]>` — **une sonde à la fois**, lecture des `maxBytes` premiers octets (défaut 2 Mo) puis **coupure**, pause `pauseMs` (défaut 3 s) avant la suivante ; redirections suivies à la main (3 max) avec `checkUrl` à chaque saut ; délai global 50 s.
  - `validateSamples(v: unknown): Sample[] | null` — au plus 6 ; `kind` ∈ live/movie/series ; `id` en chiffres ; `ext` ∈ m3u8, ts, mkv, mp4, avi, m4v.
  - `POST /api/sonde` body `{ server, username, password, samples }` → `200 ProbeResult[]`
  - Rapport : section « Sonde depuis un serveur » avec `| Type | Extension | HTTP | Octets reçus | Conteneur | Vidéo | Audio |`.
- Échantillons envoyés par la page : 1 chaîne en `ts`, puis jusqu'à 2 films et 2 épisodes, en préférant le MKV, puis 1 MP4.

- [ ] Tests `probe` contre le faux serveur (servir un faux MKV de quelques Ko) : statut 200, octets > 0, codecs détectés ; mauvais identifiants → statut d'erreur rapporté sans exception ; `validateSamples` refuse un `id` non numérique et une extension hors liste ; le rapport contient la nouvelle section et aucun identifiant.
- [ ] RED, implémentation, GREEN, vérification de bout en bout avec le faux serveur, commit, redéploiement, nouveau rapport du propriétaire (un seul appareil suffit).
