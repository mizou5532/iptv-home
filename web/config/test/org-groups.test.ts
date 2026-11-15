import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { autoKey, buildGroups, type GroupSource, type GroupSnapshot } from '../src/org/groups';
const fixture = JSON.parse(readFileSync(new URL('../../../fixtures/live-groups-v1.json', import.meta.url), 'utf8')) as {
  autoKeys: [string, string | null][];
  cases: { name: string; sources: GroupSource[]; snapshot: GroupSnapshot; expected: unknown }[];
};
describe('groupes du direct', () => {
  for (const [name, key] of fixture.autoKeys) it(`normalise ${JSON.stringify(name)}`, () => expect(autoKey(name)).toBe(key));
  for (const c of fixture.cases) it(c.name, () => {
    const simplify = (sources: GroupSource[]) => buildGroups(sources, c.snapshot).map(g => ({ id: g.id, title: g.title, sourceIds: g.sources.map(s => s.id) }));
    expect(simplify(c.sources)).toEqual(c.expected);
    expect(simplify([...c.sources].reverse())).toEqual(c.expected);
  });
  it('rejette une version de regles inconnue', () => {
    expect(() => buildGroups([], { rev: 0, rulesVersion: 2, groups: [] })).toThrow();
  });
  it('ordonne les ex aequo par identifiant sans modifier les entrees', () => {
    const sources = [{ id: 'b', categoryId: 'x', name: 'RDS HD', position: 0 }, { id: 'a', categoryId: 'y', name: 'RDS SD', position: 0 }];
    expect(buildGroups(sources, { rev: 0, rulesVersion: 1, groups: [] })[0].sources.map(s => s.id)).toEqual(['a', 'b']);
    expect(sources.map(s => s.id)).toEqual(['b', 'a']);
  });
  it('traite 100000 sources et 2000 categories avec un nombre lineaire de lectures', () => {
    let reads = 0;
    const sources = Array.from({ length: 100000 }, (_, i) => ({ id: String(i), categoryId: String(i % 2000), get name() { reads++; return `Chaine ${i % 50000} HD`; }, position: i }));
    const result = buildGroups(sources, { rev: 0, rulesVersion: 1, groups: [] });
    expect(result).toHaveLength(50000);
    expect(result.every(g => g.sources.length === 2)).toBe(true);
    expect(reads).toBeLessThanOrEqual(300000);
  });
});
