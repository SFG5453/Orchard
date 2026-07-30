// Where the incoming track becomes the authoritative deck. This is a promotion
// point for the session controller, not a shape in the fade: the gains stay
// equal-power complementary the whole way across.
const DJ_DOMINANCE_PROGRESS = 0.58;

// Where the low end changes hands, as a fraction of the fade, and how long the
// handover takes. Kept in step with BASS_SWAP_FRACTION and bass_swap_seconds on
// the rendered path so the same pairing hands its bass over at the same place
// whichever executor runs it.
const BASS_SWAP_FRACTION = 0.7;
const BASS_SWAP_SECONDS = 0.75;

// How much the outgoing track's mids give up by the end of the fade, in dB. The
// bass swap keeps the low end exclusive, but above it two equal-power halves
// sit at -3 dB each through the middle of the overlap, and beat-aligned mixes
// are correlated, so their mids sum hot exactly where they collide.
const MID_DUCK_DB = -6;

function equalPowerCurves(size = 64) {
  const fadeOut = new Float32Array(size);
  const fadeIn = new Float32Array(size);
  for (let index = 0; index < size; index += 1) {
    const progress = index / (size - 1);
    fadeOut[index] = Math.cos(progress * Math.PI * 0.5);
    fadeIn[index] = Math.sin(progress * Math.PI * 0.5);
  }
  return { fadeOut, fadeIn };
}

const CURVES = equalPowerCurves();

