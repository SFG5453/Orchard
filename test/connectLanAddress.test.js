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

import { lanAddressCandidates } from '../electron/connect/orchardConnectServer.js';

test('a Hyper-V host advertises the real NIC, not the virtual switch', () => {
  const candidates = lanAddressCandidates({
    'vEthernet (Default Switch)': [{ family: 'IPv4', internal: false, address: '172.20.112.1' }],
    'Wi-Fi': [{ family: 'IPv4', internal: false, address: '192.168.1.42' }]
  });

  assert.equal(candidates[0], '192.168.1.42');
  assert.deepEqual(candidates, ['192.168.1.42', '172.20.112.1']);
});

test('loopback, IPv6 and unleased interfaces never reach the QR code', () => {
  const candidates = lanAddressCandidates({
    lo: [{ family: 'IPv4', internal: true, address: '127.0.0.1' }],
    eth0: [
      { family: 'IPv6', internal: false, address: 'fe80::1' },
      { family: 'IPv4', internal: false, address: '10.0.0.5' }
    ],
    eth1: [{ family: 'IPv4', internal: false, address: '169.254.9.9' }]
  });

  assert.deepEqual(candidates, ['10.0.0.5']);
});

test('a router-assigned address outranks a Docker bridge in the same private space', () => {
  const candidates = lanAddressCandidates({
    'docker0': [{ family: 'IPv4', internal: false, address: '172.17.0.1' }],
    'Ethernet': [{ family: 'IPv4', internal: false, address: '172.16.4.9' }],
    'Wi-Fi': [{ family: 'IPv4', internal: false, address: '10.1.2.3' }]
  });

  assert.deepEqual(candidates, ['10.1.2.3', '172.16.4.9', '172.17.0.1']);
});

test('nothing routable leaves no candidates for the pairing URL', () => {
  assert.deepEqual(lanAddressCandidates({ lo: [{ family: 'IPv4', internal: true, address: '127.0.0.1' }] }), []);
  assert.deepEqual(lanAddressCandidates({}), []);
});
