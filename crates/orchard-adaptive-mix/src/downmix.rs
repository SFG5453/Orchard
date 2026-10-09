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

//! Anti-aliased 44.1 → 22.05 kHz decimation for Beat This. `song` folds stereo down first.

const TAPS: usize = 63;
const HALF: isize = (TAPS / 2) as isize;

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
    let last = mono.len() as isize - 1;
    let weights = kernel();
    Ok((0..mono.len() / 2)
        .map(|frame| {
            let centre = 2 * frame as isize;
            weights
                .iter()
                .enumerate()
                .map(|(i, w)| mono[(centre + i as isize - HALF).clamp(0, last) as usize] * w)
                .sum()
        })
        .collect())
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
    fn rejects_tiny_and_oversized_windows() {
        assert!(beat_mono(&[0.0; 3]).is_err());
        assert!(beat_mono(&vec![0.0; 44_100 * 61]).is_err());
    }
}
