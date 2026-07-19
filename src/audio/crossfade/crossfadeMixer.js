function clamp01(value) {
  return Math.max(0, Math.min(1, Number(value) || 0));
}

const DJ_DOMINANCE_PROGRESS = 0.58;
const DJ_BED_FADE_IN_SECONDS = 2;

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

  function scheduleDjFilters(fromNode, toNode, startTime, handoffTime, duration, style) {
    const outgoingStart = Math.min(20000, fromNode.lowPass.context.sampleRate * 0.45);
    fromNode.lowPass.frequency.cancelScheduledValues(startTime);
    fromNode.lowPass.frequency.setValueAtTime(outgoingStart, startTime);
    fromNode.lowPass.frequency.setValueAtTime(outgoingStart, handoffTime);
    toNode.highPass.frequency.cancelScheduledValues(startTime);
    const incomingCutoff = style === 'dj_filter' ? 1600 : 900;
    toNode.highPass.frequency.setValueAtTime(incomingCutoff, startTime);
    toNode.highPass.frequency.setValueAtTime(incomingCutoff, handoffTime);
    fromNode.lowPass.frequency.setValueCurveAtTime(
      filterCurve(outgoingStart, 200),
      handoffTime,
      duration
    );
    toNode.highPass.frequency.setValueCurveAtTime(
      filterCurve(incomingCutoff, 20),
      handoffTime,
      duration
    );
  }

  function scheduleDjGains(fromNode, toNode, target, startTime, handoffTime, duration, style) {
    const bedGain = target * (style === 'dj_switch' ? 0.22 : 0.28);
    const bedReadyTime = Math.min(handoffTime, startTime + DJ_BED_FADE_IN_SECONDS);
    fromNode.gain.gain.cancelScheduledValues(startTime);
    fromNode.gain.gain.setValueAtTime(target, startTime);
    fromNode.gain.gain.setValueAtTime(target, handoffTime);
    toNode.gain.gain.cancelScheduledValues(startTime);
    toNode.gain.gain.setValueAtTime(0, startTime);
    toNode.gain.gain.linearRampToValueAtTime(bedGain, bedReadyTime);
    toNode.gain.gain.setValueAtTime(bedGain, handoffTime);
    scheduleGain(fromNode, CURVES.fadeOut, target, handoffTime, duration);
    scheduleGain(toNode, CURVES.djFadeIn, target, handoffTime, duration, bedGain);
  }

  function scheduleCrossfade({
    fromAudio,
    toAudio,
    targetVolume,
    duration,
    handoffDuration = duration,
    handoffStartSeconds = 0,
    transitionStyle = 'equal_power',
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
    if (djStyle && handoffStart > startTime) {
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
        transitionStyle
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
