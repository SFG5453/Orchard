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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

export const CHOREOGRAPHY_SCHEMA_VERSION = 1;

export const CHOREOGRAPHY_STRATEGY = Object.freeze({
  STAGED_BLEND: 'staged_blend',
  FILTERED_HANDOFF: 'filtered_handoff',
  CLEAN_CUT: 'clean_cut',
  SILENCE_TRIM: 'silence_trim'
});

export const CURVE_INTERPOLATION = Object.freeze({
  LINEAR: 'linear',
  SMOOTH_STEP: 'smooth_step',
  EQUAL_POWER_IN: 'equal_power_in',
  EQUAL_POWER_OUT: 'equal_power_out',
  LOGARITHMIC: 'logarithmic'
});

const VALID_STRATEGIES = new Set(Object.values(CHOREOGRAPHY_STRATEGY));
const VALID_INTERPOLATIONS = new Set(Object.values(CURVE_INTERPOLATION));

function isFiniteNumber(value) {
  return typeof value === 'number' && Number.isFinite(value);
}

/**
 * Evaluates an automation curve at normalized position t in [0, 1].
 */
export function evaluateAutomationCurve(points = [], position = 0) {
  if (!Array.isArray(points) || points.length === 0) return 1.0;
  const t = Math.max(0, Math.min(1, Number.isFinite(position) ? position : 0));
  if (points.length === 1) return points[0].value;

  if (t <= points[0].position) return points[0].value;
  if (t >= points.at(-1).position) return points.at(-1).value;

  for (let i = 0; i < points.length - 1; i += 1) {
    const p0 = points[i];
    const p1 = points[i + 1];
    if (t >= p0.position && t <= p1.position) {
      const span = p1.position - p0.position;
      if (span <= 1e-9) return p1.value;
      const progress = (t - p0.position) / span;
      const mode = p0.interpolation || CURVE_INTERPOLATION.LINEAR;
      let factor = progress;
      if (mode === CURVE_INTERPOLATION.SMOOTH_STEP) {
        factor = progress * progress * (3 - 2 * progress);
      } else if (mode === CURVE_INTERPOLATION.EQUAL_POWER_IN) {
        factor = Math.sin(progress * Math.PI * 0.5);
      } else if (mode === CURVE_INTERPOLATION.EQUAL_POWER_OUT) {
        factor = 1 - Math.cos(progress * Math.PI * 0.5);
      } else if (mode === CURVE_INTERPOLATION.LOGARITHMIC) {
        const v0 = Math.max(1e-4, p0.value);
        const v1 = Math.max(1e-4, p1.value);
        return Math.exp(Math.log(v0) + (Math.log(v1) - Math.log(v0)) * progress);
      }
      return p0.value + (p1.value - p0.value) * factor;
    }
  }
  return points.at(-1).value;
}

/**
 * Validates a single automation curve.
 */
function validateCurve(curve, name, errors) {
  if (!Array.isArray(curve)) {
    errors.push(`Curve '${name}' must be an array.`);
    return;
  }
  let prevPos = -Infinity;
  for (let i = 0; i < curve.length; i += 1) {
    const pt = curve[i];
    if (!pt || typeof pt !== 'object') {
      errors.push(`Point ${i} in curve '${name}' is not an object.`);
      continue;
    }
    if (!isFiniteNumber(pt.position) || pt.position < 0 || pt.position > 1) {
      errors.push(`Point ${i} in curve '${name}' position ${pt.position} is out of range [0, 1].`);
    }
    if (pt.position < prevPos - 1e-9) {
      errors.push(`Point ${i} in curve '${name}' position ${pt.position} is not sorted (previous ${prevPos}).`);
    }
    prevPos = pt.position;
    if (!isFiniteNumber(pt.value)) {
      errors.push(`Point ${i} in curve '${name}' value is non-finite.`);
    }
    if (pt.interpolation && !VALID_INTERPOLATIONS.has(pt.interpolation)) {
      errors.push(`Point ${i} in curve '${name}' has unknown interpolation '${pt.interpolation}'.`);
    }
  }
}

/**
 * Validates a complete transition choreography object.
 * Returns { valid: boolean, errors: string[] }.
 */
