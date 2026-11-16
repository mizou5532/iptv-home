// @vitest-environment jsdom
import { beforeEach, expect, test, vi } from 'vitest';
const { createClient } = vi.hoisted(() => ({ createClient: vi.fn() }));
vi.mock('@supabase/supabase-js', () => ({createClient}));
const settle = () => new Promise(r => setTimeout(r, 60));
beforeEach(() => { window.scrollTo = vi.fn(); vi.resetModules(); createClient.mockReset(); document.body.innerHTML = '<main id="app"></main>'; history.replaceState(null, '', '/?demo'); sessionStorage.clear(); });
test('démo ouvre les profils, navigation conserve second profil dans organiser', async () => {
  createClient.mockReturnValue({});
  await import('../src/main'); await settle();
  expect(document.querySelector('[data-nav="profiles"][aria-current="page"]')).not.toBeNull();
  document.querySelector<HTMLButtonElement>('[data-profile="p2"]')!.click();
  document.querySelector<HTMLButtonElement>('[data-organise]')!.click(); await settle();
  expect(document.querySelector('[data-scope="p2"]')!.getAttribute('aria-pressed')).toBe('true');
  document.querySelector<HTMLButtonElement>('[data-nav="settings"]')!.click();
  expect(document.querySelector('h1')!.textContent).toBe('Réglages');
  expect(document.querySelector<HTMLInputElement>('#code')!.disabled).toBe(true);
  document.querySelector<HTMLButtonElement>('nav [data-nav="profiles"]')!.click(); await settle();
  expect(document.querySelector('h1')!.textContent).toBe('Sœur');
  expect(createClient).not.toHaveBeenCalled();
});
test('code QR en attente ouvre Réglages sans perdre le code', async () => {
  history.replaceState(null, '', '/?code=ABC123');
  createClient.mockReturnValue({
    auth: {getSession: async () => ({data: {session: {}}})},
    rpc: async (fn: string) => ({data: fn === 'my_households' ? [{id:'h'}] : null, error:null}),
    from: (table: string) => ({select: async () => ({data: table === 'xtream_credentials' ? [{household_id:'h'}] : [], error:null})}),
  });
  await import('../src/main'); await settle();
  expect(document.querySelector('h1')!.textContent).toBe('Réglages');
  expect(document.querySelector<HTMLInputElement>('#code')!.value).toBe('ABC123');
  expect(location.search).toBe('');
});
test('réponse association après navigation ne remplace pas le profil ni ne plante', async () => {
  history.replaceState(null, '', '/?code=ABC123');
  const { demoDb } = await import('../src/org/demo');
  const dataDb = demoDb().db;
  let finish!: (v: unknown) => void;
  createClient.mockReturnValue({
    auth: {getSession: async () => ({data:{session:{}}})},
    from: dataDb.from,
    rpc: (fn: string) => fn === 'approve_pairing'
      ? new Promise(r => { finish = r; })
      : Promise.resolve({data: fn === 'my_households' ? [{id:'h'}] : [], error:null}),
  });
  await import('../src/main'); await settle();
  document.querySelector('form')!.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));
  await settle();
  document.querySelector<HTMLButtonElement>('nav [data-nav="profiles"]')!.click(); await settle();
  document.querySelector<HTMLButtonElement>('[data-profile="p2"]')!.click();
  finish({data:'Télé fictive',error:null}); await settle();
  expect(document.querySelector('h1')!.textContent).toBe('Sœur');
});
