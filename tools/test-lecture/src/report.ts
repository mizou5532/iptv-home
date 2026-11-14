import type { CatalogSummary } from './summary';
import type { FailureReason, PlayResult } from './playback';
import type { XtreamCreds } from './xtream';
import { sanitize } from './sanitize';

export interface TestRun {
  date: string;
  userAgent: string;
  pageProtocol: string;
  directApi: 'ok' | FailureReason;
  summary: CatalogSummary | { error: string };
  results: PlayResult[];
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
  lines.push('', '| Type | Extension | Moteur | Résultat | Raison | Délai |', '|---|---|---|---|---|---|');
  for (const r of run.results) {
    const delay = r.msToFirstFrame !== undefined ? `${r.msToFirstFrame} ms` : '';
    const reason = r.ok ? '' : [r.reason, r.detail].filter(Boolean).join(' — ');
    lines.push(`| ${KIND_LABEL[r.sample.kind]} | ${r.sample.ext} | ${r.engine} | ${r.ok ? '✓' : '✗'} | ${reason} | ${delay} |`);
  }
  const okCount = run.results.filter((r) => r.ok).length;
  lines.push('', `**Conclusion : ${okCount} lectures réussies sur ${run.results.length}.**`);
  return sanitize(lines.join('\n'), creds);
}
