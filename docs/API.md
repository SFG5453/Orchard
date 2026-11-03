# Local scripting API

Orchard's desktop app serves a versioned HTTP API at `http://127.0.0.1:32145/api/v1`
and a WebSocket API at `ws://127.0.0.1:32146`. Both use the same commands and the
desktop's signed-in catalog. No Orchard Connect account or hub connection is required.

## Authentication and discovery

On startup Orchard writes `api.json` in its application data directory with
`version`, `http`, `websocket`, and `token` fields. The file is readable only by
its owner on systems with Unix file permissions. Treat its token as a password.

Typical locations:

- Linux: `$XDG_DATA_HOME/SFG545/Orchard/api.json`, defaulting to
  `~/.local/share/SFG545/Orchard/api.json`.
- Windows: `%LOCALAPPDATA%/SFG545/Orchard/api.json`.
- macOS: Orchard's application support directory. The app logs the exact path.

HTTP requests require `Authorization: Bearer <token>`. Browser-origin requests
are rejected; this interface is for local scripts and applications. All listeners
bind to IPv4 loopback. The API does not expose provider credentials, stream URLs,
or sign-in operations. A port conflict disables the API without stopping playback;
the **Local API** card shows the error.

Settings, Integrations, **Local API** turns the API on or off and sets the HTTP port
(1024 to 65534, default 32145); the WebSocket port is the next port. Changes apply
immediately and disconnect current clients. Turning the API off deletes `api.json`.
`ORCHARD_API_PORT` overrides the saved port and `ORCHARD_API_DISABLED=1` keeps both
listeners off for that run. The token persists while Orchard runs and across restarts
while `api.json` exists; delete `api.json` while Orchard is closed to generate a new token.

## HTTP

`POST /api/v1/rpc` accepts `{"method":"toggle","params":{}}`. Commands can also
use paths: `POST /api/v1/queue/add` is `queue.add`, with its parameters as the body.
Use `Content-Type: application/json` and a JSON object. `params` defaults to `{}`.

Read routes are `GET /api/v1`, `/state`, `/queue`, `/history`, `/mix`, `/mixes`,
`/search`, `/home`, `/playlist`, `/album`, `/artist`, `/library`, `/playlists`, and
`/connect`. Append these paths to `/api/v1`. Query parameters use the names below.
For example: `GET /api/v1/search?query=Daft%20Punk&filter=songs`.

Success: `{"ok":true,"result":...}`. Errors:
`{"ok":false,"error":{"code":"invalid_params","message":"..."}}`.
HTTP status codes include 400 (bad input), 401 (token), 403 (browser origin),
404 (missing method/result), 409 (duplicate or unavailable operation),
502 (provider failure), 503 (capacity), and 504 (catalog deadline).
Connections close after each HTTP response. Bodies are limited to 1 MiB.

## Commands

