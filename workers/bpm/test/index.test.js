import assert from 'node:assert/strict';
import test from 'node:test';
import worker, {
  buildSearchUrl,
  chooseBestMatch,
  lookupSong,
  normalizeSong,
  parseLookup
} from '../src/index.js';

const masterOfPuppets = {
  id: 'o2r0L',
  title: 'Master of Puppets',
  uri: 'https://getsongbpm.com/song/master-of-puppets/o2r0L',
  tempo: '220',
  time_sig: '4/4',
  key_of: 'Em',
  open_key: '2m',
  danceability: 55,
  acousticness: 0,
  artist: { name: 'Metallica' },
  album: { title: 'Master of Puppets' }
};

test('requires a title and accepts an optional artist', () => {
  assert.deepEqual(
    parseLookup(new URL('https://bpm.example/bpm?title=%20Song%20&artist=%20Artist%20')),
    { title: 'Song', artist: 'Artist' }
  );
  assert.throws(
    () => parseLookup(new URL('https://bpm.example/bpm?artist=Artist')),
    /title query parameter is required/
  );
});

test('builds a refined GetSongBPM search without putting the API key in the URL', () => {
  const url = buildSearchUrl({ title: 'Enter Sandman', artist: 'Metallica' });
  assert.equal(url.origin, 'https://api.getsong.co');
  assert.equal(url.pathname, '/search/');
  assert.equal(url.searchParams.get('type'), 'both');
  assert.equal(url.searchParams.get('lookup'), 'song:Enter Sandman artist:Metallica');
  assert.equal(url.searchParams.has('api_key'), false);
});

test('normalizes GetSongBPM data into the public response shape', () => {
  assert.deepEqual(normalizeSong(masterOfPuppets), {
    id: 'o2r0L',
    title: 'Master of Puppets',
    artist: 'Metallica',
    album: 'Master of Puppets',
    bpm: 220,
    key: 'Em',
    openKey: '2m',
    timeSignature: '4/4',
    danceability: 55,
    acousticness: 0,
    songUrl: 'https://getsongbpm.com/song/master-of-puppets/o2r0L'
  });
});

test('ranks an exact title and artist above a weaker candidate', () => {
  const result = chooseBestMatch([
    { ...masterOfPuppets, id: 'wrong', artist: { name: 'Puppet Players' } },
    masterOfPuppets
  ], { title: 'Master of Puppets', artist: 'Metallica' });
  assert.equal(result.id, 'o2r0L');
});

test('sends the API key in a header and returns the best song', async () => {
  let captured;
  const song = await lookupSong('secret-test-key', {
    title: 'Master of Puppets',
    artist: 'Metallica'
  }, async (url, init) => {
    captured = { url: new URL(url), init };
    return Response.json({ search: [masterOfPuppets] });
  });

  assert.equal(captured.init.headers['x-api-key'], 'secret-test-key');
  assert.equal(captured.url.searchParams.has('api_key'), false);
  assert.equal(song.bpm, 220);
});

test('landing page includes the required GetSongBPM backlink', async () => {
  const response = await worker.fetch(new Request('https://bpm.example/'), {}, {});
  const html = await response.text();
  assert.equal(response.headers.get('content-type'), 'text/html; charset=utf-8');
  assert.match(html, /href="https:\/\/getsongbpm\.com\/"/);
});
