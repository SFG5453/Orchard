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
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const WebSocket = require('ws');

const args = Object.fromEntries(process.argv.slice(2).reduce((pairs, value, index, all) => {
  if (value.startsWith('-')) pairs.push([value.replace(/^-+/, ''), all[index + 1]]);
  return pairs;
}, []));
if (!/^\d+$/.test(args.port || '') || !args.pluginUUID || !args.registerEvent) {
  console.error('Launch this plugin through Stream Deck or OpenDeck.');
  process.exit(1);
}
const deck = new WebSocket(`ws://127.0.0.1:${args.port}`);
const instances = new Map();
const requests = new Map();
let api;
let state;
let globalSettings = {};
let sequence = 0;
let retry;
let lastError = '';

function send(event, context, payload) {
  const item = instances.get(context);
  if (deck.readyState === WebSocket.OPEN) deck.send(JSON.stringify({ event, context, payload,
    ...(item ? { action: `dev.sfg.orchard.${item.action}` } : {}) }));
}
function log(message) {
  if (message === lastError) return;
  lastError = message;
  console.log(message);
}
function discovery() {
  const dataRoot = process.platform === 'win32' ? process.env.LOCALAPPDATA :
    process.platform === 'darwin' ? path.join(os.homedir(), 'Library', 'Application Support') :
    process.env.XDG_DATA_HOME || path.join(os.homedir(), '.local', 'share');
  const candidates = [path.join(dataRoot, 'SFG545', 'Orchard', 'api.json'), path.join(dataRoot, 'Orchard', 'api.json'),
    path.join(dataRoot, 'sfg.dev', 'Orchard', 'api.json')];
  const filename = globalSettings.discoveryFile || process.env.ORCHARD_API_FILE || candidates.find(file => fs.existsSync(file)) || candidates[0];
  const config = JSON.parse(fs.readFileSync(filename, 'utf8'));
  const endpoint = new URL(config.websocket);
  if (endpoint.protocol !== 'ws:' || endpoint.hostname !== '127.0.0.1' || !/^[a-f0-9]{64}$/.test(config.token))
    throw new Error('Invalid Orchard discovery file.');
  return config;
}
function connectApi() {
  clearTimeout(retry);
  if (api && api.readyState !== WebSocket.CLOSED) return;
  let config;
  try { config = discovery(); }
  catch { log('Waiting for Orchard. Start an API-enabled Orchard build.'); retry = setTimeout(connectApi, 2000); return; }
  const socket = new WebSocket(config.websocket, { handshakeTimeout: 5000, maxPayload: 8 * 1024 * 1024 });
  api = socket;
  socket.on('open', () => socket.send(JSON.stringify({ method: 'authenticate', token: config.token })));
  socket.on('error', () => log('Waiting for the Orchard API.'));
  socket.on('close', () => {
    if (api !== socket) return;
    state = undefined;
    for (const request of requests.values()) { clearTimeout(request.timer); request.reject(new Error('Orchard disconnected.')); }
    requests.clear();
    renderAll();
    retry = setTimeout(connectApi, 2000);
  });
  socket.on('message', (data) => {
    let message;
    try { message = JSON.parse(data); } catch { return; }
    if (message.event === 'ready' || message.event === 'state') {
      state = message.data;
      log('Connected to Orchard.');
      renderAll();
    } else if (requests.has(message.id)) {
      const request = requests.get(message.id);
      requests.delete(message.id);
      clearTimeout(request.timer);
      if (message.ok) request.resolve(message.result);
      else request.reject(new Error(message.error?.message || 'Orchard rejected the command.'));
    }
  });
}
function call(method, params = {}) {
  return new Promise((resolve, reject) => {
    if (!state || api?.readyState !== WebSocket.OPEN) { reject(new Error('Open Orchard to use this action.')); return; }
    const id = ++sequence;
    const timer = setTimeout(() => { requests.delete(id); reject(new Error('Orchard request timed out.')); }, 32000);
    requests.set(id, { resolve, reject, timer });
    api.send(JSON.stringify({ id, method, params }));
  });
}
function label(value, width = 15) {
  const text = String(value || '');
  return text.length > width ? text.slice(0, width - 1) + '…' : text;
}
const icons = new Map();
function escapeXml(value) {
  return String(value).replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' })[character]);
}
function icon(name, active, title) {
  if (!icons.has(name)) icons.set(name, fs.readFileSync(path.join(__dirname, 'icons', `${name}.svg`), 'utf8'));
  let drawing = icons.get(name).replace('#141414', active ? '#323232' : '#141414');
  if (title) {
    const lines = title.split('\n').slice(0, 2);
    drawing = drawing.replace('translate(40 40) scale(2.666667)', lines.length > 1 ? 'translate(52 17) scale(1.666667)' : 'translate(46 25) scale(2.166667)');
    const text = lines.map((line, index) => `<text x="72" y="${lines.length > 1 ? 105 + index * 22 : 120}">${escapeXml(line)}</text>`).join('');
    drawing = drawing.replace('</svg>', `<g fill="#f2f6f3" font-family="sans-serif" font-size="18" font-weight="500" text-anchor="middle">${text}</g></svg>`);
  }
  return 'data:image/svg+xml;base64,' + Buffer.from(drawing).toString('base64');
}
function render(context) {
  const item = instances.get(context);
  if (!item) return;
  const action = item.action;
  let title = '';
  let image = action;
  let active = false;
  if (!state) title = 'Open\nOrchard';
  else if (action === 'playpause') { image = state.playing ? 'pause' : 'playpause'; active = state.playing; }
  else if (action === 'nowplaying') {
    title = `${label(state.track?.title || 'Orchard')}\n${label(state.track?.artist || state.track?.artists?.join(', '))}`;
    active = state.playing;
  } else if (action === 'shuffle') active = state.shuffle;
  else if (action === 'repeat') { active = state.repeat !== 'off'; title = state.repeat === 'one' ? 'Repeat 1' : active ? 'Repeat all' : ''; }
  else if (action === 'mute') { active = state.volume === 0; title = active ? 'Muted' : `${Math.round(state.volume * 100)}%`; }
  else if (action === 'volumeup' || action === 'volumedown') title = `${Math.round(state.volume * 100)}%`;
  else if (action === 'seekforward' || action === 'seekback') title = `${action === 'seekback' ? '−' : '+'}${Math.min(600, Math.max(1, Number(item.settings.seconds) || 10))} s`;
  else if (action === 'like') active = state.liked && state.likeKnown;
  else if (action === 'bestmix') { active = state.mix?.bestMix?.sorted; title = state.mix?.bestMix?.busy ? 'Analyzing…' : ''; }
  else if (action === 'playmix') title = label(item.settings.mixTitle || 'Play mix');
  else if (action === 'request') title = label(item.settings.query || 'Song request');
  const rendered = JSON.stringify([title, image, active]);
  if (item.rendered === rendered) return;
  item.rendered = rendered;
  send('setTitle', context, { title: '', target: 0 });
  send('setImage', context, { image: icon(image, active, title), target: 0 });
}
function renderAll() { for (const context of instances.keys()) render(context); }
async function activate(context, ticks) {
  const item = instances.get(context);
  if (!item) return;
  const settings = item.settings;
  const step = Math.min(100, Math.max(1, Number(settings.step) || 5)) / 100;
  try {
    if (typeof ticks === 'number') await call('volume', { offset: Math.max(-1, Math.min(1, ticks * step)) });
    else {
      const action = item.action;
      const simple = { playpause: 'toggle', nowplaying: 'toggle', previous: 'previous', next: 'next',
        shuffle: 'shuffle', repeat: 'repeat', mute: 'mute', like: 'like', bestmix: 'mix.best' };
      if (simple[action]) await call(simple[action]);
      else if (action === 'volumeup' || action === 'volumedown') await call('volume', { offset: action === 'volumeup' ? step : -step });
      else if (action === 'seekforward' || action === 'seekback') {
        const seconds = Math.min(600, Math.max(1, Number(settings.seconds) || 10));
        await call('seek', { offset: action === 'seekforward' ? seconds : -seconds });
      } else if (action === 'playmix') await call('playlist.play', { id: settings.mixId || '', shuffle: Boolean(settings.shuffle) });
      else if (action === 'request') await call('request', { query: settings.query || '', next: Boolean(settings.next), deduplicate: true });
    }
  } catch (error) {
    send('showAlert', context, {});
    send('sendToPropertyInspector', context, { error: error.message });
    log(error.message);
  }
}
deck.on('open', () => {
  deck.send(JSON.stringify({ event: args.registerEvent, uuid: args.pluginUUID }));
  deck.send(JSON.stringify({ event: 'getGlobalSettings', context: args.pluginUUID }));
  connectApi();
});
deck.on('message', async (data) => {
  let message;
  try { message = JSON.parse(data); } catch { return; }
  const { event, context, payload = {} } = message;
  if (event === 'willAppear') {
    instances.set(context, { action: message.action.split('.').at(-1), settings: payload.settings || {} }); render(context);
  } else if (event === 'willDisappear') instances.delete(context);
  else if (event === 'didReceiveSettings') {
    const item = instances.get(context);
    if (item) { item.settings = payload.settings || {}; item.rendered = ''; render(context); }
  } else if (event === 'didReceiveGlobalSettings') {
    const changed = globalSettings.discoveryFile !== payload.settings?.discoveryFile;
    globalSettings = payload.settings || {};
    if (changed) api?.close();
  } else if (event === 'keyDown' || event === 'dialDown') await activate(context);
  else if (event === 'dialRotate') await activate(context, Number(payload.ticks) || 0);
  else if (event === 'sendToPlugin' && payload.command === 'mixes') {
    try { send('sendToPropertyInspector', context, { mixes: await call('mixes') }); }
    catch (error) { send('sendToPropertyInspector', context, { error: error.message }); }
  }
});
deck.on('error', (error) => console.error(error.message));
deck.on('close', () => { clearTimeout(retry); api?.close(); process.exit(0); });
