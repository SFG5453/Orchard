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

await import('../src/runtime/index.js');
const invoke = globalThis.OrchardYouTubeProvider.invoke;

const session = {
  cookie: 'SAPISID=secret',
  clientVersion: '1.test',
  visitorData: 'visitor',
  dataSyncId: 'channel-id',
  accountIndex: 2
};

function playlistTile(browseId, title) {
  return {
    musicTwoRowItemRenderer: {
      title: { runs: [{ text: title }] },
      subtitle: { runs: [{ text: 'Playlist' }] },
      navigationEndpoint: {
        browseEndpoint: {
          browseId,
          browseEndpointContextSupportedConfigs: {
            browseEndpointContextMusicConfig: {
              pageType: 'MUSIC_PAGE_TYPE_PLAYLIST'
            }
          }
        }
      }
    }
  };
}

function trackRow(videoId, title) {
  return {
    musicResponsiveListItemRenderer: {
      flexColumns: [{
        musicResponsiveListItemFlexColumnRenderer: {
          text: { runs: [{ text: title }] }
        }
      }],
      playlistItemData: { videoId }
    }
  };
}

function withSetVideoId(row, setVideoId) {
  row.musicResponsiveListItemRenderer.playlistItemData.playlistSetVideoId = setVideoId;
  return row;
}

function continuationItem(token) {
  return {
    continuationItemRenderer: {
      continuationEndpoint: { continuationCommand: { token } }
    }
  };
}

async function withFetch(handler, operation) {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = handler;
  try {
    return await operation();
  } finally {
    globalThis.fetch = originalFetch;
  }
}

function libraryPage(items, continuation = '') {
  return { continuationContents: { musicShelfContinuation: {
    contents: items,
    ...(continuation ? { continuations: [{ nextContinuationData: { continuation } }] } : {})
  } } };
}

function libraryLanding() {
  return { chips: ['Albums', 'Songs'].map(title => ({ chipCloudChipRenderer: {
    text: { simpleText: title },
    navigationEndpoint: { browseEndpoint: { browseId: `saved-${title.toLowerCase()}` } }
  } })) };
}

test('catalog library loads saved categories and subscriptions independently of home', async () => {
  const album = playlistTile('MPRE-album', 'Saved album');
  album.musicTwoRowItemRenderer.subtitle.runs[0].text = 'Album';
  album.musicTwoRowItemRenderer.navigationEndpoint.browseEndpoint
    .browseEndpointContextSupportedConfigs.browseEndpointContextMusicConfig.pageType = 'MUSIC_PAGE_TYPE_ALBUM';
  const playlist = playlistTile('VL-saved', 'Saved playlist');
  const pages = {
    FEmusic_library_landing: libraryLanding(),
    FEchannels: { contents: [
      { channelRenderer: { channelId: 'UC-artist', title: { simpleText: 'Subscribed artist' },
        badges: [{ metadataBadgeRenderer: { label: 'Official Artist Channel' } }] } },
      { channelRenderer: { channelId: 'UC-other', title: { simpleText: 'Ordinary channel' } } }
    ] },
    'saved-albums': libraryPage([album]),
    'saved-songs': libraryPage([trackRow('song-1', 'First saved song')], 'songs-next'),
    'songs-next': libraryPage([trackRow('song-1', 'First saved song'), trackRow('song-2', 'Second saved song')]),
    FEmusic_liked_playlists: libraryPage([playlist, playlistTile('VL-new', 'New playlist')], 'playlists-next'),
    'playlists-next': libraryPage([playlist, playlistTile('VL-second', 'Second playlist')])
  };
  const requests = [];
  const library = await withFetch(async (url, init) => {
    const body = JSON.parse(init.body);
    const key = body.continuation || body.browseId;
    requests.push(key);
    assert.ok(pages[key], `Unexpected request: ${key}`);
    assert.equal(new URL(url).hostname, key === 'FEchannels' ? 'www.youtube.com' : 'music.youtube.com');
    return new Response(JSON.stringify(pages[key]), { status: 200 });
  }, () => invoke('catalog.library', { session }));

  assert.deepEqual(library.sections.map(section => section.key), ['artists', 'albums', 'songs', 'playlists']);
  assert.deepEqual(library.sections.map(section => section.error), ['', '', '', '']);
  assert.deepEqual(library.sections[0].items.map(item => item.browseId), ['UC-artist']);
  assert.deepEqual(library.sections[1].items.map(item => [item.browseId, item.type]), [['MPRE-album', 'album']]);
  assert.deepEqual(library.sections[2].items.map(item => item.id), ['song-1', 'song-2']);
  assert.deepEqual(library.sections[3].items.map(item => item.browseId), ['VL-saved', 'VL-second']);
  assert.equal(requests.filter(key => key === 'FEmusic_library_landing').length, 1);
  assert.equal(requests.includes('FEmusic_home'), false);
});

