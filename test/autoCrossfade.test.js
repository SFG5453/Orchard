import assert from 'node:assert/strict';
import test from 'node:test';

import { alignTransitionToPlayback, createAutoCrossfade } from '../src/audio/crossfade/autoCrossfade.js';

function fakeClock() {
  const timers = new Map();
  let nextId = 0;
  const window = {
    clearInterval: (id) => timers.delete(id),
    clearTimeout: (id) => timers.delete(id),
    setInterval: (callback, delay) => {
      const id = ++nextId;
      timers.set(id, { callback, delay });
      return id;
    },
    setTimeout: (callback, delay) => {
      const id = ++nextId;
      timers.set(id, { callback, delay });
      return id;
    }
  };
  return {
    window,
    runNext() {
      const [id, timer] = [...timers].sort((left, right) => left[1].delay - right[1].delay)[0] || [];
      if (!timer) return false;
      timers.delete(id);
      timer.callback();
      return true;
    }
  };
}

function audio(currentTime = 0) {
  return {
    currentTime,
    duration: 120,
    load() {},
    pause() {},
    play: async () => {},
    removeAttribute() {},
    volume: 1
  };
}

test('late smart starts keep the original handoff aligned and skip expired plans', () => {
  const transition = {
    transitionStart: 100,
    transitionEnd: 110,
    fadeSeconds: 10,
    handoffStartSeconds: 4,
    handoffDuration: 6,
    incomingCueTime: 2,
    incomingPlaybackRate: 0.95
  };

  assert.deepEqual(alignTransitionToPlayback(transition, 105), {
    ...transition,
    transitionStart: 105,
    fadeSeconds: 5,
    handoffStartSeconds: 0,
    handoffDuration: 5,
    incomingCueTime: 6.75
  });
  assert.equal(alignTransitionToPlayback(transition, 110), null);
});

test('the active track is promoted at mix dominance instead of mix start', async () => {
  const originalWindow = globalThis.window;
  const clock = fakeClock();
  globalThis.window = clock.window;
  const events = [];
  const analyzer = {
    connectElement() {},
    currentTime: () => 10,
    resetMixElement() {},
    resume: async () => {},
    scheduleCrossfade: () => ({
      startTime: 10,
      handoffStart: 10.4,
      promotionTime: 10.7,
      endTime: 11
    }),
    setVolume() {}
  };
  const crossfade = createAutoCrossfade({ analyzer, settings: { mode: 'smart' } });
  const incomingAudio = audio();

  try {
    const result = crossfade.start({
      fromAudio: audio(110),
      toAudio: incomingAudio,
      transition: {
        fadeSeconds: 1,
        handoffStartSeconds: 0.4,
        incomingCueTime: 2.25,
        incomingPlaybackRate: 1
      },
      volume: 1,
      onPromote: () => events.push('promote'),
      onComplete: () => events.push('complete')
    });
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(incomingAudio.currentTime, 2.25);
    assert.deepEqual(events, []);
    assert.equal(clock.runNext(), true);
    assert.deepEqual(events, ['promote']);
    assert.equal(clock.runNext(), true);
    assert.equal(await result, true);
    assert.deepEqual(events, ['promote', 'complete']);
  } finally {
    globalThis.window = originalWindow;
  }
});
