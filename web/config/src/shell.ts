import { icon } from './icons';
export type SiteTab = 'profiles' | 'org' | 'settings';
export function siteHeader(): string {
  return `<header class="site-header"><a class="brand" data-nav="profiles" href="#profiles" aria-label="IPTV Home, profils">${icon('direct')}<span>IPTV HOME</span></a><button class="icon-button" type="button" data-nav="profiles" aria-label="Choisir un profil">${icon('profiles')}</button></header>`;
}
export function siteNav(tab: SiteTab): string {
  return `<nav class="bottom-nav" aria-label="Navigation principale">${([
    ['profiles', 'Profils', 'profiles'], ['org', 'Organiser', 'organise'], ['settings', 'Réglages', 'settings'],
  ] as const).map(([id, label, name]) => `<button type="button" data-nav="${id}" ${tab === id ? 'aria-current="page"' : ''}>${icon(name)}<span>${label}</span></button>`).join('')}</nav>`;
}
