import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { startDb, type Db } from './db';

let db: Db;
beforeAll(async () => { db = await startDb(); });
afterAll(async () => { await db?.stop(); });
beforeEach(async () => { await db.resetHouseholds(); });

interface Ctx { owner: string; tv: string; h: string; toi: string; soeur: string }

async function foyer(autre = false): Promise<Ctx> {
  const owner = await db.newUser({ email: `s${Math.random()}@exemple.test` });
  const noms = ['Toi', 'Sœur'];
  const h = autre
    ? await db.seedHousehold(owner, noms)
    : (await db.as(owner, (c) => c.query(`select create_household('Maison', $1::text[]) id`, [noms]))).rows[0].id as string;
  const tv = await db.newUser({ anonymous: true });
  await db.admin.query(`insert into household_members (household_id, user_id, role) values ($1, $2, 'device')`, [h, tv]);
  const p = await db.admin.query('select id, name from profiles where household_id = $1', [h]);
  const id = (n: string) => p.rows.find((r) => r.name === n).id as string;
  return { owner, tv, h, toi: id('Toi'), soeur: id('Sœur') };
}

const cats = (...ids: string[]) => JSON.stringify(ids.map((id, i) => ({ id, name: `Serie ${id}`, position: i })));
const q = async <T = any>(u: string, sql: string, params: unknown[] = []) =>
  (await db.as(u, (c) => c.query(sql, params))).rows as T[];
const publish = (tv: string, h: string, ...ids: string[]) => q(tv, 'select publish_movie_categories($1, $2::jsonb)', [h, cats(...ids)]);
const sections = async (u: string, h: string, p: string | null) =>
  (await q(u, 'select org_movie_categories($1, $2) r', [h, p]))[0].r as any[];
const ordre = async (u: string, h: string, p: string | null) => (await sections(u, h, p)).map((r) => r.id as string);
const prefs = async (u: string, p: string) => (await q(u, 'select profile_prefs($1) r', [p]))[0].r as any;

const BASE = Date.now() - 3600_000;
const T = (n: number) => new Date(BASE + n * 1000).toISOString();
const rec12 = (p: string, id: string, pos: number, dur: number, at: string | null, serie: string | null,
  s: number, e: number, nom: string | null, aff: string | null, cat: string | null) =>
  (c: any) => c.query('select record_progress($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12) ok',
    [p, 'episode', id, pos, dur, at, serie, s, e, nom, aff, cat]);
const rec9 = (p: string, id: string, pos: number, dur: number, at: string | null, serie: string | null, s: number, e: number) =>
  (c: any) => c.query('select record_progress($1, $2, $3, $4, $5, $6, $7, $8, $9) ok', [p, 'episode', id, pos, dur, at, serie, s, e]);


