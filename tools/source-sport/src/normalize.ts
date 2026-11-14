const QUALITY = new Set(['hd', 'fhd', 'uhd', 'sd', '4k', 'hevc', 'h265', '1080p', '720p', '50fps', '60fps', 'backup', 'bk']);

/** Ramène un nom de chaîne du fournisseur (préfixes pays, mentions de qualité, accents…) à une forme comparable. */
export function normalizeChannelName(name: string): string {
  let s = name.normalize('NFKD').replace(/\p{M}/gu, '').toLowerCase();
  s = s.replace(/\+/g, ' plus ');
  s = s.replace(/\[[^\]]*\]|\([^)]*\)/g, ' ');
  s = s.replace(/^\s*[a-z]{2,3}\s*(\||:|-)\s*/, '');
  s = s.replace(/[^a-z0-9]+/g, ' ');
  return s.split(' ').filter((w) => w && !QUALITY.has(w)).join(' ');
}

export const compactKey = (name: string): string => normalizeChannelName(name).replace(/ /g, '');
