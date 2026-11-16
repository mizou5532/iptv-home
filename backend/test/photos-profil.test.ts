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

describe('photos de profil', () => {
  it('un membre enregistre, remplace et retire la photo d’un profil de son foyer', async () => {
    const a = await db.newUser();
    const [toi, soeur] = await foyer(a);
    await put(a, 'web', `avatars/${soeur}.webp`);
    const u = await db.as(a, (c) => c.query(`update storage.objects set metadata = '{"v":2}' where name = $1`, [`avatars/${soeur}.webp`]));
    expect(u.rowCount).toBe(1);
    await put(a, 'web', `avatars/${toi}.webp`);
    const d = await db.as(a, (c) => c.query('delete from storage.objects where name = $1', [`avatars/${toi}.webp`]));
    expect(d.rowCount).toBe(1);
  });

  it('refuse un autre nom, un autre compartiment ou le profil d’un autre foyer', async () => {
    const a = await db.newUser();
    const b = await db.newUser();
    const [toi] = await foyer(a);
    await expect(put(a, 'web', 'assets/app-PIRATE.js')).rejects.toMatchObject({ code: '42501' });
    await expect(put(a, 'web', `avatars/${toi}.png`)).rejects.toMatchObject({ code: '42501' });
    await expect(put(a, 'apk', `avatars/${toi}.webp`)).rejects.toMatchObject({ code: '42501' });
    await expect(put(b, 'web', `avatars/${toi}.webp`)).rejects.toMatchObject({ code: '42501' });
    await expect(put(null as unknown as string, 'web', `avatars/${toi}.webp`)).rejects.toBeTruthy();
    // Un étranger ne voit ni ne retire la photo d'un autre foyer.
    await put(a, 'web', `avatars/${toi}.webp`);
    const d = await db.as(b, (c) => c.query('delete from storage.objects where name = $1', [`avatars/${toi}.webp`]));
    expect(d.rowCount).toBe(0);
  });

  it('le compartiment accepte WebP, JPEG et GIF', async () => {
    const r = await db.admin.query(`select allowed_mime_types from storage.buckets where id = 'web'`);
    expect(r.rows[0].allowed_mime_types).toEqual(expect.arrayContaining(['image/webp', 'image/jpeg', 'image/gif', 'text/css']));
  });
});
