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
let socket;
let context;
let plugin;
let action;
let settings = {};
let globalSettings = {};
const element = id => document.getElementById(id);
function send(event, payload, target = context) {
  if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ event, context: target, action, payload }));
}
function showMixes(value) {
  const picker = element('mixPicker');
  picker.replaceChildren(new Option('Choose a mix', ''));
  const seen = new Set();
  function visit(node) {
    if (!node || typeof node !== 'object') return;
    if (node.type === 'playlist' || node.type === 'mix' || node.type === 'station') {
      const id = node.browseId || node.browsePayload?.browseId || node.id;
      if (id && !seen.has(id)) { picker.add(new Option(node.title || id, id)); seen.add(id); }
    }
    for (const child of Object.values(node)) if (typeof child === 'object') visit(child);
  }
  visit(value);
  element('status').textContent = seen.size ? '' : 'No mixes returned. You can paste a playlist ID above.';
}
function connectElgatoStreamDeckSocket(port, uuid, registerEvent, info, actionInfo) {
  const details = JSON.parse(actionInfo);
  context = details.context;
  action = details.action;
  plugin = JSON.parse(info).plugin?.uuid || 'dev.sfg.orchard';
  settings = details.payload.settings || {};
  const name = action.split('.').at(-1);
  element('volume').hidden = !['volumeup', 'volumedown', 'mute'].includes(name);
  element('seek').hidden = !['seekback', 'seekforward'].includes(name);
  element('mix').hidden = name !== 'playmix';
  element('request').hidden = name !== 'request';
  for (const key of ['step', 'seconds', 'mixId', 'mixTitle', 'query']) element(key).value = settings[key] ?? element(key).value;
  for (const key of ['shuffle', 'next']) element(key).checked = Boolean(settings[key]);
  socket = new WebSocket(`ws://127.0.0.1:${port}`);
  socket.onopen = () => {
    socket.send(JSON.stringify({ event: registerEvent, uuid }));
    send('getGlobalSettings', {}, plugin);
  };
  socket.onmessage = event => {
    const message = JSON.parse(event.data);
    if (message.event === 'didReceiveGlobalSettings') {
      globalSettings = message.payload.settings || {};
      element('discoveryFile').value = globalSettings.discoveryFile || '';
    }
    if (message.event === 'sendToPropertyInspector') {
      if (message.payload.error) element('status').textContent = message.payload.error;
      if (message.payload.mixes) showMixes(message.payload.mixes);
    }
  };
}
function save() {
  for (const key of ['step', 'seconds', 'mixId', 'mixTitle', 'query']) settings[key] = element(key).value;
  for (const key of ['shuffle', 'next']) settings[key] = element(key).checked;
  send('setSettings', settings);
}
for (const id of ['step', 'seconds', 'mixId', 'mixTitle', 'query', 'shuffle', 'next']) element(id).addEventListener('change', save);
element('loadMixes').onclick = () => { element('status').textContent = 'Loading mixes…'; send('sendToPlugin', { command: 'mixes' }); };
element('mixPicker').onchange = () => {
  element('mixId').value = element('mixPicker').value;
  element('mixTitle').value = element('mixPicker').selectedOptions[0].text;
  save();
};
element('saveConnection').onclick = () => {
  globalSettings.discoveryFile = element('discoveryFile').value.trim();
  send('setGlobalSettings', globalSettings, plugin);
  element('status').textContent = 'Connection saved.';
};
