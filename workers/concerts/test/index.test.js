import assert from 'node:assert/strict';
import test from 'node:test';
import {
  encodeGeohash,
  normalizeEvent,
  searchParameters,
  ticketmasterUrl
} from '../src/index.js';

test('accepts city/state, ZIP, and coordinates', () => {
  assert.deepEqual(
    searchParameters(new URL('https://concerts.example.com/events?location=Example%20City,%20CA')),
    { kind: 'city', city: 'Example City', stateCode: 'CA', label: 'Example City, CA' }
  );
  assert.deepEqual(
    searchParameters(new URL('https://concerts.example.com/events?location=00000')),
    { kind: 'postal', postalCode: '00000', label: '00000' }
  );
  assert.equal(
    searchParameters(new URL('https://concerts.example.com/events?lat=0&lng=0'))?.geoPoint,
    encodeGeohash(0, 0, 9)
  );
});

test('builds a music-only Ticketmaster request without exposing the key elsewhere', () => {
  const url = ticketmasterUrl(
    { kind: 'city', city: 'Example City', stateCode: 'CA' },
    'test-key'
  );
  assert.equal(url.searchParams.get('classificationName'), 'Music');
  assert.equal(url.searchParams.get('city'), 'Example City');
  assert.equal(url.searchParams.get('stateCode'), 'CA');
  assert.equal(url.searchParams.get('apikey'), 'test-key');
});

test('normalizes event data for Orchard', () => {
  const event = normalizeEvent({
    id: 'event-1',
    name: 'Example Show',
    url: 'https://ticketmaster.example/event-1',
    dates: { start: { localDate: '2026-07-04', localTime: '19:30:00' } },
    images: [{ url: 'small.jpg', width: 100 }, { url: 'large.jpg', width: 1000 }],
    _embedded: {
      venues: [{
        name: 'Example Hall',
        city: { name: 'Example City' },
        state: { stateCode: 'CA' },
        country: { countryCode: 'US' }
      }]
    }
  });

  assert.equal(event.title, 'Example Show');
  assert.equal(event.thumbnail, 'large.jpg');
  assert.equal(event.city, 'Example City');
});
