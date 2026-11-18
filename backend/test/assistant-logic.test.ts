import { describe, expect, it } from 'vitest';
import { handle, TOOLS, friendlyError } from '../supabase/functions/assistant/logic';

describe('connecteur pour IA (protocole MCP)', () => {
  it('initialisation, liste des outils (lecture / écriture), notification sans réponse', async () => {
    const call = async () => null;
    const init = await handle({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18' } }, call) as any;
    expect(init.result.protocolVersion).toBe('2025-06-18');
    expect(init.result.capabilities).toEqual({ tools: {} });
    const list = await handle({ jsonrpc: '2.0', id: 2, method: 'tools/list' }, call) as any;
    expect(list.result.tools.map((t: { name: string }) => t.name)).toContain('playlist_creer');
    expect(list.result.tools.find((t: any) => t.name === 'profils').annotations.readOnlyHint).toBe(true);
    expect(list.result.tools.find((t: any) => t.name === 'masquer').annotations.readOnlyHint).toBe(false);
    expect(await handle({ jsonrpc: '2.0', method: 'notifications/initialized' }, call)).toBeNull();
    expect(TOOLS.every((t) => (t.inputSchema as any).type === 'object')).toBe(true);
  });

  it('appel d’un outil : résultat en texte JSON ; erreur du serveur traduite en phrase claire', async () => {
    const seen: unknown[] = [];
    const ok = await handle({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'profils', arguments: {} } },
      async (tool, args) => { seen.push([tool, args]); return [{ id: 'p', nom: 'Hamza' }]; }) as any;
    expect(JSON.parse(ok.result.content[0].text)).toEqual([{ id: 'p', nom: 'Hamza' }]);
    expect(seen).toEqual([['profils', {}]]);
    const bad = await handle({ jsonrpc: '2.0', id: 4, method: 'tools/call', params: { name: 'masquer', arguments: {} } },
      async () => { throw new Error('{"message":"profil-inconnu: Bob"}'); }) as any;
    expect(bad.result.isError).toBe(true);
    expect(bad.result.content[0].text).toContain('Profil inconnu');
    const unknown = await handle({ jsonrpc: '2.0', id: 5, method: 'tools/call', params: { name: 'effacer_tout' } }, async () => null) as any;
    expect(unknown.error.code).toBe(-32602);
    expect(friendlyError('cle-invalide')).toContain('Clé d’accès invalide');
  });
});
