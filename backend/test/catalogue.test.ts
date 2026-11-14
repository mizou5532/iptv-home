import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { startDb, type Db } from './db';

let db: Db;
beforeAll(async () => { db = await startDb(); });
afterAll(async () => { await db?.stop(); });
beforeEach(async () => { await db.resetHouseholds(); });

interface Ctx { owner: string; tv: string; h: string }

// Foyer du propriétaire et télé appairée : c'est la télé qui publie.
async function foyer(): Promise<Ctx> {
  const owner = await db.newUser({ email: `proprio-${Math.random()}@exemple.test` });
  const h = (await db.as(owner, (c) => c.query(`select create_household('Maison', $1::text[]) id`, [['Toi']]))).rows[0].id as string;
  const tv = await db.newUser({ anonymous: true });
  const code = (await db.as(tv, (c) => c.query(`select create_pairing_code('Télé') code`))).rows[0].code as string;
  await db.as(owner, (c) => c.query('select approve_pairing($1, $2)', [h, code]));
  return { owner, tv, h };
}

const cats = (...ids: string[]) => JSON.stringify(ids.map((id, i) => ({ id, name: `Catégorie ${id}`, position: i })));
const chans = (ids: (string | number)[], from = 0) =>
  JSON.stringify(ids.map((id, i) => ({ id: String(id), name: `Chaîne ${id}`, norm: `chaine ${id}`, number: from + i + 1, position: from + i })));

const publishCats = (u: string, h: string, json: string) =>
  db.as(u, (c) => c.query('select publish_live_categories($1, $2::jsonb) r', [h, json]))
    .then((r) => Object.entries(r.rows[0].r as Record<string, string | null>).map(([id, fingerprint]) => ({ id, fingerprint })));
const publishChans = (u: string, h: string, cat: string, fp: string, chunk: number, last: boolean, json: string) =>
  db.as(u, (c) => c.query('select publish_live_channels($1, $2, $3, $4, $5, $6::jsonb)', [h, cat, fp, chunk, last, json]));
const count = (u: string, table: string, where = 'true') =>
  db.as(u, (c) => c.query(`select count(*)::int n from ${table} where ${where}`)).then((r) => r.rows[0].n as number);

