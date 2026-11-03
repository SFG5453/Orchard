# Orchard for Stream Deck and OpenDeck

The plugin supplies 15 actions: play/pause, previous, next, now playing, volume up,
volume down, mute, shuffle, repeat, like, seek back, seek forward, Best Mix, play
mix/playlist, and song request. Tacto uses the same actions through OpenDeck.

Start a desktop Orchard build with the [local API](../../docs/API.md). The plugin
reads its discovery file automatically and reconnects when Orchard restarts.
Use the action inspector's **Connection** section for a custom discovery path.

## Build and install

Node.js 20 or newer and Python 3 are required to package the plugin. OpenDeck also
needs Node.js installed to run it. Stream Deck supplies its Node.js runtime.

From the repository root:

```sh
python3 plugins/streamdeck/package.py
python3 plugins/streamdeck/package.py --install-opendeck
```

The first command creates `build/streamdeck/Orchard.streamDeckPlugin`, which can be
imported into OpenDeck or Stream Deck. The second also copies the plugin into the
local OpenDeck plugins directory. Restart OpenDeck after installation, then drag
actions from **Orchard** onto keys. Package contents include the runtime dependency;
users importing the archive do not need to run npm.

The runtime uses the Stream Deck WebSocket protocol. Windows/macOS Stream Deck
compatibility is provided by the manifest and protocol; Linux OpenDeck is the local
development target.

## Action settings

- **Play Mix / Playlist**: click **Load my mixes**, select a mix, and optionally
  enable shuffle. You can also paste any playlist browse ID.
- **Song Request**: enter a song search. A press searches and queues a matching
  song while skipping duplicates. **Play next** inserts it at the queue front.
- **Volume**: configure the step percentage. Volume actions also support dial
  rotation; pressing Mute restores the last volume remembered by the API.
- **Seek**: configure the distance in seconds.

Button backgrounds indicate playback, shuffle, repeat, like, and Best Mix state.
Now Playing shows title/artist and toggles playback when pressed. Actions display
an alert if Orchard is unavailable or rejects a command.
