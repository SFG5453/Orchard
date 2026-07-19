// Self-contained pairing page served to LAN clients; it must not depend on packaged renderer assets.
export const connectHtml = `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Orchard Connect</title>
  <link rel="stylesheet" href="/connect/style.css">
</head>
<body>
  <main class="connect-shell">
    <section id="pairing" class="pairing">
      <img src="" alt="" id="artwork" class="artwork">
      <div>
        <h1>Orchard Connect</h1>
        <p id="status">Waiting for desktop approval</p>
        <div class="handoff">
          <a id="open-app" class="button-link" href="#">Open app</a>
          <button type="button" id="browser-pair">Use browser</button>
        </div>
      </div>
    </section>

    <section class="now">
      <div>
        <p id="title" class="track-title">Nothing playing</p>
        <p id="artist" class="track-artist">Open Orchard on your desktop.</p>
      </div>
      <button type="button" id="play" class="primary">Play</button>
    </section>

    <section class="timeline">
      <input id="seek" type="range" min="0" max="1" step="1" value="0">
      <div><span id="elapsed">0:00</span><span id="duration">0:00</span></div>
    </section>

    <section class="controls" aria-label="Playback controls">
      <button type="button" data-command="previous">Previous</button>
      <button type="button" data-command="next">Next</button>
      <label>Volume <input id="volume" type="range" min="0" max="1" step="0.01" value="0.85"></label>
    </section>

    <section class="lyrics">
      <h2>Lyrics</h2>
      <div id="lyrics"></div>
    </section>

    <section class="queue">
      <h2>Queue</h2>
      <div id="queue"></div>
    </section>

    <section class="search">
      <h2>Search</h2>
      <form id="search-form">
        <input id="search" type="search" placeholder="Find songs" autocomplete="off">
        <button type="submit">Search</button>
      </form>
      <div id="results"></div>
    </section>
  </main>
  <script src="/socket.io/socket.io.js"></script>
  <script src="/connect/client.js"></script>
</body>
</html>`;
export const connectCss = `
:root {
  color-scheme: dark;
  background: #090c0a;
  color: #eef2ef;
  font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, sans-serif;
}

* {
  box-sizing: border-box;
}

body {
  margin: 0;
  background: #090c0a;
}

button,
input {
  font: inherit;
}

.connect-shell {
  display: grid;
  max-width: 560px;
  gap: 18px;
  margin: 0 auto;
  padding: 18px;
}

section {
  border: 1px solid rgba(255, 255, 255, 0.09);
  border-radius: 8px;
  background: #0d110e;
}

.pairing,
.now {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 13px;
  padding: 13px;
}

.now {
  grid-template-columns: minmax(0, 1fr) auto;
}

.artwork {
  width: 58px;
  height: 58px;
  border-radius: 6px;
  background: #171c18;
  object-fit: cover;
}

h1,
h2,
p {
  margin: 0;
}

h1 {
  font-size: 18px;
}

h2 {
  padding: 12px 13px 0;
  font-size: 13px;
}

#status,
.track-artist,
.meta,
.lyric-line {
  color: #8d968f;
}

.track-title {
  overflow: hidden;
  font-size: 16px;
  font-weight: 720;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.track-artist {
  overflow: hidden;
  margin-top: 3px;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

button {
  min-height: 36px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 6px;
  background: #121713;
  color: #e6ebe8;
}

button:active {
  background: #182019;
}

.button-link {
  display: inline-flex;
  min-height: 36px;
  align-items: center;
  justify-content: center;
  padding: 0 12px;
  border: 1px solid rgba(87, 218, 137, 0.42);
  border-radius: 6px;
  background: #17331f;
  color: #e6ebe8;
  text-decoration: none;
}

.primary {
  min-width: 74px;
  border-color: rgba(87, 218, 137, 0.42);
  background: #17331f;
}

.timeline,
.controls,
.lyrics,
.queue,
.search {
  padding: 13px;
}

.timeline input,
#volume,
#search {
  width: 100%;
}

.timeline div,
.queue-row,
.result-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.timeline div {
  margin-top: 4px;
  color: #8d968f;
  font-size: 11px;
}

.controls {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.handoff {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.controls label {
  grid-column: 1 / -1;
  display: grid;
  gap: 7px;
  color: #aeb6b0;
  font-size: 12px;
}

#lyrics,
#queue,
#results {
  display: grid;
  gap: 8px;
  padding-top: 11px;
}

.lyric-line {
  line-height: 1.45;
}

.lyric-line.active {
  color: #f1f5f2;
  font-weight: 720;
}

.queue-row,
.result-row {
  min-height: 48px;
  padding: 8px 0;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.queue-row button,
.result-row button {
  min-height: 30px;
  padding: 0 10px;
}

.row-actions {
  display: flex;
  flex-shrink: 0;
  gap: 6px;
}

.item-copy {
  min-width: 0;
}

.item-copy strong,
.item-copy span {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.item-copy span {
  margin-top: 2px;
  color: #8d968f;
  font-size: 11px;
}

form {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  margin-top: 11px;
}

#search {
  min-height: 36px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 6px;
  background: #090c0a;
  color: #f1f5f2;
  padding: 0 10px;
}
`;

