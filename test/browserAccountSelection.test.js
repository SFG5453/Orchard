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
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {
  createBrowserAccountSelectionStore,
  normalizeBrowserAccountSelection
} from '../electron/auth/browserAccountSelection.js';

test('normalizeBrowserAccountSelection keeps only restorable account identity fields', () => {
  assert.deepEqual(normalizeBrowserAccountSelection({
    visitorData: ' visitor ',
    dataSyncId: ' selected-channel ',
    accountIndex: '2',
    poToken: 'short-lived-secret',
    cookie: 'SAPISID=secret'
  }), {
    visitorData: 'visitor',
    dataSyncId: 'selected-channel',
    accountIndex: 2
  });

  assert.deepEqual(normalizeBrowserAccountSelection({
    accountIndex: -1
  }), {
    visitorData: '',
    dataSyncId: '',
    accountIndex: 0
  });
});

test('browser account selection survives a new store instance and is removed on clear', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'orchard-browser-account-'));

  try {
    const first = createBrowserAccountSelectionStore(directory);
    first.save({
      visitorData: 'visitor',
      dataSyncId: 'brand-channel',
      accountIndex: 3,
      poToken: 'do-not-persist',
      cookie: 'SAPISID=do-not-persist'
    });

    const restored = createBrowserAccountSelectionStore(directory);
    assert.deepEqual(restored.cached(), {
      visitorData: 'visitor',
      dataSyncId: 'brand-channel',
      accountIndex: 3
    });

    restored.clear();
    assert.deepEqual(createBrowserAccountSelectionStore(directory).cached(), {
      visitorData: '',
      dataSyncId: '',
      accountIndex: 0
    });
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test('browser account selection ignores malformed cache files', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'orchard-browser-account-'));

  try {
    fs.writeFileSync(path.join(directory, 'youtube-browser-account.json'), '{broken');
    assert.deepEqual(createBrowserAccountSelectionStore(directory).cached(), {
      visitorData: '',
      dataSyncId: '',
      accountIndex: 0
    });
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});
