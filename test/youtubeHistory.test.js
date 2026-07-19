import assert from 'node:assert/strict';
import test from 'node:test';
import { createYouTubeHistoryService } from '../electron/catalog/youtubeHistory.js';

function historyInfo(events) {
  return {
    async addToWatchHistory() { events.push('start'); },
    async updateWatchTime(seconds) { events.push(`update:${seconds}`); }
  };
}

test('YouTube history sends guest tracking URLs through browser authentication', async () => {
  const stats = [];
  const service = createYouTubeHistoryService({
    refreshBrowserAuth: async () => {},
    getGuestInnertube: () => ({
      music: {
        getInfo: async () => ({
          cpn: 'nonce',
          page: [{
            playback_tracking: {
              videostats_playback_url: 'https://s.youtube.com/api/stats/playback',
              videostats_watchtime_url: 'https://s.youtube.com/api/stats/watchtime'
            }
          }]
        })
      }
    }),
    ensureSignedIn: async () => { throw new Error('OAuth should not be used'); },
    sendBrowserHistoryStat: async (url, params) => stats.push({ url, params })
  });

  await service.start({ sessionId: 'one', videoId: 'video' });
  await service.update({ sessionId: 'one', videoId: 'video', watchTime: 31 });

  assert.equal(stats[0].url, 'https://s.youtube.com/api/stats/playback');
  assert.deepEqual(stats[0].params, { cpn: 'nonce', fmt: 251, rtn: 0, rt: 0 });
  assert.equal(stats[1].url, 'https://s.youtube.com/api/stats/watchtime');
  assert.equal(stats[1].params.st, '31.000');
});

test('YouTube history falls back to OAuth when guest tracking is unavailable', async () => {
  const events = [];
  const service = createYouTubeHistoryService({
    refreshBrowserAuth: async () => {},
    getGuestInnertube: async () => { throw new Error('Guest player unavailable'); },
    ensureSignedIn: async () => ({
      music: { getInfo: async () => historyInfo(events) }
    }),
    sendBrowserHistoryStat: async () => {}
  });

  await service.update({ sessionId: 'two', videoId: 'video', watchTime: 31 });

  assert.deepEqual(events, ['start', 'update:31']);
});
