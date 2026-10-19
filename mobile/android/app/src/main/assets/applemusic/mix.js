'use strict';
// Adaptive mix into the spare deck. The app plans and renders the overlap from both songs'
// YouTube Music uploads and sends unprocessed reference clips; this page finds the same
// moments in Apple's audio by correlation and splices the render in on the mixer clock.
// Phases: parking -> armed -> starting -> placed (seam scheduled) -> rendering.
let mix = null;

// The incoming deck parks this far before its cue, then starts early by the headroom; its
// channel delay absorbs the lead so it meets the render's end exactly.
const INCOMING_LEAD = 3.8;
const INCOMING_HEADROOM = 0.3;
// The outgoing song is located this long before the seam, and again just before it.
const LOCATE_AHEAD = 1.2;
const REFINE_AHEAD = 0.25;
const JOIN_FADE = 0.02;

function mixTimer(callback, seconds) {
  mix.timers.push(setTimeout(callback, Math.max(0, seconds) * 1000));
}

// Audible song time of the current deck by its element clock.
function outgoingTime() {
  const deck = decks[current];
  return deck.api.time() - deck.channel.lag;
}

// Outgoing song time at which the incoming deck has to start.
function incomingStartTime() {
  return mix.outgoingStart - (mix.incomingCue - mix.parkedAt) - INCOMING_HEADROOM;
}

function reference(clip) {
  return { start: Number(clip && clip.start) || 0, data: pcm16(String((clip && clip.data) || '')) };
}

async function prepareMix(command) {
  dropMix();
  const id = String(command.id || '');
  const outgoing = decks[current];
  if (!id || !outgoing || !outgoing.api || !outgoing.api.id) return;
  fadeSeconds = Math.max(0, Number(command.fade) || 0);
  if (command.scan) {
    // Minutes of work; it runs beside the command queue so transport stays responsive.
    scanMix(id, command.scan);
    return;
  }
  await armPlan({
    id,
    outgoingStart: Number(command.outgoingStart),
    incomingCue: Number(command.incomingCue),
    render: mixer.buffer(String(command.render || '')),
    outgoing: reference(command.outgoing),
    incoming: reference(command.incoming),
  });
}

// Parks the incoming deck before its cue and arms the mix. `plan` holds the render as an
// AudioBuffer and mono reference clips ({start, data}) of both songs at MIX_RATE.
async function armPlan(plan) {
  const id = plan.id;
  if (!(plan.outgoingStart > 0) || !(plan.incomingCue >= 0) || !(plan.render.duration > 0) ||
      !plan.outgoing.data.length || !plan.incoming.data.length) {
    post({ type: 'mixFailed', id, reason: 'invalid plan' });
    await parkSpare(id, 0);
    scheduleFade();
    return;
  }
  mix = { ...plan, deck: spare, phase: 'parking', timers: [] };
  const target = mix;
  const parkedAt = await parkSpare(id, Math.max(0, plan.incomingCue - INCOMING_LEAD));
  if (mix !== target) return;
  if (parkedAt < 0 || parkedAt > plan.incomingCue) {
    abandonMix('the next song did not load');
    return;
  }
  mix.parkedAt = parkedAt;
  mix.phase = 'armed';
  post({ type: 'mixArmed', id, outgoingStart: plan.outgoingStart, duration: plan.render.duration });
  armMix();
}

// Waits for the moment to start the incoming deck; reruns whenever playback changes.
function armMix() {
  if (!mix || mix.phase !== 'armed') return;
  mix.timers.forEach(clearTimeout);
  mix.timers = [];
  if (!decks[current].api.playing) return;
  const late = outgoingTime() - incomingStartTime();
  if (late > INCOMING_HEADROOM - 0.1) {
    abandonMix('playback is past the mix point');
    return;
  }
  // The element clock can stall while buffering, so long waits check in again.
  if (late < -5) mixTimer(armMix, -late - 5);
  else mixTimer(startIncoming, -late);
}

