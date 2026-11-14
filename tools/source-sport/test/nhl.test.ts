import { it, expect } from 'vitest';
import { parseNhlSchedule, nhlScheduleUrl } from '../src/nhl';
import { isCanadianBroadcaster } from '../src/match';
import week from '../fixtures/nhl-api.json';

it('url', () => expect(nhlScheduleUrl('2026-11-15')).toBe('https://api-web.nhle.com/v1/schedule/2026-11-15'));

it('lit toute la semaine avec les diffuseurs', () => {
  const g = parseNhlSchedule(week);
  const total = week.gameWeek.reduce((n: number, d: { games: unknown[] }) => n + d.games.length, 0);
  expect(g.length).toBe(total);
  expect(g.every((x) => x.league === 'nhl')).toBe(true);
  const mtl = g.find((x) => x.teams.includes('Canadiens'));
  expect(mtl).toBeDefined();
  expect(mtl!.broadcasts.some(isCanadianBroadcaster)).toBe(true);
});

it('aliases Sportsnet supplémentaires', () => {
  for (const b of ['SNP', 'SN+', 'SNE', 'SNW', 'SNO']) expect(isCanadianBroadcaster(b)).toBe(true);
});
