import { describe, expect, it } from 'vitest';
import { saveSections, sectionsOf, sectionsSummary, toggleSection } from '../src/sections';

describe('sections du profil', () => {
  it('lit le réglage ; absent ou vide = toutes, ordre fixe', () => {
    expect(sectionsOf({})).toEqual(['direct', 'series', 'films', 'sport']);
    expect(sectionsOf({ sections: [] })).toEqual(['direct', 'series', 'films', 'sport']);
    expect(sectionsOf({ sections: ['films', 'x', 'series'] })).toEqual(['series', 'films']);
  });
  it('cacher la page d’ouverture la déplace ; jamais zéro section', () => {
    expect(toggleSection(['direct', 'series', 'films', 'sport'], 'direct', 'sport')).toEqual({ sections: ['direct', 'series', 'films'], home: 'direct' });
    expect(toggleSection(['direct', 'series', 'films'], 'direct', 'direct')).toEqual({ sections: ['series', 'films'], home: 'series' });
    expect(toggleSection(['films'], 'films', 'films')).toEqual({ error: 'Garde au moins une section.' });
    expect(toggleSection(['films'], 'films', 'sport')).toEqual({ sections: ['films', 'sport'], home: 'films' });
    expect(sectionsSummary(['series', 'films'])).toBe('Séries, Films');
    expect(sectionsSummary(['direct', 'series', 'films', 'sport'])).toBe('Toutes');
  });
  it('enregistre les sections et la page d’ouverture ensemble', async () => {
    const writes: object[] = [];
    const db = { from: () => ({ update: (v: object) => { writes.push(v); return { eq: () => ({ select: async () => ({ data: [{ id: 'p' }], error: null }) }) }; } }) };
    expect(await saveSections(db as never, 'p', { a: 1 }, ['series'], 'series', 'direct')).toEqual({ a: 1, sections: ['series'] });
    await saveSections(db as never, 'p', {}, ['series', 'films'], 'series', 'series');
    expect(writes).toEqual([{ settings: { a: 1, sections: ['series'] }, home: 'series' }, { settings: { sections: ['series', 'films'] } }]);
  });
});
