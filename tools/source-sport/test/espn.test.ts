import { it, expect } from 'vitest';
import { parseScoreboard, scoreboardUrl } from '../src/espn';
import nhl from '../fixtures/nhl.json';
import esp from '../fixtures/esp.1.json';
import nfl from '../fixtures/nfl.json';

it('url du calendrier (un jour)', () =>
  expect(scoreboardUrl('esp.1', '20261121')).toBe('https://site.api.espn.com/apis/site/v2/sports/soccer/esp.1/scoreboard?dates=20261121'));

it('LNH', () => {
  const g = parseScoreboard('nhl', nhl);
  expect(g.length).toBe(nhl.events.length);
  expect(g[0].name).toBe('Seattle Kraken at Carolina Hurricanes');
  expect(g[0].broadcasts).toEqual(['NHL Net', 'CARNHL', 'KONG', 'Prime Video (Local)']);
  expect(g[0].teams).toHaveLength(2);
});

it('Liga', () => {
  const g = parseScoreboard('esp.1', esp);
  expect(g.length).toBe(esp.events.length);
  expect(g[0].name).toBe('Deportivo at Alavés');
  expect(g[0].broadcasts).toEqual(['ESPN Deportes', 'ESPN+']);
});

it('NFL', () => {
  const g = parseScoreboard('nfl', nfl);
  expect(g[0].name).toBe('New England Patriots at Detroit Lions');
  expect(g[0].broadcasts).toEqual(['Fox']);
});

it('événement sans diffuseurs', () => {
  const g = parseScoreboard('nhl', { events: [{ date: '2026-11-14T00:00Z', name: 'A at B', competitions: [{ competitors: [] }] }] });
  expect(g[0].broadcasts).toEqual([]);
});
