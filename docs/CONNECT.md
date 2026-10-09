# Orchard Connect v2

Orchard Connect lets one Orchard device control playback on another. The protocol, roles,
transports and security live once, in Qt-free C++17 under `core/native/connect`. Desktop links
it directly (`app/src/connect`); Android reaches it through JNI
(`mobile/android/app/src/main/cpp/connect`, `mobile/connect`). Platforms only move strings and
bytes: the hub WebSocket, playback snapshots, events, RPC answers and audio.

## Roles

- **Target**: owns playback state, the audio clock, output, volume and routing.
- **Controller**: sends intent and mirrors the target's state. Never plays the session's audio.
- **Provider host** (per provider): the device signed in to that provider. Credentials never
  cross; peers get catalog results, opaque playback ids and decrypted byte ranges.
- **Mix host**: Smart Crossfade analysis and rendering. A capable desktop wins, even when the
  phone is the target.
- **Artwork host**: artwork lookup. A capable desktop wins.

`selectRoles` (`device.cpp`) decides from `DeviceInfo` capabilities: mix and artwork go to the
target when it is a capable desktop, else to a capable desktop controller, else to the target.
Each provider goes to a signed-in device, target first; no signed-in device means
`provider_unavailable`.

## Versioning

Every hello and presence carries `connect_protocol_major` (2) and `connect_protocol_minor` (0).
A missing major is rejected with `incompatible_client`, a different major with
`incompatible_protocol`. The check runs before any grant, sync or command. Features travel as
capabilities, never as version bumps.

## Account hub

`services/account` runs one Durable Object per user (`ConnectHub`) at `GET /connect/hub`. The
WebSocket offers the subprotocols `orchard-connect.2` and `bearer.<access token>`; the device id
comes from the token. The hub handles presence, discovery, session grants and WebRTC signaling.

- Device to hub: `hello`, `update` (DeviceInfo, LAN endpoints, currently playing), `refresh`
  (new token), `session.request {request_id, to}`, `signal {to, session_id, data}`,
  `session.decline`, `session.end`.
- Hub to device: `welcome`, `presence`, `session.grant`, `signal {from, session_id, data}`,
  `session.declined`, `error`.

A grant gives both ends the same random 32-byte session key, the peer's LAN endpoints and ICE
servers (Cloudflare TURN when configured, else Cloudflare STUN). The hub authorizes; it never
carries playback traffic.

## Transports and handshake

The controller races up to four ranked LAN endpoints (`ws://<ip>:32147/orchard-connect`) and
starts WebRTC after 2.5 s or once every LAN attempt fails. WebRTC uses one ordered, reliable
data channel named `orchard-connect`. Both sit behind `ConnectTransport`.

1. Controller `hello`: version, session id, device id, target id, 32-byte nonce, resume flag.
2. Target `challenge`: its nonce and an HMAC-SHA256 proof over a length-prefixed transcript
   (label, session id, both nonces, both device ids) keyed by the session key.
3. Controller `auth`: its own proof.
4. Both derive directional ChaCha20-Poly1305 keys with HKDF-SHA256. Every later frame is sealed;
   nonces are implicit counters, frames fragment at 60 KiB, messages cap at 16 MiB.

A LAN peer is untrusted until its proof checks out. Signaling alone authorizes nothing.

## Session states

`DISCOVERING`, `CONNECTING`, `AUTHENTICATING`, `NEGOTIATING_CAPABILITIES`,
`RESOLVING_INITIAL_PLAYBACK`, `CONNECTED`, `RECONNECTING`, `DISCONNECTED`. A session that cannot
recover ends with a reason code in `session_ended`.

Initial playback resolves once per session, during `RESOLVING_INITIAL_PLAYBACK`:

1. Target active: the target wins and keeps playing.
2. Else controller active: its snapshot transfers to the target, position projected by elapsed
   time plus half the round trip.
3. Else nothing happens.

Keepalive pings run every 5 s with a 15 s timeout. A controller that loses its link reconnects
with backoff (250 ms to 4 s) for 30 s without resolving initial playback again; a target holds
the session for 60 s. Ending reasons and errors are fixed codes (`protocol.h`).

## Host API

`Node` (`node.h`) is thread-safe and returns at once. The host implements `NodeHost`:
`hubSend`, `event`, `data`, `log`.

