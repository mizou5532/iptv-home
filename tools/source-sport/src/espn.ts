export type LeagueKey = 'nhl' | 'nfl' | 'nba' | 'esp.1' | 'uefa.champions' | 'esp.copa_del_rey' | 'esp.super_cup';

export const LEAGUE_PATHS: Record<LeagueKey, string> = {
  nhl: 'hockey/nhl',
  nfl: 'football/nfl',
  nba: 'basketball/nba',
  'esp.1': 'soccer/esp.1',
  'uefa.champions': 'soccer/uefa.champions',
  'esp.copa_del_rey': 'soccer/esp.copa_del_rey',
  'esp.super_cup': 'soccer/esp.super_cup',
};

export interface Game { league: LeagueKey; start: string; name: string; teams: string[]; broadcasts: string[] }

/** ESPN refuse les plages de dates (HTTP 400) : un appel par jour, date `AAAAMMJJ`. */
export function scoreboardUrl(league: LeagueKey, date: string): string {
  return `https://site.api.espn.com/apis/site/v2/sports/${LEAGUE_PATHS[league]}/scoreboard?dates=${date}`;
}

type Obj = Record<string, unknown>;
const obj = (v: unknown): Obj => (v && typeof v === 'object' ? (v as Obj) : {});
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);

export function parseScoreboard(league: LeagueKey, json: unknown): Game[] {
  return arr(obj(json).events).map((ev) => {
    const e = obj(ev);
    const comp = obj(arr(e.competitions)[0]);
    const names = [
      ...arr(comp.broadcasts).flatMap((b) => arr(obj(b).names).map(String)),
      ...arr(comp.geoBroadcasts).map((g) => obj(obj(g).media).shortName).filter((s): s is string => typeof s === 'string'),
    ];
    return {
      league,
      start: String(e.date ?? ''),
      name: String(e.name ?? ''),
      teams: arr(comp.competitors).map((c) => String(obj(obj(c).team).displayName ?? '')),
      broadcasts: [...new Set(names)],
    };
  });
}