test('catalog library retains available categories when the library landing fails', async () => {
  const library = await withFetch(async (_url, init) => {
    const { browseId } = JSON.parse(init.body);
    if (browseId === 'FEmusic_library_landing')
      return new Response(JSON.stringify({ error: { message: 'Library temporarily unavailable' } }), { status: 503 });
    return new Response(JSON.stringify(browseId === 'FEmusic_liked_playlists'
      ? libraryPage([playlistTile('VL-saved', 'Saved playlist')]) : {}), { status: 200 });
  }, () => invoke('catalog.library', { session }));

  assert.equal(library.sections[0].error, '');
  assert.equal(library.sections[1].error, 'Library temporarily unavailable');
  assert.equal(library.sections[2].error, 'Library temporarily unavailable');
  assert.equal(library.sections[3].items.length, 1);
});

test('catalog library preserves browser category errors without an OAuth fallback', async () => {
  const library = await withFetch(async (_url, init) => {
    const { browseId } = JSON.parse(init.body);
    if (browseId === 'saved-albums')
      return new Response(JSON.stringify({ error: { message: 'Albums temporarily unavailable' } }), { status: 503 });
    return new Response(JSON.stringify(browseId === 'FEmusic_library_landing' ? libraryLanding() : {}), { status: 200 });
  }, () => invoke('catalog.library', { session }));

  assert.equal(library.sections[1].error, 'Albums temporarily unavailable');
  assert.deepEqual(library.sections[1].items, []);
  assert.equal(library.sections[2].error, '');
});

test('catalog library distinguishes an empty library from a failed request', async () => {
  const empty = await withFetch(async () => new Response('{}', { status: 200 }),
    () => invoke('catalog.library', { session }));
  assert.ok(empty.sections.every(section => section.items.length === 0 && section.error === ''));

  await withFetch(async () => new Response(JSON.stringify({ error: { message: 'Please sign in again' } }), { status: 401 }),
    () => assert.rejects(invoke('catalog.library', { session }), /Please sign in again/));
});

test('catalog home fetches and normalizes every continuation', async () => {
  const requests = [];
  const pages = {
    FEmusic_library_landing: {
      contents: {
        singleColumnBrowseResultsRenderer: {
          tabs: [{ tabRenderer: { content: { sectionListRenderer: {
            contents: [{ musicCarouselShelfRenderer: {
              header: { musicCarouselShelfBasicHeaderRenderer: { title: { runs: [{ text: 'Library' }] } } },
              contents: [playlistTile('VL-library', 'Saved mix')]
            } }]
          } } } }]
        }
      }
    },
    FEmusic_liked_playlists: {
      contents: {
        singleColumnBrowseResultsRenderer: {
          tabs: [{ tabRenderer: { content: { sectionListRenderer: {
            contents: [{ gridRenderer: {
              items: [playlistTile('VL-library', 'Saved mix')]
            } }]
          } } } }]
        }
      }
    },
    FEmusic_home: {
      contents: {
        singleColumnBrowseResultsRenderer: {
          tabs: [{ tabRenderer: { content: { sectionListRenderer: {
            contents: [
              { musicCarouselShelfRenderer: {
                header: { musicCarouselShelfBasicHeaderRenderer: { title: { runs: [{ text: 'Quick picks' }] } } },
                contents: [playlistTile('VL-first', 'First mix')]
              } },
              { musicCarouselShelfRenderer: {
                header: { musicCarouselShelfBasicHeaderRenderer: { title: { runs: [{ text: 'Trending community playlists' }] } } },
                contents: [playlistTile('VL-trending', 'Trending mix')]
              } }
            ],
            continuations: [{ nextContinuationData: { continuation: 'home-next' } }]
          } } } }]
        }
      }
    },
    'home-next': {
      continuationContents: {
        sectionListContinuation: {
          contents: [{ musicCarouselShelfRenderer: {
              header: { musicCarouselShelfBasicHeaderRenderer: { title: { runs: [{ text: 'New releases' }] } } },
            contents: [playlistTile('VL-second', 'Second mix')]
          } }]
        }
      }
    }
  };

  const home = await withFetch(async (_url, init) => {
    const body = JSON.parse(init.body);
    const key = body.continuation || body.browseId;
    requests.push({ key, headers: init.headers, context: body.context });
    return new Response(JSON.stringify(pages[key]), { status: 200 });
  }, () => invoke('catalog.home', { session }));

  assert.deepEqual(home.sections.map((section) => section.title), [
    'Library',
    'Quick picks',
    'Trending community playlists',
    'New releases'
  ]);
  assert.deepEqual(home.sections.flatMap((section) => section.items.map((item) => item.browseId)), [
    'VL-library',
    'VL-first',
    'VL-trending',
    'VL-second'
  ]);
  assert.deepEqual(requests.map((request) => request.key), [
    'FEmusic_library_landing',
    'FEmusic_liked_playlists',
    'FEmusic_home',
    'FEchannels',
    'home-next'
  ]);
  assert.match(requests[0].headers.Authorization, /^SAPISIDHASH /);
  assert.equal(requests[0].headers['X-Goog-AuthUser'], '2');
  assert.equal(requests[0].headers['X-Goog-PageId'], 'channel-id');
  assert.equal(requests[0].headers['X-Goog-Visitor-Id'], 'visitor');
  assert.equal(requests[0].context.client.visitorData, 'visitor');
});

test('catalog home rejects guest responses even when HTTP succeeds', async () => {
  for (const responseContext of [
    { mainAppWebResponseContext: { loggedOut: true } },
    { serviceTrackingParams: [{ params: [{ key: 'logged_in', value: '0' }] }] }
  ]) {
    await assert.rejects(() => withFetch(async () => new Response(
      JSON.stringify({ responseContext }), { status: 200 }
    ), () => invoke('catalog.home', { session })), /did not accept the saved login session/);
  }
});

