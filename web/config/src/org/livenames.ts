// Cœur du nom d'une chaîne et pays, pour regrouper ses copies : même règle que la télé
// (apps/shared/.../text/LiveNames.kt : groupCore, countryOf), vérifiée sur les mêmes exemples
// (fixtures/live-groups-v2.json). Ne pas modifier d'un seul côté.

const TINY = new Map<string, string>();
for (const pairs of ['ᴬAᴮBᴰDᴱEᴳGᴴHᴵIᴶJᴷKᴸLᴹMᴺNᴼOᴾPᴿRᵀTᵁUⱽVᵂW', 'ᵃaᵇbᶜcᵈdᵉeᶠfᵍgʰhⁱiʲjᵏkˡlᵐmⁿnᵒoᵖpʳrˢsᵗtᵘuᵛvʷwˣxʸyᶻz', '⁰0¹1²2³3⁴4⁵5⁶6⁷7⁸8⁹9', 'ᴀAʙBᴄCᴅDᴇEꜰFɢGʜHɪIᴊJᴋKʟLᴍMɴNᴏOᴘPʀRꜱSᴛTᴜUᴠVᴡWʏYᴢZ⁽(⁾)']) for (let i = 0; i + 1 < pairs.length; i += 2) TINY.set(pairs[i], pairs[i + 1]);

const SPACES = /[\t-\r \u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff]/g;
const QUALITY = String.raw`4k|uhd|fhd|hd|sd|raw|hevc|h\.?265|\d{2,3}\s?fps|1080p|720p|2160p`;
const trailingQuality = new RegExp(String.raw`(?:[\s\-|/]+|^)(?:${QUALITY})$|\s*[\[(](?:${QUALITY})[\])]$`, 'iu');
const technicalOnly = new RegExp(`^(?:${QUALITY})$`, 'iu');
const looseEnds = /^[\s\-|/:]+|[\s\-|/:]+$/g;
const countryPrefix = /^\s*(?:\|\s*([A-Z]{2,4})\s*\||\[\s*([A-Z]{2,4})\s*\]|([A-Z]{2,4})\s*\|)\s*(?=\S)/u;
const countryColon = /^\s*([A-Z]{2})(?:\s+(?:EN|FR|ES|DE|IT|PT|AR|NL))?\s*:\s*(?=\S)/u;
const KNOWN_COUNTRIES = new Set([
  'US', 'CA', 'UK', 'GB', 'FR', 'BE', 'CH', 'LU', 'ES', 'PT', 'IT', 'DE', 'AT', 'NL', 'IE', 'MX', 'AR', 'BR', 'CL', 'CO',
  'AU', 'NZ', 'QC', 'MA', 'DZ', 'TN', 'TR', 'IN', 'PK', 'PL', 'RO', 'GR', 'SE', 'NO', 'DK', 'FI', 'RU', 'AE', 'SA', 'EG',
  'HU', 'SI', 'MK', 'KR', 'JP', 'LT', 'LV', 'AZ', 'AM', 'ID', 'CY', 'IS', 'LA', 'IL', 'IR', 'HR', 'BG', 'CZ', 'RS', 'PH', 'AL',
]);
const feedLabel = /^[A-Za-z0-9&+/!'.]{2,8}\s*:\s+(?=\S)/u;
const separator = /^\s*[#=*~•_\-]{2,}\s*(.*?)\s*[#=*~•_\-]{2,}\s*$/u;
const separatorOpen = /^\s*[#=*~•]{3,}\s*(.*?)\s*$/u;
const copyNoise = /(?:[\s\-|/]+)(?:backup|bkp|alt|alternate|alternatif|multi)$|\s*[\[(](?:backup|bkp|alt|alternate|alternatif|multi)[\])]$/iu;

function splitTiny(name: string): string {
  let text = '';
  for (const c of name) text += TINY.has(c) ? ' ' : c;
  return text;
}
const tidy = (s: string): string => s.replace(looseEnds, '').replace(/\s+/g, ' ');

function stripQuality(name: string): string {
  let title = tidy(name);
  for (;;) {
    const m = trailingQuality.exec(title);
    if (!m) return title;
    const rest = tidy(title.slice(0, m.index) + title.slice(m.index + m[0].length));
    if (!rest) return title;
    title = rest;
  }
}

function takeCountry(name: string, strict = false): [string | null, string] {
  const m = countryPrefix.exec(name);
  if (m) { const code = m[1] ?? m[2] ?? m[3]; if (!strict || KNOWN_COUNTRIES.has(code)) return [code, name.slice(m[0].length)]; }
  const c = countryColon.exec(name);
  if (c && KNOWN_COUNTRIES.has(c[1])) return [c[1], name.slice(c[0].length)];
  return [null, name];
}

/** Ligne de séparation du fournisseur (« ##### AT&T ##### ») : un titre, jamais une chaîne (télé : separatorTitle). */
export function isSeparator(raw: string): boolean {
  const name = takeCountry(raw)[1];
  return separator.test(name) || separatorOpen.test(name);
}

/** Pays écrit au début d'un nom de chaîne ou de catégorie (« US| », « [FR] », « CA: »), sinon null. */
export function countryOf(raw: string): string | null {
  return takeCountry(splitTiny(raw.replace(SPACES, ' ')))[0];
}

/** Pays écrit au début d'un nom de CHAÎNE : seulement un vrai code de pays (« VIP| ESPN » n'en a pas). */
export function channelCountry(raw: string): string | null {
  return takeCountry(splitTiny(raw.replace(SPACES, ' ')), true)[0];
}

/** Nom d'une chaîne sans pays ni qualité (affichage de la télé, `channelName` sans catégorie). */
function channelTitle(raw: string): string {
  const text = splitTiny(raw);
  let [country, afterCountry] = takeCountry(text);
  // « RDS | FHD » : « RDS » n'est pas un préfixe si le reste n'est qu'une qualité.
  if (country !== null && technicalOnly.test(stripQuality(afterCountry))) { country = null; afterCountry = text; }
  return stripQuality(tidy(afterCountry)) || tidy(raw);
}

/** Cœur du nom pour regrouper les copies : sans pays, qualité ni mots de secours (Backup, Alt…). */
export function groupCore(raw: string): string {
  let title = channelTitle(raw.replace(SPACES, ' '));
  // Étiquette de flux devant le nom (« AT&T: AMC », « PRIME: … ») : retirée, sauf s'il ne resterait qu'une qualité.
  const label = feedLabel.exec(title);
  if (label) {
    const rest = title.slice(label[0].length);
    if (/\p{L}/u.test(rest) && !technicalOnly.test(stripQuality(rest))) title = rest;
  }
  for (;;) {
    const next = stripQuality(tidy(title.replace(copyNoise, ''))) || title;
    if (next === title) return title;
    title = next;
  }
}
