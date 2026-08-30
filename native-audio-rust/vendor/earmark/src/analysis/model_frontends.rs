//! Model-contract spectrogram frontends used by Orchard's optional beat and vocal models.

use super::fft::{FftWorkspace, periodic_hann, reflect_pad};
use crate::error::Result;

pub const BEAT_SPECTROGRAM_SAMPLE_RATE: f64 = 22_050.0;
pub const BEAT_SPECTROGRAM_MELS: usize = 128;
pub const BEAT_SPECTROGRAM_FFT: usize = 1024;
pub const BEAT_SPECTROGRAM_HOP: usize = 441;

pub const VOCAL_SPECTROGRAM_SAMPLE_RATE: f64 = 44_100.0;
pub const VOCAL_SPECTROGRAM_CHANNELS: usize = 2;
pub const VOCAL_SPECTROGRAM_FFT: usize = 4096;
pub const VOCAL_SPECTROGRAM_BINS: usize = VOCAL_SPECTROGRAM_FFT / 2 + 1;
pub const VOCAL_SPECTROGRAM_HOP: usize = 1024;

#[derive(Debug, Clone, PartialEq, Default)]
pub struct BeatSpectrogram {
    /// Flattened row-major `[frames][mels]` values.
    pub values: Vec<f32>,
    pub frames: usize,
}

impl BeatSpectrogram {
    pub fn mels(&self) -> usize {
        BEAT_SPECTROGRAM_MELS
    }

    pub fn frames_per_second(&self) -> f64 {
        BEAT_SPECTROGRAM_SAMPLE_RATE / BEAT_SPECTROGRAM_HOP as f64
    }
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct VocalSpectrogram {
    /// Flattened `[channel][bin][frame]` values, matching ONNX `[1, 2, 2049, frames]`.
    pub values: Vec<f32>,
    pub frames: usize,
}

impl VocalSpectrogram {
    pub fn channels(&self) -> usize {
        VOCAL_SPECTROGRAM_CHANNELS
    }

    pub fn bins(&self) -> usize {
        VOCAL_SPECTROGRAM_BINS
    }

    pub fn frames_per_second(&self) -> f64 {
        VOCAL_SPECTROGRAM_SAMPLE_RATE / VOCAL_SPECTROGRAM_HOP as f64
    }
}

#[derive(Debug)]
struct MelFilter {
    first_bin: usize,
    weights: Vec<f64>,
}

fn hz_to_mel(hz: f64) -> f64 {
    const F_SP: f64 = 200.0 / 3.0;
    const MIN_LOG_HZ: f64 = 1000.0;
    let min_log_mel = MIN_LOG_HZ / F_SP;
    let logstep = 6.4_f64.ln() / 27.0;
    if hz >= MIN_LOG_HZ {
        min_log_mel + (hz / MIN_LOG_HZ).ln() / logstep
    } else {
        hz / F_SP
    }
}

fn mel_to_hz(mel: f64) -> f64 {
    const F_SP: f64 = 200.0 / 3.0;
    const MIN_LOG_HZ: f64 = 1000.0;
    let min_log_mel = MIN_LOG_HZ / F_SP;
    let logstep = 6.4_f64.ln() / 27.0;
    if mel >= min_log_mel {
        MIN_LOG_HZ * (logstep * (mel - min_log_mel)).exp()
    } else {
        F_SP * mel
    }
}

fn mel_filterbank(sample_rate: f64) -> Vec<MelFilter> {
    const MIN_HZ: f64 = 30.0;
    const MAX_HZ: f64 = 11_000.0;
    let bins = BEAT_SPECTROGRAM_FFT / 2 + 1;
    let mel_min = hz_to_mel(MIN_HZ);
    let mel_max = hz_to_mel(MAX_HZ);
    let edges: Vec<f64> = (0..BEAT_SPECTROGRAM_MELS + 2)
        .map(|index| {
            mel_to_hz(
                mel_min + (mel_max - mel_min) * index as f64 / (BEAT_SPECTROGRAM_MELS + 1) as f64,
            )
        })
        .collect();

    (0..BEAT_SPECTROGRAM_MELS)
        .map(|mel| {
            let left = edges[mel];
            let centre = edges[mel + 1];
            let right = edges[mel + 2];
            let to_bin = |hz: f64| hz * BEAT_SPECTROGRAM_FFT as f64 / sample_rate;
            let first = to_bin(left).floor().max(0.0) as usize;
            let last = (to_bin(right).ceil() as usize).min(bins - 1);
            let weights = (first..=last)
                .map(|bin| {
                    let hz = bin as f64 * sample_rate / BEAT_SPECTROGRAM_FFT as f64;
                    let rising = if centre > left {
                        (hz - left) / (centre - left)
                    } else {
                        0.0
                    };
                    let falling = if right > centre {
                        (right - hz) / (right - centre)
                    } else {
                        0.0
                    };
                    rising.min(falling).max(0.0)
                })
                .collect();
            MelFilter {
                first_bin: first,
                weights,
            }
        })
        .collect()
}

pub(crate) struct ModelFrontends {
    beat_fft: FftWorkspace,
    beat_window: Vec<f64>,
    beat_filters: Vec<MelFilter>,
    vocal_fft: FftWorkspace,
    vocal_window: Vec<f64>,
}

impl ModelFrontends {
    pub(crate) fn new() -> Self {
        Self {
            beat_fft: FftWorkspace::new(BEAT_SPECTROGRAM_FFT),
            beat_window: periodic_hann(BEAT_SPECTROGRAM_FFT),
            beat_filters: mel_filterbank(BEAT_SPECTROGRAM_SAMPLE_RATE),
            vocal_fft: FftWorkspace::new(VOCAL_SPECTROGRAM_FFT),
            vocal_window: periodic_hann(VOCAL_SPECTROGRAM_FFT),
        }
    }

