import type { OrgCtx } from './view';
import { autoKey, buildGroups, categoryCountry, type GroupSnapshot, type LiveGroup, type ManualGroup } from './groups';
import {
  groupCache,
  loadGroupSnapshot,
  loadGroupSources,
  saveGroups,
  setGroupCopies,
  type CopySource,
} from './groups-api';
import { searchNorm } from './norm';
const esc = (s: string): string =>
  s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);
const keysOf = (g: LiveGroup): string[] => [
  ...new Set(g.sources.map((s) => autoKey(s.name, categoryCountry(s))).filter((k): k is string => k !== null)),
];
const uniq = (ids: string[]): string[] => [...new Set(ids)];
/** Each affected automatic family becomes explicit, including the unselected remainder.
 * Existing exclusion references and disappeared source IDs survive every edit. */
export function editCopies(
  snapshot: GroupSnapshot,
  sources: CopySource[],
  selected: Set<string>,
  separate = false,
): GroupSnapshot {
  const sourceById = new Map(sources.map((s) => [s.id, s]));
  const live = buildGroups(sources, snapshot);
  const selectedKeys = new Set(
    sources.filter((s) => selected.has(s.id)).flatMap((s) => autoKey(s.name, categoryCountry(s)) ?? []),
  );
  const affected = live.filter((g) =>
    g.sources.some((s) => selected.has(s.id) || selectedKeys.has(autoKey(s.name, categoryCountry(s)) ?? '')),
  );
  const manualById = new Map(snapshot.groups.map((g) => [g.id, g]));
  const touched = new Set(affected.filter((g) => g.id.startsWith('manual:')).map((g) => g.id.slice(7)));
  const groups = snapshot.groups.filter((g) => !touched.has(g.id)).map((g) => structuredClone(g));
  const blocked = uniq(
    affected.flatMap((g) => [...keysOf(g), ...(manualById.get(g.id.slice(7))?.blockedAutoKeys ?? [])]),
  );
  const picked: string[] = [];
  let title = '';
  let reusable: string | undefined;
  for (const g of affected) {
    const old = manualById.get(g.id.slice(7));
    const ids = old?.sourceIds ?? g.sources.map((s) => s.id);
    const chosen = ids.filter((id) => selected.has(id));
    const remainder = ids.filter((id) => !selected.has(id));
    if (chosen.length) {
      picked.push(...chosen);
      title ||= g.title;
      if (old && !remainder.length) reusable ||= old.id;
    }
    if (remainder.length)
      groups.push({
        id: old?.id ?? crypto.randomUUID(),
        title: old?.title ?? g.title,
        sourceIds: remainder,
        blockedAutoKeys: uniq([...(old?.blockedAutoKeys ?? []), ...keysOf(g)]),
      });
  }
  if (separate) {
    for (const [index, id] of picked.entries())
      groups.push({
        id: crypto.randomUUID(),
        title: sourceById.get(id)?.name ?? title,
        sourceIds: [id],
        // Preserve orphaned references once; each other child owns its family key.
        blockedAutoKeys:
          index === 0
            ? blocked
            : [(() => { const src = sourceById.get(id); return src ? autoKey(src.name, categoryCountry(src)) : null; })()].filter((k): k is string => k !== null),
      });
  } else if (picked.length)
    groups.push({
      id: reusable ?? crypto.randomUUID(),
      title,
      sourceIds: picked,
      blockedAutoKeys: blocked,
    });
  return { ...snapshot, groups };
}
export async function mountGroups(ctx: OrgCtx): Promise<void> {
  const { root, db, householdId: h, scope } = ctx;
  const shell = document.createElement('section');
  root.replaceChildren(shell);
  const live = (): boolean => root.contains(shell);
  shell.innerHTML = '<p>Chargement des copies…</p>';
  let snapshot: GroupSnapshot | undefined;
  let sources: CopySource[] = [];
  let enabled = true;
  let complete = false;
  let hidden: { item_type: string; item_id: string }[] = [];
  let loadError = '';
  let visibilityLoaded = false;
  try {
    // Phone editor uses only the frozen copy. Hidden sources remain editable;
    // these flags describe their television visibility, without fetching IPTV.
    if (scope) {
      const prefs = await db.rpc('profile_prefs', { p: scope });
      if (prefs.error) throw new Error('Impossible de lire la visibilité. Réessayer.');
      const p = prefs.data as { hidden?: typeof hidden; group_copies?: boolean };
      hidden = p.hidden ?? [];
      enabled = p.group_copies ?? true;
    } else {
      const categories = await db.rpc('org_categories', { h, p: null });
      if (categories.error) throw new Error('Impossible de lire la visibilité. Réessayer.');
      hidden = (categories.data as { id: string; hidden_global: boolean }[])
        .filter((c) => c.hidden_global)
        .map((c) => ({ item_type: 'live_category', item_id: c.id }));
    }
    visibilityLoaded = true;
    [snapshot, sources] = await Promise.all([loadGroupSnapshot(db, h), loadGroupSources(db, h)]);
    complete = true;
  } catch (e) {
    loadError = (e as Error).message;
    const cache = groupCache(db, h);
    snapshot = cache.snapshot;
    sources = cache.sources ?? [];
  }
  if (!live()) return;
  if (!snapshot || !visibilityLoaded) {
    shell.innerHTML = '<p class="msg error"></p><button id="copies-reload">Réessayer</button>';
    shell.querySelector('p')!.textContent = loadError;
    shell.querySelector('button')!.onclick = () => void mountGroups(ctx);
    return;
  }
  let committed = structuredClone(snapshot);
  let draft = structuredClone(snapshot);
  let dirty = false;
  let busy = false;
  const selected = new Set<string>();
  const hiddenCats = new Set(hidden.filter((i) => i.item_type === 'live_category').map((i) => i.item_id));
  const hiddenIds = new Set(hidden.filter((i) => i.item_type === 'channel').map((i) => i.item_id));
  const editableSources = sources;
  const isHidden = (s: CopySource): boolean => hiddenCats.has(s.categoryId) || hiddenIds.has(s.id);
  const index = editableSources.map((s) => ({
    source: s,
    text: searchNorm(`${s.name} ${s.categoryName}`),
  }));
  let filtered = editableSources;
  let memberships = new Map<string, LiveGroup>();
  shell.innerHTML = `<h2>Copies des chaînes</h2><p>Corrections communes au foyer. Les favoris gardent leurs chaînes d’origine. Cette copie figée inclut les sources masquées sur la télé.</p>
    ${scope ? '<label class="copy-setting"><input id="copies-enabled" type="checkbox"> Regrouper les copies pour ce profil</label>' : '<p>Choisis un profil pour régler le regroupement.</p>'}
    <p id="copies-status" class="msg" role="status"></p><div class="tools" id="copies-confirm" hidden></div><button class="secondary" id="copies-reload">Recharger</button>
    <label for="copies-search">Chercher une chaîne ou une catégorie</label><input type="search" id="copies-search" autocomplete="off">
    <div class="tools"><button id="copies-merge">Réunir la sélection</button><button class="secondary" id="copies-cancel">Annuler</button></div>
    <p id="copies-count"></p><div id="copies-list" role="list" tabindex="0"></div>
    <section id="copies-preview" class="card" hidden><h3>Aperçu avant enregistrement</h3><p></p><label for="copies-title">Nom du groupe réuni</label><input id="copies-title" maxlength="160"><button id="copies-save">Enregistrer</button></section>`;
  const status = shell.querySelector<HTMLElement>('#copies-status')!;
  const list = shell.querySelector<HTMLElement>('#copies-list')!;
  const preview = shell.querySelector<HTMLElement>('#copies-preview')!;
  const title = shell.querySelector<HTMLInputElement>('#copies-title')!;
  let editedId: string | undefined;
  const say = (s: string): void => {
    status.textContent = s;
  };
  // Confirmations intégrées à la page (pas de confirm() native, rejetée
  // d'office dans certains contextes) : une seule en attente à la fois.
  const confirmZone = shell.querySelector<HTMLElement>('#copies-confirm')!;
  let pendingConfirm: (() => void) | null = null;
  // Source de la ligne qui a ouvert la confirmation : paint() s'en sert pour
  // rendre le focus à cette ligne une fois l'action confirmée appliquée.
  let confirmFocusSource: string | undefined;
  const closeConfirm = (): void => {
    pendingConfirm = null;
    confirmZone.hidden = true;
    confirmZone.innerHTML = '';
  };
  const askConfirm = (text: string, run: () => void): void => {
    pendingConfirm = run;
    const a = document.activeElement as HTMLElement | null;
    confirmFocusSource =
      a && list.contains(a)
        ? a.closest('.copy-row')?.querySelector<HTMLInputElement>('input[data-source]')?.dataset.source
        : undefined;
    confirmZone.innerHTML = `<span class="ctext">${esc(text)}</span>
      <button type="button" class="small" data-cf="yes">Confirmer</button>
      <button type="button" class="small secondary" data-cf="no">Annuler</button>`;
    confirmZone.hidden = false;
  };
  confirmZone.addEventListener('click', (e) => {
    const b = (e.target as HTMLElement).closest<HTMLButtonElement>('button[data-cf]');
    if (!b) return;
    const run = pendingConfirm;
    closeConfirm();
    if (b.dataset.cf === 'yes') run?.();
    else confirmFocusSource = undefined;
  });
  const rebuild = (): void => {
    memberships = new Map();
    for (const g of buildGroups(editableSources, draft)) for (const s of g.sources) memberships.set(s.id, g);
  };
  function paint(): void {
    const active = document.activeElement as HTMLElement | null;
    const activeRow = active && list.contains(active) ? active.closest('.copy-row') : null;
    const focusedSource =
      activeRow?.querySelector<HTMLInputElement>('input[data-source]')?.dataset.source ??
      (pendingConfirm ? undefined : confirmFocusSource);
    const control = active?.matches('.copy-separate')
      ? '.copy-separate'
      : active?.matches('.copy-restore')
        ? '.copy-restore'
        : 'input[data-source]';
    const first = Math.max(0, Math.floor(list.scrollTop / 96) - 3);
    const last = Math.min(filtered.length, first + 14);
    list.innerHTML = `<div style="height:${filtered.length * 96}px;position:relative">${filtered
      .slice(first, last)
      .map((s, i) => {
        const g = memberships.get(s.id)!;
        return `<div class="copy-row${isHidden(s) ? ' copy-hidden' : ''}" role="listitem" style="top:${(first + i) * 96}px"><label><input type="checkbox" data-source="${esc(s.id)}" ${selected.has(s.id) ? 'checked' : ''} ${!complete || busy ? 'disabled' : ''}><span><strong>${esc(s.name)}</strong><span class="osub">${esc(s.categoryName)} · ${isHidden(s) ? 'Masquée sur la télé · ' : ''}${esc(g.title)} · ${g.id.startsWith('manual:') ? 'Corrigé' : 'Automatique'} · ${g.sources.length} copie(s)</span></span></label><div><button class="small secondary copy-separate" data-group="${esc(g.id)}" ${!complete || busy ? 'disabled' : ''}>Séparer</button>${g.id.startsWith('manual:') ? `<button class="small secondary copy-restore" data-group="${esc(g.id)}" ${!complete || busy ? 'disabled' : ''}>Restaurer</button>` : ''}</div></div>`;
      })
      .join('')}</div>`;
    shell.querySelector('#copies-count')!.textContent =
      `${filtered.length} chaînes · ${selected.size} sélectionnée(s)`;
    shell.querySelector<HTMLButtonElement>('#copies-merge')!.disabled =
      !complete || busy || selected.size < 2;
    shell.querySelector<HTMLButtonElement>('#copies-save')!.disabled = !complete || busy || !dirty;
    preview.hidden = !dirty;
    shell.querySelector<HTMLButtonElement>('#copies-cancel')!.disabled = busy;
    shell.querySelector<HTMLButtonElement>('#copies-reload')!.disabled = busy;
    title.disabled = busy || !editedId;
    if (focusedSource) {
      // Group IDs may change after separation/restoration; the source stays stable.
      const row = [...list.querySelectorAll('.copy-row')].find(
        (row) => row.querySelector<HTMLInputElement>('input[data-source]')?.dataset.source === focusedSource,
      );
      const target =
        row?.querySelector<HTMLElement>(`${control}:not(:disabled)`) ??
        row?.querySelector<HTMLElement>('input[data-source]:not(:disabled)') ??
        list;
      // If the source left the window, retain a local navigation anchor without
      // scrolling back to the removed control or returning to the page start.
      target.focus({ preventScroll: true });
      confirmFocusSource = undefined;
    }
  }
  const stage = (next: GroupSnapshot): void => {
    closeConfirm();
    draft = next;
    dirty = true;
    selected.clear();
    rebuild();
    preview.querySelector('p')!.textContent =
      `${draft.groups.length} correction(s) après cette modification. Rien n’est encore enregistré.`;
    paint();
  };
  shell.querySelector('#copies-reload')!.addEventListener('click', () => {
    if (!dirty) {
      void mountGroups(ctx);
      return;
    }
    askConfirm('Abandonner les modifications et recharger ?', () => void mountGroups(ctx));
  });
  const toggle = shell.querySelector<HTMLInputElement>('#copies-enabled');
  if (toggle) {
    toggle.checked = enabled;
    toggle.disabled = !complete;
    toggle.onchange = () => {
      toggle.disabled = true;
      setGroupCopies(db, scope!, toggle.checked)
        .then(
          () => {
            enabled = toggle.checked;
            say('Réglage enregistré.');
          },
          () => {
            toggle.checked = enabled;
            say('Impossible de modifier le réglage. Réessayer.');
          },
        )
        .finally(() => {
          if (live()) toggle.disabled = !complete;
        });
    };
  }
  shell.querySelector<HTMLInputElement>('#copies-search')!.oninput = (e) => {
    const q = searchNorm((e.target as HTMLInputElement).value);
    filtered = q ? index.filter((i) => i.text.includes(q)).map((i) => i.source) : editableSources;
    list.scrollTop = 0;
    paint();
  };
  list.onscroll = paint;
  list.onchange = (e) => {
    const input = e.target as HTMLInputElement;
    if (input.dataset.source) {
      if (input.checked) selected.add(input.dataset.source);
      else selected.delete(input.dataset.source);
      paint();
    }
  };
  list.onclick = (e) => {
    const button = (e.target as HTMLElement).closest<HTMLButtonElement>('button[data-group]');
    if (!button || !complete || busy) return;
    const g = [...memberships.values()].find((g) => g.id === button.dataset.group);
    if (!g) return;
    if (button.classList.contains('copy-separate')) {
      stage(editCopies(draft, sources, new Set(g.sources.map((s) => s.id)), true));
      editedId = undefined;
      title.value = '';
      title.disabled = true;
    } else {
      const id = g.id.slice(7);
      const old = draft.groups.find((g) => g.id === id)!;
      const oldKeys = new Set(old.blockedAutoKeys);
      const shared = draft.groups.filter((g) => g.id !== id && g.blockedAutoKeys.some((k) => oldKeys.has(k)));
      const doRestore = (): void => {
        stage({ ...draft, groups: draft.groups.filter((g) => g.id !== id) });
        editedId = undefined;
        title.disabled = true;
      };
      if (shared.length) {
        askConfirm(
          `Restaurer cette correction ? ${shared.length} autre(s) correction(s) partagent ces familles et restent protégées ; les copies libérées peuvent changer de groupe.`,
          doRestore,
        );
        return;
      }
      doRestore();
    }
  };
  shell.querySelector('#copies-merge')!.addEventListener('click', () => {
    const chosen = new Set(selected);
    const next = editCopies(draft, sources, chosen);
    const firstChosen = chosen.values().next().value!;
    editedId = next.groups.find((g) => g.sourceIds.includes(firstChosen))?.id;
    title.disabled = false;
    title.value = next.groups.find((g) => g.id === editedId)?.title ?? '';
    stage(next);
  });
  title.oninput = () => {
    const g = draft.groups.find((g) => g.id === editedId);
    if (g) {
      g.title = title.value;
      rebuild();
      paint();
    }
  };
  shell.querySelector('#copies-cancel')!.addEventListener('click', () => {
    draft = structuredClone(committed);
    dirty = false;
    selected.clear();
    rebuild();
    paint();
    say('Modifications annulées.');
  });
  shell.querySelector('#copies-save')!.addEventListener('click', async () => {
    if (!complete || busy || !dirty) return;
    if (draft.groups.some((g) => !g.title.trim())) {
      say('Le nom du groupe est requis.');
      return;
    }
    busy = true;
    paint();
    say('Enregistrement…');
    try {
      const retainedIds = new Set(draft.groups.map((g) => g.id));
      const rev = await saveGroups(db, h, committed.rev, {
        removeIds: committed.groups.filter((g) => !retainedIds.has(g.id)).map((g) => g.id),
        groups: draft.groups,
      });
      if (!live()) return;
      committed = { ...structuredClone(draft), rev };
      draft = structuredClone(committed);
      groupCache(db, h).snapshot = committed;
      dirty = false;
      say('Enregistré.');
    } catch (e) {
      if (live()) say((e as Error).message);
    } finally {
      busy = false;
      if (live()) paint();
    }
  });
  rebuild();
  paint();
  say(
    complete
      ? 'Copie complète.'
      : `Copie ancienne ou incomplète : ${loadError} Aucune modification possible.`,
  );
}
