//! Bar position of every beat: beat-synchronous evidence scored against per-position
//! templates, then a Viterbi pass over 3/4 and 4/4 bars.

use super::onset::peak;
use crate::analysis::BEAT_SPECTROGRAM_MELS;

pub(crate) const FEATURES: usize = 12;

// Fitted on 2,907 labelled tracks from ballroom, hainsworth, hjdb, candombe, guitarset,
// beatles, filosax, jaah, tapcorrect, rwc and harmonix.
// Columns: kick, low-mid, mid and high onsets on the beat; kick and high onsets half a
// beat later; harmonic (180 Hz-4 kHz), bass and 0.9-7 kHz change across two beats on
// each side; loudness of the next two beats; harmonic changes across four beats.
#[rustfmt::skip]
const TEMPLATE_MEAN: [[f32; FEATURES]; 7] = [
    [0.484, 0.250, 0.263, 0.152, -0.148, -0.116, 0.387, 0.322, 0.037, 0.338, 0.142, 0.122], // 3/4 beat 1
    [-0.353, -0.210, -0.206, -0.118, 0.015, 0.088, -0.241, -0.259, -0.068, -0.205, -0.070, -0.056], // 3/4 beat 2
    [-0.166, -0.053, -0.078, -0.049, 0.148, 0.040, -0.177, -0.083, 0.029, -0.163, -0.085, -0.078], // 3/4 beat 3
    [0.376, 0.108, 0.027, -0.053, -0.228, -0.138, 0.438, 0.328, -0.021, 0.354, 0.280, 0.254], // 4/4 beat 1
    [-0.274, 0.000, 0.067, 0.115, 0.137, 0.039, -0.170, -0.137, 0.003, -0.126, -0.102, -0.077], // 4/4 beat 2
    [0.066, -0.171, -0.193, -0.234, 0.010, 0.046, -0.251, -0.191, -0.002, -0.179, -0.178, -0.159], // 4/4 beat 3
    [-0.173, 0.063, 0.100, 0.173, 0.084, 0.055, -0.022, -0.003, 0.020, -0.053, -0.003, -0.021], // 4/4 beat 4
];
#[rustfmt::skip]
const TEMPLATE_SPREAD: [[f32; FEATURES]; 7] = [
    [1.115, 1.149, 1.137, 1.104, 0.837, 0.901, 1.091, 1.069, 1.005, 1.133, 1.064, 1.090], // 3/4 beat 1
    [0.739, 0.804, 0.821, 0.882, 0.941, 1.010, 0.844, 0.854, 1.005, 0.852, 0.943, 0.932], // 3/4 beat 2
    [0.841, 0.938, 0.932, 0.943, 1.175, 1.073, 0.903, 0.947, 0.986, 0.870, 0.965, 0.949], // 3/4 beat 3
    [1.127, 1.090, 1.049, 1.007, 0.850, 0.917, 1.125, 1.106, 1.065, 1.201, 1.154, 1.224], // 4/4 beat 1
    [0.820, 0.946, 0.975, 0.990, 1.062, 1.012, 0.862, 0.895, 0.974, 0.855, 0.916, 0.900], // 4/4 beat 2
    [0.944, 0.909, 0.909, 0.897, 0.971, 1.029, 0.902, 0.909, 0.984, 0.905, 0.869, 0.848], // 4/4 beat 3
    [0.862, 0.963, 0.968, 0.983, 1.048, 1.014, 0.911, 0.950, 0.956, 0.882, 0.963, 0.925], // 4/4 beat 4
];

/// (beats per bar, position) for each state, in template order.
const STATES: [(u32, u32); 7] = [(3, 0), (3, 1), (3, 2), (4, 0), (4, 1), (4, 2), (4, 3)];

#[derive(Debug, Clone, Copy, PartialEq)]
pub(crate) struct BarParams {
    /// Probability that the next beat continues the bar.
    pub stay: f32,
    /// Added to every template spread so no single feature decides a bar.
    pub spread_floor: f32,
    /// Log prior of 3/4 relative to 4/4.
    pub triple_prior: f32,
}

