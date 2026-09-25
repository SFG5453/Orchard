//! Per-stem preparation and the mix-down helpers the renderer drives.

use crate::audio::stretch::TimeStretcher;
use crate::audio::{AudioBuffer, resample};
use crate::config::EngineConfig;
use crate::dsp::filters::{FilterAutomation, FilterSweep};
use crate::dsp::gain::{db_to_linear, linear_to_db};
use crate::dsp::mixer;
use crate::error::Result;

/// Stretch ratios within this of 1.0 are treated as no stretch at all, which skips the
/// stretcher entirely for the common unmatched case.
const RATIO_EPSILON: f32 = 1e-4;

/// One side of the transition, prepared and ready to mix.
pub struct Stem {
    pub audio: AudioBuffer,
    /// Seconds of source actually consumed, which differs from the planned amount only when a
    /// stretch had to be skipped.
    pub consumed: f64,
}

/// What one side of the transition needs to become, taken from the plan.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct StemRequest {
    /// Position in the source track, in seconds.
    pub start: f64,
    /// Length of the transition in output time.
    pub duration: f64,
    /// Source seconds consumed per output second.
    pub ratio: f32,
    pub semitones: f32,
    pub frames: usize,
    pub sample_rate: u32,
    pub channels: usize,
}

/// Extracts one side at the target rate and layout, stretched to exactly `request.frames`.
///
/// A transition too short for the stretcher's latency window is rendered at native tempo rather
/// than failing; [`Stem::consumed`] then reports what was really used, so the caller's resume
/// positions stay honest.
pub fn prepare(
    stretcher: &mut TimeStretcher,
    source: &AudioBuffer,
    request: &StemRequest,
) -> Result<Stem> {
    let stretching = (request.ratio - 1.0).abs() > RATIO_EPSILON || request.semitones != 0.0;
    let apply_stretch = stretching && stretcher.can_process(request.frames);
    let consumed = if apply_stretch {
        request.duration * request.ratio as f64
    } else {
        request.duration
    };

    let mut slice = source.slice_seconds(request.start, consumed);
    if slice.sample_rate() != request.sample_rate {
        slice = resample::resample(&slice, request.sample_rate)?;
    }
    if slice.channel_count() != request.channels {
        slice = slice.to_channel_count(request.channels)?;
    }

    let audio = if apply_stretch {
        stretcher.process(&slice, request.frames, request.semitones)?
    } else if slice.frames() == request.frames {
        slice
    } else {
        slice.slice(0, request.frames)
    };

    Ok(Stem { audio, consumed })
}

pub fn apply_filters(
    audio: &mut AudioBuffer,
    filters: &[FilterAutomation],
    config: &EngineConfig,
) -> Result<()> {
    if filters.is_empty() {
        return Ok(());
    }
    let frames = audio.frames();
    let sample_rate = audio.sample_rate();
    for automation in filters {
        let sweep = FilterSweep::plan(automation, frames, sample_rate, &config.filters)?;
        for channel in audio.planar_mut() {
            sweep.apply(channel);
        }
    }
    Ok(())
}

/// `mixed += stem * envelope`, across every channel.
pub fn mix_stem(mixed: &mut AudioBuffer, stem: &AudioBuffer, envelope: &[f32]) {
    for channel in 0..mixed.channel_count() {
        mixer::mix_into(mixed.channel_mut(channel), stem.channel(channel), envelope);
    }
}

