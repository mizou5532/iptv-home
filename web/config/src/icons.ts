import tv from '@phosphor-icons/core/assets/regular/television.svg?url';
import series from '@phosphor-icons/core/assets/regular/stack.svg?url';
import films from '@phosphor-icons/core/assets/regular/film-strip.svg?url';
import sport from '@phosphor-icons/core/assets/regular/trophy.svg?url';
import profiles from '@phosphor-icons/core/assets/regular/users.svg?url';
import settings from '@phosphor-icons/core/assets/regular/gear.svg?url';
import photo from '@phosphor-icons/core/assets/regular/image.svg?url';
import edit from '@phosphor-icons/core/assets/regular/pencil-simple.svg?url';
import chevron from '@phosphor-icons/core/assets/regular/caret-right.svg?url';
import exit from '@phosphor-icons/core/assets/regular/sign-out.svg?url';
const icons = { direct: tv, series, films, sport, profiles, settings, photo, edit, chevron, exit, organise: series };
export function icon(name: keyof typeof icons, className = ''): string {
  return `<span class="icon ${className}" aria-hidden="true" style="--icon:url('${icons[name]}')"></span>`;
}
export function escapeHtml(value: string): string { return value.replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[c]!); }
