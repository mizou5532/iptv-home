import type { CatalogSummary } from './summary';
import type { FailureReason, PlayResult } from './playback';
import type { XtreamCreds } from './xtream';
import { sanitize } from './sanitize';
import type { ProbeResult } from '../api/sonde';

export interface TestRun {
  date: string;
  userAgent: string;
  pageProtocol: string;
  directApi: 'ok' | FailureReason;
  summary: CatalogSummary | { error: string };
  results: PlayResult[];
  /** Vrai si la lecture dans le navigateur n'a pas été tentée (contenu mixte certain). */
  browserSkipped?: boolean;
  probes?: ProbeResult[] | { error: string };
}

const KIND_LABEL = { live: 'Direct', movie: 'Film', series: 'Épisode' } as const;
const fmtExts = (e: Record<string, number>) =>
  Object.entries(e).sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k} : ${v}`).join(', ') || '—';

export function buildReport(run: TestRun, creds: Pick<XtreamCreds, 'username' | 'password'>): string {
  const lines: string[] = [
    '## Rapport de test de lecture',
    '',
    `- Appareil / navigateur : ${run.userAgent}`,
    `- Date : ${run.date}`,
    `- Protocole de la page : ${run.pageProtocol.replace(':', '')}`,
  ];
  if ('error' in run.summary) {
    lines.push(`- Accès direct à l'API depuis le navigateur : ${run.directApi}`, `- Analyse du catalogue : échec (${run.summary.error})`);
  } else {
    const s = run.summary;
    lines.push(
      `- Protocole du serveur : ${s.serverProtocol}`,
      `- Accès direct à l'API depuis le navigateur : ${run.directApi}`,
      `- Connexions simultanées autorisées : ${s.maxConnections ?? 'inconnu'}`,
      `- Formats de sortie autorisés : ${s.allowedOutputFormats.join(', ') || '—'}`,
      `- Catalogue : ${s.counts.live} chaînes, ${s.counts.movies} films, ${s.counts.series} séries`,
      `- Extensions des films : ${fmtExts(s.movieExtensions)}`,
      `- Extensions des épisodes (séries échantillonnées) : ${fmtExts(s.episodeExtensions)}`,
    );
  }
  if (run.browserSkipped) {
    lines.push('', 'Lecture dans le navigateur non testée : le fournisseur est en http, tout navigateur la bloque depuis une page https (contenu mixte).');
  } else {
    lines.push('', '| Type | Extension | Moteur | Résultat | Raison | Délai |', '|---|---|---|---|---|---|');
  }
  for (const r of run.results) {
    const delay = r.msToFirstFrame !== undefined ? `${r.msToFirstFrame} ms` : '';
    const reason = r.ok ? '' : [r.reason, r.detail].filter(Boolean).join(' — ');
    lines.push(`| ${KIND_LABEL[r.sample.kind]} | ${r.sample.ext} | ${r.engine} | ${r.ok ? '✓' : '✗'} | ${reason} | ${delay} |`);
  }
  if (!run.browserSkipped) {
    const okCount = run.results.filter((r) => r.ok).length;
    lines.push('', `**Conclusion : ${okCount} lectures réussies sur ${run.results.length}.**`);
  }
  if (run.probes) {
    lines.push('', '## Sonde depuis un serveur', '');
    if ('error' in run.probes) {
      lines.push(`Sonde : échec (${run.probes.error})`);
    } else {
      lines.push('| Type | Extension | Essais (agent → HTTP) | Octets reçus | Conteneur | Vidéo | Audio | Message |', '|---|---|---|---|---|---|---|---|');
      for (const p of run.probes) {
        const tries = (p.attempts ?? []).map((a) => `${a.agent} → ${a.status ?? '—'}`).join(', ');
        const status = [tries, p.error].filter(Boolean).join(', ') || (p.httpStatus === null ? '—' : String(p.httpStatus));
        const message = (p.message ?? '').replace(/\|/g, '/');
        lines.push(`| ${KIND_LABEL[p.sample.kind]} | ${p.sample.ext} | ${status} | ${p.bytes} | ${p.codecs.container} | ${p.codecs.video.join(', ') || '—'} | ${p.codecs.audio.join(', ') || '—'} | ${message} |`);
      }
    }
  }
  return sanitize(lines.join('\n'), creds);
}
