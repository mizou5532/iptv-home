import { afterAll, beforeAll, beforeEach, expect, test } from 'vitest';
import { startDb, type Db } from './db';
let db: Db;
beforeAll(async()=>{db=await startDb();});
afterAll(async()=>{await db?.stop();});
beforeEach(async()=>{await db.resetHouseholds();});
async function fixture() {
 const owner=await db.newUser(), device=await db.newUser({anonymous:true}), outsider=await db.newUser();
 const h=await db.seedHousehold(owner);
 await db.admin.query("insert into household_members(household_id,user_id,role,device_name) values($1,$2,'device','TCL Google TV')",[h,device]);
 return {owner,device,outsider,h};
}
test('ancien appareil garde son nom et reçoit le pictogramme télé',async()=>{
 const f=await fixture(); const r=await db.as(f.owner,c=>c.query('select device_name,device_icon from household_members where user_id=$1',[f.device]));
 expect(r.rows[0]).toEqual({device_name:'TCL Google TV',device_icon:'tv'});
});
test('propriétaire renomme et choisit chaque pictogramme',async()=>{
 const f=await fixture();
 for(const icon of ['tv','sofa','bed','desk','other']) {
 await db.as(f.owner,c=>c.query('select set_device_settings($1,$2,$3,$4)',[f.h,f.device,'  Chambre  ',icon]));
 const r=await db.admin.query('select device_name,device_icon from household_members where user_id=$1',[f.device]);
 expect(r.rows[0]).toEqual({device_name:'Chambre',device_icon:icon});
 }
});
test('un appareil ou étranger ne peut renommer une télé',async()=>{
 const f=await fixture();
 for(const uid of [f.device,f.outsider,null]) await expect(db.as(uid,c=>c.query('select set_device_settings($1,$2,$3,$4)',[f.h,f.device,'Intrus','bed']))).rejects.toThrow();
});
test('refuse propriétaire comme cible et appareil d’un autre foyer',async()=>{
 const f=await fixture(); const h2=await db.seedHousehold(f.outsider);
 for(const [h,target] of [[f.h,f.owner],[h2,f.device]]) await expect(db.as(h===h2?f.outsider:f.owner,c=>c.query('select set_device_settings($1,$2,$3,$4)',[h,target,'Essai','tv']))).rejects.toThrow();
});
test('refuse noms et pictogrammes invalides sans modifier les données',async()=>{
 const f=await fixture();
 for(const [name,icon] of [['','tv'],['  ','tv'],['a'.repeat(41),'tv'],[null,'tv'],['Salon','inconnu'],['Salon',null]]) await expect(db.as(f.owner,c=>c.query('select set_device_settings($1,$2,$3,$4)',[f.h,f.device,name,icon]))).rejects.toThrow();
 const r=await db.admin.query('select device_name from household_members where user_id=$1',[f.device]);expect(r.rows[0].device_name).toBe('TCL Google TV');
});
test('une télé déjà associée récupère son modèle sans réappairage',async()=>{
 const f=await fixture(); await db.admin.query("update household_members set device_name='Télé du salon' where user_id=$1",[f.device]);
 const r=await db.as(f.device,c=>c.query('select report_device_identity($1,$2) name',[f.h,'TCL QM85']));
 expect(r.rows[0].name).toBe('TCL QM85 · '+f.device.replaceAll('-','').slice(-6).toUpperCase());
});
test('une identité remontée ne remplace jamais un nom personnalisé',async()=>{
 const f=await fixture();await db.as(f.owner,c=>c.query('select set_device_settings($1,$2,$3,$4)',[f.h,f.device,'Chambre','bed']));
 await db.as(f.device,c=>c.query('select report_device_identity($1,$2)',[f.h,'TCL QM85']));
 const r=await db.admin.query('select device_name,device_icon,device_default_name from household_members where user_id=$1',[f.device]);
 expect(r.rows[0]).toEqual({device_name:'Chambre',device_icon:'bed',device_default_name:'TCL QM85'});
});
test('propriétaire ne peut se faire passer pour une télé et les noms de modèle invalides sont refusés',async()=>{
 const f=await fixture();
 await expect(db.as(f.owner,c=>c.query('select report_device_identity($1,$2)',[f.h,'Modèle']))).rejects.toThrow();
 for(const name of ['',null,'a'.repeat(41)]) await expect(db.as(f.device,c=>c.query('select report_device_identity($1,$2)',[f.h,name]))).rejects.toThrow();
});

test('la remontée du modèle reste limitée au foyer demandé',async()=>{
 const f=await fixture();const other=await db.seedHousehold(f.outsider);
 await db.admin.query("insert into household_members(household_id,user_id,role,device_name) values($1,$2,'device','Autre nom')",[other,f.device]);
 await db.as(f.device,c=>c.query('select report_device_identity($1,$2)',[f.h,'TCL QM85']));
 const r=await db.admin.query('select device_name,device_default_name from household_members where household_id=$1 and user_id=$2',[other,f.device]);
 expect(r.rows[0]).toEqual({device_name:'Autre nom',device_default_name:null});
});
test('migration conserve un ancien nom personnalisé quand des appareils existent déjà',async()=>{
 let h='';const ids:string[]=[];
 const old=await startDb(async(pool,filename)=>{
  if(!filename.endsWith('20261116210619_device_settings.sql'))return;
  for(let i=0;i<3;i++)ids.push((await pool.query('insert into auth.users(is_anonymous) values($1) returning id',[i>0])).rows[0].id);
  h=(await pool.query("insert into households(name,owner_id) values('Maison', $1) returning id",[ids[0]])).rows[0].id;
  await pool.query("insert into household_members(household_id,user_id,role,device_name) values($1,$2,'owner',null),($1,$3,'device','Télé du salon'),($1,$4,'device','Chambre existante')",[h,...ids]);
 });
 try {
  expect(ids).toHaveLength(3);
  for(const device of ids.slice(1))await old.as(device,c=>c.query('select report_device_identity($1,$2)',[h,'TCL QM85']));
  const rows=(await old.admin.query('select user_id,device_name,device_icon,device_name_customized from household_members where role=$1',['device'])).rows;
  expect(rows.find(r=>r.user_id===ids[1]).device_name).toMatch(/^TCL QM85 · [A-F0-9]{6}$/);
  expect(rows.find(r=>r.user_id===ids[2])).toMatchObject({device_name:'Chambre existante',device_name_customized:true,device_icon:'tv'});
 } finally {await old.stop();}
});