describe('films', () => {
  it('categories_masques_ordre_et_preferences', async () => {
    const f = await foyer();
    await publish(f.tv, f.h, 'a', 'b', 'c');
    await q(f.tv, 'select publish_movie_categories($1, $2::jsonb)', [f.h, '[{}]']);
    expect(await ordre(f.tv, f.h, null)).toEqual(['a','b','c']);
    await q(f.owner, "select org_set_hidden($1,null,'vod_category',array['a'],true)", [f.h]);
    await q(f.tv, "select org_movie_hide_all_except($1,$2,array['c'])", [f.h,f.toi]);
    expect((await sections(f.tv,f.h,f.toi)).find(x=>x.id==='a')).toMatchObject({hidden_global:true,hidden_profile:true});
    await q(f.owner,"select org_set_movie_category_order($1,null,array['c','a','c'])",[f.h]);
    expect(await ordre(f.tv,f.h,f.toi)).toEqual(['c','a','b']);
    await q(f.tv,"select org_set_movie_category_order($1,$2,array['b'])",[f.h,f.toi]);
    expect((await prefs(f.tv,f.toi)).movie_order).toEqual(['b']);
    expect((await prefs(f.tv,f.toi)).series_list).toEqual([]);
    await expect(q(f.tv,"select org_movie_hide_all_except($1,null,array['c'])",[f.h])).rejects.toMatchObject({code:'42501'});
    await expect(q(f.tv,"select org_set_movie_category_order($1,null,array['c'])",[f.h])).rejects.toMatchObject({code:'42501'});
    await expect(q(f.tv,"insert into movie_catalog_category(household_id,id,name,position) values($1,'x','X',0)",[f.h])).rejects.toBeTruthy();
  });
  it('liste_metadonnees_ordre_revision_et_suppression', async () => {
    const f=await foyer();
    const rev=Number((await prefs(f.tv,f.toi)).rev);
    for(const id of ['m1','m2','m3']) await q(f.tv,'select org_set_movie_list($1,$2,true,$3,$4,$5)',[f.toi,id,'Film '+id,'image','cat']);
    await q(f.tv,'select org_set_movie_list($1,$2,true,null,null,null)',[f.toi,'m2']);
    await q(f.tv,"select org_reorder_movie_list($1,array['m3','m1','m3'])",[f.toi]);
    expect((await prefs(f.tv,f.toi)).movie_list).toEqual(['m3','m1','m2']);
    expect(Number((await prefs(f.tv,f.toi)).rev)).toBeGreaterThan(rev);
    expect((await q(f.tv,'select movie_list($1) r',[f.toi]))[0].r[0]).toEqual({item_id:'m3',movie_name:'Film m3',movie_cover:'image',movie_category:'cat'});
    await q(f.tv,'select org_set_movie_list($1,$2,false,null,null,null)',[f.toi,'m1']);
    expect((await prefs(f.tv,f.toi)).movie_list).toEqual(['m3','m2']);
    expect((await q(f.tv,'select movie_list($1) r',[f.soeur]))[0].r).toEqual([]);
  });
  it('progression_typee_compatibilite_horodatage_et_masquage_avant_limite', async () => {
    const f=await foyer();
    const rec=(id:string,pos:number,dur:number,t:string,cat='c')=>q(f.tv,'select record_movie_progress($1,$2,$3,$4,$5,$6,$7,$8) ok',[f.toi,id,pos,dur,t,'Film','Affiche',cat]);
    await db.as(f.tv,rec12(f.toi,'same',10,100,T(1),'same',1,1,'Serie',null,'s'));
    expect((await rec('same',20,100,T(2)))[0].ok).toBe(true);
    expect((await rec('same',1,100,T(1)))[0].ok).toBe(false);
    expect((await q(f.tv,'select movie_progress($1,$2) r',[f.toi,'same']))[0].r[0]).toMatchObject({item_type:'movie',position_s:20,movie_name:'Film',series_name:null});
    expect(await q(f.tv,'select item_type,item_id from continue_watching($1)',[f.toi])).toHaveLength(2);
    await rec('hidden',10,100,T(3),'hidden');
    await q(f.owner,"select org_set_hidden($1,null,'vod_category',array['hidden'],true)",[f.h]);
    expect(await q(f.tv,'select item_id from continue_movies($1,1)',[f.toi])).toEqual([{item_id:'same'}]);
    expect(await q(f.tv,'select item_id from continue_movies($1,-1)',[f.toi])).toEqual([]);
    await q(f.tv,'select record_progress($1,$2,$3,25,100,$4)',[f.toi,'movie','same',T(4)]);
    expect((await q(f.tv,'select movie_progress($1,$2) r',[f.toi,'same']))[0].r[0].movie_name).toBe('Film');
    await expect(rec('null',1,100,null as any)).rejects.toMatchObject({message:'horodatage-invalide'});
    await expect(rec('bad',1,-1,T(1))).rejects.toMatchObject({code:'23514'});
    await rec('unknown',123,0,T(1));
    expect((await q(f.tv,'select movie_progress($1,$2) r',[f.toi,'unknown']))[0].r[0]).toMatchObject({position_s:123,watched:false});
  });
  it('vu_non_vu_preserve_date_et_episode_identique', async () => {
    const f=await foyer();
    await q(f.tv,'select record_movie_progress($1,$2,95,100,$3,null,null,null)',[f.toi,'same',T(1)]);
    await db.as(f.tv,rec9(f.toi,'same',20,100,T(2),'s',1,1));
    const mark=(id:string,on:boolean)=>q(f.tv,'select set_movie_watched($1,$2,$3,$4,$5,$6,$7)',[f.toi,id,on,'Film','image','c',100]);
    await mark('same',false);
    let row=(await q(f.tv,'select movie_progress($1,$2) r',[f.toi,'same']))[0].r[0];
    expect(row).toMatchObject({watched:false,position_s:0});
    expect(new Date(row.client_updated_at).toISOString()).toBe(T(1));
    await mark('same',true);
    expect(await q(f.tv,'select item_id from continue_movies($1)',[f.toi])).toEqual([]);
    expect((await q(f.tv,'select position_s from watch_progress where item_type=$1',['episode']))[0].position_s).toBe(20);
    await mark('new',true);
    row=(await q(f.tv,'select movie_progress($1,$2) r',[f.toi,'new']))[0].r[0];
    expect(new Date(row.client_updated_at).toISOString()).toBe('2000-01-01T00:00:00.000Z');
    expect((await q(f.tv,'select movie_progress($1,$2) r',[f.toi,'missing']))[0].r).toEqual([]);
  });
  it('isolation_foyers_et_aucun_acces_anonyme', async () => {
    const f=await foyer(),g=await foyer(true);
    await publish(f.tv,f.h,'a');
    const calls:[string,unknown[]][]=[
      ['select publish_movie_categories($1,$2::jsonb)',[f.h,cats('x')]],
      ['select org_movie_categories($1,null)',[f.h]],
      ["select org_movie_hide_all_except($1,null,array['x'])",[f.h]],
      ["select org_set_movie_category_order($1,null,array['x'])",[f.h]],
      ['select org_set_movie_list($1,$2,true,null,null,null)',[f.toi,'m']],
      ["select org_reorder_movie_list($1,array['m'])",[f.toi]],
      ['select movie_list($1)',[f.toi]],
      ['select record_movie_progress($1,$2,1,100,$3,null,null,null)',[f.toi,'m',T(1)]],
      ['select movie_progress($1,$2)',[f.toi,'m']],
      ['select continue_movies($1)',[f.toi]],
      ['select set_movie_watched($1,$2,true,null,null,null,100)',[f.toi,'m']]];
    for(const [sql,args] of calls){
      await expect(q(g.owner,sql,args)).rejects.toMatchObject({code:'42501'});
      await expect(q(null as any,sql,args)).rejects.toBeTruthy();
    }
    expect(await q(g.owner,'select * from movie_catalog_category')).toEqual([]);
  });
});