test('catalog search requests a real YouTube Music filter and returns videos', async () => {
  const video = trackRow('video-one', 'Sunset Drive (Official Video)');
  const renderer = video.musicResponsiveListItemRenderer;
  renderer.flexColumns[0].musicResponsiveListItemFlexColumnRenderer.text.runs[0].navigationEndpoint = {
    watchEndpoint: {
      videoId: 'video-one',
      watchEndpointMusicSupportedConfigs: {
        watchEndpointMusicConfig: { musicVideoType: 'MUSIC_VIDEO_TYPE_OMV' }
      }
    }
  };
  renderer.flexColumns.push({
    musicResponsiveListItemFlexColumnRenderer: {
      text: { runs: [{ text: 'Orchard Avenue', navigationEndpoint: {
        browseEndpoint: { browseId: 'UC-orchard-avenue' }
      } }] }
    }
  });
  renderer.fixedColumns = [{ musicResponsiveListItemFixedColumnRenderer: {
    text: { runs: [{ text: '3:42' }] }
  } }];

  const requests = [];
  const response = {
    contents: {
      tabbedSearchResultsRenderer: {
        tabs: [{ tabRenderer: { content: { sectionListRenderer: { contents: [
          { musicShelfRenderer: {
            title: { runs: [{ text: 'Videos' }] },
            contents: [video]
          } }
        ] } } } }]
      }
    }
  };

  const result = await withFetch(async (url, init) => {
    requests.push({ url, body: JSON.parse(init.body), headers: init.headers });
    return new Response(JSON.stringify(response), { status: 200 });
  }, () => invoke('catalog.search', {
    session,
    query: 'sunset drive',
    filter: 'videos'
  }));

  assert.equal(requests.length, 1);
  assert.match(requests[0].url, /youtubei\/v1\/search/);
  assert.equal(requests[0].body.query, 'sunset drive');
  assert.equal(requests[0].body.params, 'EgWKAQIQAQ%3D%3D');
  assert.equal(requests[0].body.context.client.clientName, 'WEB_REMIX');
  assert.match(requests[0].headers.Authorization, /^SAPISIDHASH /);
  assert.deepEqual(result.sections.map((section) => section.key), ['videos']);
  assert.deepEqual(result.sections[0].items.map((item) => [item.id, item.type, item.duration]), [
    ['video-one', 'video', '3:42']
  ]);
});

test('all catalog search fills categories omitted by broad search', async () => {
  const requested = [];
  const result = await withFetch(async (_url, init) => {
    const body = JSON.parse(init.body);
    requested.push(body.params);
    const contents = body.params === 'EgWKAQIoAQ%3D%3D'
      ? [{ musicShelfRenderer: { title: { runs: [{ text: 'Community playlists' }] },
          contents: [playlistTile('VL-sunset', 'Sunset mix')] } }]
      : [];
    return new Response(JSON.stringify({ contents: { tabbedSearchResultsRenderer: {
      tabs: [{ tabRenderer: { content: { sectionListRenderer: { contents } } } }]
    } } }));
  }, () => invoke('catalog.search', { session, query: 'sunset', filter: 'all' }));
  assert.ok(requested.includes('EgWKAQIQAQ%3D%3D'));
  assert.ok(requested.includes('EgWKAQIYAQ%3D%3D'));
  assert.ok(requested.includes('EgWKAQIoAQ%3D%3D'));
  assert.equal(result.sections.find(section => section.key === 'playlists').items[0].browseId, 'VL-sunset');
});

test('catalog playlists paginate and remove YouTube duplicates', async () => {
  const requests = [];
  const pages = {
    FEmusic_liked_playlists: {
      contents: {
        singleColumnBrowseResultsRenderer: {
          tabs: [{ tabRenderer: { content: { sectionListRenderer: { contents: [
            { gridRenderer: {
              items: [playlistTile('VL-first', 'First')],
              continuations: [{ nextContinuationData: { continuation: 'playlists-next' } }]
            } }
          ] } } } }]
        }
      }
    },
    'playlists-next': {
      continuationContents: {
        gridContinuation: {
          items: [playlistTile('VL-first', 'First'), playlistTile('VL-second', 'Second')]
        }
      }
    }
  };

  const playlists = await withFetch(async (_url, init) => {
    const body = JSON.parse(init.body);
    const key = body.continuation || body.browseId;
    requests.push(key);
    return new Response(JSON.stringify(pages[key]), { status: 200 });
  }, () => invoke('catalog.playlists', { session }));

  assert.deepEqual(playlists.map((playlist) => playlist.browseId), ['VL-first', 'VL-second']);
  assert.deepEqual(requests, ['FEmusic_liked_playlists', 'playlists-next']);
});

