// @vitest-environment jsdom
import { beforeEach, expect, test } from 'vitest';
import { mountPlaybackAdmin } from '../src/org/playback-admin';
import { demoDb } from '../src/org/demo';
import type { Db } from '../src/flow';
let root:HTMLElement; let db:Db; let h:string;
const settle=()=>new Promise(r=>setTimeout(r,10));
beforeEach(()=>{document.body.innerHTML='<main></main>';root=document.querySelector('main')!;const d=demoDb();db=d.db;h=d.householdId;});
const open=()=>mountPlaybackAdmin({db,householdId:h,root});
const yes=()=>root.querySelector<HTMLButtonElement>('button[data-cf="yes"]')!.click();
const no=()=>root.querySelector<HTMLButtonElement>('button[data-cf="no"]')!.click();
test('chargement sans activation, refus confirmation puis activation explicite',async()=>{
 await open(); expect((await db.rpc('playback_admin_state',{h})).data).toMatchObject({enabled:false});
 root.querySelector<HTMLButtonElement>('#playback-enable')!.click(); await settle();
 // Confirmation intégrée affichée avec le texte d'avertissement, rien n'est encore écrit.
 expect(root.querySelector('#playback-confirm')!.textContent).toContain('physiquement arrêtées');
 expect((await db.rpc('playback_admin_state',{h})).data).toMatchObject({enabled:false});
 no(); await settle();
 expect(root.querySelector('#playback-confirm')).toBeNull();
 expect((await db.rpc('playback_admin_state',{h})).data).toMatchObject({enabled:false});
 root.querySelector<HTMLButtonElement>('#playback-enable')!.click(); yes(); await settle();
 expect(root.textContent).toContain('Protection activée');
});
test('récupération explicite uniquement, confirmation physique requise',async()=>{
 await db.rpc('playback_enable',{h});
 await open();expect(root.textContent).toContain('Télé démo');
 root.querySelector<HTMLButtonElement>('[data-recover]')!.click();await settle();
 expect(root.querySelector('#playback-confirm')!.textContent).toContain('vérifié physiquement');
 expect((await db.rpc('playback_admin_state',{h})).data).toMatchObject({requests:[{state:'waiting'}]});
 yes();await settle();
 expect((await db.rpc('playback_admin_state',{h})).data).toMatchObject({requests:[]});
});
test('refus propriétaire ne présente aucune action',async()=>{
 db={...db,rpc:async()=>({data:null,error:{message:'acces-refuse'}})};await open();
 expect(root.textContent).toContain('propriétaire'); expect(root.querySelector('button')).toBeNull();
});
test('échec récupération reste visible sans exposer le message serveur',async()=>{
 await db.rpc('playback_enable',{h}); const actual=db.rpc.bind(db);
 db={...db,rpc:(fn,args)=>fn==='playback_confirm_physical_stop'?Promise.resolve({data:null,error:{message:'demande-invalide'}}):actual(fn,args)};
 await open();root.querySelector<HTMLButtonElement>('[data-recover]')!.click();yes();await settle();
 expect(root.textContent).toContain('Actualise');
});
