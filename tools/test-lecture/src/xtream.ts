export type XtreamCreds = { server: string; username: string; password: string };
export type StreamKind = 'live' | 'movie' | 'series';

/** Ramène toute saisie d'adresse de serveur à la forme `protocole://hôte[:port]`. */
export function normalizeServer(input: string): string {
  let s = input.trim();
  if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(s)) s = `http://${s}`;
  try {
    const url = new URL(s);
    if (url.origin !== 'null') return url.origin;
  } catch { /* adresse illisible : repli ci-dessous */ }
  return s.replace(/[?#].*$/, '').replace(/\/+$/, '').replace(/\/player_api\.php$/i, '').replace(/\/+$/, '');
}

export function apiUrl(creds: XtreamCreds, action?: string, params: Record<string, string> = {}): string {
  const parts: [string, string][] = [['username', creds.username], ['password', creds.password]];
  if (action) parts.push(['action', action]);
  for (const [k, v] of Object.entries(params)) parts.push([k, v]);
  const query = parts.map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&');
  return `${normalizeServer(creds.server)}/player_api.php?${query}`;
}

export function streamUrl(creds: XtreamCreds, kind: StreamKind, id: string, ext: string): string {
  const u = encodeURIComponent(creds.username);
  const p = encodeURIComponent(creds.password);
  return `${normalizeServer(creds.server)}/${kind}/${u}/${p}/${encodeURIComponent(id)}.${ext}`;
}
