//! Perceived loudness of a transition region, via EBU R128.
//!
//! Only the regions a transition actually touches are measured. Normalising whole tracks would
//! flatten the dynamics the planner is trying to read.

use ebur128::{EbuR128, Mode};

use crate::audio::AudioBuffer;
use crate::dsp::gain;
use crate::error::Result;
use crate::types::analysis::LoudnessStats;

/// Measures `[start, start + duration)` of `buffer`.
///
/// Regions shorter than R128's gating window, or quiet enough that every block is gated out,
/// fall back to an RMS estimate flagged via [`LoudnessStats::estimated`].
pub fn region_loudness(buffer: &AudioBuffer, start: f64, duration: f64) -> Result<LoudnessStats> {
    let channels = buffer.channel_count();
    let start_frame = buffer.frame_index(start).min(buffer.frames());
    let requested = (duration.max(0.0) * buffer.sample_rate() as f64).round() as usize;
    let frames = requested.min(buffer.frames() - start_frame);
    if frames == 0 {
        return Ok(LoudnessStats::default());
    }

    let slices: Vec<&[f32]> = (0..channels)
        .map(|c| &buffer.channel(c)[start_frame..start_frame + frames])
        .collect();

    let mut meter = EbuR128::new(
        channels as u32,
        buffer.sample_rate(),
        Mode::I | Mode::SAMPLE_PEAK,
    )?;
    meter.add_frames_planar_f32(&slices)?;

    let mut peak = 0.0f32;
    for channel in 0..channels as u32 {
        peak = peak.max(meter.sample_peak(channel)? as f32);
    }

    let measured = meter.loudness_global()?;
    if measured.is_finite() && measured > -70.0 {
        Ok(LoudnessStats {
            lufs: measured as f32,
            peak,
            estimated: false,
        })
    } else {
        let power: f32 = slices.iter().map(|s| gain::rms(s).powi(2)).sum();
        Ok(LoudnessStats {
            lufs: gain::linear_to_db((power / channels as f32).sqrt()).max(-70.0),
            peak,
            estimated: true,
        })
    }
}

/// Level difference between two regions, positive when `a` is the louder one.
pub fn difference_db(a: LoudnessStats, b: LoudnessStats) -> f32 {
    a.lufs - b.lufs
}

#[cfg(test)]
mod tests {
    use super::*;

    const SR: u32 = 48_000;

    fn sine(amplitude: f32, seconds: f64, channels: usize) -> AudioBuffer {
        let frames = (seconds * SR as f64) as usize;
        let channel: Vec<f32> = (0..frames)
            .map(|i| amplitude * (i as f32 / SR as f32 * 440.0 * std::f32::consts::TAU).sin())
            .collect();
        AudioBuffer::new(vec![channel; channels], SR).unwrap()
    }

    #[test]
    fn a_full_scale_tone_measures_near_minus_three_lufs() {
        let stats = region_loudness(&sine(1.0, 3.0, 1), 0.0, 3.0).unwrap();
        assert!(!stats.estimated);
        assert!(
            (-6.0..0.0).contains(&stats.lufs),
            "unexpected loudness {}",
            stats.lufs
        );
        assert!((stats.peak - 1.0).abs() < 0.01);
    }

    #[test]
    fn halving_amplitude_costs_six_db() {
        let loud = region_loudness(&sine(1.0, 3.0, 2), 0.0, 3.0).unwrap();
        let quiet = region_loudness(&sine(0.5, 3.0, 2), 0.0, 3.0).unwrap();
        assert!(
            (difference_db(loud, quiet) - 6.02).abs() < 0.2,
            "difference {}",
            difference_db(loud, quiet)
        );
    }

    #[test]
    fn only_the_requested_window_is_measured() {
        let mut buffer = sine(1.0, 6.0, 1);
        buffer.channel_mut(0)[..3 * SR as usize].fill(0.0);

        let silent_half = region_loudness(&buffer, 0.0, 3.0).unwrap();
        let loud_half = region_loudness(&buffer, 3.0, 3.0).unwrap();
        assert!(loud_half.lufs > silent_half.lufs + 40.0);
    }

    #[test]
    fn short_regions_fall_back_to_an_estimate() {
        let stats = region_loudness(&sine(1.0, 0.1, 1), 0.0, 0.1).unwrap();
        assert!(stats.estimated);
        assert!(stats.lufs > -20.0, "estimate {}", stats.lufs);
    }

    #[test]
    fn silence_reports_the_floor() {
        let buffer = AudioBuffer::silent(2, SR as usize * 2, SR).unwrap();
        let stats = region_loudness(&buffer, 0.0, 2.0).unwrap();
        assert!(stats.estimated);
        assert!(stats.lufs <= -70.0);
        assert_eq!(stats.peak, 0.0);
    }

    #[test]
    fn a_window_past_the_end_is_empty() {
        let stats = region_loudness(&sine(1.0, 1.0, 1), 5.0, 1.0).unwrap();
        assert_eq!(stats, LoudnessStats::default());
    }

    #[test]
    fn a_window_is_clipped_to_the_available_audio() {
        let stats = region_loudness(&sine(1.0, 3.0, 1), 1.0, 60.0).unwrap();
        assert!(!stats.estimated);
    }

    #[test]
    fn measurement_is_repeatable() {
        let buffer = sine(0.7, 3.0, 2);
        let first = region_loudness(&buffer, 0.5, 2.0).unwrap();
        let second = region_loudness(&buffer, 0.5, 2.0).unwrap();
        assert_eq!(first, second);
    }
}
