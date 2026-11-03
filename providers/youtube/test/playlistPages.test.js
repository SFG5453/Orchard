/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { parsePlaylistToken, playlistToken } from '../src/runtime/playlistPages.js';

await import('../src/runtime/index.js');
const invoke = globalThis.OrchardYouTubeProvider.invoke;

const session = { cookie: 'SAPISID=secret', clientVersion: '1.test' };
// A real WEB_REMIX continuation for the second page of a 1,035 track playlist.
const realToken = '4qmFsgKHARIkVkxQTDh3d2JrenFLMnNkVTlUaHdvbDFKZEtBMGFYRmtNdnNVGjplaDVRVkRwRFIxRnBSVVJqTkUxRVdYZE9WVXBFVG1wc1JFNXJUWGxPVkVHU0FRTUl1Z1R3QVFFJTNEmgIiUEw4d3dia3pxSzJzZFU5VGh3b2wxSmRLQTBhWEZrTXZzVQ%3D%3D';

const row = (videoId) => ({
  musicResponsiveListItemRenderer: {
    flexColumns: [{ musicResponsiveListItemFlexColumnRenderer: { text: { runs: [{ text: videoId }] } } }],
    playlistItemData: { videoId }
  }
});
const rows = (start, count) => Array.from({ length: count }, (_, index) => row(`v${start + index}`));
const more = (token) => ({ continuationItemRenderer: { continuationEndpoint: { continuationCommand: { token } } } });

function firstPage(total, next) {
  return {
    header: { musicEditablePlaylistDetailHeaderRenderer: { header: { musicResponsiveHeaderRenderer: {
      title: { runs: [{ text: 'Long' }] },
      secondSubtitle: { runs: [{ text: `${total.toLocaleString('en-US')} tracks` }] }
    } } } },
    contents: { twoColumnBrowseResultsRenderer: { secondaryContents: { sectionListRenderer: {
      contents: [{ musicPlaylistShelfRenderer: { contents: [...rows(0, 100), more(next)] } }]
    } } } }
  };
}

const shelfPage = (contents) => ({ continuationContents: { musicPlaylistShelfContinuation: { contents } } });

async function withPages(respond, operation) {
  const originalFetch = globalThis.fetch;
  const requests = [];
  globalThis.fetch = async (_url, init) => {
    const body = JSON.parse(init.body);
    requests.push(body.continuation ? parsePlaylistToken(body.continuation)?.offset ?? body.continuation : body.browseId);
    const page = respond(body);
    return page ? new Response(JSON.stringify(page), { status: 200 }) : new Response('{}', { status: 500 });
  };
  try {
    return await operation(requests);
  } finally {
    globalThis.fetch = originalFetch;
  }
}

test('playlist tokens round trip and reject unfamiliar layouts', () => {
  assert.equal(playlistToken('VLPL8wwbkzqK2sdU9Thwol1JdKA0aXFkMvsU', 100, '780605BC69C6C250'), realToken);
  assert.deepEqual(parsePlaylistToken(realToken), { browseId: 'VLPL8wwbkzqK2sdU9Thwol1JdKA0aXFkMvsU', offset: 100 });
  assert.deepEqual(parsePlaylistToken(playlistToken('VLPLx', 1100)), { browseId: 'VLPLx', offset: 1100 });
  assert.equal(parsePlaylistToken('tracks-next'), null);
  assert.equal(parsePlaylistToken(`${realToken}AA`), null);
});

test('playlist pages after the first load together up to the header total', async () => {
  const id = 'VLPL-wave';
  await withPages((body) => {
    if (!body.continuation) return firstPage(250, playlistToken(id, 100, 'set-99'));
    const offset = parsePlaylistToken(body.continuation).offset;
    const count = Math.min(100, 250 - offset);
    return shelfPage([...rows(offset, count), ...(offset + count < 250 ? [more(playlistToken(id, offset + count, 'set'))] : [])]);
  }, async (requests) => {
    const playlist = await invoke('catalog.playlist', { session, browseId: id });
    const rest = await invoke('catalog.playlist.more', { session, continuation: playlist.continuation, startIndex: 100 });
    assert.equal(rest.tracks.length, 150);
    assert.equal(rest.tracks[0].id, 'v100');
    assert.equal(rest.tracks.at(-1).id, 'v249');
    assert.equal(rest.tracks.at(-1).index, '250');
    assert.equal(rest.hasMoreTracks, false);
    assert.deepEqual(requests, [id, 100, 200]);
  });
});

test('a failed extrapolated page ends the wave at the last real continuation', async () => {
  const id = 'VLPL-flaky';
  await withPages((body) => {
    if (!body.continuation) return firstPage(400, playlistToken(id, 100, 'set-99'));
    const offset = parsePlaylistToken(body.continuation).offset;
    if (offset === 200) return null;
    return shelfPage([...rows(offset, 100), more(playlistToken(id, offset + 100, `set-${offset + 99}`))]);
  }, async () => {
    const playlist = await invoke('catalog.playlist', { session, browseId: id });
    const rest = await invoke('catalog.playlist.more', { session, continuation: playlist.continuation, startIndex: 100 });
    assert.deepEqual(rest.tracks.map((track) => track.id), rows(100, 100).map((item) => item.musicResponsiveListItemRenderer.playlistItemData.videoId));
    assert.deepEqual(parsePlaylistToken(rest.continuation), { browseId: id, offset: 200 });
  });
});