/// Pulls the whole mix down if summing the two sides pushed it past the configured ceiling.
/// Returns the trim in dB, or zero when none was needed.
pub fn trim_to_ceiling(mixed: &mut AudioBuffer, config: &EngineConfig) -> f32 {
    if !config.loudness.prevent_clipping {
        return 0.0;
    }
    let ceiling = db_to_linear(config.loudness.ceiling_db);
    let peak = mixed.peak();
    if peak <= ceiling || peak <= 0.0 {
        return 0.0;
    }
    let trim = ceiling / peak;
    mixed.apply_gain(trim);
    linear_to_db(trim)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::LoudnessConfig;
    use crate::dsp::automation::{AutomationCurve, CurveShape};
    use crate::dsp::filters::FilterKind;
    use crate::dsp::gain::rms;

    const SR: u32 = 48_000;

    fn tone(freq: f32, seconds: f64, amplitude: f32, channels: usize, rate: u32) -> AudioBuffer {
        let frames = (seconds * rate as f64) as usize;
        let channel: Vec<f32> = (0..frames)
            .map(|i| amplitude * (i as f32 / rate as f32 * freq * std::f32::consts::TAU).sin())
            .collect();
        AudioBuffer::new(vec![channel; channels], rate).unwrap()
    }

    fn stretcher() -> TimeStretcher {
        TimeStretcher::new(2, SR).unwrap()
    }

    fn request(duration: f64, ratio: f32, frames: usize) -> StemRequest {
        StemRequest {
            start: 1.0,
            duration,
            ratio,
            semitones: 0.0,
            frames,
            sample_rate: SR,
            channels: 2,
        }
    }

    #[test]
    fn preparation_hits_the_requested_frame_count() {
        let mut stretcher = stretcher();
        let frames = 2 * SR as usize;
        let stem = prepare(
            &mut stretcher,
            &tone(440.0, 10.0, 0.5, 2, SR),
            &request(2.0, 1.0, frames),
        )
        .unwrap();
        assert_eq!(stem.audio.frames(), frames);
        assert!((stem.consumed - 2.0).abs() < 1e-9);
    }

    #[test]
    fn a_stretched_stem_consumes_more_source() {
        let mut stretcher = stretcher();
        let frames = 2 * SR as usize;
        let stem = prepare(
            &mut stretcher,
            &tone(440.0, 10.0, 0.5, 2, SR),
            &request(2.0, 1.06, frames),
        )
        .unwrap();
        assert_eq!(stem.audio.frames(), frames);
        assert!((stem.consumed - 2.0 * 1.06f32 as f64).abs() < 1e-9);
        assert!(stem.consumed > 2.0);
    }

    #[test]
    fn a_transition_too_short_to_stretch_falls_back_to_native_tempo() {
        let mut stretcher = stretcher();
        let frames = stretcher.min_output_frames() - 1;
        let duration = frames as f64 / SR as f64;
        let stem = prepare(
            &mut stretcher,
            &tone(440.0, 10.0, 0.5, 2, SR),
            &request(duration, 1.06, frames),
        )
        .unwrap();
        assert_eq!(stem.audio.frames(), frames);
        assert!((stem.consumed - duration).abs() < 1e-9);
    }

    #[test]
    fn rate_and_layout_are_converted_during_preparation() {
        let mut stretcher = stretcher();
        let frames = SR as usize;
        let stem = prepare(
            &mut stretcher,
            &tone(440.0, 10.0, 0.5, 1, 44_100),
            &request(1.0, 1.0, frames),
        )
        .unwrap();
        assert_eq!(stem.audio.frames(), frames);
        assert_eq!(stem.audio.channel_count(), 2);
        assert_eq!(stem.audio.sample_rate(), SR);
    }

    #[test]
    fn filters_are_applied_to_every_channel() {
        let config = EngineConfig::default();
        let mut audio = tone(8_000.0, 1.0, 0.8, 2, SR);
        let before = rms(audio.channel(1));

        apply_filters(
            &mut audio,
            &[FilterAutomation::new(
                FilterKind::LowPass,
                AutomationCurve::ramp(500.0, 500.0, CurveShape::Logarithmic),
                config.filters.q,
            )],
            &config,
        )
        .unwrap();

        assert!(rms(audio.channel(0)) < before * 0.1);
        assert_eq!(audio.channel(0), audio.channel(1));
    }

    #[test]
    fn an_empty_filter_list_is_a_no_op() {
        let config = EngineConfig::default();
        let mut audio = tone(1_000.0, 0.5, 0.5, 2, SR);
        let original = audio.clone();
        apply_filters(&mut audio, &[], &config).unwrap();
        assert_eq!(audio, original);
    }

    #[test]
    fn mixing_accumulates_both_stems() {
        let mut mixed = AudioBuffer::silent(2, 4, SR).unwrap();
        let stem = AudioBuffer::new(vec![vec![1.0; 4], vec![1.0; 4]], SR).unwrap();
        mix_stem(&mut mixed, &stem, &[0.5; 4]);
        mix_stem(&mut mixed, &stem, &[0.25; 4]);
        assert_eq!(mixed.channel(0), &[0.75; 4]);
        assert_eq!(mixed.channel(1), &[0.75; 4]);
    }

    #[test]
    fn the_ceiling_only_engages_above_the_limit() {
        let config = EngineConfig::default();
        let mut quiet = tone(440.0, 0.5, 0.3, 2, SR);
        assert_eq!(trim_to_ceiling(&mut quiet, &config), 0.0);

        let mut loud = tone(440.0, 0.5, 1.5, 2, SR);
        let trim = trim_to_ceiling(&mut loud, &config);
        assert!(trim < 0.0);
        assert!(loud.peak() <= db_to_linear(config.loudness.ceiling_db) + 1e-4);
    }

    #[test]
    fn silence_is_never_trimmed() {
        let config = EngineConfig::default();
        let mut silent = AudioBuffer::silent(2, 128, SR).unwrap();
        assert_eq!(trim_to_ceiling(&mut silent, &config), 0.0);
    }

    #[test]
    fn the_ceiling_can_be_disabled() {
        let config = EngineConfig {
            loudness: LoudnessConfig {
                prevent_clipping: false,
                ..LoudnessConfig::default()
            },
            ..EngineConfig::default()
        };
        let mut loud = tone(440.0, 0.5, 1.5, 2, SR);
        assert_eq!(trim_to_ceiling(&mut loud, &config), 0.0);
        assert!(loud.peak() > 1.0);
    }
}
