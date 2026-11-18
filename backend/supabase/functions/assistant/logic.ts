// Connecteur pour IA (protocole MCP, « Streamable HTTP ») : liste des outils et traduction des messages JSON-RPC.
// Logique pure, testée avec vitest ; index.ts ne fait que l'appel au serveur (ai_call) avec la clé de l'utilisateur.

type Schema = Record<string, unknown>;
export interface Tool { name: string; description: string; inputSchema: Schema; write?: boolean }

const str = (description: string) => ({ type: 'string', description });
const ids = (description: string) => ({ type: 'array', items: { type: 'string' }, description });
const obj = (properties: Record<string, unknown>, required: string[] = []) => ({ type: 'object', properties, required, additionalProperties: false });
const section = { type: 'string', enum: ['direct', 'films', 'series'], description: 'direct (chaînes), films ou series' };
const profil = str('Nom du profil (« Hamza », « Maha ») ou son identifiant');

export const TOOLS: Tool[] = [
  { name: 'profils', description: 'Liste les profils de la maison (identifiant et nom).', inputSchema: obj({}) },
  { name: 'categories', description: 'Catégories d’une section, avec leur état de masquage (pour tout le monde et par profil). Utilise « recherche » pour filtrer par nom (« sport », « FR| »).',
    inputSchema: obj({ section, recherche: str('Partie du nom, facultatif') }, ['section']) },
  { name: 'chaines', description: 'Chaînes du Direct (identifiant, nom, catégorie). Filtre par « recherche » (partie du nom) et/ou « categorie » (identifiant). 100 par défaut, 300 au plus.',
    inputSchema: obj({ recherche: str('Partie du nom, facultatif'), categorie: str('Identifiant de catégorie, facultatif'), limite: { type: 'integer', minimum: 1, maximum: 300 } }) },
  { name: 'masquages', description: 'Ce qui est masqué (pour tout le monde, et pour le profil donné).', inputSchema: obj({ profil }) },
  { name: 'masquer', write: true, description: 'Masque des catégories ou des titres. Sans « profil » : pour tout le monde (une catégorie du Direct masquée pour tout le monde n’est plus téléchargée). Une sauvegarde est faite avant.',
    inputSchema: obj({ section, sorte: { type: 'string', enum: ['categorie', 'titre'], description: 'categorie, ou titre (chaîne, film ou série)' }, ids: ids('Identifiants à masquer'), profil }, ['section', 'sorte', 'ids']) },
  { name: 'afficher', write: true, description: 'Réaffiche des catégories ou des titres masqués (même paramètres que « masquer »).',
    inputSchema: obj({ section, sorte: { type: 'string', enum: ['categorie', 'titre'] }, ids: ids('Identifiants à réafficher'), profil }, ['section', 'sorte', 'ids']) },
  { name: 'favoris_ajouter', write: true, description: 'Ajoute des chaînes aux favoris d’un profil.', inputSchema: obj({ profil, chaines: ids('Identifiants de chaînes') }, ['profil', 'chaines']) },
  { name: 'favoris_retirer', write: true, description: 'Retire des chaînes des favoris d’un profil.', inputSchema: obj({ profil, chaines: ids('Identifiants de chaînes') }, ['profil', 'chaines']) },
  { name: 'playlists', description: 'Playlists (avec leurs chaînes), de tous les profils ou d’un seul.', inputSchema: obj({ profil }) },
  { name: 'playlist_creer', write: true, description: 'Crée une playlist pour un profil, avec des chaînes dans l’ordre donné.',
    inputSchema: obj({ profil, nom: str('Nom de la playlist (40 caractères au plus)'), chaines: ids('Identifiants de chaînes, dans l’ordre') }, ['profil', 'nom']) },
  { name: 'playlist_ajouter', write: true, description: 'Ajoute des chaînes à la fin d’une playlist.', inputSchema: obj({ playlist: str('Identifiant de la playlist'), chaines: ids('Identifiants de chaînes') }, ['playlist', 'chaines']) },
  { name: 'playlist_retirer', write: true, description: 'Retire des chaînes d’une playlist.', inputSchema: obj({ playlist: str('Identifiant de la playlist'), chaines: ids('Identifiants de chaînes') }, ['playlist', 'chaines']) },
  { name: 'playlist_supprimer', write: true, description: 'Supprime une playlist.', inputSchema: obj({ playlist: str('Identifiant de la playlist') }, ['playlist']) },
  { name: 'annuler', write: true, description: 'Annule les derniers changements faits par une IA : remet l’organisation (masquages, favoris, playlists…) de la dernière sauvegarde « avant IA ». Appeler encore remonte d’un cran.', inputSchema: obj({}) },
  { name: 'journal', description: 'Les 30 derniers changements faits par une IA.', inputSchema: obj({}) },
];

