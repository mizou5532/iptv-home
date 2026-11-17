import { describe, it, expect } from 'vitest';
import { History, addLayer, duplicateLayer, emptyProject, hitTest, moveLayer, parseProject, pinch, referencedKeys, removeLayer, studioPath, updateLayer } from '../src/studio';

describe('atelier de fond d’écran', () => {
  it('création relue et corrigée, jamais d’erreur', () => {
    expect(parseProject(null)).toBeNull();
    const p = parseProject({
      bg: { kind: 'photo', key: 'abcdef123', frame: { zoom: 9, dx: 3 } }, blur: 'fort', dim: 200,
      layers: [
        { kind: 'image', key: 'img000001', x: 0.2, y: 0.3, w: 9, ar: 1.5, rot: 0.3 },
        { kind: 'emoji', emoji: '⭐' }, { kind: 'emoji', emoji: '💩' },
        { kind: 'text', text: 'x'.repeat(100), color: 'rouge' }, { kind: 'image', key: '../../etc' },
      ],
    })!;
    expect(p.bg).toEqual({ kind: 'photo', key: 'abcdef123', frame: { zoom: 4, dx: 3, dy: 0 } });
    expect(p.blur).toBe('fort'); expect(p.dim).toBe(80);
    expect(p.layers.map((l) => l.kind)).toEqual(['image', 'emoji', 'text']);
    expect(p.layers[0]!.w).toBe(1.5);
    expect(p.layers[2]!.text).toHaveLength(60); expect(p.layers[2]!.color).toBe('#FFFFFF');
    expect([...referencedKeys(p)]).toEqual(['abcdef123', 'img000001']);
    expect(studioPath('p1', 'abcdef123')).toBe('studio/p1/abcdef123.webp');
    expect(parseProject({ bg: { kind: 'color', color: '#ff0000' } })!.bg).toEqual({ kind: 'color', color: '#FF0000' });
  });

  it('ajouter, modifier, ordonner, dupliquer, retirer', () => {
    let p = addLayer(emptyProject(), { kind: 'emoji', emoji: '⭐', ar: 1 }, 'aaaaaa1');
    p = addLayer(p, { kind: 'text', text: 'Maha', color: '#FFFFFF', ar: 0.3 }, 'bbbbbb2');
    expect(p.layers.map((l) => l.id)).toEqual(['aaaaaa1', 'bbbbbb2']);
    p = moveLayer(p, 'bbbbbb2', -1);
    expect(p.layers.map((l) => l.id)).toEqual(['bbbbbb2', 'aaaaaa1']);
    expect(moveLayer(p, 'bbbbbb2', -1)).toBe(p);
    p = updateLayer(p, 'aaaaaa1', { w: 99, x: 0.1 });
    expect(p.layers[1]).toMatchObject({ w: 0.25, x: 0.1 });
    expect(updateLayer(p, 'bbbbbb2', { w: 99 }).layers[0]!.w).toBe(1.5);
    p = duplicateLayer(p, 'aaaaaa1', 'cccccc3');
    expect(p.layers[2]).toMatchObject({ id: 'cccccc3', emoji: '⭐' });
    expect(removeLayer(p, 'aaaaaa1').layers).toHaveLength(2);
  });

  it('toucher : l’élément le plus en avant, rotation comprise', () => {
    let p = addLayer(emptyProject(), { kind: 'emoji', emoji: '⭐', ar: 1, w: 0.1 }, 'aaaaaa1');
    p = addLayer(p, { kind: 'text', text: 'Long', color: '#FFFFFF', ar: 0.1, w: 0.5 }, 'bbbbbb2');
    expect(hitTest(p, 960, 540, 1920, 1080)).toBe('bbbbbb2');
    expect(hitTest(p, 960, 620, 1920, 1080)).toBe('aaaaaa1');
    expect(hitTest(p, 10, 10, 1920, 1080)).toBeNull();
    p = updateLayer(p, 'bbbbbb2', { rot: Math.PI / 2 }); // debout : le bout gauche n'est plus touché
    expect(hitTest(p, 960 - 400, 540, 1920, 1080)).toBeNull();
    expect(hitTest(p, 960, 540 - 400, 1920, 1080)).toBe('bbbbbb2');
  });

  it('pincer : taille et rotation suivent les doigts', () => {
    const start = { id: 'x', kind: 'emoji' as const, x: 0.5, y: 0.5, w: 0.2, ar: 1, rot: 0 };
    const r = pinch(start, [0, 0], [100, 0], [0, 0], [0, 200]);
    expect(r.w).toBeCloseTo(0.25, 5); expect(r.rot).toBeCloseTo(Math.PI / 2); // emoji : un quart de l'écran au plus
    expect(pinch({ ...start, kind: 'text' }, [0, 0], [100, 0], [0, 0], [0, 200]).w).toBeCloseTo(0.4);
  });

  it('annuler et rétablir', () => {
    const h = new History(emptyProject());
    const a = h.push(addLayer(h.current, { kind: 'emoji', emoji: '⭐', ar: 1 }, 'aaaaaa1'));
    h.replace(updateLayer(a, 'aaaaaa1', { x: 0.2 }));
    expect(h.undo().layers).toHaveLength(0);
    expect(h.redo().layers[0]!.x).toBe(0.2);
    expect(h.canRedo).toBe(false);
  });
});
