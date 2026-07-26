import FFT from 'fft.js';

export const ALL_IN_ONE_LABELS = Object.freeze([
  'start',
  'end',
  'intro',
  'outro',
  'break',
  'bridge',
  'inst',
  'solo',
  'verse',
  'chorus'
]);

export const ALL_IN_ONE_SAMPLE_RATE = 44_100;

const ALL_IN_ONE_FRAME_SIZE = 2048;
const ALL_IN_ONE_HOP_SIZE = 441;
const ALL_IN_ONE_FPS = ALL_IN_ONE_SAMPLE_RATE / ALL_IN_ONE_HOP_SIZE;
const ALL_IN_ONE_INPUT_PLANES = 4;

function clamp(value, minimum, maximum) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.max(minimum, Math.min(maximum, number)) : minimum;
}

function sigmoid(value) {
  if (value >= 0) {
    const inverse = Math.exp(-value);
    return 1 / (1 + inverse);
  }
  const exponential = Math.exp(value);
  return exponential / (1 + exponential);
}

function average(left, right) {
  const length = Math.min(left?.length || 0, right?.length || 0);
  const output = new Float32Array(length);
  for (let index = 0; index < length; index += 1) {
    output[index] = ((left[index] || 0) + (right[index] || 0)) * 0.5;
  }
  return output;
}

export function resampleAudioChannels(channels, sourceRate, targetRate = ALL_IN_ONE_SAMPLE_RATE) {
  const input = channels
    .slice(0, 2)
    .map((channel) => channel instanceof Float32Array ? channel : new Float32Array(channel));
  if (!input.length || !input[0].length) return [];
  const fromRate = Number(sourceRate);
  const toRate = Number(targetRate);
  if (!Number.isFinite(fromRate) || fromRate <= 0 || !Number.isFinite(toRate) || toRate <= 0) {
    throw new Error('Audio sample rates must be positive finite numbers.');
  }
  if (fromRate === toRate) {
    return input.length === 1 ? [input[0], input[0]] : input;
  }

  const outputLength = Math.max(1, Math.round(input[0].length * toRate / fromRate));
  const scale = fromRate / toRate;
  const output = input.map((channel) => {
    const resampled = new Float32Array(outputLength);
    for (let index = 0; index < outputLength; index += 1) {
      const position = Math.min(channel.length - 1, index * scale);
      const left = Math.floor(position);
      const right = Math.min(channel.length - 1, left + 1);
      const fraction = position - left;
      resampled[index] = (channel[left] || 0) * (1 - fraction) + (channel[right] || 0) * fraction;
    }
    return resampled;
  });
  return output.length === 1 ? [output[0], output[0]] : output;
}

function nearestBin(frequency, binWidth, maximumBin) {
  return Math.max(1, Math.min(maximumBin, Math.round(frequency / binWidth)));
}

/**
 * Reproduces madmom's normalized, unique, semitone-spaced triangular
 * LogarithmicFilterbank used by All-In-One. At 44.1 kHz / 2048 samples the
 * filterbank contains the model's expected 81 bands.
 */
export function createAllInOneFilterbank({
  fftSize = ALL_IN_ONE_FRAME_SIZE,
  fmax = 17_000,
  fmin = 30,
  sampleRate = ALL_IN_ONE_SAMPLE_RATE
} = {}) {
  const maximumBin = fftSize / 2 - 1;
  const binWidth = sampleRate / fftSize;
  const left = Math.floor(Math.log2(fmin / 440) * 12);
  const right = Math.ceil(Math.log2(fmax / 440) * 12);
  const centerBins = [];
  for (let pitch = left; pitch < right; pitch += 1) {
    const frequency = 440 * (2 ** (pitch / 12));
    if (frequency < fmin || frequency > fmax) continue;
    const bin = nearestBin(frequency, binWidth, maximumBin);
    if (centerBins[centerBins.length - 1] !== bin) centerBins.push(bin);
  }

  const filters = [];
  for (let index = 0; index + 2 < centerBins.length; index += 1) {
    let [start, center, stop] = centerBins.slice(index, index + 3);
    if (stop - start < 2) {
      center = start;
      stop = start + 1;
    }
    const bins = [];
    let sum = 0;
    for (let bin = start; bin < stop; bin += 1) {
      const value = bin < center
        ? (center === start ? 0 : (bin - start) / (center - start))
        : (stop === center ? 0 : (stop - bin) / (stop - center));
      bins.push({ bin, value });
      sum += value;
    }
    if (sum > 0) bins.forEach((entry) => { entry.value /= sum; });
    filters.push(bins);
  }
  return filters;
}