export function validateChoreography(choreography, isFallback = false) {
  const errors = [];
  if (!choreography || typeof choreography !== 'object') {
    return { valid: false, errors: ['Choreography must be a non-null object.'] };
  }

  if (choreography.schemaVersion !== CHOREOGRAPHY_SCHEMA_VERSION) {
    errors.push(`Unsupported schemaVersion '${choreography.schemaVersion}', expected ${CHOREOGRAPHY_SCHEMA_VERSION}.`);
  }

  if (!VALID_STRATEGIES.has(choreography.strategy)) {
    errors.push(`Unknown strategy '${choreography.strategy}'.`);
  }

  const { outgoing, incoming, duration, dominancePoint, curves, bassSwapPoint, fallback } = choreography;

  if (!outgoing || typeof outgoing !== 'object') {
    errors.push('Missing outgoing track choreography object.');
  } else {
    if (!isFiniteNumber(outgoing.start) || outgoing.start < 0) errors.push(`Invalid outgoing start: ${outgoing.start}`);
    if (!isFiniteNumber(outgoing.end) || outgoing.end < 0) errors.push(`Invalid outgoing end: ${outgoing.end}`);
    if (!isFiniteNumber(outgoing.tempoRatio) || outgoing.tempoRatio <= 0) errors.push(`Invalid outgoing tempoRatio: ${outgoing.tempoRatio}`);
    if (isFiniteNumber(outgoing.start) && isFiniteNumber(outgoing.end) && outgoing.end < outgoing.start - 1e-6) {
      errors.push(`Outgoing end (${outgoing.end}) is before start (${outgoing.start}).`);
    }
  }

  if (!incoming || typeof incoming !== 'object') {
    errors.push('Missing incoming track choreography object.');
  } else {
    if (!isFiniteNumber(incoming.cue) || incoming.cue < 0) errors.push(`Invalid incoming cue: ${incoming.cue}`);
    if (!isFiniteNumber(incoming.arrival) || incoming.arrival < 0) errors.push(`Invalid incoming arrival: ${incoming.arrival}`);
    if (!isFiniteNumber(incoming.resume) || incoming.resume < 0) errors.push(`Invalid incoming resume: ${incoming.resume}`);
    if (!isFiniteNumber(incoming.tempoRatio) || incoming.tempoRatio <= 0) errors.push(`Invalid incoming tempoRatio: ${incoming.tempoRatio}`);
    if (isFiniteNumber(incoming.cue) && isFiniteNumber(incoming.arrival) && incoming.arrival < incoming.cue - 1e-6) {
      errors.push(`Incoming arrival (${incoming.arrival}) is before cue (${incoming.cue}).`);
    }
    if (isFiniteNumber(incoming.cue) && isFiniteNumber(incoming.resume) && incoming.resume < incoming.cue - 1e-6) {
      errors.push(`Incoming resume (${incoming.resume}) is before cue (${incoming.cue}).`);
    }
  }

  if (!isFiniteNumber(duration) || duration < 0) {
    errors.push(`Invalid duration: ${duration}`);
  }

  if (outgoing && incoming && isFiniteNumber(duration)) {
    const outgoingConsumed = outgoing.end - outgoing.start;
    const expectedOutDuration = duration * outgoing.tempoRatio;
    if (Math.abs(outgoingConsumed - expectedOutDuration) > 1e-3) {
      errors.push(`Duration ${duration} with tempoRatio ${outgoing.tempoRatio} inconsistent with outgoing consumption (${outgoingConsumed} vs ${expectedOutDuration}).`);
    }

    const incomingConsumed = incoming.resume - incoming.cue;
    const expectedInDuration = duration * incoming.tempoRatio;
    if (Math.abs(incomingConsumed - expectedInDuration) > 1e-3) {
      errors.push(`Duration ${duration} with tempoRatio ${incoming.tempoRatio} inconsistent with incoming consumption (${incomingConsumed} vs ${expectedInDuration}).`);
    }
  }

  if (dominancePoint !== undefined && dominancePoint !== null) {
    if (!isFiniteNumber(dominancePoint) || dominancePoint < 0 || dominancePoint > 1) {
      errors.push(`Invalid dominancePoint: ${dominancePoint}, must be in [0, 1].`);
    }
  }

  if (bassSwapPoint !== undefined && bassSwapPoint !== null) {
    if (!isFiniteNumber(bassSwapPoint) || bassSwapPoint < 0 || bassSwapPoint > 1) {
      errors.push(`Invalid bassSwapPoint: ${bassSwapPoint}, must be in [0, 1].`);
    }
  }

  if (curves && typeof curves === 'object') {
    validateCurve(curves.outgoingGain, 'outgoingGain', errors);
    validateCurve(curves.incomingGain, 'incomingGain', errors);
    validateCurve(curves.outgoingLowPass, 'outgoingLowPass', errors);
    validateCurve(curves.outgoingBass, 'outgoingBass', errors);
    validateCurve(curves.incomingBass, 'incomingBass', errors);

    // Validate bass ownership: outside a short swap ramp (~25% of duration or 1.5s),
    // both decks must not be simultaneously active (> 0.8).
    if (Array.isArray(curves.outgoingBass) && Array.isArray(curves.incomingBass) && duration > 0) {
      let dualActiveCount = 0;
      const sampleSteps = 20;
      for (let i = 0; i <= sampleSteps; i += 1) {
        const t = i / sampleSteps;
        const outBass = evaluateAutomationCurve(curves.outgoingBass, t);
        const inBass = evaluateAutomationCurve(curves.incomingBass, t);
        if (outBass > 0.8 && inBass > 0.8) {
          dualActiveCount += 1;
        }
      }
      const dualActiveFraction = dualActiveCount / (sampleSteps + 1);
      if (dualActiveFraction > 0.25) {
        errors.push(`Bass ownership leaves both decks active simultaneously for ${(dualActiveFraction * 100).toFixed(1)}% of the transition.`);
      }
    }
  } else if (duration > 0 && choreography.strategy !== CHOREOGRAPHY_STRATEGY.CLEAN_CUT) {
    errors.push('Missing curves object.');
  }

  if (!isFallback) {
    if (fallback !== null && fallback !== undefined) {
      const fallbackResult = validateChoreography(fallback, true);
      if (!fallbackResult.valid) {
        errors.push(...fallbackResult.errors.map((err) => `Fallback: ${err}`));
      }
    }
  }

  return {
    valid: errors.length === 0,
    errors
  };
}