async function startIncoming() {
  if (!mix || mix.phase !== 'armed') return;
  const late = outgoingTime() - incomingStartTime();
  if (late < -0.1) {
    armMix();
    return;
  }
  if (late > INCOMING_HEADROOM - 0.1) {
    abandonMix('playback is past the mix point');
    return;
  }
  mix.phase = 'starting';
  const target = mix;
  const incoming = decks[mix.deck];
  incoming.channel.level(0);
  incoming.channel.setDelay(0);
  try {
    await incoming.api.play();
  } catch (e) {
    if (mix === target) abandonMix('the next song did not start');
    return;
  }
  if (mix !== target) return;
  mixTimer(placeSeam, mix.outgoingStart - LOCATE_AHEAD - outgoingTime());
}

// How far the incoming deck runs ahead of the render's clock, from its monitored audio.
function measureIncoming() {
  const incoming = decks[mix.deck];
  const located = mixer.locate(incoming.channel, mix.incoming, incoming.api.time(), 0.3);
  if (!located) return;
  const before = located.time - incoming.channel.lag;
  mix.ahead = located.song - (mix.incomingCue + (before - mix.seam));
}

function scheduleSeam() {
  const seam = mix.seam;
  const end = seam + mix.render.duration;
  mixer.playRender(mix.render, seam);
  mixer.endRender(end, JOIN_FADE);
  const outgoing = decks[current].channel.gain.gain;
  outgoing.cancelScheduledValues(mixer.now);
  outgoing.setValueAtTime(1, mixer.now);
  outgoing.setValueAtTime(1, seam);
  outgoing.linearRampToValueAtTime(0, seam + 0.005);
  const incoming = decks[mix.deck].channel.gain.gain;
  incoming.cancelScheduledValues(mixer.now);
  incoming.setValueAtTime(0, mixer.now);
  incoming.setValueAtTime(0, end - JOIN_FADE);
  incoming.linearRampToValueAtTime(1, end);
}

function placeSeam() {
  if (!mix || mix.phase !== 'starting') return;
  const located = mixer.locate(decks[current].channel, mix.outgoing, decks[current].api.time(), 0.6);
  if (!located) {
    abandonMix('the current song does not match its YouTube Music upload');
    return;
  }
  mix.seam = located.time + (mix.outgoingStart - located.song);
  if (mix.seam - mixer.now < REFINE_AHEAD + 0.1) {
    abandonMix('playback is past the mix point');
    return;
  }
  mix.phase = 'placed';
  note('mix: seam in ' + ((mix.seam - mixer.now) * 1000).toFixed(0) + ' ms');
  scheduleSeam();
  measureIncoming();
  const measure = () => {
    if (!mix || mix.phase !== 'placed') return;
    measureIncoming();
  };
  mixTimer(measure, 0.2);
  mixTimer(measure, 0.4);
  mixTimer(refineSeam, mix.seam - REFINE_AHEAD - mixer.now);
}

// Locates the outgoing song again just before the seam, then commits the incoming delay.
function refineSeam() {
  if (!mix || mix.phase !== 'placed') return;
  const located = mixer.locate(decks[current].channel, mix.outgoing, decks[current].api.time(), 0.4);
  if (located) {
    const again = located.time + (mix.outgoingStart - located.song);
    note('mix: seam moves ' + ((again - mix.seam) * 1000).toFixed(2) + ' ms');
    if (Math.abs(again - mix.seam) > 0.0005 && again - mixer.now > 0.03) {
      if (mix.ahead !== undefined) mix.ahead += again - mix.seam;
      mix.seam = again;
      scheduleSeam();
    }
  }
  if (mix.ahead === undefined || mix.ahead < 0 || mix.ahead > 1.9) {
    abandonMix('the next song could not be lined up');
    return;
  }
  note('mix: incoming delayed ' + (mix.ahead * 1000).toFixed(2) + ' ms');
  decks[mix.deck].channel.setDelay(mix.ahead);
  mixTimer(beginRender, mix.seam - mixer.now);
}

function beginRender() {
  if (!mix || mix.phase !== 'placed') return;
  mix.phase = 'rendering';
  const incoming = decks[mix.deck].api;
  post({ type: 'mixing', id: mix.id, seconds: mix.render.duration, duration: incoming.state().duration,
         at: mix.incomingCue, from: mix.outgoingStart });
  mixTimer(finishMix, mix.seam + mix.render.duration + 0.03 - mixer.now);
}