describe('copie du catalogue du direct', () => {
  it('publie_categories_puis_chaines_et_le_proprietaire_les_lit', async () => {
    const { owner, tv, h } = await foyer();
    const rows = await publishCats(tv, h, cats('1', '2', '3'));
    expect(rows.map((r) => r.id).sort()).toEqual(['1', '2', '3']);
    expect(rows.every((r) => r.fingerprint === null)).toBe(true);
    await publishChans(tv, h, '1', 'empreinte-a', 0, true, chans(['10', '11']));
    expect(await count(owner, 'live_catalog_category')).toBe(3);
    expect(await count(owner, 'live_catalog_channel')).toBe(2);
    const again = await publishCats(tv, h, cats('1', '2', '3'));
    expect(again.find((r) => r.id === '1')?.fingerprint).toBe('empreinte-a');
    expect(again.find((r) => r.id === '2')?.fingerprint).toBeNull();
  });

  it('categorie_disparue_supprimee_avec_ses_chaines', async () => {
    const { owner, tv, h } = await foyer();
    await publishCats(tv, h, cats('1', '2', '3'));
    await publishChans(tv, h, '2', 'f2', 0, true, chans(['20', '21']));
    await publishChans(tv, h, '1', 'f1', 0, true, chans(['10']));
    await publishCats(tv, h, cats('1', '3'));
    expect(await count(owner, 'live_catalog_category', `id = '2'`)).toBe(0);
    expect(await count(owner, 'live_catalog_channel', `category_id = '2'`)).toBe(0);
    expect(await count(owner, 'live_catalog_channel', `category_id = '1'`)).toBe(1);
  });

  it('categorie_absente_de_l_envoi_de_chaines_garde_ses_chaines', async () => {
    const { owner, tv, h } = await foyer();
    await publishCats(tv, h, cats('1', '2', '3'));
    await publishChans(tv, h, '3', 'f3', 0, true, chans(['30', '31', '32']));
    await publishCats(tv, h, cats('1', '2', '3'));
    expect(await count(owner, 'live_catalog_channel', `category_id = '3'`)).toBe(3);
    const rows = await publishCats(tv, h, cats('1', '2', '3'));
    expect(rows.find((r) => r.id === '3')?.fingerprint).toBe('f3');
  });

  it('morceaux_empreinte_ecrite_seulement_au_dernier', async () => {
    const { owner, tv, h } = await foyer();
    await publishCats(tv, h, cats('1'));
    await publishChans(tv, h, '1', 'ancienne', 0, true, chans(['10']));
    await publishChans(tv, h, '1', 'nouvelle', 0, false, chans(['10', '11']));
    expect((await publishCats(tv, h, cats('1')))[0].fingerprint).toBeNull();
    await publishChans(tv, h, '1', 'nouvelle', 1, true, chans(['12', '13'], 2));
    expect((await publishCats(tv, h, cats('1')))[0].fingerprint).toBe('nouvelle');
    expect(await count(owner, 'live_catalog_channel', `category_id = '1'`)).toBe(4);
  });

  it('chaine_deplacee_vers_une_autre_categorie_dernier_gagne', async () => {
    const { owner, tv, h } = await foyer();
    await publishCats(tv, h, cats('1', '2'));
    await publishChans(tv, h, '1', 'f1', 0, true, chans(['10', '11']));
    await publishChans(tv, h, '2', 'f2', 0, true, chans(['11']));
    expect(await count(owner, 'live_catalog_channel', `category_id = '1'`)).toBe(1);
    expect(await count(owner, 'live_catalog_channel', `category_id = '2' and id = '11'`)).toBe(1);
  });

  it('categorie_inconnue_refusee', async () => {
    const { tv, h } = await foyer();
    await publishCats(tv, h, cats('1'));
    await expect(publishChans(tv, h, '9', 'f', 0, true, chans(['90']))).rejects.toThrow('categorie-inconnue');
  });

  it('etranger_ni_lecture_ni_publication', async () => {
    const { tv, h } = await foyer();
    await publishCats(tv, h, cats('1'));
    await publishChans(tv, h, '1', 'f1', 0, true, chans(['10']));
    const intrus = await db.newUser({ email: 'intrus-cat@exemple.test' });
    expect(await count(intrus, 'live_catalog_category')).toBe(0);
    expect(await count(intrus, 'live_catalog_channel')).toBe(0);
    await expect(publishCats(intrus, h, cats('1'))).rejects.toThrow('acces-refuse');
    await expect(publishChans(intrus, h, '1', 'f', 0, true, chans(['10']))).rejects.toThrow('acces-refuse');
  });

  it('aucune_ecriture_directe', async () => {
    const { tv, h } = await foyer();
    await publishCats(tv, h, cats('1'));
    await expect(db.as(tv, (c) => c.query(
      `insert into live_catalog_channel (household_id, id, category_id, name, norm, position) values ($1, 'x', '1', 'X', 'x', 0)`, [h],
    ))).rejects.toThrow(/permission denied|row-level security/);
    await expect(db.as(tv, (c) => c.query(
      `insert into live_catalog_category (household_id, id, name, position) values ($1, 'y', 'Y', 0)`, [h],
    ))).rejects.toThrow(/permission denied|row-level security/);
  });

  it('cent_mille_chaines', async () => {
    const { owner, tv, h } = await foyer();
    await publishCats(tv, h, cats('1'));
    const t0 = Date.now();
    for (let k = 0; k < 50; k++) {
      const ids = Array.from({ length: 2000 }, (_, i) => k * 2000 + i);
      await publishChans(tv, h, '1', 'gros', k, k === 49, chans(ids, k * 2000));
    }
    expect(await count(owner, 'live_catalog_channel')).toBe(100_000);
    expect(Date.now() - t0).toBeLessThan(60_000);
  }, 120_000);
});
