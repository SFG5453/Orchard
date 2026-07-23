function clamp01(value) {
  return Math.max(0, Math.min(1, Number(value) || 0));
}

const DJ_DOMINANCE_PROGRESS = 0.58;
const DJ_BED_FADE_IN_SECONDS = 4;

function equalPowerCurves(size = 64) {
  const fadeOut = new Float32Array(size);
  const fadeIn = new Float32Array(size);
  const djFadeIn = new Float32Array(size);
  for (let index = 0; index < size; index += 1) {
    const progress = index / (size - 1);
    fadeOut[index] = Math.cos(progress * Math.PI * 0.5);
    fadeIn[index] = Math.sin(progress * Math.PI * 0.5);
    const dominanceProgress = Math.min(1, progress / DJ_DOMINANCE_PROGRESS);
    djFadeIn[index] = Math.sin(dominanceProgress * Math.PI * 0.5);
  }
  return { djFadeIn, fadeOut, fadeIn };
}

const CURVES = equalPowerCurves();

export function createCrossfadeMixer({ connectElement, currentTime }) {
  function scheduleGain(node, curve, scale, startTime, duration, floor = 0) {
    const values = Float32Array.from(curve, (value) => floor + value * (scale - floor));
    node.gain.gain.cancelScheduledValues(startTime);
    node.gain.gain.setValueAtTime(values[0], startTime);
    node.gain.gain.setValueCurveAtTime(values, startTime, duration);
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

  function scheduleDjFilters(fromNode, toNode, startTime, handoffTime, duration, style, bassSwap = false) {
    const prerollSeconds = handoffTime - startTime;
    const isLongPreroll = prerollSeconds > 6;
    const outgoingStart = Math.min(20000, fromNode.lowPass.context.sampleRate * 0.45);
    fromNode.lowPass.frequency.cancelScheduledValues(startTime);
    fromNode.lowPass.frequency.setValueAtTime(outgoingStart, startTime);
    fromNode.lowPass.frequency.setValueAtTime(outgoingStart, handoffTime);
    toNode.highPass.frequency.cancelScheduledValues(startTime);
    // For long prerolls, start HP filter higher to fully isolate bass during
    // the preroll, preventing muddy low-end clashing in the long overlap.
    const incomingCutoff = isLongPreroll ? 500 : (bassSwap ? 350 : (style === 'dj_filter' ? 1600 : 900));
    let incomingHandoffTime = handoffTime;
    toNode.highPass.frequency.setValueAtTime(incomingCutoff, startTime);
    if (isLongPreroll && prerollSeconds > 10) {
      // During a long preroll, progressively open the HP filter from the
      // initial cutoff down to 350Hz so the incoming track gradually gains
      // warmth before the main handoff.
      const preOpenTime = startTime + prerollSeconds * 0.5;
      const preOpenDuration = prerollSeconds * 0.5;
      // Reuse the curve's computed end as the next curve's start. Recomputing
      // the boundary from handoffTime can differ by a floating-point epsilon,
      // which Web Audio treats as an overlapping automation event.
      incomingHandoffTime = preOpenTime + preOpenDuration;
      toNode.highPass.frequency.setValueCurveAtTime(
        filterCurve(incomingCutoff, 350),
        preOpenTime,
        preOpenDuration
      );
    } else {
      toNode.highPass.frequency.setValueAtTime(incomingCutoff, handoffTime);
    }
    // Outgoing LP sweep: for long prerolls use a gentler cutoff endpoint (300Hz)
    // to retain some body in the outgoing track's tail fade.
    const outgoingEndFreq = isLongPreroll ? 300 : 200;
    fromNode.lowPass.frequency.setValueCurveAtTime(
      filterCurve(outgoingStart, outgoingEndFreq),
      handoffTime,
      duration
    );
    toNode.highPass.frequency.setValueCurveAtTime(
      filterCurve(isLongPreroll ? 350 : incomingCutoff, 20),
      incomingHandoffTime,
      duration
    );
  }

  function scheduleDjGains(fromNode, toNode, target, startTime, handoffTime, duration, style) {
    const prerollSeconds = handoffTime - startTime;
    const isLongPreroll = prerollSeconds > 6;
    const bedGain = target * (style === 'dj_switch' ? 0.22 : (isLongPreroll ? 0.20 : 0.28));
    const bedFadeSeconds = isLongPreroll ? Math.min(prerollSeconds * 0.4, DJ_BED_FADE_IN_SECONDS) : DJ_BED_FADE_IN_SECONDS;
    const bedReadyTime = Math.min(handoffTime, startTime + bedFadeSeconds);
    // Outgoing: hold full volume, then gently reduce during preroll before
    // the main handoff fade. For long prerolls (>6s), reduce to ~0.88 of
    // target by the handoff point to create a gradual energy taper.
    const outgoingPreHandoff = isLongPreroll ? target * 0.88 : target;
    fromNode.gain.gain.cancelScheduledValues(startTime);
    fromNode.gain.gain.setValueAtTime(target, startTime);
    if (isLongPreroll) {
      fromNode.gain.gain.linearRampToValueAtTime(outgoingPreHandoff, handoffTime);
    } else {
      fromNode.gain.gain.setValueAtTime(target, handoffTime);
    }
    toNode.gain.gain.cancelScheduledValues(startTime);
    toNode.gain.gain.setValueAtTime(0, startTime);
    if (handoffTime > startTime) {
      toNode.gain.gain.linearRampToValueAtTime(bedGain, bedReadyTime);
      toNode.gain.gain.setValueAtTime(bedGain, handoffTime);
    }
    scheduleGain(fromNode, CURVES.fadeOut, outgoingPreHandoff, handoffTime, duration);
    scheduleGain(
      toNode,
      CURVES.djFadeIn,
      target,
      handoffTime,
      duration,
      handoffTime > startTime ? bedGain : 0
    );
  }

  function scheduleCrossfade({
    fromAudio,
    toAudio,
    targetVolume,
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
    const target = clamp01(targetVolume);

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