export const INSTRUCTIONS = 'Organisation de la télé IPTV de la maison : profils, catégories, chaînes, masquages, favoris et playlists. ' +
  'Lis d’abord (profils, categories, chaines) pour trouver les identifiants, puis modifie. Chaque série de changements est précédée ' +
  'd’une sauvegarde ; « annuler » revient en arrière. Les identifiants IPTV et les appareils ne sont jamais accessibles.';

const ERRORS: Record<string, string> = {
  'cle-invalide': 'Clé d’accès invalide ou coupée : crée un nouvel accès sur la page iPhone (Réglages → Accès pour les IA).',
  'profil-inconnu': 'Profil inconnu : utilise l’outil « profils ».',
  'profil-requis': 'Indique le profil (« profil »).',
  'playlist-inconnue': 'Playlist inconnue : utilise l’outil « playlists » pour son identifiant.',
  'rien-a-annuler': 'Rien à annuler : aucune sauvegarde « avant IA ».',
  'ids-requis': 'Donne entre 1 et 2000 identifiants dans « ids ».',
  'section-ou-sorte-inconnue': 'Section : direct, films ou series ; sorte : categorie ou titre.',
};
export function friendlyError(message: string): string {
  for (const k of Object.keys(ERRORS)) if (message.includes(k)) return ERRORS[k]!;
  return 'Erreur du serveur de la maison. Réessaie dans un instant.';
}

export type Rpc = { jsonrpc: '2.0'; id?: string | number | null; method: string; params?: any };
export type Call = (tool: string, args: Record<string, unknown>) => Promise<unknown>;

/** Réponse à un message JSON-RPC ; `null` pour une notification (pas de réponse). */
export async function handle(msg: Rpc, call: Call): Promise<object | null> {
  const ok = (result: unknown) => ({ jsonrpc: '2.0', id: msg.id ?? null, result });
  const fail = (code: number, message: string) => ({ jsonrpc: '2.0', id: msg.id ?? null, error: { code, message } });
  if (msg.id === undefined || msg.id === null) return null; // notification (« notifications/initialized »…)
  switch (msg.method) {
    case 'initialize':
      return ok({
        protocolVersion: typeof msg.params?.protocolVersion === 'string' ? msg.params.protocolVersion : '2025-06-18',
        capabilities: { tools: {} },
        serverInfo: { name: 'iptv-maison', version: '1.0.0' },
        instructions: INSTRUCTIONS,
      });
    case 'ping': return ok({});
    case 'tools/list':
      return ok({ tools: TOOLS.map(({ name, description, inputSchema, write }) => ({
        name, description, inputSchema, annotations: { readOnlyHint: !write, destructiveHint: !!write },
      })) });
    case 'tools/call': {
      const name = String(msg.params?.name ?? '');
      if (!TOOLS.some((t) => t.name === name)) return fail(-32602, `Outil inconnu : ${name}`);
      const args = (msg.params?.arguments ?? {}) as Record<string, unknown>;
      try {
        const out = await call(name, args);
        return ok({ content: [{ type: 'text', text: JSON.stringify(out ?? null) }] });
      } catch (e) {
        return ok({ content: [{ type: 'text', text: friendlyError(String((e as Error)?.message ?? e)) }], isError: true });
      }
    }
    default: return fail(-32601, `Méthode inconnue : ${msg.method}`);
  }
}
