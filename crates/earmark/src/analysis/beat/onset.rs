//! Onset strength per frequency band from the 50 fps log-mel beat spectrogram.

use crate::analysis::BEAT_SPECTROGRAM_MELS;

/// Half-open mel band ranges: kick/bass (< 160 Hz), low mids, mids, and highs (> 1.6 kHz).
pub(crate) const BANDS: [(usize, usize); 4] = [(0, 6), (6, 20), (20, 60), (60, 128)];

/// SuperFlux-style rise per band group. Each band is compared with the loudest
/// neighbouring band one frame earlier, so vibrato and pitch slides do not read as onsets.
pub(crate) fn band_flux(values: &[f32], frames: usize) -> [Vec<f32>; 4] {
    let mut out: [Vec<f32>; 4] = std::array::from_fn(|_| vec![0.0; frames]);
    let mels = BEAT_SPECTROGRAM_MELS;
    for t in 1..frames {
        let previous = &values[(t - 1) * mels..t * mels];
        let current = &values[t * mels..(t + 1) * mels];
        for (group, &(lo, hi)) in BANDS.iter().enumerate() {
            let mut rise = 0.0;
            for band in lo..hi {
                let reference = previous[band.saturating_sub(1)]
                    .max(previous[band])
                    .max(previous[(band + 1).min(mels - 1)]);
                rise += (current[band] - reference).max(0.0);
            }
            out[group][t] = rise / (hi - lo) as f32;
        }
    }
    out
}

/// Subtracts the local mean over `radius` frames each side, half-wave rectifies,
/// and scales the result to unit RMS so band weights compare like with like.
pub(crate) fn normalize(values: &mut [f32], radius: usize) {
    let n = values.len();
    if n == 0 {
        return;
    }
    let mut prefix = vec![0.0f64; n + 1];
    for (i, v) in values.iter().enumerate() {
        prefix[i + 1] = prefix[i] + *v as f64;
    }
    let mut square = 0.0f64;
    for (i, value) in values.iter_mut().enumerate() {
        let lo = i.saturating_sub(radius);
        let hi = (i + radius + 1).min(n);
        let mean = (prefix[hi] - prefix[lo]) / (hi - lo) as f64;
        let v = (*value as f64 - mean).max(0.0);
        *value = v as f32;
        square += v * v;
    }
    let rms = (square / n as f64).sqrt();
    if rms > 1e-9 {
        let scale = (1.0 / rms) as f32;
        values.iter_mut().for_each(|v| *v *= scale);
    }
}

/// Weighted mean of the band onsets.
pub(crate) fn weighted(bands: &[Vec<f32>; 4], weights: &[f64; 4]) -> Vec<f32> {
    let total: f64 = weights.iter().sum::<f64>().max(1e-6);
    let mut out = vec![0.0f32; bands[0].len()];
    for (band, w) in bands.iter().zip(weights) {
        let w = (*w / total) as f32;
        if w != 0.0 {
            for (slot, v) in out.iter_mut().zip(band) {
                *slot += v * w;
            }
        }
    }
    out
}

/// Linear interpolation to `factor` times the frame rate.
pub(crate) fn upsample(values: &[f32], factor: usize) -> Vec<f32> {
    if factor <= 1 {
        return values.to_vec();
    }
    let mut out = Vec::with_capacity(values.len() * factor);
    for (i, &v) in values.iter().enumerate() {
        let next = values.get(i + 1).copied().unwrap_or(v);
        out.extend((0..factor).map(|j| v + (next - v) * j as f32 / factor as f32));
    }
    out
}

/// Strongest onset within two frames of `frame`.
pub(crate) fn peak(onset: &[f32], frame: f64) -> f32 {
    let centre = frame.round().max(0.0) as usize;
    let lo = centre.saturating_sub(2).min(onset.len());
    let hi = (centre + 3).min(onset.len());
    onset[lo..hi].iter().copied().fold(0.0, f32::max)
}

/// Moves a decoded beat onto the strongest onset within two frames, with sub-frame
/// precision from a parabola through the peak and its neighbours.
pub(crate) fn refine(onset: &[f32], frame: usize) -> f64 {
    let lo = frame.saturating_sub(2);
    let hi = (frame + 3).min(onset.len());
    let mut top = frame.min(onset.len() - 1);
    for t in lo..hi {
        if onset[t] > onset[top] {
            top = t;
        }
    }
    if top == 0 || top + 1 >= onset.len() {
        return top as f64;
    }
    let (l, m, r) = (
        onset[top - 1] as f64,
        onset[top] as f64,
        onset[top + 1] as f64,
    );
    let d = l - 2.0 * m + r;
    if d.abs() < 1e-9 {
        return top as f64;
    }
    top as f64 + (0.5 * (l - r) / d).clamp(-0.5, 0.5)
}
