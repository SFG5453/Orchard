'use strict';
// Host protocol: the app calls orchardPlayer.command({type, ...}); events go to
// window.orchardAppleMusic.postMessage(json). Decrypted audio never leaves this page.
const outbox = [];
function post(message) {
  const text = JSON.stringify(message);
  if (window.orchardAppleMusic) window.orchardAppleMusic.postMessage(text);
  else outbox.push(text);
}
window.addEventListener('orchardbridge', () => {
  while (outbox.length && window.orchardAppleMusic) window.orchardAppleMusic.postMessage(outbox.shift());
});

const mixer = new Mixer();
const decks = {};
let current = 'a';
let spare = 'b';
let developerToken = '';
let bitrate = 'high';
let ticker = 0;
// MusicKit passes through several states while a song loads; report once it settles.
let loadingSong = false;
// Crossfade length into the prepared song; 0 starts it gaplessly when the current one ends.
let fadeSeconds = 0;
let fadeTimer = 0;
// {outgoing, incoming, end, timer} while both decks play; `end` is mixer time.
let fading = null;
// A transition paused mid-way: both decks and the mixer clock are stopped together.
let held = false;

function errorCode(error) {
  const code = String((error && (error.errorCode || error.name)) || '');
  if (/UNAUTHORIZED|AUTHORIZATION|USER_TOKEN|TOKEN_EXPIRED/i.test(code)) return 'unauthorized';
  if (/SUBSCRIPTION/i.test(code)) return 'subscription';
  if (/CONTENT_(RESTRICTED|UNAVAILABLE|UNSUPPORTED)|NOT_FOUND|UNSUPPORTED_ERROR/i.test(code)) return 'unavailable';
  if (/DRM|KEY_SYSTEM|MEDIA_KEY|WIDEVINE/i.test(code)) return 'drm';
  if (/NETWORK/i.test(code)) return 'network';
  return 'playback';
}

// One line for the app's log; mixes report their placement this way.
function note(text) {
  post({ type: 'log', text: String(text).slice(0, 500) });
}

function report(error, id) {
  const code = errorCode(error);
  post({ type: 'error', id: id || '', code, detail: String((error && (error.message || error.description || error.errorCode)) || error || '') });
}

function sendState() {
  const deck = decks[current];
  if (loadingSong || !deck || !deck.api || !deck.api.id) return;
  const state = deck.api.state();
  // A deck aligned into a mix plays behind its element clock by its channel delay.
  state.position = Math.max(0, state.position - deck.channel.lag);
  post({ type: 'state', ...state });
}

// Starts the crossfade once the playing song is within fadeSeconds of its end.
function scheduleFade() {
  clearTimeout(fadeTimer);
  fadeTimer = 0;
  const now = decks[current];
  const next = decks[spare];
  if (mix || fading || fadeSeconds <= 0 || !now || !now.api || !next || !next.api || !next.ready || !next.api.parked) return;
  const remaining = now.api.remaining();
  if (remaining < 0) return;
  if (remaining <= fadeSeconds + 0.02) startFade(remaining);
  else fadeTimer = setTimeout(scheduleFade, Math.min(5000, (remaining - fadeSeconds) * 1000));
}

function startFade(remaining) {
  const seconds = Math.max(0.05, Math.min(fadeSeconds, remaining));
  fading = { outgoing: current, incoming: spare, end: mixer.now + seconds,
             timer: setTimeout(completeFade, seconds * 1000 + 50) };
  const incoming = decks[spare];
  incoming.channel.level(0);
  incoming.channel.fade(1, seconds);
  incoming.api.play().catch((e) => report(e, incoming.api.id));
  decks[current].channel.fade(0, seconds);
  post({ type: 'fading', id: incoming.api.id, seconds, duration: incoming.api.state().duration });
}