/**
 * Creates an immutable AutomationPoint.
 */
export function createAutomationPoint(position, value, interpolation = CURVE_INTERPOLATION.LINEAR) {
  return Object.freeze({
    position: Math.round(position * 1e6) / 1e6,
    value: Math.round(value * 1e6) / 1e6,
    interpolation
  });
}

/**
 * Constructs a valid, frozen TransitionChoreography object.
 */
export function createTransitionChoreography({
  strategy = CHOREOGRAPHY_STRATEGY.STAGED_BLEND,
  outgoing = {},
  incoming = {},
  duration = 0,
  dominancePoint = 0.5,
  curves = {},
  bassSwapPoint = null,
  confidence = 1.0,
  diagnostics = null,
  fallback = null
} = {}) {
  const result = Object.freeze({
    schemaVersion: CHOREOGRAPHY_SCHEMA_VERSION,
    strategy,
    outgoing: Object.freeze({
      start: Math.round((outgoing.start ?? 0) * 1e6) / 1e6,
      end: Math.round((outgoing.end ?? 0) * 1e6) / 1e6,
      tempoRatio: Math.round((outgoing.tempoRatio ?? 1.0) * 1e6) / 1e6
    }),
    incoming: Object.freeze({
      cue: Math.round((incoming.cue ?? 0) * 1e6) / 1e6,
      arrival: Math.round((incoming.arrival ?? incoming.cue ?? 0) * 1e6) / 1e6,
      resume: Math.round((incoming.resume ?? incoming.cue ?? 0) * 1e6) / 1e6,
      tempoRatio: Math.round((incoming.tempoRatio ?? 1.0) * 1e6) / 1e6
    }),
    duration: Math.round(duration * 1e6) / 1e6,
    dominancePoint: dominancePoint !== null ? Math.round(dominancePoint * 1e6) / 1e6 : null,
    curves: Object.freeze({
      outgoingGain: Object.freeze([...(curves.outgoingGain || [])]),
      incomingGain: Object.freeze([...(curves.incomingGain || [])]),
      outgoingLowPass: Object.freeze([...(curves.outgoingLowPass || [])]),
      outgoingBass: Object.freeze([...(curves.outgoingBass || [])]),
      incomingBass: Object.freeze([...(curves.incomingBass || [])])
    }),
    bassSwapPoint: bassSwapPoint !== null ? Math.round(bassSwapPoint * 1e6) / 1e6 : null,
    confidence: Math.round(confidence * 1e6) / 1e6,
    diagnostics: diagnostics ? Object.freeze({ ...diagnostics }) : null,
    fallback: fallback ? Object.freeze({ ...fallback }) : null
  });

  const validation = validateChoreography(result);
  if (!validation.valid) {
    throw new Error(`Invalid TransitionChoreography: ${validation.errors.join('; ')}`);
  }
  return result;
}
