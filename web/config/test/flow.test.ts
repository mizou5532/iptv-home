import { describe, it, expect } from 'vitest';
import { normalizePairingCode, loadState, setupHousehold, approveCode, updateCredentials, revokeDevice, savePendingCode, loadPendingCode, clearPendingCode, type Db } from '../src/flow';

type Call = { fn: string; args?: object };
function fakeDb(handlers: Record<string, (args?: object) => unknown> = {}, tables: Record<string, unknown[]> = {}) {
  const calls: Call[] = [];
  const db: Db = {
    async rpc(fn, args) {
      calls.push({ fn, args });
      const h = handlers[fn];
      if (!h) return { data: null, error: null };
      try { return { data: h(args), error: null }; }
      catch (e) { return { data: null, error: { message: (e as Error).message } }; }
    },
    from(table) {
      return { select: async () => ({ data: tables[table] ?? [], error: null }) };
    },
  };
  return { db, calls };
}

describe('normalizePairingCode', () => {
  it('retire espaces et tirets, met en majuscules', () => {
    expect(normalizePairingCode(' ab3 k7m ')).toBe('AB3K7M');
    expect(normalizePairingCode('ab3-k7m')).toBe('AB3K7M');
  });
});

describe('setupHousehold', () => {
  it('crée le foyer avec les profils non vides puis enregistre les identifiants tels quels', async () => {
    const { db, calls } = fakeDb({ create_household: () => 'h1' });
    const id = await setupHousehold(db, { name: 'Maison', profiles: ['Toi', '  ', 'Sœur', ''], server: 'http://s.tv:80', username: 'u', password: ' p w ' });
    expect(id).toBe('h1');
    expect(calls).toEqual([
      { fn: 'create_household', args: { name: 'Maison', profile_names: ['Toi', 'Sœur'] } },
      { fn: 'set_xtream_credentials', args: { h: 'h1', server: 'http://s.tv:80', username: 'u', password: ' p w ' } },
    ]);
  });
  it('refuse 11 profils avant tout appel', async () => {
    const { db, calls } = fakeDb();
    const profiles = Array.from({ length: 11 }, (_, i) => `P${i}`);
    await expect(setupHousehold(db, { name: 'M', profiles, server: 's', username: 'u', password: 'p' })).rejects.toThrow();
    expect(calls).toHaveLength(0);
  });
  it('traduit foyer-existe-deja', async () => {
    const { db } = fakeDb({ create_household: () => { throw new Error('foyer-existe-deja'); } });
    await expect(setupHousehold(db, { name: 'M', profiles: ['A'], server: 's', username: 'u', password: 'p' })).rejects.toThrow(/déjà/);
  });
});

describe('approveCode', () => {
  it('normalise le code et renvoie le nom de l\'appareil', async () => {
    const { db, calls } = fakeDb({ approve_pairing: () => 'Salon' });
    expect(await approveCode(db, 'h1', 'ab3 k7m')).toEqual({ ok: true, deviceName: 'Salon' });
    expect(calls[0]).toEqual({ fn: 'approve_pairing', args: { h: 'h1', code: 'AB3K7M' } });
  });
  it('code déjà utilisé', async () => {
    const { db } = fakeDb({ approve_pairing: () => { throw new Error('code-invalide'); } });
    const r = await approveCode(db, 'h1', 'AB3K7M');
    expect(r).toEqual({ ok: false, message: "Ce code n'existe pas ou a déjà été utilisé." });
  });
  it('code expiré', async () => {
    const { db } = fakeDb({ approve_pairing: () => { throw new Error('code-expire'); } });
    const r = await approveCode(db, 'h1', 'AB3K7M');
    expect(r).toEqual({ ok: false, message: 'Ce code a expiré. Sur la télé, un nouveau code va s\'afficher.' });
  });
  it('accès refusé', async () => {
    const { db } = fakeDb({ approve_pairing: () => { throw new Error('acces-refuse'); } });
    const r = await approveCode(db, 'h1', 'AB3K7M');
    expect(r).toEqual({ ok: false, message: 'Seul le compte du propriétaire peut ajouter un appareil.' });
  });
  it('refuse un code vide sans appel', async () => {
    const { db, calls } = fakeDb();
    const r = await approveCode(db, 'h1', ' - ');
    expect(r.ok).toBe(false);
    expect(calls).toHaveLength(0);
  });
});

