const MAX_PHRASES = 256;
const MAX_ACTIVITY_FRAMES = 2400;

function clamp(value, minimum = 0, maximum = 1) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.max(minimum, Math.min(maximum, number)) : minimum;
}

function finiteTime(value, duration) {
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 && number <= duration ? number : null;
}

function cleanPhrases(value, duration) {
  if (!Array.isArray(value)) return [];
  return value
    .slice(0, MAX_PHRASES)
    .map((phrase) => {
      const start = finiteTime(phrase?.start, duration);
      const end = finiteTime(phrase?.end, duration);
      const type = String(phrase?.type || phrase?.label || '')
        .toLowerCase()
        .replace(/[^a-z0-9_-]/g, '')
        .slice(0, 32);
      if (start === null || end === null || end <= start || !type) return null;
      return {
        start,
        end,
        type,
        confidence: clamp(phrase?.confidence)
      };
    })
    .filter(Boolean)
    .sort((left, right) => left.start - right.start);
}

function cleanCueCandidates(value, duration) {
  if (!Array.isArray(value)) return [];
  return value.slice(0, 32).map((candidate) => {
    const time = finiteTime(candidate?.time, duration);
    if (time === null) return null;
    return {
      time,
      score: clamp(candidate?.score),
      type: String(candidate?.type || 'ai_cue')
        .toLowerCase()
        .replace(/[^a-z0-9_-]/g, '')
        .slice(0, 40)
    };
  }).filter(Boolean);
}

function uniqueTimes(value, duration) {
  if (!Array.isArray(value)) return [];
  return [...new Set(value
    .map((item) => finiteTime(item, duration))
    .filter((item) => item !== null)
    .map((item) => Math.round(item * 10_000) / 10_000))]
    .sort((left, right) => left - right);
}

/**
 * Validates and conservatively fuses optional neural cues into the deterministic
 * native/worker result. A low-confidence model result is retained for
 * diagnostics but cannot replace structural timing used by the planner.
 */
export function mergeAiAudioAnalysis(base = {}, neural = {}, modelSignature = '') {
  const duration = Number(base.duration) || Number(neural.duration) || 0;
  if (duration <= 0) return base;
  const signature = String(modelSignature || neural.aiModelSignature || '').slice(0, 200);
  const phrases = cleanPhrases(neural.phrases, duration);
  const structureConfidence = clamp(
    neural.aiStructureConfidence ?? neural.structureConfidence
  );
  const trustedStructure = phrases.length > 0 && structureConfidence >= 0.3;
  const activity = Array.isArray(neural.vocalActivityMask)
    ? neural.vocalActivityMask
      .slice(0, MAX_ACTIVITY_FRAMES)
      .map((value) => clamp(value))
    : [];
  const frameSeconds = clamp(neural.vocalActivityFrameSeconds, 0.05, 10);
  const introEndTime = finiteTime(neural.introEndTime, duration);
  const outroStartTime = finiteTime(neural.outroStartTime, duration);
  const mixInTime = finiteTime(neural.mixInTime, duration);
  const mixOutTime = finiteTime(neural.mixOutTime, duration);
  const neuralPhraseBoundaries = uniqueTimes(neural.phraseBoundaries, duration);
  const deterministicCues = base.deterministicCues || {
    phrases: base.phrases,
    phraseBoundaries: base.phraseBoundaries,
    introEndTime: base.introEndTime,
    outroStartTime: base.outroStartTime,
    mixInTime: base.mixInTime,
    mixInConfidence: base.mixInConfidence,
    mixInCandidates: base.mixInCandidates,
    mixOutTime: base.mixOutTime,
    mixOutCandidates: base.mixOutCandidates,
    vocalActivityMask: base.vocalActivityMask,
    vocalActivityFrameSeconds: base.vocalActivityFrameSeconds,
    vocalProbability: base.vocalProbability,
    instrumentalProbability: base.instrumentalProbability
  };

  const merged = {
    ...base,
    deterministicCues,
    aiAnalysisStatus: neural.aiAnalysisStatus === 'ready' ? 'ready' : 'unavailable',
    aiPipeline: String(neural.aiPipeline || '').slice(0, 80),
    aiModelSignature: signature,
    aiStructureConfidence: structureConfidence,
    aiBeatActivationConfidence: clamp(neural.aiBeatActivationConfidence),
    aiDownbeatActivationConfidence: clamp(neural.aiDownbeatActivationConfidence),
    ...(activity.length
      ? {
          vocalActivityMask: activity,
          vocalActivityFrameSeconds: frameSeconds,
          vocalProbability: clamp(neural.vocalProbability),
          instrumentalProbability: 1 - clamp(neural.vocalProbability)
        }
      : {})
  };

  if (!trustedStructure) return merged;
  const phraseBoundaries = neuralPhraseBoundaries.length
    ? neuralPhraseBoundaries
    : uniqueTimes([
        ...phrases.map((phrase) => phrase.start),
        phrases.at(-1)?.end
      ], duration);
  return {
    ...merged,
    phrases,
    phraseBoundaries,
    ...(introEndTime !== null && introEndTime > 0 ? { introEndTime } : {}),
    ...(outroStartTime !== null && outroStartTime > duration * 0.45 ? { outroStartTime } : {}),
    ...(mixInTime !== null && mixInTime > 0
      ? {
          mixInTime,
          mixInConfidence: clamp(neural.mixInConfidence ?? structureConfidence),
          mixInCandidates: [
            ...cleanCueCandidates(neural.mixInCandidates, duration),
            ...cleanCueCandidates(base.mixInCandidates, duration)
          ]
        }
      : {}),
    ...(mixOutTime !== null && mixOutTime > duration * 0.45
      ? {
          mixOutTime,
          mixOutCandidates: [
            ...cleanCueCandidates(neural.mixOutCandidates, duration),
            ...cleanCueCandidates(base.mixOutCandidates, duration)
          ]
        }
      : {})
  };
}

export function markAiAudioAnalysisUnavailable(base = {}, modelSignature = '') {
  return {
    ...base,
    aiAnalysisStatus: 'unavailable',
    aiModelSignature: String(modelSignature || '').slice(0, 200)
  };
}

export function withoutAiAudioAnalysis(value = {}) {
  const {
    aiAnalysisStatus: _aiAnalysisStatus,
    aiBeatActivationConfidence: _aiBeatActivationConfidence,
    aiDownbeatActivationConfidence: _aiDownbeatActivationConfidence,
    aiModelSignature: _aiModelSignature,
    aiPipeline: _aiPipeline,
    aiStructureConfidence: _aiStructureConfidence,
    deterministicCues,
    ...base
  } = value;
  return deterministicCues
    ? { ...base, ...deterministicCues }
    : base;
}
