import assert from 'node:assert/strict';
import test from 'node:test';
import { ListeningPartyClient } from '../src/app/social/listeningPartyClient.js';

class FakeWebSocket extends EventTarget {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSED = 3;

  constructor(url) {
    super();
    this.url = url;
    this.readyState = FakeWebSocket.CONNECTING;
    this.sent = [];
    queueMicrotask(() => {
      this.readyState = FakeWebSocket.OPEN;
      this.dispatchEvent(new Event('open'));
    });
  }

  send(message) {
    this.sent.push(JSON.parse(message));
  }

  close() {
    this.readyState = FakeWebSocket.CLOSED;
    this.dispatchEvent(new Event('close'));
  }
}

test('waits for the listening-party socket before reporting connected', async (t) => {
  const originalWebSocket = globalThis.WebSocket;
  globalThis.WebSocket = FakeWebSocket;
  t.after(() => { globalThis.WebSocket = originalWebSocket; });

  const client = new ListeningPartyClient();
  const connecting = client.connect({
    room: { id: 'ABC234', socketUrl: 'wss://party.example/rooms/ABC234/socket' },
    participant: { id: 'abc123', token: 'def456', role: 'guest' }
  });

  assert.equal(client.status, 'connecting');
  await connecting;
  assert.equal(client.status, 'connected');
});

test('uses one WebSocket path for state updates and host requests', (t) => {
  const originalWebSocket = globalThis.WebSocket;
  globalThis.WebSocket = FakeWebSocket;
  t.after(() => { globalThis.WebSocket = originalWebSocket; });

  const client = new ListeningPartyClient();
  const sent = [];
  client.socket = { readyState: FakeWebSocket.OPEN, send: (message) => sent.push(JSON.parse(message)) };
  client.peers.set('peer', {
    channel: { readyState: 'open', send: () => assert.fail('control messages must not use the peer channel') }
  });

  client.participant = { id: 'host', role: 'host' };
  client.broadcast('party:state', { track: { id: 'song' } });
  client.participant = { id: 'guest', role: 'guest' };
  client.requestHost({ action: 'next' });

  assert.deepEqual(sent, [
    { type: 'party:update', payload: { track: { id: 'song' } } },
    { type: 'party:request', payload: { action: 'next' } }
  ]);
});
