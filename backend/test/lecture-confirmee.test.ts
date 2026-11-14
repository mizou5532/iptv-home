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
it('contrôle, battements privés, démarrage, changement et annulation',async()=>{
 await rpc('playback_enable',[h]); const a=await claim();
 expect(await rpc('playback_control',[h,a.generation,'a',true])).toEqual({status:'keep'});
 await rpc('playback_started',[h,a.generation,'a']);
 await expect(rpc('playback_update',[h,a.generation,'b','source','Fiction'])).rejects.toThrow();
 await rpc('playback_update',[h,a.generation,'a','source','Autre titre']);
 const r=await rpc('playback_request_stop',[h,a.generation,p,'b','Autre','source','Fiction']);
 expect(await rpc('playback_control',[h,a.generation,'a'])).toEqual({status:'stop',request_id:r});
 expect((await rpc('playback_poll_request',[r,'b'])).status).toBe('waiting');
 await expect(rpc('playback_poll_request',[r,'a'])).rejects.toThrow();
 await rpc('playback_cancel_request',[r,'b']);
 expect((await rpc('playback_poll_request',[r,'b'])).status).toBe('cancelled');
 expect((await claim('c')).status).toBe('occupied');
});
it('récupération propriétaire seulement et rejet périmé',async()=>{
 await rpc('playback_enable',[h]); const a=await claim();
 const r=await rpc('playback_request_stop',[h,a.generation,p,'b','Autre','source','Fiction']);
 const other=await db.newUser();
 await expect(rpc('playback_admin_state',[h],other)).rejects.toThrow();
 await expect(rpc('playback_confirm_physical_stop',[r],other)).rejects.toThrow();
 expect((await rpc('playback_admin_state',[h])).requests[0].id).toBe(r);
 await rpc('playback_confirm_physical_stop',[r]); await rpc('playback_promote',[r,'b']);
 await expect(rpc('playback_confirm_physical_stop',[r])).rejects.toThrow();
});
it('revalidation promote, profil supprimé et révocation ne libèrent pas la place',async()=>{
 await rpc('playback_enable',[h]); const a=await claim();
 const r=await rpc('playback_request_stop',[h,a.generation,p,'b','Autre','source','Fiction']);
 await rpc('playback_stopped',[h,a.generation,'a',r]);
 await rpc('org_set_hidden',[h,null,'channel',['source'],true]);
 await expect(rpc('playback_promote',[r,'b'])).rejects.toThrow('source-masquee');
 await rpc('org_set_hidden',[h,null,'channel',['source'],false]);
 const b=await rpc('playback_promote',[r,'b']);
 await db.admin.query('delete from profiles where id=$1',[p]);
 expect((await rpc('playback_control',[h,b.generation,'b'])).status).toBe('stop');
 expect((await db.admin.query('select state from playback_slot')).rows[0].state).toBe('stopping');
 await db.admin.query('delete from household_members where user_id=$1',[owner]);
 await expect(rpc('playback_control',[h,b.generation,'b'])).rejects.toThrow();
 await rpc('playback_stopped',[h,b.generation,'b']);
});
it('bloque v1 activé et protège profils, sources, clés, RLS',async()=>{
 await rpc('playback_enable',[h]);
 await expect(rpc('claim_stream',[h,p,'Appareil','Fiction',1,'a',true])).rejects.toThrow('protocole-obsolete');
 for(const key of ['',null,'x'.repeat(65)]) await expect(claim(key as string)).rejects.toThrow();
 await expect(rpc('playback_claim',[h,null,'a','A','source','Fiction'])).rejects.toThrow();
 await expect(rpc('playback_claim',[h,p,'a','A','inconnue','Fiction'])).rejects.toThrow('source-inconnue');
 await expect(db.as(owner,c=>c.query('select device_hash from playback_request'))).rejects.toThrow();
 await expect(db.as(owner,c=>c.query('select * from playback_slot'))).rejects.toThrow();
});
it('expiration attente conserve la lecture; expiration ready libère seulement la priorité',async()=>{
 await rpc('playback_enable',[h]); const a=await claim();
 const r=await rpc('playback_request_stop',[h,a.generation,p,'b','Autre','source','Fiction']);
 await db.admin.query("update playback_request set expires_at=now()-interval '1 second' where id=$1",[r]);
 expect((await rpc('playback_poll_request',[r,'b'])).status).toBe('cancelled');
 await expect(rpc('playback_confirm_physical_stop',[r])).rejects.toThrow('demande-invalide');
 expect((await claim('c')).status).toBe('occupied');
 await rpc('playback_stopped',[h,a.generation,'a']);
 expect((await claim('c')).status).toBe('ok');
});
it('ready périmé ne permet pas promote, réservation sans première image reste bloquante',async()=>{
 await rpc('playback_enable',[h]);const a=await claim();
 const r=await rpc('playback_request_stop',[h,a.generation,p,'b','Autre','source','Fiction']);
 await rpc('playback_stopped',[h,a.generation,'a',r]);
 await db.admin.query("update playback_request set expires_at=now()-interval '1 second'");
 await expect(rpc('playback_promote',[r,'b'])).rejects.toThrow('demande-invalide');
 const c=await claim('c');expect(c.status).toBe('ok');
 await db.admin.query("update playback_slot set last_heartbeat=now()-interval '1 hour'");
 expect(await claim('d')).toMatchObject({status:'occupied',stale:true});
});
it('masques globaux et profil, source update, profil étranger, propriétaire étranger',async()=>{
 const foreign=await db.newUser(); const h2=await db.seedHousehold(foreign,['Étranger']);
 const p2=(await db.admin.query('select id from profiles where household_id=$1',[h2])).rows[0].id;
 await expect(rpc('playback_enable',[h],foreign)).rejects.toThrow('acces-refuse');
 await rpc('playback_enable',[h]);
 await expect(rpc('playback_claim',[h,p2,'a','A','source','Fiction'])).rejects.toThrow('acces-refuse');
 for(const scope of [null,p]) {
  for(const [type,id] of [['channel','source'],['live_category','cat']]){
   await rpc('org_set_hidden',[h,scope,type,[id],true]);await expect(claim()).rejects.toThrow('source-masquee');
   await rpc('org_set_hidden',[h,scope,type,[id],false]);
  }
 }
 const a=await claim();
 await expect(rpc('playback_update',[h,a.generation,'a','absente','Fiction'])).rejects.toThrow('source-inconnue');
 await rpc('org_set_hidden',[h,p,'live_category',['cat'],true]);
 await expect(rpc('playback_update',[h,a.generation,'a','source','Fiction'])).rejects.toThrow('source-masquee');
 expect((await rpc('playback_control',[h,a.generation,'a'])).status).toBe('stop');
});
it('révocation appareil signale stop, ACK restreint après révocation, aucune cascade de place',async()=>{
 const device=await db.newUser({anonymous:true});
 await db.admin.query("insert into household_members(household_id,user_id,role,device_name) values($1,$2,'device','Télé')",[h,device]);
 await expect(rpc('playback_enable',[h],device)).rejects.toThrow('acces-refuse');
 await rpc('playback_enable',[h]);
 const a=await rpc('playback_claim',[h,p,'a','Télé','source','Fiction'],device);
 await rpc('revoke_device',[h,device]);
 expect((await db.admin.query('select state from playback_slot')).rows[0].state).toBe('stopping');
 expect((await db.admin.query('select kind from playback_signal')).rows[0].kind).toBe('revoked');
 await expect(rpc('playback_stopped',[h,a.generation,'a'])).rejects.toThrow('acces-refuse');
 await expect(rpc('playback_stopped',[h,a.generation,'b'],device)).rejects.toThrow('acces-refuse');
 await rpc('playback_stopped',[h,a.generation,'a'],device);
 expect((await claim('b')).status).toBe('ok');
});
it('seuls les signaux et demandes publiques sont publiés; helpers privés et anon refusé',async()=>{
 const pubs=(await db.admin.query("select tablename,attnames from pg_publication_tables where pubname='supabase_realtime' and tablename like 'playback_%'")).rows;
 expect(pubs.map(r=>r.tablename).sort()).toEqual(['playback_request','playback_signal']);
 expect(JSON.stringify(pubs)).not.toContain('device_hash');
 await expect(db.as(owner,c=>c.query('select playback_lock($1)',[h]))).rejects.toThrow('permission denied');
 await expect(db.as(null,c=>c.query('select playback_enable($1)',[h]))).rejects.toThrow('permission denied');
 await rpc('playback_enable',[h]);await claim();
 const stored=(await db.admin.query('select encode(device_hash,\'hex\') hash from playback_slot')).rows[0].hash;
 expect(stored).toHaveLength(64);expect(stored).not.toBe('a');
});
it('expiration vérifiée à l’instant réel même dans une transaction commencée avant',async()=>{
 await rpc('playback_enable',[h]); const a=await claim();
 const r=await rpc('playback_request_stop',[h,a.generation,p,'b','Autre','source','Fiction']);
 await rpc('playback_stopped',[h,a.generation,'a',r]);
 await expect(db.as(owner,async c=>{
  // Fixe une échéance proche sans attendre 30 secondes dans le test.
  await db.admin.query("update playback_request set expires_at=clock_timestamp()+interval '50 milliseconds' where id=$1",[r]);
  await c.query('select pg_sleep(0.1)');
  return c.query('select playback_promote($1,$2)',[r,'b']);
 })).rejects.toThrow('demande-invalide');
});
