// Logique de la page de configuration. Le client Supabase est injecté (interface Db).
export interface DbResult { data: unknown; error: { message: string } | null }
export interface Db {
  rpc(fn: string, args?: object): Promise<DbResult>;
  from(table: string): { select(columns: string): PromiseLike<DbResult> };
}

export const MAX_PROFILES = 10;

export function normalizePairingCode(input: string): string {
  return input.replace(/[\s-]+/g, '').toUpperCase();
}

const MESSAGES: Record<string, string> = {
  'code-invalide': "Ce code n'existe pas ou a déjà été utilisé.",
  'code-expire': "Ce code a expiré. Sur la télé, un nouveau code va s'afficher.",
  'acces-refuse': 'Seul le compte du propriétaire peut ajouter un appareil.',
  'foyer-existe-deja': 'Un foyer existe déjà. Connecte-toi avec le compte du propriétaire.',
  'trop-de-profils': 'Dix profils au plus.',
  'identifiants-incomplets': 'Remplis les trois champs : adresse du serveur, nom d’utilisateur et mot de passe.',
};

function friendly(message: string, fallback = 'Une erreur est survenue. Réessaie dans un instant.'): string {
  for (const key of Object.keys(MESSAGES)) if (message.includes(key)) return MESSAGES[key];
  return fallback;
}

async function call(db: Db, fn: string, args?: object): Promise<unknown> {
  const { data, error } = await db.rpc(fn, args);
  if (error) throw new Error(friendly(error.message));
  return data;
}

export interface Device { userId: string; name: string; role: string }
export interface State { householdId: string | null; hasCredentials: boolean; devices: Device[] }

export async function loadState(db: Db): Promise<State> {
  const homes = (await call(db, 'my_households')) as { id: string }[] | null;
  const householdId = homes && homes.length > 0 ? homes[0].id : null;
  if (!householdId) return { householdId: null, hasCredentials: false, devices: [] };
  // On ne lit que l'identifiant du foyer : jamais le contenu des identifiants.
  const creds = await db.from('xtream_credentials').select('household_id');
  const members = await db.from('household_members').select('user_id, role, device_name');
  if (members.error) throw new Error(friendly(members.error.message));
  const rows = (members.data ?? []) as { user_id: string; role: string; device_name: string | null }[];
  return {
    householdId,
    hasCredentials: !creds.error && Array.isArray(creds.data) && creds.data.length > 0,
    devices: rows.map((m) => ({
      userId: m.user_id,
      role: m.role,
      name: m.role === 'owner' ? 'Propriétaire' : m.device_name ?? 'Appareil',
    })),
  };
}

export interface XtreamCreds { server: string; username: string; password: string }

const INCOMPLETE = "Remplis l'adresse du serveur, le nom d'utilisateur et le mot de passe IPTV.";
function checkCreds(c: XtreamCreds): void {
  if (!c.server.trim() || !c.username.trim() || !c.password.trim()) throw new Error(INCOMPLETE);
}

export async function setupHousehold(
  db: Db,
  input: { name: string; profiles: string[] } & XtreamCreds,
): Promise<string> {
  checkCreds(input);
  const profiles = input.profiles.map((p) => p.trim()).filter((p) => p.length > 0);
  if (profiles.length > MAX_PROFILES) throw new Error(MESSAGES['trop-de-profils']);
  const id = (await call(db, 'create_household', { name: input.name.trim(), profile_names: profiles })) as string;
  await updateCredentials(db, id, input);
  return id;
}

export async function updateCredentials(db: Db, householdId: string, creds: XtreamCreds): Promise<void> {
  checkCreds(creds);
  await call(db, 'set_xtream_credentials', {
    h: householdId, server: creds.server, username: creds.username, password: creds.password,
  });
}

export async function revokeDevice(db: Db, householdId: string, userId: string): Promise<void> {
  await call(db, 'revoke_device', { h: householdId, device: userId });
}

export async function approveCode(
  db: Db, householdId: string, rawCode: string,
): Promise<{ ok: true; deviceName: string } | { ok: false; message: string }> {
  const code = normalizePairingCode(rawCode);
  if (!code) return { ok: false, message: 'Saisis le code affiché sur la télé.' };
  try {
    const name = await call(db, 'approve_pairing', { h: householdId, code });
    return { ok: true, deviceName: String(name) };
  } catch (e) {
    return { ok: false, message: (e as Error).message };
  }
}

// Code d'appairage gardé le temps d'un aller-retour (confirmation par e-mail).
export interface KV { getItem(k: string): string | null; setItem(k: string, v: string): void; removeItem(k: string): void }
const CODE_KEY = 'pendingCode';
export function savePendingCode(store: KV, code: string): void {
  try { store.setItem(CODE_KEY, normalizePairingCode(code)); } catch { /* stockage indisponible */ }
}
export function loadPendingCode(store: KV): string {
  try { return normalizePairingCode(store.getItem(CODE_KEY) ?? ''); } catch { return ''; }
}
export function clearPendingCode(store: KV): void {
  try { store.removeItem(CODE_KEY); } catch { /* stockage indisponible */ }
}
