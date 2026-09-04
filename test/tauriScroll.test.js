import assert from 'node:assert/strict';
import test from 'node:test';

import {
  installTauriWheelNormalization,
  normalizedTauriWheelDelta
} from '../src/platform/desktop/tauriScroll.js';

test('Tauri discrete wheel input uses Chromium-sized steps', () => {
  assert.equal(normalizedTauriWheelDelta({ deltaY: 53, deltaX: 0, deltaMode: 0 }), 100);
  assert.equal(normalizedTauriWheelDelta({ deltaY: -53, deltaX: 0, deltaMode: 0 }), -100);
  assert.equal(normalizedTauriWheelDelta({ deltaY: 3, deltaX: 0, deltaMode: 1 }), 120);
});

test('Tauri preserves precise touchpad, horizontal, and zoom gestures', () => {
  assert.equal(normalizedTauriWheelDelta({ deltaY: 9.5, deltaX: 0, deltaMode: 0 }), 0);
  assert.equal(normalizedTauriWheelDelta({ deltaY: 20, deltaX: 40, deltaMode: 0 }), 0);
  assert.equal(normalizedTauriWheelDelta({ deltaY: 53, deltaX: 0, deltaMode: 0, ctrlKey: true }), 0);
});

test('Tauri wheel normalization scrolls the nearest eligible container', () => {
  const listeners = new Map();
  const outer = { scrollTop: 10, scrollHeight: 1200, clientHeight: 400 };
  const inner = { scrollTop: 20, scrollHeight: 600, clientHeight: 200 };
  const target = {
    document: {
      addEventListener(event, callback) {
        listeners.set(event, callback);
      },
      removeEventListener(event) {
        listeners.delete(event);
      }
    },
    getComputedStyle: () => ({ overflowY: 'auto' }),
    innerHeight: 800
  };
  const dispose = installTauriWheelNormalization(target);
  let prevented = false;

  listeners.get('wheel')({
    deltaY: 53,
    deltaX: 0,
    deltaMode: 0,
    defaultPrevented: false,
    composedPath: () => [inner, outer],
    preventDefault() {
      prevented = true;
    }
  });

  assert.equal(inner.scrollTop, 120);
  assert.equal(outer.scrollTop, 10);
  assert.equal(prevented, true);
  dispose();
  assert.equal(listeners.has('wheel'), false);
});