fn segment_mean(values: &[f32], from: usize, to: usize, out: &mut [f32; BEAT_SPECTROGRAM_MELS]) {
    out.fill(0.0);
    for row in values[from * BEAT_SPECTROGRAM_MELS..to * BEAT_SPECTROGRAM_MELS]
        .as_chunks::<BEAT_SPECTROGRAM_MELS>()
        .0
    {
        for (slot, v) in out.iter_mut().zip(row) {
            *slot += v;
        }
    }
    let scale = 1.0 / (to - from) as f32;
    out.iter_mut().for_each(|v| *v *= scale);
}

/// Subtracts a five-band moving average (mirrored at the edges) so harmonic peaks,
/// not the spectral envelope, decide whether two stretches sound alike.
fn whiten(spectrum: &[f32; BEAT_SPECTROGRAM_MELS]) -> [f32; BEAT_SPECTROGRAM_MELS] {
    let n = BEAT_SPECTROGRAM_MELS as isize;
    let at = |i: isize| -> f32 {
        let j = if i < 0 {
            -i - 1
        } else if i >= n {
            2 * n - i - 1
        } else {
            i
        };
        spectrum[j as usize]
    };
    std::array::from_fn(|b| {
        let b = b as isize;
        spectrum[b as usize] - (at(b - 2) + at(b - 1) + at(b) + at(b + 1) + at(b + 2)) / 5.0
    })
}

/// One minus the Pearson correlation of two spectra.
fn correlation_distance(a: &[f32], b: &[f32]) -> f32 {
    let n = a.len() as f32;
    let ma = a.iter().sum::<f32>() / n;
    let mb = b.iter().sum::<f32>() / n;
    let (mut ab, mut aa, mut bb) = (0.0, 0.0, 0.0);
    for (x, y) in a.iter().zip(b) {
        let (x, y) = (x - ma, y - mb);
        ab += x * y;
        aa += x * x;
        bb += y * y;
    }
    1.0 - ab / ((aa * bb).sqrt() + 1e-9)
}

/// Z-scored per-beat features; `beats` are fractional frames.
pub(crate) fn features(
    values: &[f32],
    frames: usize,
    bands: &[Vec<f32>; 4],
    beats: &[f64],
) -> Vec<[f32; FEATURES]> {
    let n = beats.len();
    let mut out = vec![[0.0f32; FEATURES]; n];
    let mut before = [0.0f32; BEAT_SPECTROGRAM_MELS];
    let mut after = [0.0f32; BEAT_SPECTROGRAM_MELS];
    for i in 0..n {
        let f = beats[i];
        let next = match (beats.get(i + 1), i.checked_sub(1)) {
            (Some(&next), _) => next,
            (None, Some(j)) => 2.0 * f - beats[j],
            (None, None) => f + 25.0,
        };
        let step = next - f;
        // Stretch from `k` beats back to `k` beats ahead, extrapolated past either end.
        let edges = |k: usize| -> (usize, usize) {
            let lo = if i >= k {
                beats[i - k]
            } else {
                f - k as f64 * step
            };
            let hi = beats.get(i + k).copied().unwrap_or(f + k as f64 * step);
            (
                lo.max(0.0) as usize,
                hi.min(frames as f64).max(0.0) as usize,
            )
        };
        let row = &mut out[i];
        for band in 0..4 {
            row[band] = peak(&bands[band], f);
        }
        row[4] = peak(&bands[0], f + 0.5 * step);
        row[5] = peak(&bands[3], f + 0.5 * step);
        let a1 = f.max(0.0) as usize;
        let (a0, b1) = edges(2);
        if a1 > a0 && b1 > a1 && a1 < frames {
            segment_mean(values, a0, a1, &mut before);
            segment_mean(values, a1, b1, &mut after);
            row[8] = after.iter().sum::<f32>() / BEAT_SPECTROGRAM_MELS as f32;
            let (b, a) = (whiten(&before), whiten(&after));
            row[6] = correlation_distance(&b[6..90], &a[6..90]);
            row[7] = correlation_distance(&b[..14], &a[..14]);
            row[9] = correlation_distance(&b[40..110], &a[40..110]);
            let (a0, b1) = edges(4);
            if a1 > a0 && b1 > a1 {
                segment_mean(values, a0, a1, &mut before);
                segment_mean(values, a1, b1, &mut after);
                let (b, a) = (whiten(&before), whiten(&after));
                row[10] = correlation_distance(&b[6..90], &a[6..90]);
                row[11] = correlation_distance(&b[40..110], &a[40..110]);
            }
        }
    }
    for column in 0..FEATURES {
        let mean = out.iter().map(|r| r[column]).sum::<f32>() / n.max(1) as f32;
        let var = out.iter().map(|r| (r[column] - mean).powi(2)).sum::<f32>() / n.max(1) as f32;
        let spread = var.sqrt() + 1e-6;
        out.iter_mut()
            .for_each(|r| r[column] = (r[column] - mean) / spread);
    }
    out
}