export const connectClientJs = `
const socket = io({ transports: ['websocket'] });
const qs = new URLSearchParams(location.search), token = qs.get('token') || '';
const appUrl = 'orchard-connect://pair?server=' + encodeURIComponent(location.origin) + '&token=' + encodeURIComponent(token);
const state = { approved: false, browserPairing: !token || qs.get('browser') === '1', latest: null };
const els = {
  browserPair: document.getElementById('browser-pair'),
  openApp: document.getElementById('open-app'),
  artwork: document.getElementById('artwork'),
  status: document.getElementById('status'),
  title: document.getElementById('title'),
  artist: document.getElementById('artist'),
  play: document.getElementById('play'),
  seek: document.getElementById('seek'),
  elapsed: document.getElementById('elapsed'),
  duration: document.getElementById('duration'),
  volume: document.getElementById('volume'),
  lyrics: document.getElementById('lyrics'),
  queue: document.getElementById('queue'),
  results: document.getElementById('results'),
  search: document.getElementById('search'),
  searchForm: document.getElementById('search-form')
};

els.openApp.href = appUrl;
els.openApp.hidden = !token;
els.browserPair.hidden = !token;
els.openApp.parentElement.hidden = !token;

function deviceName() {
  const platform = navigator.userAgentData?.platform || navigator.platform || 'Phone';
  return 'Phone - ' + platform;
}

function fmt(seconds) {
  const value = Math.max(0, Math.floor(Number(seconds) || 0));
  return Math.floor(value / 60) + ':' + String(value % 60).padStart(2, '0');
}

function send(type, value) {
  if (!state.approved) return;
  socket.emit('connect:command', { type, value });
}

function activeLyric(lines = [], currentTime = 0) {
  let index = -1;
  lines.forEach((line, lineIndex) => {
    if (typeof line.startTime === 'number' && line.startTime <= currentTime + 0.12) index = lineIndex;
  });
  return index;
}

function renderLyrics(snapshot) {
  const lyrics = snapshot.lyrics || {};
  const lines = Array.isArray(lyrics.lines) ? lyrics.lines.slice(0, 80) : [];
  const active = lyrics.mode === 'synced' ? activeLyric(lines, snapshot.playback?.currentTime) : -1;
  els.lyrics.innerHTML = '';
  if (!lines.length) {
    els.lyrics.innerHTML = '<p class="meta">No Lyrics :/</p>';
    return;
  }
  lines.slice(Math.max(0, active - 3), active >= 0 ? active + 7 : 10).forEach((line, offset) => {
    const row = document.createElement('p');
    row.className = 'lyric-line' + (active >= 0 && Math.max(0, active - 3) + offset === active ? ' active' : '');
    row.textContent = line.text || '';
    els.lyrics.appendChild(row);
  });
}

function renderQueue(queue = []) {
  els.queue.innerHTML = '';
  if (!queue.length) {
    els.queue.innerHTML = '<p class="meta">Queue is empty.</p>';
    return;
  }
  queue.slice(0, 20).forEach((track, index) => {
    const row = document.createElement('div');
    row.className = 'queue-row';
    row.innerHTML = '<div class="item-copy"><strong></strong><span></span></div><div class="row-actions"><button type="button" data-action="play">Play</button><button type="button" data-action="remove">Remove</button></div>';
    row.querySelector('strong').textContent = track.title || 'Untitled';
    row.querySelector('span').textContent = track.artist || track.subtitle || '';
    row.querySelector('[data-action="play"]').addEventListener('click', () => send('play-queue-index', index));
    row.querySelector('[data-action="remove"]').addEventListener('click', () => send('remove-queue-index', index));
    els.queue.appendChild(row);
  });
}

function renderState(snapshot) {
  state.latest = snapshot;
  const track = snapshot.track || {};
  const playback = snapshot.playback || {};
  els.title.textContent = track.title || 'Nothing playing';
  els.artist.textContent = track.artist || 'Open Orchard on your desktop.';
  els.artwork.src = track.artwork || '';
  els.artwork.style.visibility = track.artwork ? 'visible' : 'hidden';
  els.play.textContent = playback.isPlaying ? 'Pause' : 'Play';
  els.seek.max = Math.max(1, Math.floor(playback.duration || 1));
  els.seek.value = Math.max(0, Math.floor(playback.currentTime || 0));
  els.elapsed.textContent = fmt(playback.currentTime);
  els.duration.textContent = fmt(playback.duration);
  els.volume.value = Math.max(0, Math.min(1, Number(playback.volume) || 0));
  renderLyrics(snapshot);
  renderQueue(snapshot.queue || []);
}

function renderResults(items = []) {
  els.results.innerHTML = '';
  if (!items.length) {
    els.results.innerHTML = '<p class="meta">No songs found.</p>';
    return;
  }
  items.slice(0, 12).forEach((track) => {
    const row = document.createElement('div');
    row.className = 'result-row';
    row.innerHTML = '<div class="item-copy"><strong></strong><span></span></div><button type="button">Play</button>';
    row.querySelector('strong').textContent = track.title || 'Untitled';
    row.querySelector('span').textContent = track.artist || track.subtitle || '';
    row.querySelector('button').addEventListener('click', () => send('play-track', track));
    els.results.appendChild(row);
  });
}

function readReply(response = {}) {
  return response.data || response;
}

function requestPairing() {
  els.status.textContent = 'Waiting for desktop approval';
  socket.emit('connect:hello', {
    token,
    deviceToken: localStorage.getItem('orchard-connect-token') || '',
    name: deviceName()
  }, (response = {}) => {
    const payload = readReply(response);
    if (payload.status === 'approved') {
      state.approved = true;
      els.status.textContent = 'Connected';
      renderState(payload.state || {});
    } else if (payload.status === 'expired') {
      els.status.textContent = 'Pairing link expired. Refresh the QR code in Orchard.';
    } else if (payload.status === 'pending') {
      els.status.textContent = 'Approve this device in Orchard.';
    }
  });
}

socket.on('connect', () => {
  if (state.browserPairing) requestPairing();
  else els.status.textContent = 'Open Orchard Connect to pair.';
});

socket.on('connect:approved', ({ deviceToken, state: snapshot }) => {
  localStorage.setItem('orchard-connect-token', deviceToken);
  state.approved = true;
  els.status.textContent = 'Connected';
  renderState(snapshot || {});
});

socket.on('connect:state', renderState);
socket.on('connect:rejected', () => { els.status.textContent = 'Pairing rejected'; });
socket.on('connect:revoked', () => {
  localStorage.removeItem('orchard-connect-token');
  state.approved = false;
  els.status.textContent = 'Access revoked';
});
socket.on('connect:search-results', ({ results = [] } = {}) => renderResults(results));
socket.on('disconnect', () => {
  state.approved = false;
  els.status.textContent = 'Disconnected';
});

els.play.addEventListener('click', () => send('play-pause'));
els.browserPair.addEventListener('click', () => {
  state.browserPairing = true;
  requestPairing();
});
document.querySelector('[data-command="previous"]').addEventListener('click', () => send('previous'));
document.querySelector('[data-command="next"]').addEventListener('click', () => send('next'));
els.volume.addEventListener('input', () => send('volume', Number(els.volume.value)));
els.seek.addEventListener('change', () => send('seek', Number(els.seek.value)));
els.searchForm.addEventListener('submit', (event) => {
  event.preventDefault();
  const query = els.search.value.trim();
  if (!query || !state.approved) return;
  els.results.innerHTML = '<p class="meta">Searching...</p>';
  socket.emit('connect:search', { query, requestId: String(Date.now()) });
});
`;