| Method | Parameters and result |
| --- | --- |
| `api` | API version and method list. |
| `state` | Current track, playing/loading, position/duration in seconds, volume, shuffle, repeat, queue length, catalog source, remote flag, playback error, like status, and mix state. |
| `queue`, `history` | Arrays of upcoming tracks or playback history. Queue indices exclude the current track and start at zero. |
| `play`, `pause`, `toggle`, `stop`, `next`, `previous` | Playback controls. `stop` requires local playback. |
| `seek` | Absolute `position` or relative `offset`, in seconds. |
| `volume` | Absolute `value` (0–1) or relative `offset` (-1–1); relative adjustments clamp to 0–1. Follows a Connect target. |
| `mute` | Toggle zero volume and the last nonzero volume remembered by the API. |
| `shuffle` | Optional boolean `enabled`; omitted means toggle. |
| `repeat` | Optional `mode`: `off`, `all`, `one`; omitted means cycle. |
| `like` | Toggle the local current track's library like. Requires a known like status and no pending like write. |
| `track.play` | `track`: a playable normalized track object. |
| `queue.add` | `track`, optional `next` (default false), `deduplicate` (default false). Duplicates compare track IDs with the current track and queue. |
| `queue.remove`, `queue.play` | `index` in the upcoming queue. |
| `queue.move` | `from`, `to` in the upcoming queue. |
| `queue.clear` | Clear upcoming tracks. |
| `queue.replace` | `tracks` (1–2500), optional `index` (default 0), `position` (default 0), `play` (default true). The indexed track becomes current; later tracks become upcoming. |
| `request` | `query`, optional `next`, `deduplicate` (default true), positive `maxDuration` in seconds. Searches songs and queues the first playable result meeting these constraints. Returns `{track, queued, next}`. Unknown durations are excluded when a limit is supplied. |
| `search` | `query` (1–512 characters), optional `filter`: `songs` (default), `all`, `videos`, `albums`, `artists`, `playlists`. Returns normalized `sections[].items`. |
| `home` | Fresh home sections from the selected catalog. |
| `mixes` | YouTube Music's personalized mixed-for-you shelves; Apple Music returns its home recommendations. Items include playlist/browse IDs for `playlist` and `playlist.play`. |
| `playlist`, `album`, `artist` | `id`: catalog browse ID. Returns catalog detail. |
| `playlist.more` | YouTube `continuation` from a playlist page, optional integer `startIndex`. Returns the next page. |
| `playlist.play` | `id`, optional boolean `shuffle`. Resolves the playlist/mix and plays it, preserving its continuation for queue loading. |
| `library`, `playlists` | Catalog library or saved playlists. YouTube `playlists` returns an array; Apple Music returns `{items}`. |
| `mix.state` | Desktop crossfade settings, active transition/progress/incoming track, adaptive mix availability/status/style/BPM, and Best Mix download/analysis progress, error, and tempos. |
| `mix.configure` | Optional `crossfadeEnabled`, `gaplessEnabled`, integer `duration` (1–12 seconds), `mode` (`standard`/`adaptive`). Applies desktop settings. |
| `mix.best` | Start/cancel Best Mix or restore queue order, matching the app button. Requires local playback. |
| `connect` | Connect state, role, peer, and discovered devices. |

Controls return a current state snapshot unless the method documents another result.
An accepted playback command can still fail later during stream resolution; monitor
`state.error` and `state.loading`. A remote command snapshot may precede the target's
acknowledgement. Playback and queue controls follow the selected Connect target;
mix settings/status and likes describe this desktop. Catalog calls, `request`, and
playback use the music source chosen in Settings, General (YouTube Music or Apple
Music). Apple Music tracks (`am.` IDs) play only while Apple Music is selected and
connected; a request whose source changes mid-call returns `source_changed`.

Use normalized track objects returned by search/detail. A track needs an `id` and
`type` of `song`, `track`, or `video`; omitted `type` defaults to `song` for direct
queue commands. Unplayable rows are rejected. Catalog calls are independent of the
app's open search/detail page. In offline mode search, home, saved playlists, and
collection detail use downloaded/local content.

## WebSocket events and commands

Within five seconds of connecting, send:

```json
{"method":"authenticate","token":"<token from api.json>"}
```

The server sends `{"event":"ready","version":1,"data":<state>}`. Every authenticated
client receives `{"event":"state","data":<state>}` and
`{"event":"queue","data":[<tracks>]}` when their contents change, at most four
times per second. Fetch `queue` once after `ready` for an initial queue snapshot.

Send `{"id":"unique-request-id","method":"search","params":{"query":"Daft Punk"}}`.
Replies echo `id` and include `ok`, `result` or `error`, and an HTTP-equivalent
`status`. Events can arrive between replies. Use unique IDs for concurrent requests.
Authentication, slow-consumer limits, and 30-second operation deadlines apply.
The protocol is plain WebSocket JSON, with no Socket.IO dependency.

## Python song requests

This uses only Python's standard library on Linux:

```python
import json
import os
from pathlib import Path
from urllib.request import Request, urlopen

data = Path(os.environ.get("XDG_DATA_HOME", str(Path.home() / ".local/share")))
config = json.loads((data / "SFG545/Orchard/api.json").read_text())

def orchard(method, **params):
    request = Request(
        config["http"] + "/rpc",
        data=json.dumps({"method": method, "params": params}).encode(),
        headers={"Authorization": "Bearer " + config["token"],
                 "Content-Type": "application/json"},
    )
    with urlopen(request, timeout=35) as response:
        return json.load(response)["result"]

print(orchard("state"))
print(orchard("request", query="Daft Punk Something About Us",
              deduplicate=True, maxDuration=600))
# orchard("toggle")
# orchard("next")
```

Chat bots should enforce their own viewer permissions, rate limits, and moderation
before calling `request`. Share the bot's command interface with viewers, keeping
the local API token on the machine running Orchard.

The [Stream Deck plugin](../plugins/streamdeck/README.md) uses this API for OpenDeck,
Tacto, and Stream Deck controls.