describe('loadState', () => {
  it('sans foyer', async () => {
    const { db } = fakeDb({ my_households: () => [] });
    expect(await loadState(db)).toEqual({ householdId: null, hasCredentials: false, devices: [] });
  });
  it('avec foyer, identifiants et appareils', async () => {
    const { db } = fakeDb({ my_households: () => [{ id: 'h1', name: 'M' }] }, {
      xtream_credentials: [{ household_id: 'h1' }],
      household_members: [
        { user_id: 'o', role: 'owner', device_name: null },
        { user_id: 't', role: 'device', device_name: 'Salon', app_version: '0.40.0' },
      ],
    });
    expect(await loadState(db)).toEqual({
      householdId: 'h1', hasCredentials: true,
      devices: [{ userId: 'o', name: 'Propriétaire', role: 'owner' }, { userId: 't', name: 'Salon', role: 'device', appVersion: '0.40.0' }],
    });
  });
});

describe('updateCredentials / revokeDevice', () => {
  it('appelle les fonctions du backend', async () => {
    const { db, calls } = fakeDb();
    await updateCredentials(db, 'h1', { server: 's', username: 'u', password: 'p' });
    await revokeDevice(db, 'h1', 't');
    expect(calls).toEqual([
      { fn: 'set_xtream_credentials', args: { h: 'h1', server: 's', username: 'u', password: 'p' } },
      { fn: 'revoke_device', args: { h: 'h1', device: 't' } },
    ]);
  });
  it('les erreurs ne contiennent pas le mot de passe', async () => {
    const { db } = fakeDb({ set_xtream_credentials: () => { throw new Error('identifiants-incomplets'); } });
    await expect(updateCredentials(db, 'h1', { server: 's', username: '', password: 'secret' })).rejects.toThrow(/incomplets|Remplis/);
  });
});

describe('validation des identifiants', () => {
  it('setupHousehold refuse un champ vide sans aucun appel', async () => {
    const { db, calls } = fakeDb();
    await expect(setupHousehold(db, { name: 'M', profiles: ['A'], server: 's', username: '  ', password: 'p' }))
      .rejects.toThrow("Remplis l'adresse du serveur, le nom d'utilisateur et le mot de passe IPTV.");
    expect(calls).toHaveLength(0);
  });
  it('updateCredentials refuse un champ vide sans appel', async () => {
    const { db, calls } = fakeDb();
    await expect(updateCredentials(db, 'h1', { server: '', username: 'u', password: 'p' })).rejects.toThrow(/Remplis/);
    expect(calls).toHaveLength(0);
  });
});

describe('code en attente (sessionStorage)', () => {
  const mem = () => { const m = new Map<string, string>(); return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v), removeItem: (k: string) => void m.delete(k) }; };
  const broken = { getItem() { throw new Error('x'); }, setItem() { throw new Error('x'); }, removeItem() { throw new Error('x'); } };
  it('enregistre puis relit le code normalisé', () => {
    const s = mem();
    savePendingCode(s, 'ab3 k7m');
    expect(loadPendingCode(s)).toBe('AB3K7M');
  });
  it('efface', () => {
    const s = mem();
    savePendingCode(s, 'AB3K7M'); clearPendingCode(s);
    expect(loadPendingCode(s)).toBe('');
  });
  it('un stockage qui échoue ne plante pas', () => {
    expect(() => { savePendingCode(broken, 'A'); clearPendingCode(broken); }).not.toThrow();
    expect(loadPendingCode(broken)).toBe('');
  });
});
