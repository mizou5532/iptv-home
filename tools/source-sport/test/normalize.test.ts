import { it, expect } from 'vitest';
import { normalizeChannelName } from '../src/normalize';

it.each([
  ['CA| RDS FHD', 'rds'], ['RDS 2 HD', 'rds 2'], ['TVA Sports ᴴᴰ', 'tva sports'],
  ['[CA] TSN 1 (backup)', 'tsn 1'], ['Réseau des Sports', 'reseau des sports'],
  ['TSN+', 'tsn plus'], ['beIN Sports 4K', 'bein sports'],
  ['CA: Sportsnet One 1080p', 'sportsnet one'], ['FR - Canal+ Sport UHD', 'canal plus sport'],
])('normalise %s', (a, b) => expect(normalizeChannelName(a)).toBe(b));
