import { compactKey, normalizeChannelName } from './normalize';

const tsnN = [1, 2, 3, 4, 5].map((n) => `tsn ${n}`);
const sportsnet = ['sportsnet', 'sportsnet ontario', 'sportsnet east', 'sportsnet west', 'sportsnet pacific'];

/** Clé = nom de diffuseur normalisé sans espaces → noms de chaînes normalisés acceptés. */
export const BROADCASTER_ALIASES: Record<string, string[]> = {
  rds: ['rds'],
  rds2: ['rds 2'],
  rdsinfo: ['rds info'],
  tvas: ['tva sports'], tvasports: ['tva sports'],
  tvas2: ['tva sports 2'], tvasports2: ['tva sports 2'],
  tsn: ['tsn', ...tsnN],
  ...Object.fromEntries(tsnN.map((n) => [n.replace(' ', ''), [n]])),
  tsnplus: ['tsn plus'],
  sn: sportsnet, sportsnet,
  sn1: ['sportsnet one'], sportsnetone: ['sportsnet one'],
  sn360: ['sportsnet 360'], sportsnet360: ['sportsnet 360'],
  snnow: ['sportsnet plus'], sportsnetplus: ['sportsnet plus'],
  cbc: ['cbc'],
  citytv: ['citytv'],
  dazn: ['dazn', 'dazn 1', 'dazn 2'],
  primevideo: ['prime video'],
};

const CANADIAN_KEYS = new Set(Object.keys(BROADCASTER_ALIASES).filter((k) => k !== 'primevideo'));

/** Renvoie les noms d'origine des chaînes du fournisseur qui correspondent au diffuseur, dans l'ordre reçu. */
export function matchBroadcaster(broadcaster: string, channels: string[]): string[] {
  const accepted = BROADCASTER_ALIASES[compactKey(broadcaster)] ?? [normalizeChannelName(broadcaster)];
  return channels.filter((c) => accepted.includes(normalizeChannelName(c)));
}

export function isCanadianBroadcaster(broadcaster: string): boolean {
  return CANADIAN_KEYS.has(compactKey(broadcaster));
}