Events (`{"event": name, ...}`): `hub`, `devices`, `session`, `session_ended`,
`initial_playback`, `remote_state`, `roles`, `command`, `command_result`, `rpc`, `rpc_result`,
`stream_end`, `stream_sent`, `stream_failed`, `flow`, `error`.

Commands (`normalizeCommand`): `play`, `pause`, `toggle`, `next`, `previous`, `clear_queue`,
`seek {position}`, `set_volume {volume}`, `set_repeat {mode}`, `set_shuffle {enabled}`,
`play_queue_index {index}`, `remove_queue_item {index}`, `move_queue_item {from, to}`,
`enqueue {track, next}`, `play_track {track, tracks, position, play, context_title}`,
`replace_queue {tracks, index, position, play, context_title}`. Queue indices count upcoming
tracks only, from 0.

## RPC methods

`Node::request({method, params, host, timeout_ms?})` routes to `provider:<name>`, `artwork`,
`mix` or a device id. The default deadline is 30 s.

- `ResolveTrack {provider, track}`: provider host resolves a track to an opaque playback id.
- `ReadRange {provider, playback_id, start, end}`: decrypted bytes (4 MiB max) arrive as a data
  frame `{kind: "range", rpc}` ahead of the result `{length}`.
- `PlaybackReport {provider, playback_id, started, position}`: playback reporting on the
  provider host's account.
- `Search {provider, query, filter}`, `GetAlbum {provider, id}`, `GetArtist {provider, id}`:
  catalog calls on the YouTube provider host.
- `ResolveArtwork {track}`: artwork host answers `{static_url, animated_url}`.
- `MixPrepare {mix, request, codecs}`: see below.

## Audio streams

`Node::sendStream(session, meta, bytes)` sends one AudioChunk stream; the core splits, paces and
reassembles it (`stream.h`). Each frame header carries `kind: "audio"`, `stream`, `track`,
`sequence`, `offset`, `total`, `timestamp`, `codec`, `sample_rate`, `channels`, `final` and an
app `meta` object. Codecs: `pcm_s16`, `pcm_f32`, `flac`, `opus`, and `source` (a provider's
own encoded bytes, passed through). PCM chunks hold whole frames, so every chunk timestamp is
exact target media time. Chunks wait while the link holds 4 MiB or more, so pings and RPCs never
queue behind a whole song. Receivers get the whole stream once through `data()`; a gap, an
overflow or a dropped link fails it with `stream_failed`.

## Remote mixing

When the phone is the target and a desktop holds the mix role:

1. The phone uploads both songs' cached bytes as `source` streams tagged
   `{mix, role: outgoing|incoming}` (64 MiB each at most).
2. It calls `MixPrepare {mix, request, codecs: ["pcm_s16", "pcm_f32"]}` on host `mix` with a
   60 s deadline. `request` holds the planner inputs only; the desktop copies whitelisted fields
   into its worker request.
3. The desktop serves the bytes to `orchard-adaptive-mix` through two loopback proxies and asks
   for `sourceRate`, so the render comes back at the outgoing song's decoded rate.
4. It streams the render in the first codec the phone listed, stamped with the outgoing song's
   media time, and answers with the plan (`outgoingStart`, `incomingCue`, `incomingResume`,
   `duration`, `rate`, `incomingRate`, `strategy`, ...). A planner refusal answers
   `{error}` instead.
5. The phone builds the same `PreparedMix.Ready` a local render gives and splices it on its own
   clock.

Any failure on the way (no mix host, upload refused, host error, timeout) makes the phone mix
locally. A natural-boundary refusal is honored as is.

## Tests

- `tests/connect/connect_core_test.cpp`: versioning, roles, initial playback, commands, LAN
  ranking, sealing, proofs, AudioChunk framing.
- `tests/connect/connect_session_test.cpp`: two real nodes over loopback LAN and WebRTC with an
  in-process hub: transfers, control, provider RPCs, streams, reconnects, rejections.
- `tests/connect/connect_mix_test.cpp`: the desktop mix host with the real worker.
- `services/account/test/connect.test.js`: the hub.
- `mobile/android/app/src/test/.../connect`: wire format, target commands, remote mix results.