    pub(crate) fn beat_spectrogram(
        &mut self,
        samples: &[f32],
        sample_rate: f64,
    ) -> Result<BeatSpectrogram> {
        if (sample_rate - BEAT_SPECTROGRAM_SAMPLE_RATE).abs() > 1.0 {
            return Ok(BeatSpectrogram::default());
        }
        let Some(padded) = reflect_pad(samples, BEAT_SPECTROGRAM_FFT / 2) else {
            return Ok(BeatSpectrogram::default());
        };
        if padded.len() < BEAT_SPECTROGRAM_FFT {
            return Ok(BeatSpectrogram::default());
        }
        let frames = (padded.len() - BEAT_SPECTROGRAM_FFT) / BEAT_SPECTROGRAM_HOP + 1;
        let mut result = BeatSpectrogram {
            values: vec![0.0; frames * BEAT_SPECTROGRAM_MELS],
            frames,
        };
        let normalization = (BEAT_SPECTROGRAM_FFT as f64).sqrt();
        for frame in 0..frames {
            let start = frame * BEAT_SPECTROGRAM_HOP;
            let spectrum = self.beat_fft.process_windowed(
                &padded[start..start + BEAT_SPECTROGRAM_FFT],
                &self.beat_window,
            )?;
            let row = &mut result.values
                [frame * BEAT_SPECTROGRAM_MELS..(frame + 1) * BEAT_SPECTROGRAM_MELS];
            for (mel, filter) in self.beat_filters.iter().enumerate() {
                let energy = filter
                    .weights
                    .iter()
                    .enumerate()
                    .map(|(offset, weight)| {
                        spectrum[filter.first_bin + offset].norm() / normalization * weight
                    })
                    .sum::<f64>();
                row[mel] = (1000.0 * energy.max(1e-10)).ln_1p() as f32;
            }
        }
        Ok(result)
    }

