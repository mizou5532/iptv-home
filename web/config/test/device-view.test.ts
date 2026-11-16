// @vitest-environment jsdom
import { beforeEach, expect, test, vi } from 'vitest';
import { mountDevices } from '../src/device-view';
import type { Device, Db } from '../src/flow';
const settle=()=>new Promise(r=>setTimeout(r,20));
let root:HTMLElement; let devices:Device[];
beforeEach(()=>{document.body.innerHTML='<section></section>';root=document.querySelector('section')!;devices=[{userId:'tv1',name:'Télé du salon',role:'device',icon:'tv'}];});
const click=(selector:string)=>root.querySelector<HTMLButtonElement>(selector)!.click();
test('modifier conserve la saisie jusqu’à réussite et cible le bon appareil',async()=>{
 let finish!:(v:unknown)=>void; const rpc=vi.fn(()=>new Promise(r=>finish=r));
 mountDevices(root,{db:{rpc} as unknown as Db,householdId:'h',devices,onRemove:vi.fn()});
 click('[data-edit="tv1"]');const input=root.querySelector<HTMLInputElement>('input')!;input.value='Chambre';click('[data-icon="bed"]');
 root.querySelector('form')!.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));
 expect(devices[0]!.name).toBe('Télé du salon');expect(rpc).toHaveBeenCalledWith('set_device_settings',{h:'h',device:'tv1',name:'Chambre',icon:'bed'});
 finish({data:null,error:null});await settle();expect(devices[0]!.name).toBe('Chambre');expect(devices[0]!.icon).toBe('bed');
 expect(root.querySelector('form')).toBeNull();
});
test('échec conserve ancien nom et saisie, annuler ne fait aucune écriture',async()=>{
 const rpc=vi.fn(async()=>({data:null,error:{message:'refus'}}));
 mountDevices(root,{db:{rpc} as unknown as Db,householdId:'h',devices,onRemove:vi.fn()});click('[data-edit="tv1"]');root.querySelector<HTMLInputElement>('input')!.value='Bureau';
 root.querySelector('form')!.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await settle();
 expect(root.querySelector<HTMLInputElement>('input')!.value).toBe('Bureau');expect(devices[0]!.name).toBe('Télé du salon');expect(root.querySelector('[role="status"]')!.textContent).toContain('Impossible');
 click('[data-cancel]');expect(root.querySelector('form')).toBeNull();expect(rpc).toHaveBeenCalledTimes(1);
});
test('nom vide et trop long refusés, retrait reste une action distincte',async()=>{
 const rpc=vi.fn();const remove=vi.fn();mountDevices(root,{db:{rpc} as unknown as Db,householdId:'h',devices,onRemove:remove});click('[data-edit="tv1"]');
 for(const name of ['  ','a'.repeat(41)]) {root.querySelector<HTMLInputElement>('input')!.value=name;root.querySelector('form')!.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await settle();}
 expect(rpc).not.toHaveBeenCalled();click('[data-cancel]');click('[data-remove="tv1"]');expect(remove).toHaveBeenCalledWith(devices[0]);
});
test('les noms sont du texte et un écran quitté ne revient pas à la réception réseau',async()=>{
 devices[0]!.name='<img src=x onerror=alert(1)>';let finish!:(v:unknown)=>void;
 mountDevices(root,{db:{rpc:()=>new Promise(r=>finish=r)} as unknown as Db,householdId:'h',devices,onRemove:vi.fn()});
 expect(root.querySelector('img')).toBeNull();click('[data-edit="tv1"]');root.querySelector<HTMLInputElement>('input')!.value='Salon';root.querySelector('form')!.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));root.remove();
 document.body.innerHTML='<h1>Profils</h1>';finish({data:null,error:null});await settle();expect(document.querySelector('h1')!.textContent).toBe('Profils');
});
