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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';

import vue from '@vitejs/plugin-vue';
import { createSSRApp, h, ref } from 'vue';
import { renderToString } from 'vue/server-renderer';
import { createServer } from 'vite';

let server;
let WelcomeWindow;

before(async () => {
  server = await createServer({
    appType: 'custom',
    configFile: false,
    root: process.cwd(),
    plugins: [vue()],
    server: { middlewareMode: true }
  });
  ({ default: WelcomeWindow } = await server.ssrLoadModule('/src/components/chrome/WelcomeWindow.vue'));
});

after(async () => {
  await server?.close();
});

test('welcome controls expose boolean values and update the application refs immediately', async () => {
  const app = {
    audioEngineConfig: ref({ autoEqEnabled: false }),
    authState: ref({ signedIn: false }),
    autoplayEnabled: ref(false),
    setupState: ref({ welcomeCompleted: false }),
    welcomeMode: ref('')
  };

  let state;
  const setup = WelcomeWindow.setup;
  WelcomeWindow.setup = (...args) => {
    state = setup(...args);
    return state;
  };

  const renderer = createSSRApp({ render: () => h(WelcomeWindow, { app }) });
  renderer.component('q-icon', { render: () => h('i') });

  const originalWarn = console.warn;
  console.warn = () => {};
  try {
    await renderToString(renderer);
  } finally {
    console.warn = originalWarn;
    WelcomeWindow.setup = setup;
  }

  assert.equal(state.app.autoplayEnabled, false);
  assert.equal(state.app.audioEngineConfig.autoEqEnabled, false);

  state.app.autoplayEnabled = true;
  state.app.audioEngineConfig = { autoEqEnabled: true };

  assert.equal(app.autoplayEnabled.value, true);
  assert.deepEqual(app.audioEngineConfig.value, { autoEqEnabled: true });
});
