export const PARTICIPANT_RESERVATION_MS = 60_000;

export function participantIsActive(peer, sessions, now = Date.now()) {
  if (!peer || peer.leftAt) return false;
  if (sessions.has(peer.id) || peer.connectedAt) return true;
  return now - peer.joinedAt <= PARTICIPANT_RESERVATION_MS;
}

export function expireParticipantReservations(room, sessions, now = Date.now()) {
  let changed = false;
  for (const peer of Object.values(room.participants || {})) {
    if (participantIsActive(peer, sessions, now) || peer.leftAt) continue;
    peer.leftAt = now;
    peer.lastSeenAt = now;
    changed = true;
  }
  return changed;
}

export function activeParticipants(room, sessions, now = Date.now()) {
  return Object.values(room.participants || {}).filter((peer) => participantIsActive(peer, sessions, now));
}
