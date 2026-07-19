# Orchard Listening Party Worker

Peer-to-peer listening parties use this Worker only for room coordination and
WebRTC signaling. Each client plays audio locally in Orchard; the Worker never
proxies or streams media.

## Endpoints

- `GET /health` returns service status.
- `POST /rooms` creates a room and returns a host participant token.
- `GET /rooms/:roomId` returns public room metadata.
- `POST /rooms/:roomId/join` creates a guest participant token.
- `GET /rooms/:roomId/socket?participantId=...&token=...` upgrades to the
  room WebSocket.

## WebSocket Messages

Clients send:

- `signal` with `{ to, kind, data }` to relay WebRTC offer/answer/ICE payloads.
- `party:update` from the host to broadcast synced playback state.
- `party:request` from guests to ask the host to play, pause, seek, or queue.
- `party:host-transfer` from the host with `{ participantId }`.
- `party:close` from the host to close the room.
- `party:ping` for keepalive checks.

The server emits `party:welcome`, `peer:joined`, `peer:left`, `signal`,
`party:update`, `party:request`, `party:host-changed`, `party:closed`, and
`party:pong`.