test('catalog playlist fetches metadata, tracks, and the next track page', async () => {
  const requests = [];
  const firstPage = {
    header: {
      musicDetailHeaderRenderer: {
        title: { runs: [{ text: 'Road trip' }] },
        subtitle: { runs: [{ text: 'A very loud playlist' }] }
      }
    },
    contents: {
      twoColumnBrowseResultsRenderer: {
        secondaryContents: {
          sectionListRenderer: {
            contents: [{ musicPlaylistShelfRenderer: {
              contents: [trackRow('song-one', 'One'), continuationItem('tracks-next')]
            } }]
          }
        }
      }
    }
  };
  const nextPage = {
    continuationContents: {
      musicPlaylistShelfContinuation: {
        contents: [trackRow('song-two', 'Two')]
      }
    }
  };

  await withFetch(async (_url, init) => {
    const body = JSON.parse(init.body);
    requests.push(body.continuation || body.browseId);
    return new Response(JSON.stringify(body.continuation ? nextPage : firstPage), { status: 200 });
  }, async () => {
    const playlist = await invoke('catalog.playlist', { session, browseId: 'PL-road' });
    assert.equal(playlist.browseId, 'VLPL-road');
    assert.equal(playlist.title, 'Road trip');
    assert.deepEqual(playlist.tracks.map((track) => track.id), ['song-one']);
    assert.equal(playlist.continuation, 'tracks-next');
    assert.equal(playlist.hasMoreTracks, true);
    assert.equal(playlist.editable, false);

    const more = await invoke('catalog.playlist.more', {
      session,
      continuation: playlist.continuation,
      startIndex: playlist.tracks.length
    });
    assert.deepEqual(more.tracks.map((track) => [track.id, track.index]), [['song-two', '2']]);
    assert.equal(more.hasMoreTracks, false);
  });

  assert.deepEqual(requests, ['VLPL-road', 'tracks-next']);
});

test('catalog playlist prefetches the next page for the same account only', async () => {
  const shelf = (contents) => ({ continuationContents: { musicPlaylistShelfContinuation: { contents } } });
  const pages = {
    'VLPL-long': { contents: { twoColumnBrowseResultsRenderer: { secondaryContents: { sectionListRenderer: {
      contents: [{ musicPlaylistShelfRenderer: { contents: [trackRow('a', 'A'), continuationItem('page-2')] } }]
    } } } } },
    'page-2': shelf([trackRow('b', 'B'), continuationItem('page-3')]),
    'page-3': shelf([trackRow('c', 'C')])
  };
  const requests = [];

  await withFetch(async (_url, init) => {
    const body = JSON.parse(init.body);
    const key = body.continuation || body.browseId;
    requests.push([key, init.headers.Cookie.includes('other') ? 'other' : 'main']);
    return new Response(JSON.stringify(pages[key]), { status: 200 });
  }, async () => {
    const playlist = await invoke('catalog.playlist', { session, browseId: 'PL-long' });
    assert.deepEqual(requests.map(([key]) => key), ['VLPL-long', 'page-2']);

    const second = await invoke('catalog.playlist.more', { session, continuation: 'page-2', startIndex: 1 });
    assert.deepEqual(second.tracks.map((track) => track.id), ['b']);
    assert.deepEqual(requests.map(([key]) => key), ['VLPL-long', 'page-2', 'page-3']);

    const other = { ...session, cookie: 'SAPISID=other' };
    const third = await invoke('catalog.playlist.more', { session: other, continuation: 'page-3', startIndex: 2 });
    assert.deepEqual(third.tracks.map((track) => track.id), ['c']);
    assert.equal(playlist.continuation, 'page-2');
  });

  assert.deepEqual(requests, [
    ['VLPL-long', 'main'], ['page-2', 'main'], ['page-3', 'main'], ['page-3', 'other']
  ]);
});

test('catalog album fetches the Music browse page and normalizes its tracklist', async () => {
  let request;
  const albumPage = {
    contents: {
      twoColumnBrowseResultsRenderer: {
        tabs: [{
          tabRenderer: {
            content: {
              sectionListRenderer: {
                contents: [{
                  musicResponsiveHeaderRenderer: {
                    title: { runs: [{ text: 'Night Drive' }] },
                    subtitle: { runs: [{ text: 'Album' }, { text: ' • ' }, { text: '2024' }] },
                    straplineTextOne: {
                      runs: [{ text: 'Orchard Arcade', navigationEndpoint: { browseEndpoint: { browseId: 'UC-artist' } } }]
                    }
                  }
                }]
              }
            }
          }
        }],
        secondaryContents: {
          sectionListRenderer: { contents: [{ musicShelfRenderer: {
            contents: [trackRow('night-one', 'Night One'), trackRow('night-two', 'Night Two')]
          } }] }
        }
      }
    },
    microformat: {
      microformatDataRenderer: { title: 'Night Drive - Album by Orchard Arcade' }
    }
  };

  const album = await withFetch(async (_url, init) => {
    request = JSON.parse(init.body);
    return new Response(JSON.stringify(albumPage), { status: 200 });
  }, () => invoke('catalog.album', { session, browseId: 'MPREb_night-drive' }));

  assert.equal(request.browseId, 'MPREb_night-drive');
  assert.equal(request.client, 'YTMUSIC');
  assert.equal(album.kind, 'album');
  assert.equal(album.title, 'Night Drive');
  assert.equal(album.artist, 'Orchard Arcade');
  assert.deepEqual(album.tracks.map((track) => track.id), ['night-one', 'night-two']);
  assert.equal(album.tracks[0].artist, 'Orchard Arcade');
});

