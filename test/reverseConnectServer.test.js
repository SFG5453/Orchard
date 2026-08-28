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
import { EventEmitter, once } from 'node:events';
import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { Server } from 'socket.io';
import { io } from 'socket.io-client';

import { createOrchardConnectServer } from '../electron/connect/orchardConnectServer.js';

function request(socket, event, payload = {}) {
  return new Promise((resolve, reject) => {
    socket.emit(event, payload, (response) => {
      if (response?.ok) resolve(response.data);
      else reject(new Error(response?.error || `${event} failed`));
    });
  });
}

test('v4 desktop commands and phone state cross the real Socket.IO bridge', async (t) => {
  const desktopIo = new EventEmitter();
  const desktopSocket = new EventEmitter();
  const storeDir = await mkdtemp(path.join(tmpdir(), 'orchard-connect-test-'));
  const server = await createOrchardConnectServer({
    Server,
    desktopIo,
    deviceStorePath: path.join(storeDir, 'devices.json')
  });
  server.registerDesktop(desktopSocket);
  t.after(() => server.close());

  const info = await request(desktopSocket, 'connect:pairing-info');
  const pairUrl = new URL(info.webPairUrl);
  const remote = io(`${pairUrl.protocol}//${pairUrl.host}`, {
    transports: ['websocket'],
    forceNew: true
  });
  t.after(() => remote.close());
  await once(remote, 'connect');

  const pendingPromise = once(desktopIo, 'connect:pairing-request');
  const helloReply = await new Promise((resolve) => remote.emit('connect:hello', {
    token: pairUrl.searchParams.get('token'),
    deviceToken: 'test-device-token',
    name: 'Test Phone',
    protocolVersion: 4
  }, resolve));
  assert.equal(helloReply.data.status, 'pending');

  const [pending] = await pendingPromise;
  const approvedPromise = once(remote, 'connect:approved');
  const pairingStatePromise = once(desktopIo, 'connect:pairing-state');
  const approval = await request(desktopSocket, 'connect:pairing-approve', { id: pending.id });
  await approvedPromise;
  const [pairingState] = await pairingStatePromise;
  const device = pairingState.devices.find((item) => item.id === approval.device.id);
  assert.equal(device.protocolVersion, 4);

  const commandPromise = once(remote, 'connect:command');
  const delivery = await request(desktopSocket, 'connect:device-command', {
    deviceId: device.id,
    command: { type: 'next' }
  });
  assert.equal(delivery.delivered, true);
  const [command] = await commandPromise;
  assert.deepEqual(command, { type: 'next' });

  const statePromise = once(desktopIo, 'connect:device-state');
  remote.emit('connect:device-state', { status: 'playing', track: { id: 'phone-track' } });
  const [stateEvent] = await statePromise;
  assert.equal(stateEvent.deviceId, device.id);
  assert.equal(stateEvent.state.track.id, 'phone-track');

  const legacyInfo = await request(desktopSocket, 'connect:pairing-refresh');
  const legacyPairUrl = new URL(legacyInfo.webUrl);
  const legacy = io(`${legacyPairUrl.protocol}//${legacyPairUrl.host}`, {
    transports: ['websocket'],
    forceNew: true
  });
  t.after(() => legacy.close());
  await once(legacy, 'connect');
  const legacyPendingPromise = once(desktopIo, 'connect:pairing-request');
  await new Promise((resolve) => legacy.emit('connect:hello', {
    token: legacyPairUrl.searchParams.get('token'),
    deviceToken: 'legacy-device-token',
    name: 'Legacy Phone',
    protocolVersion: 3
  }, resolve));
  const [legacyPending] = await legacyPendingPromise;
  const legacyApprovedPromise = once(legacy, 'connect:approved');
  const legacyStatePromise = once(desktopIo, 'connect:pairing-state');
  const legacyApproval = await request(desktopSocket, 'connect:pairing-approve', { id: legacyPending.id });
  await legacyApprovedPromise;
  const [legacyState] = await legacyStatePromise;
  const legacyDevice = legacyState.devices.find((item) => item.id === legacyApproval.device.id);
  assert.equal(legacyDevice.protocolVersion, 3);

  const unsupported = await request(desktopSocket, 'connect:device-command', {
    deviceId: legacyDevice.id,
    command: { type: 'next' }
  });
  assert.deepEqual(unsupported, { delivered: false, reason: 'unsupported' });
});