// The incoming deck becomes current; the app hears about it as a gapless advance.
function swapTo(incoming, position = 0) {
  const outgoing = incoming === current ? spare : current;
  current = incoming;
  spare = outgoing;
  decks[incoming].ready = false;
  decks[outgoing].ready = false;
  decks[incoming].channel.level(1);
  decks[outgoing].channel.level(0);
  decks[outgoing].api.stop().catch(() => {});
  post({ type: 'advanced', id: decks[incoming].api.id, position });
  sendState();
  tick();
}

function completeFade() {
  if (!fading) return;
  const { incoming, timer } = fading;
  fading = null;
  clearTimeout(timer);
  swapTo(incoming);
}

// Pausing mid-transition freezes the mixer with both decks, so the blend resumes where it stopped.
async function holdTransition() {
  const incoming = fading ? decks[fading.incoming] : mixRunning() ? decks[mix.deck] : null;
  if (!incoming || held) return false;
  held = true;
  if (fading) clearTimeout(fading.timer);
  // The seam has passed even if its timer has not fired yet.
  if (mix && mix.phase === 'placed') beginRender();
  if (mix) mix.timers.forEach(clearTimeout);
  decks[current].api.pause();
  incoming.api.pause();
  await mixer.context.suspend();
  return true;
}

async function releaseTransition() {
  if (!held) return false;
  held = false;
  const incoming = fading ? decks[fading.incoming] : mix ? decks[mix.deck] : null;
  await mixer.context.resume();
  await Promise.all([decks[current].api.play(), incoming ? incoming.api.play() : null]);
  if (fading) fading.timer = setTimeout(completeFade, Math.max(0, fading.end - mixer.now) * 1000 + 50);
  if (mix) resumeMix();
  return true;
}

function tick() {
  const deck = decks[current];
  const playing = deck && deck.api && deck.api.state().playing;
  if (playing && !ticker) ticker = setInterval(sendState, 500);
  if (!playing && ticker) { clearInterval(ticker); ticker = 0; }
}

window.orchardDeckHost = {
  attached(name) { decks[name].attach(); },
  changed(name) {
    if (name !== current) return;
    sendState();
    tick();
    scheduleFade();
    armMix();
  },
  ended(name) {
    if (name !== current) return;
    if (fading) {
      completeFade();
      return;
    }
    // The render already covers the rest of this song.
    if (mixRunning()) return;
    if (mix) abandonMix('the song ended first');
    const next = decks[spare];
    const id = decks[name].api.id;
    // Gapless: the spare deck already holds the next song, paused at its start.
    if (next && next.api && next.api.parked && next.ready) {
      next.api.play().catch((e) => report(e, next.api.id));
      swapTo(spare);
      return;
    }
    post({ type: 'ended', id });
    tick();
  },
  failed(name, error) {
    if (name === current) report(error, decks[name].api.id);
    else if (decks[name]) {
      decks[name].ready = false;
      if (mix && mix.deck === name) abandonMix('the next song failed to load');
    }
  },
  log(name, text) { note('deck ' + name + ': ' + text); },
};

function deck(name) {
  if (decks[name]) return decks[name].booted;
  const entry = { api: null, channel: null, ready: false };
  decks[name] = entry;
  entry.booted = new Promise((resolve, reject) => {
    entry.attach = () => {
      entry.api = frame.contentWindow.deck;
      mixer.channel(entry.api.stream)
        .then((channel) => { entry.channel = channel; return entry.api.boot(developerToken, bitrate); })
        .then(resolve, reject);
    };
    const frame = document.createElement('iframe');
    entry.frame = frame;
    frame.name = name;
    frame.hidden = true;
    frame.allow = 'encrypted-media; autoplay';
    frame.srcdoc = deckSource();
    document.body.appendChild(frame);
  });
  entry.booted.catch(() => { delete decks[name]; });
  return entry.booted;
}

// Removes a deck and its MusicKit instance; the scan deck only lives for one scan.
function dropDeck(name) {
  const entry = decks[name];
  if (!entry) return;
  delete decks[name];
  if (entry.api) entry.api.stop().catch(() => {});
  if (entry.channel) entry.channel.release();
  if (entry.frame) entry.frame.remove();
}