test('catalog operations reject missing auth and playlist identities', async () => {
  await assert.rejects(invoke('catalog.home', { session: {} }), /Authenticated YouTube cookies/);
  await assert.rejects(
    invoke('catalog.search', { session: {}, query: 'sunset drive' }),
    /Authenticated YouTube cookies/
  );
  await assert.rejects(invoke('catalog.album', { session, browseId: '' }), /browse ID/);
  await assert.rejects(invoke('catalog.playlist', { session, browseId: '' }), /browse ID/);
  await assert.rejects(invoke('catalog.playlist.more', { session, continuation: '' }), /continuation/);
});

test('catalog albums accept public responses regardless of login markers', async () => {
  for (const responseContext of [
    { mainAppWebResponseContext: { loggedOut: true } },
    { serviceTrackingParams: [{ params: [{ key: 'logged_in', value: '0' }] }] }
  ]) {
    const album = await withFetch(async () => new Response(JSON.stringify({
      responseContext,
      header: { musicDetailHeaderRenderer: { title: { runs: [{ text: 'Public album' }] } } },
      contents: { twoColumnBrowseResultsRenderer: { secondaryContents: { sectionListRenderer: { contents: [
        { musicShelfRenderer: { contents: [trackRow('public-song', 'Public song')] } }
      ] } } } }
    })), () => invoke('catalog.album', { session, browseId: 'MPREb_public' }));
    assert.equal(album.title, 'Public album');
    assert.deepEqual(album.tracks.map((track) => track.id), ['public-song']);
  }
});

test('catalog albums retry rejected sessions without account credentials', async () => {
  const requests = [];
  const album = await withFetch(async (_url, init) => {
    requests.push(init);
    if (requests.length === 1) {
      return new Response(JSON.stringify({ error: { message: 'Unauthorized' } }), { status: 401 });
    }
    return new Response(JSON.stringify({
      responseContext: { mainAppWebResponseContext: { loggedOut: true } },
      contents: { twoColumnBrowseResultsRenderer: { secondaryContents: { sectionListRenderer: { contents: [
        { musicShelfRenderer: { contents: [trackRow('guest-song', 'Guest song')] } }
      ] } } } }
    }));
  }, () => invoke('catalog.album', { session, browseId: 'MPREb_public' }));
  assert.equal(requests.length, 2);
  assert.ok(requests[0].headers.Authorization);
  for (const key of ['Authorization', 'Cookie', 'X-Goog-AuthUser', 'X-Goog-PageId', 'X-Youtube-Bootstrap-Logged-In']) {
    assert.equal(requests[1].headers[key], undefined);
  }
  const body = JSON.parse(requests[1].body);
  assert.equal(body.context.user, undefined);
  assert.equal(body.context.client.visitorData, undefined);
  assert.equal(body.browseId, 'MPREb_public');
  assert.deepEqual(album.tracks.map((track) => track.id), ['guest-song']);
});

test('catalog albums reject empty guest responses instead of showing a blank album', async () => {
  await assert.rejects(() => withFetch(async () => new Response(JSON.stringify({
    responseContext: { mainAppWebResponseContext: { loggedOut: true } }
  })), () => invoke('catalog.album', { session, browseId: 'MPREb_missing' })), /did not return the requested album/);
});

test('album tracks inherit the cover without replacing track-specific artwork', async () => {
  const ownArtwork = trackRow('own', 'Own artwork');
  ownArtwork.musicResponsiveListItemRenderer.thumbnail = {
    musicThumbnailRenderer: { thumbnail: { thumbnails: [{ url: 'https://example.com/track.jpg', width: 300 }] } }
  };
  const album = await withFetch(async () => new Response(JSON.stringify({
    microformat: { microformatDataRenderer: { thumbnail: { thumbnails: [{ url: 'https://example.com/album.jpg', width: 600 }] } } },
    contents: { twoColumnBrowseResultsRenderer: { secondaryContents: { sectionListRenderer: { contents: [
      { musicShelfRenderer: { contents: [trackRow('inherited', 'Album artwork'), ownArtwork] } }
    ] } } } }
  })), () => invoke('catalog.album', { session, browseId: 'MPREb_cover' }));
  assert.equal(album.thumbnail, 'https://example.com/album.jpg');
  assert.deepEqual(album.tracks.map((track) => track.thumbnail), [
    'https://example.com/album.jpg', 'https://example.com/track.jpg'
  ]);
});

function artistPage(sections = []) {
  return {
    header: { musicImmersiveHeaderRenderer: { title: { runs: [{ text: 'Example artist' }] } } },
    contents: { singleColumnBrowseResultsRenderer: { tabs: [{ tabRenderer: {
      content: { sectionListRenderer: { contents: sections } }
    } }] } }
  };
}

test('artist runtime exposes normalized artist pages and retries as a guest', async () => {
  const requests = [];
  const result = await withFetch(async (_url, init) => {
    requests.push(init);
    if (requests.length === 1) return new Response('{}', { status: 401 });
    return new Response(JSON.stringify(artistPage()));
  }, () => invoke('catalog.artist', { session, browseId: 'UC-example' }));
  assert.equal(result.kind, 'artist');
  assert.equal(result.title, 'Example artist');
  assert.equal(result.browseId, 'UC-example');
  assert.deepEqual(result.tracks, []);
  assert.equal(requests.length, 2);
  assert.ok(requests[0].headers.Authorization);
  assert.equal(requests[1].headers.Authorization, undefined);
  assert.equal(requests[1].headers.Cookie, undefined);
});

