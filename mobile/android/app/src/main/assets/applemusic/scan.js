'use strict';
// Scan fallback for a pair with no YouTube Music upload to plan from. Both songs play muted
// at 2x into the mix engine for analysis (Apple counts these as plays), the blend spans are
// captured again at 1x and spliced in, since 2x loses everything above about 11 kHz, and the
// result plays through the same exact-seam path as a planned mix (mix.js).
let scan = null;

const SCAN_RATE = 2;
// Deck contexts run at MIX_RATE, so a 2x capture holds the song at half that rate.
const SCAN_SONG_RATE = MIX_RATE / SCAN_RATE;
// Seconds a scan needs beyond playing the longer song at 2x (two plans, the 1x spans), plus
// room for a mix that starts up to a minute before the end.
const SCAN_OVERHEAD = 80;
const SCAN_DECK = 's';
// Settles once a cancelled scan's spare deck has stopped, so parking it again cannot race.
let scanStopped = Promise.resolve();

function cancelScan() {
  if (!scan) return;
  scan.cancelled = true;
  scan = null;
  if (decks[SCAN_DECK]) dropDeck(SCAN_DECK);
  if (decks[spare] && decks[spare].api) scanStopped = decks[spare].api.stop().catch(() => {});
}

// Streams one 2x pass of `songId` on `deckName` into a song for the engine.
async function scanSong(engine, deckName, songId, windows) {
  const builder = engine.song(SCAN_SONG_RATE, windows);
  let padded = false;
  try {
    await decks[deckName].api.scan(songId, SCAN_RATE, (data, start) => {
      // Capture starts a moment into the song; silence keeps the analysis on its timeline.
      if (!padded) {
        padded = true;
        const frames = Math.round(Math.max(0, start || 0) * SCAN_SONG_RATE);
        if (frames > 0) builder.push(new Float32Array(frames * 2));
      }
      builder.push(data);
    });
    return builder.finish();
  } catch (e) {
    builder.drop();
    throw e;
  }
}

// One 1x clip: interleaved stereo at MIX_RATE and the element-clock time of its first sample.
async function captureClip(deckName, songId, from, seconds) {
  const parts = [];
  const start = await decks[deckName].api.clip(songId, Math.max(0, from), seconds, (data) => parts.push(data));
  const data = new Float32Array(parts.reduce((total, part) => total + part.length, 0));
  let offset = 0;
  for (const part of parts) { data.set(part, offset); offset += part.length; }
  if (start < 0 || data.length < MIX_RATE) throw new Error('a 1x clip came back empty');
  return { start, data };
}

function monoOf(stereo) {
  const out = new Float32Array(stereo.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = (stereo[2 * i] + stereo[2 * i + 1]) * 0.5;
  return out;
}

async function scanMix(id, request) {
  const outgoing = decks[current].api;
  const outgoingId = outgoing.id;
  const longest = Math.max(Number(request.outgoingDuration) || 0, Number(request.incomingDuration) || 0);
  const remaining = outgoing.remaining();
  if (remaining >= 0 && remaining < longest / SCAN_RATE + SCAN_OVERHEAD) {
    fallBack(id, 'not enough time left in this song to scan both');
    return;
  }
  const job = (scan = { id, cancelled: false });
  const live = () => !job.cancelled && scan === job;
  let engine = null;
  const songs = [];
  const started = performance.now();
  try {
    note('mix: scanning both songs at 2x');
    engine = await loadMixEngine();
    await Promise.all([deck(SCAN_DECK), deck(spare)]);
    if (!live()) return;
    decks[spare].ready = false;
    const [songA, songB] = await Promise.all([
      scanSong(engine, SCAN_DECK, outgoingId, { tail: true }),
      scanSong(engine, spare, id, { head: true }),
    ]);
    songs.push(songA, songB);
    note('mix: scan captured both songs');
    // Each MusicKit player costs about 130 MB; the scan deck returns for the 1x spans.
    dropDeck(SCAN_DECK);
    if (!live()) return;
    const first = await engine.run(request, songA, songB);
    if (first.json.error) throw new Error(first.json.error);
    if (!live()) return;
    const P = first.json;
    note('mix: scan first plan ready');
    const spanA = [P.outgoingStart - 6, P.duration * 1.15 + 8];
    const spanB = [Math.max(0, P.incomingCue - 5), P.incomingResume + 3 - Math.max(0, P.incomingCue - 5)];
    await deck(SCAN_DECK);
    if (!live()) return;
    const [clipA, clipB] = await Promise.all([
      captureClip(SCAN_DECK, outgoingId, spanA[0], spanA[1]),
      captureClip(spare, id, spanB[0], spanB[1]),
    ]);
    if (!live()) return;
    const correctionA = engine.patch(songA, true, clipA.start, clipA.data, MIX_RATE);
    const correctionB = engine.patch(songB, false, clipB.start, clipB.data, MIX_RATE);
    note('mix: scan spliced the 1x spans');
    dropDeck(SCAN_DECK);
    const second = await engine.run(request, songA, songB);
    if (second.json.error) throw new Error(second.json.error);
    if (!live()) return;
    const plan = second.json;
    if (plan.outgoingStart < spanA[0] || plan.outgoingStart + plan.duration > spanA[0] + spanA[1] ||
        plan.incomingResume > spanB[0] + spanB[1]) {
      throw new Error('the final plan left the 1x spans');
    }
    note('mix: scan planned in ' + ((performance.now() - started) / 1000).toFixed(0) + ' s');
    scan = null;
    dropDeck(SCAN_DECK);
    queue = queue.then(() => armPlan({
      id,
      outgoingStart: plan.outgoingStart,
      incomingCue: plan.incomingCue,
      render: mixer.stereoBuffer(second.pcm),
      outgoing: { start: clipA.start + correctionA, data: monoOf(clipA.data) },
      incoming: { start: clipB.start + correctionB, data: monoOf(clipB.data) },
    })).catch(() => {});
  } catch (e) {
    if (!live()) return;
    scan = null;
    dropDeck(SCAN_DECK);
    fallBack(id, 'scan failed: ' + (e && e.message || e));
  } finally {
    if (engine) for (const song of songs) engine.dropSong(song);
  }
}
