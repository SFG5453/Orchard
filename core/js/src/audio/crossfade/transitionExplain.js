/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

// Why a pair plays out, from the candidate that came closest to a real mix.
// The overall winner can be an unmatched fade whose first veto is tempo even when vocals
// blocked every matched candidate, so refusals read the best matched candidate instead.

const CATEGORY = Object.freeze({
  'tempo-distance': 'tempo',
  'moving-tempo': 'tempo',
  'outgoing-tempo': 'tempo',
  'incoming-tempo': 'tempo',
  'beat-confidence': 'timing',
  'phase-error': 'timing',
  'structure-confidence': 'timing',
  'evidence-coverage': 'timing',
  'voice-cut': 'vocals',
  'voice-over-voice': 'vocals',
  'vocal-collision': 'vocals',
  'needs-filter': 'vocals',
  'harmonic-clash': 'harmony',
  'style-contrast': 'style',
  'incoming-runway': 'room',
  'no-room': 'room',
  'bridge-unsafe': 'vocals'
});

function fixed(value, places = 2) {
  return Number.isFinite(value) ? Number(value.toFixed(places)) : null;
}

function percent(value) {
  return Number.isFinite(value) ? `${(value * 100).toFixed(1)}%` : '?';
}

function detailFor(code, evaluation, contrast) {
  const voice = evaluation?.voice || {};
  const collision = evaluation?.collision || {};
  const harmonic = evaluation?.harmonic || {};
  switch (code) {
    case 'voice-cut':
      return `the outgoing singer is mid-line at the exit (${fixed(voice.outgoing)})`;
    case 'voice-over-voice':
      return `both songs sing through the overlap (in ${fixed(voice.incoming)}, out ${fixed(voice.outgoing)}, ` +
        `${fixed(collision.longestRunBeats, 1)} beats together)`;
    case 'needs-filter':
      return `the blend only works behind a filter (voices together ${fixed(collision.simultaneousMean)}, ` +
        `harmony ${fixed(harmonic.score)}, spectra ${fixed(evaluation?.components?.spectral)})`;
    case 'harmonic-clash':
      return harmonic.source === 'local'
        ? `the keys clash where the songs meet (chroma correlation ${fixed(harmonic.correlation)})`
        : `the keys clash (${fixed(harmonic.score)})`;
    case 'style-contrast':
      return `the productions clash (808 contrast ${fixed(contrast)})`;
    case 'beat-confidence':
      return 'a beat grid is not trustworthy enough to beatmatch';
    case 'phase-error':
      return `the bar lines miss by ${fixed(evaluation?.phase?.beatErrorSeconds, 3)} s`;
    case 'structure-confidence':
      return 'no confident phrase boundary on either side';
    default:
      return code;
  }
}

function sectionsDiffer(nearest, fit) {
  const off = (section, song) => Number.isFinite(section) && song > 0 && Math.abs(section / song - 1) > 0.035;
  return off(nearest.outgoingBpm, fit.outgoingBpm) || off(nearest.incomingBpm, fit.incomingBpm);
}

/** Plain reason for a refusal; `fits` counts cue pairs by tempo outcome. */
export function explainRefusal({
  matched = [], fit = {}, fits = {}, nearest = null, rejected = {}, precheck = '', bridge = null, contrast = null
}) {
  if (precheck) {
    return { code: precheck, category: CATEGORY[precheck] || 'room', detail: precheck };
  }
  const best = matched[0];
  if (best) {
    const veto = best.gates.find((gate) => gate.severity === 'veto') ||
      best.gates.find((gate) => gate.maximumClass === 'normal_boundary');
    const code = veto?.code || `confidence-${best.transitionClass}`;
    return {
      code,
      category: CATEGORY[code] || 'confidence',
      detail: veto ? detailFor(code, best, contrast) : `best blend only reached ${fixed(best.confidence)} confidence`,
      confidence: fixed(best.confidence),
      ...(bridge ? { bridge } : {})
    };
  }
  const outgoingBpm = fixed(fit.outgoingBpm, 1);
  const incomingBpm = fixed(fit.incomingBpm, 1);
  if ((fits.matched ?? 0) > 0) {
    const room = (rejected.bounds ?? 0) >= (rejected.tempo ?? 0) ? 'no-room' : 'moving-tempo';
    return {
      code: room,
      category: CATEGORY[room],
      detail: room === 'no-room'
        ? 'tempos match but no overlap fits the intro and outro'
        : 'the tempo moves inside every overlap that fits',
      ...(bridge ? { bridge } : {})
    };
  }
  const moving = (fits.moving ?? 0) > 0 && !(fits.far ?? 0);
  const code = moving ? 'moving-tempo' : 'tempo-distance';
  // Cue sections can run at their own tempo after a beat switch; name the tempos that met.
  const section = !moving && nearest && sectionsDiffer(nearest, fit) ? nearest : null;
  const metBpm = section ? [fixed(section.outgoingBpm, 1), fixed(section.incomingBpm, 1)] : [outgoingBpm, incomingBpm];
  return {
    code,
    category: 'tempo',
    detail: moving
      ? 'the tempo moves where the songs would meet'
      : section
        ? `${metBpm[0]} vs ${metBpm[1]} BPM where the songs meet, ${percent(section.deviation)} apart ` +
          `(songs are ${outgoingBpm} and ${incomingBpm} BPM)`
        : `${outgoingBpm} vs ${incomingBpm} BPM, ${percent(fit.deviation)} apart`,
    bpm: metBpm,
    ...(bridge ? { bridge } : {})
  };
}
