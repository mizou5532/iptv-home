import { it, expect } from 'vitest';
import { matchBroadcaster, isCanadianBroadcaster } from '../src/match';

const chaines = ['CA| RDS FHD', 'RDS 2 HD', 'RDS Info', 'RDS HD', 'TVA Sports ᴴᴰ', 'TVA Sports 2', 'TSN 1', 'TSN 4 FHD', 'TSN+', 'ESPN', 'ESPN 2'];

it('RDS sans RDS 2 ni RDS Info', () => expect(matchBroadcaster('RDS', chaines)).toEqual(['CA| RDS FHD', 'RDS HD']));
it('TVAS', () => expect(matchBroadcaster('TVAS', chaines)).toEqual(['TVA Sports ᴴᴰ']));
it('TSN famille', () => expect(matchBroadcaster('TSN', chaines)).toEqual(['TSN 1', 'TSN 4 FHD']));
it('TSN+', () => expect(matchBroadcaster('TSN+', chaines)).toEqual(['TSN+']));
it('sans alias : égalité', () => expect(matchBroadcaster('ESPN', chaines)).toEqual(['ESPN']));
it('inconnu', () => expect(matchBroadcaster('Fox', chaines)).toEqual([]));
it('canadien', () => {
  expect(isCanadianBroadcaster('TVA Sports')).toBe(true);
  expect(isCanadianBroadcaster('SN1')).toBe(true);
  expect(isCanadianBroadcaster('ESPN')).toBe(false);
  expect(isCanadianBroadcaster('Prime Video')).toBe(false);
});