/// Bar position (0 = downbeat) of every beat, the dominant beats per bar, and the
/// per-beat log-likelihood margin of that bar phase over the best shifted phase.
pub(crate) fn decode(features: &[[f32; FEATURES]], params: &BarParams) -> (Vec<u32>, u32, f32) {
    let n = features.len();
    if n == 0 {
        return (Vec::new(), 4, 0.0);
    }
    let spread: Vec<[f32; FEATURES]> = TEMPLATE_SPREAD
        .iter()
        .map(|row| row.map(|s| s + params.spread_floor))
        .collect();
    let norm: Vec<f32> = spread
        .iter()
        .map(|row| row.iter().map(|s| s.ln()).sum())
        .collect();
    let log_likelihood = |i: usize, s: usize| -> f32 {
        let mut sum = 0.0;
        for k in 0..FEATURES {
            let z = (features[i][k] - TEMPLATE_MEAN[s][k]) / spread[s][k];
            sum += z * z;
        }
        -0.5 * sum - norm[s]
    };
    let advance = |s: usize| -> usize {
        let (meter, position) = STATES[s];
        let first = if meter == 3 { 0 } else { 3 };
        first + ((position + 1) % meter) as usize
    };
    let log_stay = params.stay.ln();
    let log_jump = ((1.0 - params.stay) / STATES.len() as f32).ln();
    let mut delta: Vec<f32> = (0..STATES.len())
        .map(|s| if STATES[s].0 == 3 { params.triple_prior } else { 0.0 } + log_likelihood(0, s))
        .collect();
    let mut back = vec![[0u8; 7]; n];
    let mut next = [0.0f32; 7];
    for (i, pointers) in back.iter_mut().enumerate().skip(1) {
        let (jump_from, jump_best) =
            delta
                .iter()
                .enumerate()
                .fold(
                    (0, f32::NEG_INFINITY),
                    |b, (s, v)| if *v > b.1 { (s, *v) } else { b },
                );
        next.fill(jump_best + log_jump);
        pointers.fill(jump_from as u8);
        for (source, value) in delta.iter().enumerate() {
            let target = advance(source);
            if value + log_stay > next[target] {
                next[target] = value + log_stay;
                pointers[target] = source as u8;
            }
        }
        for (s, slot) in next.iter_mut().enumerate() {
            *slot += log_likelihood(i, s);
        }
        delta.copy_from_slice(&next);
    }
    let mut state = (0..STATES.len()).fold(0, |b, s| if delta[s] > delta[b] { s } else { b });
    let mut positions = vec![0u32; n];
    let mut triple = 0;
    for i in (0..n).rev() {
        positions[i] = STATES[state].1;
        triple += usize::from(STATES[state].0 == 3);
        state = back[i][state] as usize;
    }
    let meter = if triple * 2 > n { 3 } else { 4 };
    // Same path, every bar position rotated: how much better is the chosen phase?
    let first = if meter == 3 { 0 } else { 3 };
    let shifted = |k: u32| -> f32 {
        (0..n)
            .map(|i| log_likelihood(i, first + ((positions[i] + k) % meter) as usize))
            .sum()
    };
    let chosen = shifted(0);
    let rival = (1..meter).map(shifted).fold(f32::NEG_INFINITY, f32::max);
    (positions, meter, (chosen - rival) / n as f32)
}