test('artist runtime hydrates popular song album metadata through release browsing', async () => {
  const song = trackRow('popular-song', 'Popular song');
  song.musicResponsiveListItemRenderer.flexColumns.push({
    musicResponsiveListItemFlexColumnRenderer: { text: { runs: [{
      text: 'Example artist', navigationEndpoint: { browseEndpoint: { browseId: 'UC-example' } }
    }] } }
  });
  const release = playlistTile('MPREb_example', 'Example album');
  release.musicTwoRowItemRenderer.subtitle.runs = [{ text: 'Album' }];
  release.musicTwoRowItemRenderer.navigationEndpoint.browseEndpoint
    .browseEndpointContextSupportedConfigs.browseEndpointContextMusicConfig.pageType = 'MUSIC_PAGE_TYPE_ALBUM';
  const albumSong = structuredClone(song);
  albumSong.musicResponsiveListItemRenderer.fixedColumns = [{
    musicResponsiveListItemFixedColumnRenderer: { text: { runs: [{ text: '3:42' }] } }
  }];
  const result = await withFetch(async (url, init) => {
    // A broken search service must not swallow metadata from a working album browse.
    if (url.includes('/search?')) return new Response('{}', { status: 503 });
    const request = JSON.parse(init.body);
    return new Response(JSON.stringify(request.browseId === 'UC-example' ? artistPage([
      { musicShelfRenderer: { title: { runs: [{ text: 'Top songs' }] }, contents: [song] } },
      { musicCarouselShelfRenderer: { title: { runs: [{ text: 'Albums' }] }, contents: [release] } }
    ]) : {
      header: { musicDetailHeaderRenderer: { title: { runs: [{ text: 'Example album' }] } } },
      contents: { twoColumnBrowseResultsRenderer: { secondaryContents: { sectionListRenderer: {
        contents: [{ musicShelfRenderer: { contents: [albumSong] } }]
      } } } }
    }));
  }, () => invoke('catalog.artist', { session, browseId: 'UC-example' }));
  assert.equal(result.tracks.length, 1);
  assert.equal(result.tracks[0].albumId, 'MPREb_example');
  assert.equal(result.tracks[0].album, 'Example album');
  assert.equal(result.tracks[0].duration, '3:42');
  assert.equal(result.tracks[0].durationSeconds, 222);
  assert.equal(result.sections[0].items[0].browseId, 'MPREb_example');
  assert.deepEqual(result.sections[0].items[0].artistBrowseIds, ['UC-example']);
});

test('artist runtime rejects missing identities and malformed browse responses', async () => {
  await assert.rejects(invoke('catalog.artist', { session }), /browse ID/);
  await assert.rejects(() => withFetch(async () => new Response('{}'),
    () => invoke('catalog.artist', { session, browseId: 'UC-missing' })), /requested artist/);
});

test('artist runtime searches durations for all popular songs with authenticated and guest sessions', async () => {
  const titles = ['One', 'Two', 'Three', 'Four', 'Five'];
  const durations = ['3:01', '2:42', '4:13', '3:24', '2:55'];
  const songs = titles.map((title, index) => {
    const song = trackRow(`popular-${index}`, title);
    song.musicResponsiveListItemRenderer.flexColumns.push({
      musicResponsiveListItemFlexColumnRenderer: { text: { runs: [{
        text: 'Example artist', navigationEndpoint: { browseEndpoint: { browseId: 'UC-example' } }
      }] } }
    });
    return song;
  });

  for (const guest of [false, true]) {
    const searches = [];
    const result = await withFetch(async (url, init) => {
      const request = JSON.parse(init.body);
      if (guest && init.headers.Authorization) return new Response('{}', { status: 401 });
      if (!url.includes('/search?')) return new Response(JSON.stringify(artistPage([
        { musicShelfRenderer: { title: { runs: [{ text: 'Top songs' }] }, contents: songs } }
      ])));

      searches.push({ request, headers: init.headers });
      const index = titles.findIndex(title => request.query === `Example artist ${title}`);
      assert.notEqual(index, -1);
      const song = trackRow(`popular-${index}`, titles[index]);
      song.musicResponsiveListItemRenderer.flexColumns.push({
        musicResponsiveListItemFlexColumnRenderer: { text: { runs: [
          { text: 'Example artist', navigationEndpoint: { browseEndpoint: { browseId: 'UC-example' } } },
          { text: ' • ' },
          { text: 'Example album', navigationEndpoint: { browseEndpoint: { browseId: 'MPREb_example' } } },
          { text: ' • ' },
          { text: durations[index] }
        ] } }
      });
      return new Response(JSON.stringify({ contents: { tabbedSearchResultsRenderer: { tabs: [
        { tabRenderer: { content: { sectionListRenderer: { contents: [
          { musicShelfRenderer: { contents: [song] } }
        ] } } } }
      ] } } }));
    }, () => invoke('catalog.artist', { session, browseId: 'UC-example' }));

    assert.deepEqual(result.tracks.map(track => track.id), songs.map(song => song.musicResponsiveListItemRenderer.playlistItemData.videoId));
    assert.deepEqual(result.tracks.map(track => track.duration), durations);
    assert.deepEqual(result.tracks.map(track => track.durationSeconds), [181, 162, 253, 204, 175]);
    assert.ok(result.tracks.every(track => track.albumId === 'MPREb_example'));
    assert.equal(searches.length, 5);
    for (const { request, headers } of searches) {
      assert.equal(request.params, 'EgWKAQIIAQ%3D%3D');
      assert.equal(request.context.client.clientName, 'WEB_REMIX');
      assert.equal(Boolean(headers.Authorization), !guest);
      if (guest) assert.equal(headers.Cookie, undefined);
      else assert.match(headers.Cookie, /SAPISID=secret/);
      assert.equal(headers['X-Goog-AuthUser'], guest ? undefined : '2');
    }
  }
});