// Parks the spare deck on `id` at `at` seconds, silent and without a delay.
async function parkSpare(id, at) {
  await scanStopped;
  await deck(spare);
  const next = decks[spare];
  next.ready = false;
  next.channel.level(0);
  next.channel.setDelay(0);
  const parkedAt = await next.api.park(id, at);
  if (next.api.id !== id) return -1;
  next.ready = true;
  return parkedAt;
}

async function handle(command) {
  const active = () => decks[current] && decks[current].api;
  switch (command.type) {
    case 'configure': {
      developerToken = String(command.developerToken || '');
      bitrate = command.bitrate === 'standard' ? 'standard' : 'high';
      const authorized = await deck(current);
      post({ type: 'ready', authorized });
      return;
    }
    case 'load': {
      await deck(current);
      dropMix();
      const id = String(command.id || '');
      const next = decks[spare];
      const position = Math.max(0, Number(command.position) || 0);
      loadingSong = true;
      try {
        if (next && next.api && next.ready && next.api.id === id && command.play && position === 0 && next.api.time() < 0.5) {
          [current, spare] = [spare, current];
          await decks[spare].api.stop();
          decks[spare].channel.level(0);
          decks[current].channel.level(1);
          await next.api.play();
        } else {
          decks[current].channel.level(1);
          decks[current].channel.setDelay(0);
          await active().load(id, position, !!command.play);
        }
      } finally {
        loadingSong = false;
      }
      sendState();
      tick();
      return;
    }
    case 'prepare': {
      const id = String(command.id || '');
      const next = decks[spare];
      dropMix();
      fadeSeconds = id ? Math.max(0, Number(command.fade) || 0) : 0;
      if (!id) {
        if (next && next.api) { next.ready = false; await next.api.stop(); }
        return;
      }
      if (next && next.api && next.api.id === id && next.ready && next.api.time() < 0.5) {
        scheduleFade();
        return;
      }
      await parkSpare(id, 0);
      scheduleFade();
      return;
    }
    case 'mix':
      await prepareMix(command);
      return;
    case 'play':
      if (!(await releaseTransition()) && active()) await active().play();
      break;
    case 'pause':
      if (await holdTransition()) break;
      await rewindMix();
      if (active()) active().pause();
      break;
    case 'seek': if (active()) await active().seek(Math.max(0, Number(command.position) || 0)); break;
    case 'stop':
      dropMix();
      for (const name of Object.keys(decks)) {
        if (!decks[name].api) continue;
        decks[name].ready = false;
        await decks[name].api.stop();
      }
      break;
    case 'bitrate':
      bitrate = command.value === 'standard' ? 'standard' : 'high';
      for (const name of Object.keys(decks)) if (decks[name].api) decks[name].api.setBitrate(bitrate);
      return;
    default:
      return;
  }
  sendState();
  tick();
  scheduleFade();
  armMix();
}

// Commands run in order; volume skips the line so a drag stays smooth while a song loads.
let queue = Promise.resolve();
window.orchardPlayer = {
  command(command) {
    if (!command || typeof command !== 'object') return;
    if (command.type === 'volume') {
      mixer.setVolume(Math.min(1, Math.max(0, Number(command.value) || 0)));
      return;
    }
    // Seeks, skips and loads settle a running transition onto the incoming song first; pause holds it.
    if (!['bitrate', 'mix', 'pause', 'play'].includes(command.type)) {
      held = false;
      if (mixer.context.state === 'suspended') mixer.context.resume();
      if (fading) completeFade();
      interruptMix();
    }
    queue = queue.then(() => handle(command)).catch((error) => {
      if (command.type === 'prepare' || command.type === 'mix') return;
      report(error, command.type === 'load' ? command.id : (decks[current] && decks[current].api && decks[current].api.id));
    });
  },
};