    pub(crate) fn vocal_spectrogram(
        &mut self,
        channels: &[&[f32]],
        sample_rate: f64,
    ) -> Result<VocalSpectrogram> {
        if (sample_rate - VOCAL_SPECTROGRAM_SAMPLE_RATE).abs() > 1.0
            || channels.len() != VOCAL_SPECTROGRAM_CHANNELS
            || channels[0].len() != channels[1].len()
        {
            return Ok(VocalSpectrogram::default());
        }
        let padded: Option<Vec<Vec<f32>>> = channels
            .iter()
            .map(|channel| reflect_pad(channel, VOCAL_SPECTROGRAM_FFT / 2))
            .collect();
        let Some(padded) = padded else {
            return Ok(VocalSpectrogram::default());
        };
        let frames = (padded[0].len() - VOCAL_SPECTROGRAM_FFT) / VOCAL_SPECTROGRAM_HOP + 1;
        let mut result = VocalSpectrogram {
            values: vec![0.0; VOCAL_SPECTROGRAM_CHANNELS * VOCAL_SPECTROGRAM_BINS * frames],
            frames,
        };
        for (channel, source) in padded.iter().enumerate() {
            for frame in 0..frames {
                let start = frame * VOCAL_SPECTROGRAM_HOP;
                let spectrum = self.vocal_fft.process_windowed(
                    &source[start..start + VOCAL_SPECTROGRAM_FFT],
                    &self.vocal_window,
                )?;
                let base = channel * VOCAL_SPECTROGRAM_BINS * frames;
                for (bin, value) in spectrum.iter().take(VOCAL_SPECTROGRAM_BINS).enumerate() {
                    result.values[base + bin * frames + frame] = value.norm() as f32;
                }
            }
        }
        Ok(result)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use approx::assert_abs_diff_eq;

    fn reference_signal(sample_rate: usize, samples: usize) -> Vec<f32> {
        (0..samples)
            .map(|index| {
                let time = index as f64 / sample_rate as f64;
                let mut value = 0.18 * (std::f64::consts::TAU * 220.0 * time).sin()
                    + 0.11 * (std::f64::consts::TAU * 880.0 * time).sin();
                let phase = time % 0.5;
                if phase < 0.03 {
                    value +=
                        0.6 * (1.0 - phase / 0.03) * (std::f64::consts::TAU * 70.0 * time).sin();
                }
                value as f32
            })
            .collect()
    }

    #[test]
    fn beat_frontend_preserves_shape_and_rate_contract() {
        let mut frontends = ModelFrontends::new();
        let samples: Vec<f32> = (0..22_050)
            .map(|i| (std::f32::consts::TAU * 440.0 * i as f32 / 22_050.0).sin())
            .collect();
        let spectrogram = frontends.beat_spectrogram(&samples, 22_050.0).unwrap();
        assert_eq!(spectrogram.frames, 51);
        assert_eq!(spectrogram.values.len(), 51 * 128);
        assert!((spectrogram.frames_per_second() - 50.0).abs() < 1e-12);
        assert!(spectrogram.values.iter().all(|value| value.is_finite()));
        assert_eq!(
            frontends
                .beat_spectrogram(&samples, 44_100.0)
                .unwrap()
                .frames,
            0
        );
    }

    #[test]
    fn vocal_frontend_is_channel_bin_frame_ordered() {
        let mut frontends = ModelFrontends::new();
        let left: Vec<f32> = (0..4_410)
            .map(|i| (std::f32::consts::TAU * 440.0 * i as f32 / 44_100.0).sin())
            .collect();
        let right: Vec<f32> = left.iter().map(|value| value * 0.5).collect();
        let spectrogram = frontends
            .vocal_spectrogram(&[&left, &right], 44_100.0)
            .unwrap();
        assert_eq!(spectrogram.frames, 5);
        assert_eq!(spectrogram.values.len(), 2 * 2049 * 5);
        let bin = 41;
        let left_value = spectrogram.values[bin * 5 + 2];
        let right_value = spectrogram.values[2049 * 5 + bin * 5 + 2];
        assert!((right_value / left_value - 0.5).abs() < 1e-4);
    }

    #[test]
    fn beat_frontend_matches_cpp_reference_bins() {
        let mut frontends = ModelFrontends::new();
        let values = frontends
            .beat_spectrogram(&reference_signal(22_050, 22_050), 22_050.0)
            .unwrap()
            .values;
        for (index, expected) in [
            (0, 7.830_829_6),
            (127, 2.439_076_2),
            (128, 7.329_582_7),
            (1000, 0.000_091_394_875),
        ] {
            assert_abs_diff_eq!(values[index], expected, epsilon = 2e-5);
        }
    }

    #[test]
    fn vocal_frontend_matches_cpp_reference_magnitudes_and_reflect_edge() {
        let mut frontends = ModelFrontends::new();
        let left = reference_signal(44_100, 8_820);
        let right: Vec<f32> = left.iter().map(|value| value * 0.5).collect();
        let spectrogram = frontends
            .vocal_spectrogram(&[&left, &right], 44_100.0)
            .unwrap();
        assert_eq!(spectrogram.frames, 9);
        for (index, expected) in [
            (0, 134.692_89),
            (1, 64.178_23),
            (1000, 1.457_197_5),
            (36_881, 0.003_800_718),
        ] {
            assert_abs_diff_eq!(spectrogram.values[index], expected, epsilon = 2e-4);
        }
    }
}
