import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { startDb, type Db } from './db';

let db: Db;
beforeAll(async () => { db = await startDb(); });
afterAll(async () => { await db?.stop(); });
beforeEach(async () => { await db.resetHouseholds(); await db.admin.query('delete from storage.objects'); });

async function foyer(owner: string): Promise<string[]> {
  const r = await db.as(owner, (c) => c.query(`select create_household('Maison', array['Toi','Sœur']) id`));
  const p = await db.admin.query('select id from profiles where household_id = $1 order by position', [r.rows[0].id]);
  return p.rows.map((x) => x.id);
}
const put = (uid: string, bucket: string, name: string) =>
  db.as(uid, (c) => c.query('insert into storage.objects (bucket_id, name) values ($1, $2) returning id', [bucket, name]));

describe('autocollants par profil', () => {
  it('un membre enregistre, remplace et retire l’autocollant d’un profil de son foyer', async () => {
    const a = await db.newUser();
    const [, soeur] = await foyer(a);
    await put(a, 'web', `stickers/${soeur}.webp`);
    const u = await db.as(a, (c) => c.query(`update storage.objects set metadata = '{"v":2}' where name = $1`, [`stickers/${soeur}.webp`]));
    expect(u.rowCount).toBe(1);
    const d = await db.as(a, (c) => c.query('delete from storage.objects where name = $1', [`stickers/${soeur}.webp`]));
    expect(d.rowCount).toBe(1);
  });

  it('refuse un autre nom ou le profil d’un autre foyer ; la photo marche toujours', async () => {
    const a = await db.newUser();
    const b = await db.newUser();
    const [toi] = await foyer(a);
    await expect(put(a, 'web', `stickers/${toi}.png`)).rejects.toMatchObject({ code: '42501' });
    await expect(put(a, 'web', `stickers/autre.webp`)).rejects.toMatchObject({ code: '42501' });
    await expect(put(b, 'web', `stickers/${toi}.webp`)).rejects.toMatchObject({ code: '42501' });
    await put(a, 'web', `avatars/${toi}.webp`);
  });

  it('le compartiment accepte aussi le PNG (transparence sur iPhone)', async () => {
    const r = await db.admin.query(`select allowed_mime_types from storage.buckets where id = 'web'`);
    expect(r.rows[0].allowed_mime_types).toEqual(expect.arrayContaining(['image/webp', 'image/jpeg', 'image/gif', 'image/png']));
  });

  it('fond d’écran : même règle (nom exact, son foyer seulement)', async () => {
    const a = await db.newUser();
    const b = await db.newUser();
    const [toi] = await foyer(a);
    await put(a, 'web', `wallpapers/${toi}.webp`);
    await expect(put(a, 'web', `wallpapers/${toi}.jpg`)).rejects.toMatchObject({ code: '42501' });
    await expect(put(b, 'web', `wallpapers/${toi}.webp`)).rejects.toMatchObject({ code: '42501' });
  });
});
