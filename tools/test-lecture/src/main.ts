import { apiUrl, normalizeServer, streamUrl, type XtreamCreds } from './xtream';
import { classifyFailure, enginesFor, tryPlay, type FailureReason, type PlayResult } from './playback';
import { buildReport, type TestRun } from './report';
import type { CatalogSummary } from './summary';
import { sanitize } from './sanitize';
import { browserPlaybackPointless, pickProbeSamples } from './probe-plan';
import type { ProbeResult } from '../api/sonde';

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
const form = $<HTMLFormElement>('form');
const startBtn = $<HTMLButtonElement>('start');
const steps = $<HTMLOListElement>('steps');
const video = $<HTMLVideoElement>('video');
const reportBox = $<HTMLTextAreaElement>('report');

/** Laisse le fournisseur clore la session précédente (une seule connexion autorisée). */
const PAUSE_ENTRE_ESSAIS_MS = 5_000;
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

let creds: XtreamCreds | null = null;
startBtn.disabled = false; // le formulaire reste inactif tant que ce script n'a pas tourné

function step(text: string, state: 'ok' | 'ko' | '' = ''): HTMLLIElement {
  const li = document.createElement('li');
  li.textContent = creds ? sanitize(text, creds) : text;
  if (state) li.className = state;
  steps.append(li);
  return li;
}
function settle(li: HTMLLIElement, text: string, ok: boolean) {
  li.textContent = creds ? sanitize(text, creds) : text;
  li.className = ok ? 'ok' : 'ko';
}

async function checkDirectApi(c: XtreamCreds): Promise<'ok' | FailureReason> {
  const url = apiUrl(c);
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(15_000) });
    await res.json();
    return 'ok';
  } catch {
    const r = classifyFailure({ pageProtocol: location.protocol, url, engineNetworkError: true });
    return r;
  }
}

async function getSummary(c: XtreamCreds): Promise<CatalogSummary | { error: string }> {
  try {
    const res = await fetch('/api/analyse', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(c) });
    const body = await res.json();
    return res.ok ? (body as CatalogSummary) : { error: String(body?.error ?? `http-${res.status}`) };
  } catch {
    return { error: 'fonction-analyse-injoignable' };
  }
}

async function getProbes(c: XtreamCreds, samples: CatalogSummary['samples']): Promise<ProbeResult[] | { error: string }> {
  try {
    const res = await fetch('/api/sonde', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...c, samples }) });
    const body = await res.json();
    return res.ok ? (body as ProbeResult[]) : { error: String(body?.error ?? `http-${res.status}`) };
  } catch {
    return { error: 'fonction-sonde-injoignable' };
  }
}

async function support(): Promise<{ hls: boolean; mpegts: boolean }> {
  const [Hls, mpegts] = await Promise.all([import('hls.js'), import('mpegts.js')]);
  return { hls: Hls.default.isSupported(), mpegts: mpegts.default.getFeatureList().mseLivePlayback };
}

form.addEventListener('submit', async (e) => {
  e.preventDefault();
  creds = {
    server: normalizeServer($<HTMLInputElement>('server').value),
    username: $<HTMLInputElement>('username').value,
    password: $<HTMLInputElement>('password').value,
  };
  startBtn.disabled = true;
  steps.replaceChildren();
  $('progress').hidden = false;
  $('result').hidden = true;

  const s1 = step('Accès direct à l\'API depuis ce navigateur…');
  const directApi = await checkDirectApi(creds);
  settle(s1, `Accès direct à l'API : ${directApi}`, directApi === 'ok');

  const s2 = step('Analyse du catalogue…');
  const summary = await getSummary(creds);
  if ('error' in summary) settle(s2, `Analyse du catalogue : échec (${summary.error})`, false);
  else settle(s2, `Catalogue : ${summary.counts.live} chaînes, ${summary.counts.movies} films, ${summary.counts.series} séries`, true);

  const results: PlayResult[] = [];
  let browserSkipped = false;
  let probes: ProbeResult[] | { error: string } | undefined;
  if (!('error' in summary) && browserPlaybackPointless(location.protocol, summary.serverProtocol)) {
    browserSkipped = true;
    step('Lecture dans ce navigateur : non testée (fournisseur en http, bloqué depuis une page https)', 'ko');
  } else if (!('error' in summary)) {
    const sup = await support();
    let first = true;
    for (const sample of summary.samples) {
      const attempts = enginesFor(sample, sup);
      if (attempts.length === 0) step(`Ignoré : ${sample.label} (extension inconnue)`);
      for (const { engine, ext } of attempts) {
        if (!first) await sleep(PAUSE_ENTRE_ESSAIS_MS);
        first = false;
        const li = step(`Lecture : ${sample.label} (${ext}, ${engine})…`);
        const r = await tryPlay(video, streamUrl(creds, sample.kind, sample.id, ext), engine);
        results.push({ sample: { ...sample, ext }, engine, ...r });
        settle(li, `${r.ok ? '✓' : '✗'} ${sample.label} (${ext}, ${engine})${r.ok ? ` — ${r.msToFirstFrame} ms` : ` — ${r.reason}`}`, r.ok);
      }
    }
  }

  if (!('error' in summary)) {
    const picked = pickProbeSamples(summary.samples);
    const s3 = step(`Sonde depuis un serveur (${picked.length} vidéos, environ 1 minute)…`);
    probes = await getProbes(creds, picked);
    if ('error' in probes) settle(s3, `Sonde : échec (${probes.error})`, false);
    else settle(s3, `Sonde : ${probes.filter((p) => p.httpStatus === 200 && p.bytes > 0).length} vidéos reçues sur ${probes.length}`, probes.some((p) => p.bytes > 0));
  }

  const run: TestRun = { date: new Date().toISOString(), userAgent: navigator.userAgent, pageProtocol: location.protocol, directApi, summary, results, browserSkipped, probes };
  reportBox.value = buildReport(run, creds);
  $('result').hidden = false;
  startBtn.disabled = false;
});

$<HTMLButtonElement>('copy').addEventListener('click', async () => {
  try { await navigator.clipboard.writeText(reportBox.value); } catch { reportBox.select(); document.execCommand('copy'); }
  $<HTMLButtonElement>('copy').textContent = 'Copié !';
});
