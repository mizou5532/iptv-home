import tv from '@phosphor-icons/core/assets/regular/television.svg?raw';
import series from '@phosphor-icons/core/assets/regular/stack.svg?raw';
import films from '@phosphor-icons/core/assets/regular/film-strip.svg?raw';
import sport from '@phosphor-icons/core/assets/regular/trophy.svg?raw';
import profiles from '@phosphor-icons/core/assets/regular/users.svg?raw';
import settings from '@phosphor-icons/core/assets/regular/gear.svg?raw';
import photo from '@phosphor-icons/core/assets/regular/image.svg?raw';
import edit from '@phosphor-icons/core/assets/regular/pencil-simple.svg?raw';
import chevron from '@phosphor-icons/core/assets/regular/caret-right.svg?raw';
import exit from '@phosphor-icons/core/assets/regular/sign-out.svg?raw';
import sofa from '@phosphor-icons/core/assets/regular/couch.svg?raw';
import bed from '@phosphor-icons/core/assets/regular/bed.svg?raw';
import desk from '@phosphor-icons/core/assets/regular/desk.svg?raw';
import other from '@phosphor-icons/core/assets/regular/house.svg?raw';
import sticker from '@phosphor-icons/core/assets/regular/sticker.svg?raw';
const icons = { tv, sofa, bed, desk, other, direct: tv, series, films, sport, profiles, settings, photo, sticker, edit, chevron, exit, organise: series };
export function icon(name: keyof typeof icons, className = ''): string {
  // Tracés fournis tels quels par Phosphor : pas de data URI interpolée dans un attribut CSS.
  return `<span class="icon ${className}" aria-hidden="true">${icons[name]}</span>`;
}
export function escapeHtml(value: string): string { return value.replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[c]!); }
