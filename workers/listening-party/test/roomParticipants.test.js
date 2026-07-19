import assert from 'node:assert/strict';
import test from 'node:test';
import {
  activeParticipants,
  expireParticipantReservations,
  PARTICIPANT_RESERVATION_MS
} from '../src/roomParticipants.js';

function participant(id, joinedAt, overrides = {}) {
  return { id, joinedAt, connectedAt: 0, lastSeenAt: joinedAt, leftAt: 0, ...overrides };
}

test('expires participants that never complete their socket connection', () => {
  const now = 100_000;
  const room = {
    participants: {
      stale: participant('stale', now - PARTICIPANT_RESERVATION_MS - 1),
      pending: participant('pending', now - PARTICIPANT_RESERVATION_MS)
    }
  };

  assert.equal(expireParticipantReservations(room, new Map(), now), true);
  assert.equal(room.participants.stale.leftAt, now);
  assert.equal(room.participants.pending.leftAt, 0);
  assert.deepEqual(activeParticipants(room, new Map(), now).map(({ id }) => id), ['pending']);
});

test('keeps connected participants active beyond the reservation window', () => {
  const now = 100_000;
  const connected = participant('connected', now - PARTICIPANT_RESERVATION_MS - 1);
  const room = { participants: { connected } };

  assert.equal(expireParticipantReservations(room, new Map([['connected', {}]]), now), false);
  assert.deepEqual(activeParticipants(room, new Map([['connected', {}]]), now), [connected]);
});