test('artist runtime retains Electron popular items and classifies music videos as videos', async () => {
  function mediaRow(id, musicVideoType, album = '') {
    const song = trackRow(id, id);
    const renderer = song.musicResponsiveListItemRenderer;
    renderer.flexColumns[0].musicResponsiveListItemFlexColumnRenderer.text.runs[0].navigationEndpoint = {
      watchEndpoint: { videoId: id, watchEndpointMusicSupportedConfigs: {
        watchEndpointMusicConfig: { musicVideoType }
      } }
    };
    renderer.flexColumns.push({ musicResponsiveListItemFlexColumnRenderer: { text: { runs: [
      { text: 'Example artist', navigationEndpoint: { browseEndpoint: { browseId: 'UC-example' } } },
      ...(album ? [{ text: album, navigationEndpoint: { browseEndpoint: { browseId: album } } }] : [])
    ] } } });
    renderer.fixedColumns = [{ musicResponsiveListItemFixedColumnRenderer: {
      text: { runs: [{ text: '3:42' }] }
    } }];
    return song;
  }
  const videos = [mediaRow('official-video', 'MUSIC_VIDEO_TYPE_OMV'), mediaRow('uploaded-video', 'MUSIC_VIDEO_TYPE_UGC')];
  const songs = Array.from({ length: 5 }, (_, index) =>
    mediaRow(`song-${index}`, 'MUSIC_VIDEO_TYPE_ATV', `MPREb_album-${index}`));
  const result = await withFetch(async () => new Response(JSON.stringify(artistPage([
    { musicShelfRenderer: { title: { runs: [{ text: 'Top songs' }] }, contents: [...videos, ...songs] } },
    { musicShelfRenderer: { title: { runs: [{ text: 'Videos' }] }, contents: videos } }
  ]))), () => invoke('catalog.artist', { session, browseId: 'UC-example' }));

  assert.deepEqual(result.tracks.map(track => track.id), ['official-video', 'uploaded-video', 'song-0', 'song-1', 'song-2']);
  assert.deepEqual(result.tracks.map(track => track.index), ['1', '2', '3', '4', '5']);
  assert.deepEqual(result.tracks.map(track => track.type), ['video', 'video', 'track', 'track', 'track']);
  assert.deepEqual(result.sections[0].items.map(item => [item.id, item.type]), [
    ['official-video', 'video'], ['uploaded-video', 'video']
  ]);
});

for (const editable of [false, true]) {
  test(`catalog playlist reads tab metadata despite generic page header (editable: ${editable})`, async () => {
    const header = {
      title: { runs: [{ text: 'chill' }] },
      straplineTextOne: { runs: [{ text: 'Playlist creator' }] },
      description: { musicDescriptionShelfRenderer: {
        description: { runs: [{ text: 'A quiet evening with my favorites.' }] }
      } }
    };
    const response = {
      header: { musicHeaderRenderer: { title: { runs: [{ text: 'Playlist' }] } } },
      contents: { twoColumnBrowseResultsRenderer: {
        tabs: [{ tabRenderer: { content: { sectionListRenderer: { contents: [
          { musicShelfRenderer: { contents: [] } },
          editable
            ? { musicEditablePlaylistDetailHeaderRenderer: { header: { musicResponsiveHeaderRenderer: header } } }
            : { musicResponsiveHeaderRenderer: header }
        ] } } } }],
        secondaryContents: { sectionListRenderer: { contents: [
          { musicPlaylistShelfRenderer: { contents: [withSetVideoId(trackRow('song-one', 'One'), 'SET-one')] } }
        ] } }
      } },
      microformat: { microformatDataRenderer: { title: 'chill', description: 'Listen to chill on YouTube Music' } }
    };
    const playlist = await withFetch(async () => new Response(JSON.stringify(response), { status: 200 }),
      () => invoke('catalog.playlist', { session, browseId: 'PL-chill', diagnostics: true }));
    assert.equal(playlist.author, 'Playlist creator');
    assert.equal(playlist.description, 'A quiet evening with my favorites.');
    const diagnostic = JSON.stringify(playlist.metadataDiagnostics);
    assert.match(diagnostic, /straplineTextOne/);
    assert.match(diagnostic, /musicDescriptionShelfRenderer/);
    assert.ok(!diagnostic.includes('Playlist creator'));
    assert.ok(!diagnostic.includes('A quiet evening'));
    assert.ok(!diagnostic.includes(session.cookie));
    assert.equal(playlist.title, 'chill');
    assert.equal(playlist.tracks.length, 1);
    assert.equal(playlist.editable, editable);
    assert.equal(playlist.tracks[0].setVideoId, 'SET-one');
  });
}

