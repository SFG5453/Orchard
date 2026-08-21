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

import {
  extractMissingReleaseVersion,
  isMissingReleaseArtifactError,
  isProxyUpdateError,
  resolveBetaUpdateCheckFallback,
  STABLE_RELEASE_AVAILABLE_MESSAGE,
  UP_TO_DATE_MESSAGE,
  updateErrorMessage
} from '../electron/integrations/updateErrors.js';

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

test('identifies missing release artifact errors across platforms and manifest names', () => {
  const samples = [
    'Cannot find latest.yml in the latest release artifacts (https://github.com/sfg5453/orchard/releases/download/v5.1.0/latest.yml): HttpError: 404',
    'Cannot find latest-mac.yml in the latest release artifacts (https://github.com/sfg5453/orchard/releases/download/5.1.0/latest-mac.yml): 404',
    'Cannot find latest-linux.yml in the latest release artifacts (https://github.com/sfg5453/orchard/releases/download/v5.1.0/latest-linux.yml): 404',
    'Cannot find latest-linux-arm64.yml in the latest release artifacts (https://github.com/sfg5453/orchard/releases/download/v5.1.0/latest-linux-arm64.yml): 404',
    'Cannot find beta.yml in the latest release artifacts (https://github.com/sfg5453/orchard/releases/download/v5.2.0-beta.1/beta.yml): 404'
  ];

  for (const msg of samples) {
    assert.equal(isMissingReleaseArtifactError(msg), true, msg);
    assert.equal(isMissingReleaseArtifactError(new Error(msg)), true, msg);
  }

  const errWithCode = new Error('channel file missing');
  errWithCode.code = 'ERR_UPDATER_CHANNEL_FILE_NOT_FOUND';
  assert.equal(isMissingReleaseArtifactError(errWithCode), true);

  assert.equal(isMissingReleaseArtifactError('net::ERR_INTERNET_DISCONNECTED'), false);
  assert.equal(isMissingReleaseArtifactError(null), false);
});

test('extracts the release version from artifact download URLs', () => {
  assert.equal(
    extractMissingReleaseVersion('Cannot find latest.yml in the latest release artifacts (https://github.com/sfg5453/orchard/releases/download/v5.1.0/latest.yml): HttpError: 404'),
    '5.1.0'
  );
  assert.equal(
    extractMissingReleaseVersion('Cannot find latest-mac.yml in the latest release artifacts (https://github.com/sfg5453/orchard/releases/download/5.1.0/latest-mac.yml): 404'),
    '5.1.0'
  );
  assert.equal(
    extractMissingReleaseVersion('Cannot find beta.yml in the latest release artifacts (https://github.com/sfg5453/orchard/releases/download/v5.2.0-beta.1/beta.yml): 404'),
    '5.2.0-beta.1'
  );
  assert.equal(extractMissingReleaseVersion('net::ERR_INTERNET_DISCONNECTED'), null);
});

test('beta fallback reminds users when the stable version of their release is available', () => {
  const errNewerStable = 'Cannot find latest.yml in the latest release artifacts (https://github.com/sfg5453/orchard/releases/download/v5.1.0/latest.yml): HttpError: 404';

  const betaVsStable = resolveBetaUpdateCheckFallback(errNewerStable, '5.0.0-beta.1');
  assert.deepEqual(betaVsStable, {
    status: 'current',
    message: STABLE_RELEASE_AVAILABLE_MESSAGE,
    availableVersion: '5.1.0',
    error: ''
  });

  const sameMajorBetaVsStable = resolveBetaUpdateCheckFallback(
    'Cannot find latest-mac.yml in the latest release artifacts (https://github.com/sfg5453/orchard/releases/download/v5.0.0/latest-mac.yml): 404',
    '5.0.0-beta.1'
  );
  assert.deepEqual(sameMajorBetaVsStable, {
    status: 'current',
    message: STABLE_RELEASE_AVAILABLE_MESSAGE,
    availableVersion: '5.0.0',
    error: ''
  });
});

test('beta fallback reports up to date without error when installed version is equal or ahead', () => {
  const errOlderStable = 'Cannot find latest.yml in the latest release artifacts (https://github.com/sfg5453/orchard/releases/download/v4.7.1/latest.yml): 404';

  const aheadBeta = resolveBetaUpdateCheckFallback(errOlderStable, '5.0.0-beta.1');
  assert.deepEqual(aheadBeta, {
    status: 'current',
    message: UP_TO_DATE_MESSAGE,
    availableVersion: '4.7.1',
    error: ''
  });

  const patchAhead = resolveBetaUpdateCheckFallback(errOlderStable, '4.7.2');
  assert.deepEqual(patchAhead, {
    status: 'current',
    message: UP_TO_DATE_MESSAGE,
    availableVersion: '4.7.1',
    error: ''
  });

  const errSameVersion = 'Cannot find latest.yml in the latest release artifacts (https://github.com/sfg5453/orchard/releases/download/v5.1.0/latest.yml): 404';
  const sameVersion = resolveBetaUpdateCheckFallback(errSameVersion, '5.1.0');
  assert.deepEqual(sameVersion, {
    status: 'current',
    message: UP_TO_DATE_MESSAGE,
    availableVersion: '5.1.0',
    error: ''
  });
});

test('beta fallback ignores non-artifact errors', () => {
  assert.equal(resolveBetaUpdateCheckFallback(new Error('net::ERR_INTERNET_DISCONNECTED'), '5.0.0-beta.1'), null);
  assert.equal(resolveBetaUpdateCheckFallback('net::ERR_TUNNEL_CONNECTION_FAILED', '5.0.0-beta.1'), null);
});

