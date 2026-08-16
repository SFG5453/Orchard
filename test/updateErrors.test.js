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

import { isProxyUpdateError, updateErrorMessage } from '../electron/integrations/updateErrors.js';

test('a dead system proxy is named as the cause, not left as a net:: code', () => {
  const message = updateErrorMessage(new Error('net::ERR_TUNNEL_CONNECTION_FAILED'));

  assert.match(message, /system proxy/);
  assert.match(message, /net::ERR_TUNNEL_CONNECTION_FAILED/);
  // Artwork fails through the same stack, so the report explains both at once.
  assert.match(message, /Album art/);
  assert.match(message, /Ignore system proxy/);
});

test('every Chromium proxy failure routes to the proxy explanation', () => {
  for (const code of [
    'net::ERR_TUNNEL_CONNECTION_FAILED',
    'net::ERR_PROXY_CONNECTION_FAILED',
    'net::ERR_PROXY_AUTH_UNSUPPORTED',
    'net::ERR_MANDATORY_PROXY_CONFIGURATION_FAILED',
    'net::ERR_PROXY_CERTIFICATE_INVALID'
  ]) {
    assert.equal(isProxyUpdateError(code), true, code);
  }
});

test('ordinary failures are reported verbatim', () => {
  assert.equal(isProxyUpdateError(new Error('net::ERR_INTERNET_DISCONNECTED')), false);
  assert.equal(updateErrorMessage(new Error('net::ERR_INTERNET_DISCONNECTED')), 'net::ERR_INTERNET_DISCONNECTED');
  assert.equal(updateErrorMessage('HTTP 404'), 'HTTP 404');
  assert.equal(updateErrorMessage(null), 'Update check failed.');
  assert.equal(updateErrorMessage(''), 'Update check failed.');
});
