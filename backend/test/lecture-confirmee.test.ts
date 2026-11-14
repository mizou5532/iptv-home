import { afterAll, beforeAll, beforeEach, expect, it } from 'vitest';
import { startDb, type Db } from './db';
let db: Db; let h: string; let p: string; let owner: string;
beforeAll(async()=>{db=await startDb();});
afterAll(async()=>{await db?.stop();});
beforeEach(async()=>{
 await db.resetHouseholds(); owner=await db.newUser({email:'fiction@exemple.test'});
 h=await db.seedHousehold(owner,['Profil']);
 p=(await db.admin.query('select id from profiles where household_id=$1',[h])).rows[0].id;
 await db.admin.query("insert into live_catalog_category(household_id,id,name,position) values ($1,'cat','Fiction',0)",[h]);
 await db.admin.query("insert into live_catalog_channel(household_id,id,category_id,name,norm,position) values ($1,'source','cat','Fiction','fiction',0)",[h]);
});
const rpc=async(name:string,args:unknown[]=[],user=owner)=> (await db.as(user,c=>c.query(`select ${name}(${args.map((_,i)=>'$'+(i+1)).join(',')}) r`,args))).rows[0].r;
const claim=(key='a')=>rpc('playback_claim',[h,p,key,'Appareil','source','Fiction']);
it('activation explicite et idempotente, concurrence entre installations du même compte',async()=>{
 expect(await claim()).toEqual({status:'setup_required'}); await rpc('playback_enable',[h]);
 const results=await Promise.all([claim('a'),claim('b')]);
 expect(results.map(r=>r.status).sort()).toEqual(['occupied','ok']);
 await rpc('playback_enable',[h]); expect((await claim('c')).status).toBe('occupied');
});
it('aucune reprise sans accusé, même après 45s; priorité au demandeur après arrêt',async()=>{
 await rpc('playback_enable',[h]); const a=await claim();
 const r=await rpc('playback_request_stop',[h,a.generation,p,'b','Autre','source','Fiction']);
 await db.admin.query("update playback_slot set last_heartbeat=now()-interval '60 seconds'");
 expect((await claim('c')).status).toBe('occupied');
 await expect(rpc('playback_promote',[r,'b'])).rejects.toThrow();
 await expect(rpc('playback_stopped',[h,a.generation,'b',r])).rejects.toThrow();
 await rpc('playback_stopped',[h,a.generation,'a',r]);
 expect((await claim('c')).status).toBe('occupied');
 const b=await rpc('playback_promote',[r,'b']); expect(b.status).toBe('ok'); expect(b.generation).not.toBe(a.generation);
 await rpc('playback_stopped',[h,a.generation,'a',r]);
 expect((await claim('c')).status).toBe('occupied');
 await expect(rpc('playback_started',[h,a.generation,'a'])).rejects.toThrow();
});
