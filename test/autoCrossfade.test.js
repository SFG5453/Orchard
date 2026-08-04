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
    pauseCalls: 0,
    pause() {
      this.pauseCalls += 1;
    },
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
    setMixVolume: () => true,
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

test('volume changes update both master gains while a transition is active', async () => {
  const originalWindow = globalThis.window;
  const clock = fakeClock();
  globalThis.window = clock.window;
  const volumes = [];
  const analyzer = {
    connectElement() {},
    currentTime: () => 10,
    resetMixElement() {},
    setMixVolume: () => true,
    resume: async () => {},
    scheduleCrossfade: () => ({
      startTime: 10,
      handoffStart: 11,
      promotionTime: 12,
      endTime: 13
    }),
    setVolume(element, value) {
      volumes.push({ element, value });
    }
  };
  const crossfade = createAutoCrossfade({ analyzer });
  const outgoing = audio(110);
  const incoming = audio();

  try {
    const result = crossfade.start({
      fromAudio: outgoing,
      toAudio: incoming,
      transition: { fadeSeconds: 3 },
      volume: 0.8
    });
    await new Promise((resolve) => setImmediate(resolve));

    crossfade.setTargetVolume(0.35);

    assert.deepEqual(volumes.slice(-2), [
      { element: outgoing, value: 0.35 },
      { element: incoming, value: 0.35 }
    ]);
    crossfade.cancel();
    assert.equal(await result, false);
  } finally {
    globalThis.window = originalWindow;
  }
});

test('canceling while the incoming play request is pending cannot restart the transition', async () => {
  const originalWindow = globalThis.window;
  const clock = fakeClock();
  globalThis.window = clock.window;
  let releasePlay;
  let scheduled = 0;
  const analyzer = {
    connectElement() {},
    currentTime: () => 10,
    resetMixElement() {},
    setMixVolume: () => true,
    resume: async () => {},
    scheduleCrossfade: () => {
      scheduled += 1;
      return {
        startTime: 10,
        handoffStart: 11,
        promotionTime: 12,
        endTime: 13
      };
    },
    setVolume() {}
  };
  const crossfade = createAutoCrossfade({ analyzer });
  const outgoing = audio(110);
  const incoming = audio();
  incoming.play = () => new Promise((resolve) => {
    releasePlay = resolve;
  });

  try {
    const result = crossfade.start({
      fromAudio: outgoing,
      toAudio: incoming,
      transition: { fadeSeconds: 3 },
      volume: 0.8
    });
    await new Promise((resolve) => setImmediate(resolve));

    crossfade.cancel();
    releasePlay();

    assert.equal(await result, false);
    assert.equal(scheduled, 0);
    assert.equal(incoming.pauseCalls, 1);
  } finally {
    globalThis.window = originalWindow;
  }
});

// The incoming deck is connected to the graph at unity gain, and
// scheduleCrossfade only pins it to zero from its own start time -- one lead
// time into the future. Anything audible before that point is the burst heard
// at the top of a transition, so the mute has to land before play() does.
test('the incoming deck is muted before it is allowed to play', async () => {
  const originalWindow = globalThis.window;
  const clock = fakeClock();
  globalThis.window = clock.window;
  const order = [];
  const analyzer = {
    connectElement() {},
    currentTime: () => 10,
    resetMixElement() {},
    setMixVolume(element, value) {
      order.push(`mix:${value}`);
      return true;
    },
    resume: async () => {},
    scheduleCrossfade: () => {
      order.push('schedule');
      return { startTime: 10, handoffStart: 10.4, promotionTime: 10.7, endTime: 11 };
    },
    setVolume() {}
  };
  const crossfade = createAutoCrossfade({ analyzer });
  const incoming = audio();
  incoming.play = async () => {
    order.push('play');
  };

  try {
    const result = crossfade.start({
      fromAudio: audio(110),
      toAudio: incoming,
      transition: { fadeSeconds: 1, handoffStartSeconds: 0.4, incomingCueTime: 0 },
      volume: 1
    });
    await new Promise((resolve) => setImmediate(resolve));

    assert.deepEqual(order, ['mix:0', 'play', 'schedule']);

    clock.runNext();
    clock.runNext();
    await result;
  } finally {
    globalThis.window = originalWindow;
  }
});

// An analyzer that cannot mute the incoming deck cannot start a transition
// either: playing it anyway is exactly the burst this guards against.
test('a deck outside the audio graph refuses the transition instead of bursting', async () => {
  const originalWindow = globalThis.window;
  const clock = fakeClock();
  globalThis.window = clock.window;
  let played = false;
  const analyzer = {
    connectElement() {},
    currentTime: () => 10,
    resetMixElement() {},
    setMixVolume: () => false,
    resume: async () => {},
    scheduleCrossfade: () => ({ startTime: 10, handoffStart: 10.4, promotionTime: 10.7, endTime: 11 }),
    setVolume() {}
  };
  const crossfade = createAutoCrossfade({ analyzer });
  const incoming = audio();
  incoming.play = async () => {
    played = true;
  };
  const errors = [];

  try {
    const started = await crossfade.start({
      fromAudio: audio(110),
      toAudio: incoming,
      transition: { fadeSeconds: 1, incomingCueTime: 0 },
      volume: 1,
      onError: (error) => errors.push(error.message)
    });

    assert.equal(started, false);
    assert.equal(played, false);
    assert.equal(crossfade.isActive(), false);
    assert.match(errors[0], /outside the audio graph/);
  } finally {
    globalThis.window = originalWindow;
  }
});