function extractLogSpectrogram(stem, filters) {
  const frames = Math.max(1, Math.ceil(stem.length / ALL_IN_ONE_HOP_SIZE));
  const output = new Float32Array(frames * filters.length);
  const fft = new FFT(ALL_IN_ONE_FRAME_SIZE);
  const fftInput = new Float64Array(ALL_IN_ONE_FRAME_SIZE);
  const spectrum = fft.createComplexArray();
  const magnitudes = new Float64Array(ALL_IN_ONE_FRAME_SIZE / 2);
  const window = new Float64Array(ALL_IN_ONE_FRAME_SIZE);
  for (let index = 0; index < window.length; index += 1) {
    window[index] = 0.5 - 0.5 * Math.cos(2 * Math.PI * index / (window.length - 1));
  }

  for (let frame = 0; frame < frames; frame += 1) {
    const frameStart = frame * ALL_IN_ONE_HOP_SIZE - ALL_IN_ONE_FRAME_SIZE / 2;
    for (let sample = 0; sample < ALL_IN_ONE_FRAME_SIZE; sample += 1) {
      const sourceIndex = frameStart + sample;
      fftInput[sample] = (sourceIndex >= 0 && sourceIndex < stem.length ? stem[sourceIndex] : 0) *
        window[sample];
    }
    fft.realTransform(spectrum, fftInput);
    for (let bin = 0; bin < magnitudes.length; bin += 1) {
      const real = spectrum[bin * 2] || 0;
      const imaginary = spectrum[bin * 2 + 1] || 0;
      magnitudes[bin] = Math.hypot(real, imaginary);
    }
    const frameOffset = frame * filters.length;
    filters.forEach((filter, band) => {
      let value = 0;
      for (const entry of filter) value += (magnitudes[entry.bin] || 0) * entry.value;
      output[frameOffset + band] = Math.log10(1 + value);
    });
  }
  return { data: output, frames, bands: filters.length };
}

/**
 * Runs All-In-One directly on the stereo mix. The model's four input planes
 * receive the same mix spectrogram, avoiding the heavyweight separation pass
 * while preserving the neural beat, downbeat, boundary, and section heads.
 */
export function extractAllInOneMixSpectrogram({
  channels,
  onProgress = () => {},
  sampleRate
}) {
  const stereo = resampleAudioChannels(channels, sampleRate, ALL_IN_ONE_SAMPLE_RATE);
  if (stereo.length !== 2 || !stereo[0].length) {
    throw new Error('All-In-One mix analysis requires decoded audio.');
  }
  const mono = average(stereo[0], stereo[1]);
  const filters = createAllInOneFilterbank();
  if (filters.length !== 81) {
    throw new Error(`All-In-One filterbank has ${filters.length} bands instead of 81.`);
  }
  const spectrogram = extractLogSpectrogram(mono, filters);
  const planeLength = spectrogram.data.length;
  const output = new Float32Array(planeLength * ALL_IN_ONE_INPUT_PLANES);
  for (let index = 0; index < ALL_IN_ONE_INPUT_PLANES; index += 1) {
    output.set(spectrogram.data, index * planeLength);
  }
  onProgress({ stage: 'spectrogram', completed: 1, total: 1 });
  return {
    data: output,
    frames: spectrogram.frames,
    bands: spectrogram.bands
  };
}

function prefixSums(values) {
  const sums = new Float64Array(values.length + 1);
  for (let index = 0; index < values.length; index += 1) {
    sums[index + 1] = sums[index] + values[index];
  }
  return sums;
}