function finishMix() {
  if (!mix) return;
  const { deck: deckName, incoming: clip, incomingCue, seam } = mix;
  mix.timers.forEach(clearTimeout);
  mix = null;
  mixer.render = null;
  const incoming = decks[deckName];
  swapTo(deckName, Math.max(0, incoming.api.time() - incoming.channel.lag));
  // Diagnostics: where the audible incoming song sits against the render's clock.
  setTimeout(() => {
    const located = mixer.locate(incoming.channel, clip, incoming.api.time(), 0.3);
    if (located) note('mix: join off by ' + ((located.song - (incomingCue + located.time - seam)) * 1000).toFixed(2) + ' ms');
  }, 500);
}

// After a held render the incoming deck restarted with its own latency; measure it again
// while it is still silent.
function resumeMix() {
  if (!mix || mix.phase !== 'rendering') return;
  const incoming = decks[mix.deck];
  const end = mix.seam + mix.render.duration;
  incoming.channel.blocks = [];
  const realign = () => {
    if (!mix || mixer.now > end - 0.1) return;
    mix.ahead = undefined;
    measureIncoming();
    if (mix.ahead !== undefined && mix.ahead >= 0 && mix.ahead <= 1.9) incoming.channel.setDelay(mix.ahead);
  };
  mixTimer(realign, 0.4);
  mixTimer(realign, 0.7);
  mixTimer(finishMix, end + 0.03 - mixer.now);
}

// Pausing before the seam puts the incoming deck back a second and re-arms the mix.
async function rewindMix() {
  if (!mix || (mix.phase !== 'starting' && mix.phase !== 'placed')) return;
  mix.timers.forEach(clearTimeout);
  mix.timers = [];
  mixer.stopRender();
  decks[current].channel.level(1);
  const incoming = decks[mix.deck];
  incoming.channel.level(0);
  incoming.channel.setDelay(0);
  delete mix.seam;
  delete mix.ahead;
  incoming.api.pause();
  const at = Math.max(0, incoming.api.time() - 1);
  await incoming.api.seek(at);
  mix.parkedAt = at;
  mix.phase = 'armed';
}

function mixRunning() {
  return !!mix && mix.seam !== undefined && mixer.now >= mix.seam - 0.01;
}

// Clears the mix without a fallback; the caller decides what the spare deck does next.
function dropMix() {
  cancelScan();
  if (!mix) return;
  const dropped = mix;
  mix = null;
  dropped.timers.forEach(clearTimeout);
  mixer.stopRender();
  if (decks[current] && decks[current].channel) decks[current].channel.level(1);
  const incoming = decks[dropped.deck];
  if (incoming && incoming.channel && dropped.deck !== current) {
    incoming.channel.level(0);
    if (dropped.phase === 'starting' || dropped.phase === 'placed') incoming.api.pause();
  }
}

// Gives up on the mix and parks the next song at its start for the crossfade or gapless start.
function abandonMix(reason) {
  if (!mix) return;
  const id = mix.id;
  dropMix();
  fallBack(id, reason);
}

// Reports a mix that will not happen and parks `id` for the crossfade or gapless start.
function fallBack(id, reason) {
  note('mix: abandoned, ' + reason);
  post({ type: 'mixFailed', id, reason });
  queue = queue.then(async () => {
    const holder = decks[spare] && decks[spare].api;
    if (mix || scan || !holder || (holder.id && holder.id !== id)) return;
    await parkSpare(id, 0);
    scheduleFade();
  }).catch(() => {});
}

// Seeks, skips and loads during the render jump to the incoming song; earlier ones re-plan.
function interruptMix() {
  if (!mix) return;
  if (mixRunning()) {
    mixer.stopRender();
    decks[mix.deck].channel.level(1);
    finishMix();
  } else if (mix.phase === 'starting' || mix.phase === 'placed') {
    abandonMix('playback changed before the mix');
  }
}
