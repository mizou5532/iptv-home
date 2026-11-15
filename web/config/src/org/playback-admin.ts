import type { Db } from '../flow';

interface AdminState {
  enabled: boolean;
  slot: { generation: string; state: string; device_name: string | null; label: string | null; stale: boolean } | null;
  requests: { id: string; generation: string; device_name: string; label: string; expires_at: string; state: 'waiting' }[];
}
interface Context { db: Db; householdId: string; root: HTMLElement }

/** Aucun changement à l'ouverture : seules les actions confirmées appellent une RPC d'écriture. */
export async function mountPlaybackAdmin({ db, householdId: h, root }: Context): Promise<void> {
  const section = document.createElement('section');
  section.setAttribute('aria-label', 'Protection de la lecture');
  section.innerHTML = '<h2>Protection de la lecture</h2><p role="status">Chargement…</p>';
  root.replaceChildren(section);
  const active = () => section.parentElement === root;
  const message = (text: string) => {
    const p = document.createElement('p'); p.textContent = text; p.setAttribute('role', 'status'); section.append(p);
  };
  const button = (text: string, action: () => void) => {
    const b = document.createElement('button'); b.type = 'button'; b.textContent = text; b.onclick = action; section.append(b); return b;
  };
  const load = async () => {
    try {
      const result = await db.rpc('playback_admin_state', { h });
      if (!active()) return;
      section.innerHTML = '<h2>Protection de la lecture</h2>';
      if (result.error) {
        message(result.error.message.includes('acces-refuse') ? 'Réservé au propriétaire du foyer.' : 'Impossible de lire la protection. Réessaie plus tard.');
        return;
      }
      const state = result.data as AdminState;
      const mutate = async (name: string, args: object, prompt: string) => {
        if (!window.confirm(prompt)) return;
        section.querySelectorAll('button').forEach(b => { b.disabled = true; });
        try {
          const r = await db.rpc(name, args);
          if (!active()) return;
          if (r.error) throw new Error('action-refusee');
          await load();
        } catch {
          if (!active()) return;
          message('Action impossible ou demande expirée. Actualise puis vérifie de nouveau les appareils.');
          section.querySelectorAll('button').forEach(b => { b.disabled = false; });
        }
      };
      if (!state.enabled) {
        message('Avant l’activation, mets à jour toutes les installations et arrête toutes les lectures en cours.');
        button('Activer la protection', () => { void mutate('playback_enable', { h }, 'Toutes les installations sont-elles à jour et toutes les anciennes lectures physiquement arrêtées ?'); }).id = 'playback-enable';
      } else {
        message('Protection activée');
        if (state.slot?.state !== 'empty') message(`Lecture : ${state.slot?.device_name ?? 'Appareil'} — ${state.slot?.label ?? ''}${state.slot?.stale ? ' (sans réponse récente)' : ''}`);
        for (const request of state.requests) {
          const p = document.createElement('p'); p.textContent = `${request.device_name} demande la lecture de ${request.label}.`; section.append(p);
          const b = button('Confirmer l’arrêt physique', () => { void mutate('playback_confirm_physical_stop', { request_id: request.id }, 'As-tu vérifié physiquement que la lecture précédente est arrêtée et le lecteur fermé ? Une absence de réponse ne suffit pas.'); });
          b.dataset.recover = request.id;
        }
        if (!state.requests.length) message('Aucune demande de récupération en attente.');
      }
      button('Actualiser', () => { void load(); });
    } catch {
      if (active()) message('Impossible de lire la protection. Réessaie plus tard.');
    }
  };
  await load();
}