function rangeMean(sums, start, end, divisor = end - start) {
  const safeStart = Math.max(0, Math.min(sums.length - 1, start));
  const safeEnd = Math.max(safeStart, Math.min(sums.length - 1, end));
  return divisor > 0 ? (sums[safeEnd] - sums[safeStart]) / divisor : 0;
}

function sectionBoundaries(sectionProbabilities, fps, minHopsPerBeat) {
  const localRadius = Math.floor((4 * minHopsPerBeat + 1) / 2);
  const local = new Float32Array(sectionProbabilities.length);
  for (let index = 0; index < sectionProbabilities.length; index += 1) {
    let maximum = -Infinity;
    for (let cursor = Math.max(0, index - localRadius);
      cursor <= Math.min(sectionProbabilities.length - 1, index + localRadius);
      cursor += 1) {
      maximum = Math.max(maximum, sectionProbabilities[cursor]);
    }
    if (sectionProbabilities[index] >= maximum) local[index] = sectionProbabilities[index];
  }

  const past = Math.round(12 * fps);
  const future = Math.round(12 * fps);
  const sums = prefixSums(local);
  const boundaries = [];
  for (let index = 0; index < local.length; index += 1) {
    if (local[index] <= 0) continue;
    let maximum = 0;
    for (let cursor = Math.max(0, index - past);
      cursor <= Math.min(local.length - 1, index + future);
      cursor += 1) {
      maximum = Math.max(maximum, local[cursor]);
    }
    if (local[index] < maximum) continue;
    const pastMean = rangeMean(sums, index - past, index, past);
    const futureMean = rangeMean(sums, index + 1, index + future + 1, future);
    const strength = local[index] - (pastMean + futureMean) * 0.5;
    if (strength > 0) boundaries.push({ frame: index, confidence: clamp(strength, 0, 1) });
  }
  return boundaries;
}

function normalizedLabelProbabilities(tensor, frames) {
  const data = tensor?.data;
  if (!data || data.length < ALL_IN_ONE_LABELS.length * frames) return null;
  const probabilities = new Float32Array(ALL_IN_ONE_LABELS.length * frames);
  for (let frame = 0; frame < frames; frame += 1) {
    let maximum = -Infinity;
    for (let label = 0; label < ALL_IN_ONE_LABELS.length; label += 1) {
      maximum = Math.max(maximum, Number(data[label * frames + frame]) || 0);
    }
    let sum = 0;
    for (let label = 0; label < ALL_IN_ONE_LABELS.length; label += 1) {
      const value = Math.exp((Number(data[label * frames + frame]) || 0) - maximum);
      probabilities[label * frames + frame] = value;
      sum += value;
    }
    for (let label = 0; label < ALL_IN_ONE_LABELS.length; label += 1) {
      probabilities[label * frames + frame] /= Math.max(1e-8, sum);
    }
  }
  return probabilities;
}

function labeledSegments(boundaries, labelProbabilities, frames, duration, fps) {
  const points = [{ frame: 0, confidence: 1 }, ...boundaries]
    .filter((point, index, values) =>
      point.frame >= 0 && point.frame < frames &&
      values.findIndex((candidate) => candidate.frame === point.frame) === index
    )
    .sort((left, right) => left.frame - right.frame);
  if (points[points.length - 1]?.frame !== frames) points.push({ frame: frames, confidence: 1 });

  const segments = [];
  for (let index = 0; index + 1 < points.length; index += 1) {
    const startFrame = points[index].frame;
    const endFrame = points[index + 1].frame;
    if (endFrame <= startFrame) continue;
    const scores = new Float64Array(ALL_IN_ONE_LABELS.length);
    for (let label = 0; label < scores.length; label += 1) {
      let sum = 0;
      for (let frame = startFrame; frame < endFrame; frame += 1) {
        sum += labelProbabilities[label * frames + frame] || 0;
      }
      scores[label] = sum / (endFrame - startFrame);
    }
    let bestLabel = 0;
    for (let label = 1; label < scores.length; label += 1) {
      if (scores[label] > scores[bestLabel]) bestLabel = label;
    }
    segments.push({
      start: Math.min(duration, startFrame / fps),
      end: Math.min(duration, endFrame / fps),
      type: ALL_IN_ONE_LABELS[bestLabel],
      confidence: clamp(scores[bestLabel] * (0.7 + points[index].confidence * 0.3), 0, 1)
    });
  }
  return segments;
}