export function createCrossfadeMixer({ connectElement, currentTime }) {
  function mixParam(node) {
    return node.mixGain.gain;
  }

  function scheduleGain(node, curve, scale, startTime, duration, floor = 0) {
    const values = Float32Array.from(curve, (value) => floor + value * (scale - floor));
    const gain = mixParam(node);
    gain.cancelScheduledValues(startTime);
    gain.setValueAtTime(values[0], startTime);
    gain.setValueCurveAtTime(values, startTime, duration);
  }

  function filterCurve(start, end, size = 64) {
    const values = new Float32Array(size);
    for (let index = 0; index < size; index += 1) {
      const progress = index / (size - 1);
      const smooth = progress * progress * (3 - 2 * progress);
      values[index] = Math.exp(Math.log(start) + (Math.log(end) - Math.log(start)) * smooth);
    }
    return values;
  }

  // The low end changes hands exclusively: one track owns it at any instant and
  // the handover is a short equal-power ramp centred on the swap point. Linear
  // gains would leave two uncorrelated bass lines summing 3 dB down at the
  // midpoint, an audible hole exactly where the bass changes hands.
  // Sampled finer than the fade curves: the handover is under a second inside
  // an overlap of ten or more, so 64 points across the whole fade would put
  // only a handful anywhere near the ramp and step it audibly.
  function bassSwapCurves(duration, size = 256) {
    const out = new Float32Array(size);
    const into = new Float32Array(size);
    const swapAt = duration * BASS_SWAP_FRACTION;
    const ramp = Math.max(0.05, Math.min(BASS_SWAP_SECONDS, duration * 0.5));
    for (let index = 0; index < size; index += 1) {
      const seconds = (index / (size - 1)) * duration;
      const raw = Math.min(1, Math.max(0, (seconds - swapAt) / ramp + 0.5));
      const handover = raw * raw * (3 - 2 * raw);
      out[index] = Math.cos(handover * Math.PI * 0.5);
      into[index] = Math.sin(handover * Math.PI * 0.5);
    }
    return { out, into };
  }

  // The outgoing mids give way at the rate the incoming's arrive to replace
  // them: the duck rides fade_in squared, which is the incoming track's power.
  function midDuckCurve(size = 64) {
    const values = new Float32Array(size);
    for (let index = 0; index < size; index += 1) {
      const fadeIn = CURVES.fadeIn[index];
      values[index] = MID_DUCK_DB * fadeIn * fadeIn;
    }
    return values;
  }

  function scheduleParamCurve(param, values, startTime, duration) {
    param.cancelScheduledValues(startTime);
    param.setValueAtTime(values[0], startTime);
    param.setValueCurveAtTime(values, startTime, duration);
  }

  function scheduleDjFilters(fromNode, toNode, startTime, handoffTime, duration, style, bassSwap = false) {
    // The bass branch carries its own gain, so isolation is a gain handover
    // rather than a high-pass on the incoming track. Sweeping a high-pass up
    // to 350 Hz and back down while the outgoing low-pass swept *to* 200 Hz
    // left both tracks owning the low end at the end of the fade, which is the
    // muddiest arrangement available and the opposite of a swap.
    if (bassSwap) {
      const { out, into } = bassSwapCurves(duration);
      const fromBass = fromNode.bassGain.gain;
      const toBass = toNode.bassGain.gain;
      fromBass.cancelScheduledValues(startTime);
      fromBass.setValueAtTime(1, startTime);
      toBass.cancelScheduledValues(startTime);
      toBass.setValueAtTime(0, startTime);
      // During any pre-roll the incoming track is bass-free and the outgoing
      // keeps the whole low end; the swap itself belongs to the fade.
      fromBass.setValueAtTime(1, handoffTime);
      toBass.setValueAtTime(0, handoffTime);
      fromBass.setValueCurveAtTime(out, handoffTime, duration);
      toBass.setValueCurveAtTime(into, handoffTime, duration);
    }

    scheduleParamCurve(fromNode.midDuck.gain, midDuckCurve(), handoffTime, duration);

    // What is left for the sweep is character, not separation. It runs on the
    // high branch, so its endpoint is a colour choice: a tempo-mismatched blend
    // (`dj_filter`) leans on it to disguise the seam, a beat-matched one barely
    // needs it. Sweeping to 200 Hz here would attenuate the outgoing track a
    // second time on top of its own equal-power fade -- hollow, and gone early.
    const outgoingStart = Math.min(20000, fromNode.lowPass.context.sampleRate * 0.45);
    const outgoingEndFreq = style === 'dj_filter' ? 700 : 2200;
    fromNode.lowPass.frequency.cancelScheduledValues(startTime);
    fromNode.lowPass.frequency.setValueAtTime(outgoingStart, startTime);
    fromNode.lowPass.frequency.setValueAtTime(outgoingStart, handoffTime);
    fromNode.lowPass.frequency.setValueCurveAtTime(
      filterCurve(outgoingStart, outgoingEndFreq),
      handoffTime,
      duration
    );
  }

  function scheduleDjGains(fromNode, toNode, target, startTime, handoffTime, duration, style) {
    // Both sides run the same equal-power pair across the whole fade. The
    // incoming used to reach full gain at 58% and sit there while the outgoing
    // was still descending, so the last 42% of every DJ transition was two
    // tracks at full level -- the single largest reason these sounded muddy.
    const bedGain = handoffTime > startTime
      ? target * (style === 'dj_switch' ? 0.22 : 0.28)
      : 0;
    const fromGain = mixParam(fromNode);
    const toGain = mixParam(toNode);
    fromGain.cancelScheduledValues(startTime);
    fromGain.setValueAtTime(target, startTime);
    fromGain.setValueAtTime(target, handoffTime);
    toGain.cancelScheduledValues(startTime);
    toGain.setValueAtTime(0, startTime);
    if (bedGain > 0) {
      // A pre-roll, when one is planned, is the incoming track arriving under
      // the outgoing at bed level; it is not part of the fade.
      toGain.linearRampToValueAtTime(bedGain, Math.min(handoffTime, startTime + 4));
      toGain.setValueAtTime(bedGain, handoffTime);
    }
    scheduleGain(fromNode, CURVES.fadeOut, target, handoffTime, duration);
    scheduleGain(toNode, CURVES.fadeIn, target, handoffTime, duration, bedGain);
  }

  function scheduleCrossfade({
    fromAudio,
    toAudio,
    duration,
    handoffDuration = duration,
    handoffStartSeconds = 0,
    transitionStyle = 'equal_power',
    bassSwap = false,
    leadTime = 0.05
  }) {
    const fromNode = connectElement(fromAudio);
    const toNode = connectElement(toAudio);
    if (!fromNode || !toNode) return null;
    const startTime = currentTime() + leadTime;
    const overlapDuration = Math.max(0.05, Number(duration) || 0.05);
    const handoffStart = startTime + Math.max(0, Math.min(overlapDuration, Number(handoffStartSeconds) || 0));
    const fadeDuration = Math.max(
      0.05,
      Math.min(overlapDuration - (handoffStart - startTime), Number(handoffDuration) || overlapDuration)
    );
    // The mix envelope stays normalized. Overall playback volume lives on a
    // separate master gain so slider changes can take effect mid-transition.
    const target = 1;

    const djStyle = ['dj_switch', 'dj_filter', 'dj_blend'].includes(transitionStyle);
    if (djStyle) {
      scheduleDjGains(
        fromNode,
        toNode,
        target,
        startTime,
        handoffStart,
        fadeDuration,
        transitionStyle
      );
    } else {
      scheduleGain(fromNode, CURVES.fadeOut, target, startTime, fadeDuration);
      scheduleGain(toNode, CURVES.fadeIn, target, startTime, fadeDuration);
    }
    if (djStyle) {
      scheduleDjFilters(
        fromNode,
        toNode,
        startTime,
        handoffStart,
        fadeDuration,
        transitionStyle,
        bassSwap
      );
    }

    return {
      startTime,
      handoffStart,
      promotionTime: handoffStart + fadeDuration * (djStyle ? DJ_DOMINANCE_PROGRESS : 0.5),
      endTime: handoffStart + fadeDuration
    };
  }

  function resetElement(element) {
    const node = connectElement(element);
    if (!node) return;
    const now = currentTime();
    const gain = mixParam(node);
    gain.cancelScheduledValues(now);
    gain.setTargetAtTime(1, now, 0.02);
    node.bassGain?.gain.cancelScheduledValues(now);
    node.bassGain?.gain.setTargetAtTime(1, now, 0.02);
    node.midDuck?.gain.cancelScheduledValues(now);
    node.midDuck?.gain.setTargetAtTime(0, now, 0.02);
    node.lowPass.frequency.cancelScheduledValues(now);
    node.highPass.frequency.cancelScheduledValues(now);
    node.lowPass.frequency.setTargetAtTime(
      Math.min(20000, node.lowPass.context.sampleRate * 0.45),
      now,
      0.02
    );
    node.highPass.frequency.setTargetAtTime(20, now, 0.02);
  }

  return { resetElement, scheduleCrossfade };
}
