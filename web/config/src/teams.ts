// Équipes favorites d'un profil (onglet Sport de la télé) : profiles.settings.sport_teams.
// Le catalogue (teams-data.json) vient d'ESPN, généré par tools/sport/equipes-espn.py.
import type { SettingsDb } from './avatar';

export interface SportTeam { league: string; id: string; name: string; abbr?: string; logo?: string; color?: string }
export interface LeagueTeams { key: string; label: string; teams: { id: string; abbr: string; name: string; logo?: string | null; color?: string | null }[] }

/** Sans choix, la télé suit les Canadiens et le Barça : la page part de là. */
export const DEFAULT_TEAMS: SportTeam[] = [
  { league: 'nhl', id: '10', name: 'Canadiens de Montréal', abbr: 'MTL', logo: 'https://a.espncdn.com/i/teamlogos/nhl/500/mtl.png', color: '#AF1E2D' },
  { league: 'esp.1', id: '83', name: 'FC Barcelone', abbr: 'BAR', logo: 'https://a.espncdn.com/i/teamlogos/soccer/500/83.png', color: '#A50044' },
];
export const MAX_TEAMS = 8;

function isTeam(v: unknown): v is SportTeam {
  const t = v as SportTeam;
  return !!t && typeof t === 'object' && typeof t.league === 'string' && typeof t.id === 'string' && typeof t.name === 'string';
}

/** Équipes du profil ; [chosen] = faux tant que rien n'a été choisi (les équipes par défaut s'appliquent). */
export function teamsOf(settings: Record<string, unknown>): { teams: SportTeam[]; chosen: boolean } {
  const v = settings.sport_teams;
  return Array.isArray(v) ? { teams: v.filter(isTeam), chosen: true } : { teams: DEFAULT_TEAMS, chosen: false };
}

export function sameTeam(a: SportTeam, b: SportTeam): boolean { return a.league === b.league && a.id === b.id }

/** Ajoute (sans doublon, au plus 8) ou retire une équipe ; renvoie la nouvelle liste. */
export function withTeam(list: SportTeam[], team: SportTeam): SportTeam[] {
  if (list.some((t) => sameTeam(t, team))) return list;
  if (list.length >= MAX_TEAMS) throw new Error(`${MAX_TEAMS} équipes au plus.`);
  return [...list, team];
}
export function withoutTeam(list: SportTeam[], team: SportTeam): SportTeam[] { return list.filter((t) => !sameTeam(t, team)) }

/** Entrée du catalogue → équipe enregistrée (couleur « #rrggbb »). */
export function fromCatalog(league: string, t: LeagueTeams['teams'][number]): SportTeam {
  return { league, id: t.id, name: t.name, abbr: t.abbr || undefined, logo: t.logo ?? undefined, color: t.color ? `#${t.color.replace(/^#/, '')}` : undefined };
}

/** Enregistre la liste dans les réglages du profil (les autres réglages sont gardés). */
export async function saveTeams(db: SettingsDb, profileId: string, settings: Record<string, unknown>, teams: SportTeam[]): Promise<Record<string, unknown>> {
  const next = { ...settings, sport_teams: teams };
  const r = await db.from('profiles').update({ settings: next }).eq('id', profileId).select('id');
  if (r.error) throw new Error("Les équipes n'ont pas pu être enregistrées. Réessaie.");
  if (!Array.isArray(r.data) || r.data.length !== 1) throw new Error("Ce profil n'existe plus. Recharge la page.");
  return next;
}