function structuralCues(phrases, duration) {
  const musical = phrases.filter((phrase) => !['start', 'end'].includes(phrase.type));
  const first = musical[0];
  const intros = musical.filter((phrase, index) =>
    phrase.type === 'intro' && (index === 0 || phrase.start <= Math.min(45, duration * 0.3))
  );
  const introEndTime = intros.length
    ? intros[intros.length - 1].end
    : (first?.type === 'intro' ? first.end : 0);
  const outro = musical.find((phrase) =>
    phrase.type === 'outro' && phrase.start >= Math.max(duration * 0.5, 30)
  );
  const outroStartTime = outro?.start || 0;
  const confidences = musical.map((phrase) => Number(phrase.confidence) || 0);
  const structureConfidence = confidences.length
    ? confidences.reduce((sum, value) => sum + value, 0) / confidences.length
    : 0;

  return {
    introEndTime,
    mixInTime: introEndTime,
    mixInConfidence: introEndTime > 0 ? structureConfidence : 0,
    outroStartTime,
    mixOutTime: outroStartTime > 0 ? outroStartTime : duration,
    mixInCandidates: introEndTime > 0
      ? [{ time: introEndTime, score: structureConfidence, type: 'ai_intro_end' }]
      : [],
    mixOutCandidates: outroStartTime > 0
      ? [{ time: outroStartTime, score: structureConfidence, type: 'ai_outro_start' }]
      : [],
    structureConfidence
  };
}

export function postprocessAllInOne({
  config = {},
  duration,
  outputs,
  pipeline = 'all-in-one-mix',
  stemActivity = {}
}) {
  const fps = Number(config.fps) || ALL_IN_ONE_FPS;
  const sectionData = outputs?.logits_section?.data;
  const frames = Number(outputs?.logits_section?.dims?.at(-1)) || sectionData?.length || 0;
  if (!sectionData || !frames) throw new Error('All-In-One did not return section logits.');
  const sectionProbabilities = Float32Array.from(sectionData, sigmoid);
  const labelProbabilities = normalizedLabelProbabilities(outputs.logits_function, frames);
  if (!labelProbabilities) throw new Error('All-In-One did not return functional label logits.');
  const boundaries = sectionBoundaries(
    sectionProbabilities,
    fps,
    Number(config.min_hops_per_beat) || 24
  );
  const analyzedDuration = Math.min(Number(duration) || frames / fps, frames / fps);
  const phrases = labeledSegments(boundaries, labelProbabilities, frames, analyzedDuration, fps);
  const cues = structuralCues(phrases, analyzedDuration);
  const beatData = outputs?.logits_beat?.data || [];
  const downbeatData = outputs?.logits_downbeat?.data || [];
  let beatConfidence = 0;
  let downbeatConfidence = 0;
  for (let index = 0; index < Math.min(frames, beatData.length); index += 1) {
    beatConfidence = Math.max(beatConfidence, sigmoid(beatData[index]));
  }
  for (let index = 0; index < Math.min(frames, downbeatData.length); index += 1) {
    downbeatConfidence = Math.max(downbeatConfidence, sigmoid(downbeatData[index]));
  }

  return {
    aiAnalysisStatus: 'ready',
    aiPipeline: pipeline,
    aiStructureConfidence: cues.structureConfidence,
    aiBeatActivationConfidence: beatConfidence,
    aiDownbeatActivationConfidence: downbeatConfidence,
    phrases,
    phraseBoundaries: phrases.map((phrase) => phrase.start)
      .concat(phrases.at(-1)?.end ?? analyzedDuration)
      .filter((value, index, values) => values.indexOf(value) === index),
    ...cues,
    ...stemActivity
  };
}
