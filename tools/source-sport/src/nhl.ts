import type { Game } from './espn';

/** API officielle de la LNH : une semaine de matchs à partir de la date, diffuseurs canadiens inclus. */
export function nhlScheduleUrl(date: string): string {
  return `https://api-web.nhle.com/v1/schedule/${date}`;
}

type Obj = Record<string, unknown>;
const obj = (v: unknown): Obj => (v && typeof v === 'object' ? (v as Obj) : {});
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const teamName = (t: unknown) => String(obj(obj(t).commonName).default ?? obj(t).abbrev ?? '');

export function parseNhlSchedule(json: unknown): Game[] {
  return arr(obj(json).gameWeek).flatMap((day) => arr(obj(day).games).map((gv) => {
    const g = obj(gv);
    const away = teamName(g.awayTeam);
    const home = teamName(g.homeTeam);
    return {
      league: 'nhl' as const,
      start: String(g.startTimeUTC ?? ''),
      name: `${away} at ${home}`,
      teams: [away, home],
      broadcasts: [...new Set(arr(g.tvBroadcasts).map((b) => String(obj(b).network ?? '')).filter(Boolean))],
    };
  }));
}