for (const [editDescription, expected] of [
  ['Music for slow evenings.', 'Music for slow evenings.'],
  ['Description', ''],
  ['  description  ', ''],
  ['Description of a perfect evening.', 'Description of a perfect evening.'],
  ['', ''],
  ['Listen to chill on YouTube Music', ''],
  ['Playlist • Example creator', '']
]) {
  test(`catalog private playlist reads facepile creator and editor description: ${editDescription}`, async () => {
    // Structure from the private-playlist diagnostic; all text here is synthetic.
    const response = {
      contents: { twoColumnBrowseResultsRenderer: {
        tabs: [{ tabRenderer: { content: { sectionListRenderer: { contents: [{
          musicEditablePlaylistDetailHeaderRenderer: {
            editHeader: { musicPlaylistEditHeaderRenderer: {
              editDescription: { runs: [{ text: editDescription }] },
              privacy: 'PRIVATE'
            } },
            header: { musicResponsiveHeaderRenderer: {
              title: { runs: [{ text: 'chill' }] },
              subtitle: { runs: [{ text: 'Playlist' }, { text: ' • ' }, { text: 'Private' }] },
              facepile: { avatarStackViewModel: { text: { content: 'Example creator' } } }
            } }
          }
        }] } } } }],
        secondaryContents: { sectionListRenderer: { contents: [{
          musicPlaylistShelfRenderer: { contents: [trackRow('song-one', 'One')] }
        }] } }
      } },
      microformat: { microformatDataRenderer: { title: 'chill', description: 'Listen to chill on YouTube Music' } }
    };
    const playlist = await withFetch(async () => new Response(JSON.stringify(response), { status: 200 }),
      () => invoke('catalog.playlist', { session, browseId: 'PL-private' }));
    assert.equal(playlist.author, 'Example creator');
    assert.equal(playlist.description, expected);
    assert.equal(playlist.tracks.length, 1);
    assert.equal(playlist.metadataDiagnostics, undefined);
  });
}

test('catalog browse keeps shelves and appends grid continuations to the last one', async () => {
  const requests = [];
  const pages = {
    'FEmusic_moods_and_genres_category:mood': {
      header: { musicHeaderRenderer: { title: { runs: [{ text: 'Chill' }] } } },
      contents: {
        singleColumnBrowseResultsRenderer: {
          tabs: [{ tabRenderer: { content: { sectionListRenderer: { contents: [
            { gridRenderer: {
              header: { gridHeaderRenderer: { title: { runs: [{ text: 'Playlists' }] } } },
              items: [playlistTile('VL-first', 'First')],
              continuations: [{ nextContinuationData: { continuation: 'grid-next' } }]
            } }
          ] } } } }]
        }
      }
    },
    'grid-next': { continuationContents: { gridContinuation: { items: [playlistTile('VL-second', 'Second')] } } }
  };
  const page = await withFetch(async (_url, init) => {
    const body = JSON.parse(init.body);
    const key = body.continuation || `${body.browseId}:${body.params}`;
    requests.push(key);
    return new Response(JSON.stringify(pages[key]), { status: 200 });
  }, () => invoke('catalog.browse', { session, browseId: 'FEmusic_moods_and_genres_category', params: 'mood' }));

  assert.equal(page.title, 'Chill');
  assert.equal(page.sections.length, 1);
  assert.deepEqual(page.sections[0].items.map((item) => item.browseId), ['VL-first', 'VL-second']);
  assert.deepEqual(requests, ['FEmusic_moods_and_genres_category:mood', 'grid-next']);
});

test('catalog track reads every credited artist from the queue row', async () => {
  const run = (text, browseId) => ({ text, navigationEndpoint: { browseEndpoint: { browseId,
    browseEndpointContextSupportedConfigs: { browseEndpointContextMusicConfig: { pageType: 'MUSIC_PAGE_TYPE_ARTIST' } } } } });
  const next = { contents: { singleColumnMusicWatchNextResultsRenderer: { playlist: { playlistPanelRenderer: { contents: [
    { playlistPanelVideoRenderer: { videoId: 'otherotherx', title: { runs: [{ text: 'Other' }] } } },
    { playlistPanelVideoRenderer: {
      videoId: 'abcdefghijk',
      title: { runs: [{ text: 'Duet' }] },
      longBylineText: { runs: [run('First', 'UCfirst'), { text: ' & ' }, run('Second', 'UCsecond'), { text: ' • 3:10' }] },
      lengthText: { runs: [{ text: '3:10' }] }
    } }
  ] } } } } };
  const track = await withFetch(async (url) => {
    assert.match(url, /\/youtubei\/v1\/next/);
    return new Response(JSON.stringify(next), { status: 200 });
  }, () => invoke('catalog.track', { session, videoId: 'abcdefghijk' }));
  assert.equal(track.id, 'abcdefghijk');
  assert.deepEqual(track.artists, ['First', 'Second']);
  assert.deepEqual(track.artistBrowseIds, ['UCfirst', 'UCsecond']);
  assert.equal(track.durationSeconds, 190);
});
