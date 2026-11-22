import { icon } from './icons';
export type SiteTab = 'watch' | 'profiles' | 'org' | 'settings';
export function siteHeader(tab?: SiteTab): string {
  const watchBtn = tab === 'watch'
    ? ''
    : `<button class="small header-watch-btn" type="button" data-nav="watch">▶ Regarder</button>`;
  return `<header class="site-header"><a class="brand" data-nav="profiles" href="#profiles" aria-label="IPTV Home, profils">${icon('direct')}<span>IPTV HOME</span></a><div class="header-actions">${watchBtn}<button class="icon-button" type="button" data-nav="profiles" aria-label="Choisir un profil">${icon('profiles')}</button></div></header>`;
}
export function siteNav(tab: SiteTab): string {
  return `<nav class="bottom-nav" aria-label="Navigation principale">${([
    ['watch', 'Regarder', 'direct'], ['profiles', 'Profils', 'profiles'], ['org', 'Organiser', 'organise'], ['settings', 'Réglages', 'settings'],
  ] as const).map(([id, label, name]) => `<button type="button" data-nav="${id}" ${tab === id ? 'aria-current="page"' : ''}>${icon(name)}<span>${label}</span></button>`).join('')}<div class="side-profiles" id="side-profiles" aria-label="Profils"></div></nav>`;
}
