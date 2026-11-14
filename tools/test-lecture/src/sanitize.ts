import type { XtreamCreds } from './xtream';

/** Remplace le nom d'utilisateur et le mot de passe (en clair et encodés) par `***`. */
export function sanitize(text: string, creds: Pick<XtreamCreds, 'username' | 'password'>): string {
  const secrets = new Set<string>();
  for (const v of [creds.username, creds.password]) {
    if (!v) continue;
    secrets.add(v);
    secrets.add(encodeURIComponent(v));
  }
  // Les plus longs d'abord, pour qu'une forme encodée ne soit pas coupée par une forme plus courte.
  let out = text;
  for (const s of [...secrets].sort((a, b) => b.length - a.length)) out = out.split(s).join('***');
  return out;
}
