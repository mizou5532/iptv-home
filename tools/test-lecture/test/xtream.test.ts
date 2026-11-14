import { describe, it, expect } from 'vitest';
import { normalizeServer, apiUrl, streamUrl } from '../src/xtream';

const c = { server: 'http://tv.exemple.com:8080', username: 'jean@x', password: 'a&b#c/d e' };

describe('normalizeServer', () => {
  it.each([
    ['tv.exemple.com:8080', 'http://tv.exemple.com:8080'],
    ['http://tv.exemple.com:8080/', 'http://tv.exemple.com:8080'],
    ['  http://tv.exemple.com:8080/player_api.php ', 'http://tv.exemple.com:8080'],
    ['https://tv.exemple.com', 'https://tv.exemple.com'],
  ])('%s', (input, out) => expect(normalizeServer(input)).toBe(out));
});

describe('apiUrl', () => {
  it('encode les identifiants et ajoute action et paramètres', () => {
    expect(apiUrl(c, 'get_series_info', { series_id: '12' })).toBe(
      'http://tv.exemple.com:8080/player_api.php?username=jean%40x&password=a%26b%23c%2Fd%20e&action=get_series_info&series_id=12');
  });
  it('sans action', () => {
    expect(apiUrl(c)).toBe('http://tv.exemple.com:8080/player_api.php?username=jean%40x&password=a%26b%23c%2Fd%20e');
  });
});

describe('streamUrl', () => {
  it('film', () => {
    expect(streamUrl(c, 'movie', '991', 'mkv')).toBe('http://tv.exemple.com:8080/movie/jean%40x/a%26b%23c%2Fd%20e/991.mkv');
  });
  it('direct', () => {
    expect(streamUrl(c, 'live', '7', 'm3u8')).toBe('http://tv.exemple.com:8080/live/jean%40x/a%26b%23c%2Fd%20e/7.m3u8');
  });
});

describe('normalizeServer — ne garde que protocole://hôte[:port]', () => {
  it.each([
    ['http://tv.exemple.com:8080/player_api.php?username=a&password=b', 'http://tv.exemple.com:8080'],
    ['http://tv.exemple.com:8080/get.php?username=a&password=b&type=m3u_plus', 'http://tv.exemple.com:8080'],
    ['HTTP://TV.Exemple.com:8080', 'http://tv.exemple.com:8080'],
    ['http://evil.com:9001/any/path?', 'http://evil.com:9001'],
  ])('%s', (input, out) => expect(normalizeServer(input)).toBe(out));
});
