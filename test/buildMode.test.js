/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { isDevelopmentBuild } from '../electron/main/buildMode.js';

test('Orchard Packages installations are release builds when Electron reports them as unpackaged', () => {
  const development = isDevelopmentBuild({
    app: { isPackaged: false, getAppPath: () => '/installed/orchard' },
    isDev: false,
    pathExists: (candidate) => candidate === '/installed/orchard/.orchard-package.json'
  });

  assert.equal(development, false);
});

test('source launches without an Orchard package marker are development builds', () => {
  const development = isDevelopmentBuild({
    app: { isPackaged: false, getAppPath: () => '/source/orchard' },
    isDev: false,
    pathExists: () => false
  });

  assert.equal(development, true);
});

test('Vite launches remain development builds even if a package marker is present', () => {
  const development = isDevelopmentBuild({
    app: { isPackaged: false, getAppPath: () => '/installed/orchard' },
    isDev: true,
    pathExists: () => true
  });

  assert.equal(development, true);
});
