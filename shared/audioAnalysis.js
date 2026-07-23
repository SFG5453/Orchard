export const AUDIO_ANALYSIS_VERSION = 7;
export const MIN_LOCAL_BPM = 40;
export const MAX_LOCAL_BPM = 240;

export function isValidLocalBpm(value) {
  const bpm = Number(value);
  return Number.isFinite(bpm) && bpm >= MIN_LOCAL_BPM && bpm <= MAX_LOCAL_BPM;
}

export function isValidLocalAnalysis(value) {
  if (!value || value.analysisVersion !== AUDIO_ANALYSIS_VERSION || !isValidLocalBpm(value.bpm)) {
    return false;
  }
  const duration = Number(value.duration);
  const beatInterval = Number(value.beatInterval);
  return Number.isFinite(duration) && duration > 0 &&
    Number.isFinite(beatInterval) && beatInterval > 0 &&
    Array.isArray(value.beats) && value.beats.length >= 2 &&
    Array.isArray(value.downbeats) &&
    Array.isArray(value.phraseBoundaries);
}

export function localAnalysisWithSource(value, source) {
  if (!isValidLocalAnalysis(value)) return null;
  const bpm = Number(value.bpm);
  const originalSource = String(value.analysisSource || value.bpmSource || '');
  return {
    ...value,
    bpm,
    analyzedBpm: Number(value.analyzedBpm) || bpm,
    analyzedTempoConfidence: Number(value.analyzedTempoConfidence) ||
      Number(value.tempoConfidence) || Number(value.beatConfidence) || 0,
    analysisSource: source === 'cache' ? (originalSource || 'local-cache') : source,
    ...(source === 'cache' && originalSource ? { cachedBpmSource: originalSource } : {}),
    bpmSource: source
  };
}

function redactText(value) {
  return String(value ?? '')
    .replace(/\b(authorization|cookie|set-cookie|x-goog-visitor-id)\s*[:=]\s*[^\s,;]+/gi, '$1=[redacted]')
    .replace(/https?:\/\/[^\s"']+/gi, (match) => {
      try {
        const url = new URL(match);
        return `${url.origin}${url.pathname}${url.search ? '?[redacted]' : ''}`;
      } catch {
        return '[redacted-url]';
      }
    })
    .slice(0, 1000);
}

export function safeAudioAnalysisDiagnostics(value, depth = 0) {
  if (depth > 3) return '[truncated]';
  if (value == null || typeof value === 'boolean' || typeof value === 'number') return value;
  if (typeof value === 'string') return redactText(value);
  if (Array.isArray(value)) {
    return value.slice(0, 20).map((item) => safeAudioAnalysisDiagnostics(item, depth + 1));
  }
  if (typeof value !== 'object') return redactText(value);

  const output = {};
  Object.entries(value).slice(0, 50).forEach(([key, item]) => {
    if (/authorization|cookie|credential|signature|token/i.test(key)) {
      output[key] = '[redacted]';
      return;
    }
    output[key] = safeAudioAnalysisDiagnostics(item, depth + 1);
  });
  return output;
}
