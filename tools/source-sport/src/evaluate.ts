// Évalue, sur les 14 prochains jours, si les sources gratuites donnent les diffuseurs canadiens des matchs suivis.
// Usage : npm run evaluer -w tools/source-sport > docs/rapports/source-sport.md
import { parseScoreboard, scoreboardUrl, type Game, type LeagueKey } from './espn';
import { nhlScheduleUrl, parseNhlSchedule } from './nhl';
import { isCanadianBroadcaster } from './match';

const DAYS = 14;
const day = (offset: number) => new Date(Date.now() + offset * 86_400_000);
const ymd = (d: Date) => d.toISOString().slice(0, 10).replace(/-/g, '');
const iso = (d: Date) => d.toISOString().slice(0, 10);

async function getJson(url: string): Promise<unknown> {
  const res = await fetch(url, { headers: { 'Accept-Encoding': 'gzip' } });
  if (!res.ok) throw new Error(`HTTP ${res.status} pour ${new URL(url).host}`);
  return res.json();
}

async function espn(league: LeagueKey): Promise<Game[]> {
  const out: Game[] = [];
  for (let i = 0; i < DAYS; i++) out.push(...parseScoreboard(league, await getJson(scoreboardUrl(league, ymd(day(i))))));
  return out;
}

async function nhlOfficial(): Promise<Game[]> {
  const end = day(DAYS).toISOString();
  const now = day(0).toISOString().slice(0, 10);
  const weeks = [...parseNhlSchedule(await getJson(nhlScheduleUrl(iso(day(0))))), ...parseNhlSchedule(await getJson(nhlScheduleUrl(iso(day(7)))))];
  const seen = new Set<string>();
  return weeks.filter((g) => g.start >= now && g.start < end && !seen.has(g.start + g.name) && seen.add(g.start + g.name));
}

const pct = (n: number, d: number) => (d === 0 ? '—' : `${Math.round((100 * n) / d)} %`);

function group(title: string, games: Game[], listGames: boolean): string {
  const withAny = games.filter((g) => g.broadcasts.length > 0).length;
  const withCa = games.filter((g) => g.broadcasts.some(isCanadianBroadcaster)).length;
  const lines = [`### ${title}`, '', `- Matchs : ${games.length}`, `- Avec au moins un diffuseur : ${pct(withAny, games.length)}`, `- Avec au moins un diffuseur canadien : ${pct(withCa, games.length)}`];
  if (listGames && games.length) {
    lines.push('', '| Date (UTC) | Match | Diffuseurs |', '|---|---|---|');
    for (const g of games) lines.push(`| ${g.start.slice(0, 16).replace('T', ' ')} | ${g.name} | ${g.broadcasts.join(', ') || '—'} |`);
  }
  return lines.join('\n');
}

async function main() {
  const has = (re: RegExp) => (g: Game) => g.teams.some((t) => re.test(t)) || re.test(g.name);
  const nhlEspn = await espn('nhl');
  const nhlApi = await nhlOfficial();
  const soccer = (await Promise.all((['esp.1', 'uefa.champions', 'esp.copa_del_rey', 'esp.super_cup'] as LeagueKey[]).map(espn))).flat();
  const nfl = await espn('nfl');
  const nba = await espn('nba');

  console.log([
    '# Rapport — source des diffuseurs sportifs',
    '',
    `Période : ${iso(day(0))} → ${iso(day(DAYS - 1))} (${DAYS} jours). Sources : API publique d'ESPN (un appel par jour et par ligue) et API officielle de la LNH (api-web.nhle.com).`,
    '',
    group('Canadiens — API officielle LNH', nhlApi.filter(has(/Canadiens/)), true),
    '',
    group('Canadiens — ESPN', nhlEspn.filter(has(/Canadiens/)), true),
    '',
    group('Toute la LNH — API officielle LNH', nhlApi, false),
    '',
    group('FC Barcelone — ESPN (Liga, Ligue des champions, Coupe du Roi, Supercoupe)', soccer.filter(has(/Barcelona/)), true),
    '',
    group('NFL — ESPN', nfl, false),
    '',
    group('NBA — ESPN', nba, false),
  ].join('\n'));
}

main().catch((e) => { console.error(String(e)); process.exit(1); });
