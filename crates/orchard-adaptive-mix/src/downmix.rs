/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

//! Anti-aliased 44.1 → 22.05 kHz decimation for the beat model. `song` folds stereo down first.

const TAPS: usize = 63;
const HALF: isize = (TAPS / 2) as isize;
const LANES: usize = 8;

// 63-tap Hann-windowed sinc, cutoff 0.48 of the input Nyquist, normalized to unity DC gain.
// Beats deserve an anti-aliasing filter; the cymbals did not ask to become a bass drum.
fn kernel() -> [f32; TAPS] {
    let mut weights = [0.0f32; TAPS];
    for (i, weight) in weights.iter_mut().enumerate() {
        let x = (i as isize - HALF) as f32;
        let sinc = if x == 0.0 {
            0.48
        } else {
            (std::f32::consts::PI * 0.48 * x).sin() / (std::f32::consts::PI * x)
        };
        *weight = sinc * (0.5 + 0.5 * (std::f32::consts::PI * x / 32.0).cos());
    }
    let norm: f32 = weights.iter().sum();
    weights.iter_mut().for_each(|w| *w /= norm);
    weights
}

/// 44.1 kHz mono to 22.05 kHz mono. Edges clamp to the first and last sample.
pub fn beat_mono(mono: &[f32]) -> Result<Vec<f32>, String> {
    if mono.len() < 4 || mono.len() > 44_100 * 60 {
        return Err("Invalid PCM window".into());
    }
    let weights = kernel();
    let half = HALF as usize;
    // Edge padding replaces a per-tap clamp, so the loop below vectorizes.
    let mut padded = Vec::with_capacity(mono.len() + TAPS + 2 * LANES);
    padded.resize(half, mono[0]);
    padded.extend_from_slice(mono);
    padded.resize(padded.len() + half + 2 * LANES, mono[mono.len() - 1]);
    let frames = mono.len() / 2;
    let mut output = vec![0.0f32; frames.next_multiple_of(LANES)];
    // Each lane sums its taps in order, so results match a scalar dot product bit for bit.
    for (block, out) in output.as_chunks_mut::<LANES>().0.iter_mut().enumerate() {
        let start = 2 * LANES * block;
        let mut acc = [-0.0f32; LANES];
        for (i, w) in weights.iter().enumerate() {
            let taps = &padded[start + i..start + i + 2 * LANES];
            for (lane, value) in acc.iter_mut().enumerate() {
                *value += taps[2 * lane] * w;
            }
        }
        *out = acc;
    }
    output.truncate(frames);
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn preserves_dc_and_rejects_aliasing() {
        let dc = vec![0.3f32; 44100];
        let result = beat_mono(&dc).unwrap();
        assert_eq!(result.len(), 22050);
        assert!(result.iter().all(|v| (*v - 0.3).abs() < 1e-5));
        let high: Vec<_> = (0..44100)
            .map(|i| (std::f32::consts::TAU * 18000.0 * i as f32 / 44100.0).sin())
            .collect();
        let result = beat_mono(&high).unwrap();
        let rms = (result[32..result.len() - 32].iter().map(|v| v * v).sum::<f32>()
            / (result.len() - 64) as f32)
            .sqrt();
        assert!(rms < 0.01, "aliased signal RMS: {rms}");
    }

    #[test]
    fn matches_a_clamped_scalar_dot_product() {
        let mono: Vec<f32> = (0..9_001u32).map(|i| ((i * 7_919 % 1_000) as f32 / 500.0 - 1.0).powi(3)).collect();
        let weights = kernel();
        let last = mono.len() as isize - 1;
        let expected: Vec<f32> = (0..mono.len() / 2).map(|frame| {
            let centre = 2 * frame as isize;
            weights.iter().enumerate()
                .map(|(i, w)| mono[(centre + i as isize - HALF).clamp(0, last) as usize] * w)
                .sum()
        }).collect();
        let result = beat_mono(&mono).unwrap();
        assert!(result.iter().zip(&expected).all(|(a, b)| a.to_bits() == b.to_bits()));
        assert_eq!(result.len(), expected.len());
    }

    #[test]
    fn rejects_tiny_and_oversized_windows() {
        assert!(beat_mono(&[0.0; 3]).is_err());
        assert!(beat_mono(&vec![0.0; 44_100 * 61]).is_err());
    }
}
