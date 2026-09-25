//! Sample-rate conversion.
//!
//! Only used to reconcile two tracks that were decoded at different rates. Musical tempo
//! matching is never done by resampling — see [`crate::audio::stretch`].

use rubato::audioadapter_buffers::direct::SequentialSliceOfVecs;
use rubato::{Fft, FixedSync, Resampler};

use crate::audio::AudioBuffer;
use crate::error::{CrossfadeError, Result};

/// Frames per processing chunk. Large enough to keep the FFT resampler efficient, small enough
/// that its internal delay stays short.
const CHUNK_FRAMES: usize = 1024;

pub fn resample(buffer: &AudioBuffer, target_rate: u32) -> Result<AudioBuffer> {
    if target_rate == 0 {
        return Err(CrossfadeError::audio("target sample rate must be non-zero"));
    }
    if buffer.sample_rate() == target_rate {
        return Ok(buffer.clone());
    }

    let channels = buffer.channel_count();
    let frames = buffer.frames();
    if frames == 0 {
        return AudioBuffer::silent(channels, 0, target_rate);
    }

    let mut resampler = Fft::<f32>::new(
        buffer.sample_rate() as usize,
        target_rate as usize,
        CHUNK_FRAMES,
        channels,
        FixedSync::Input,
    )?;

    let input = SequentialSliceOfVecs::new(buffer.planar(), channels, frames)
        .map_err(|e| CrossfadeError::dsp(format!("resampler input adapter: {e}")))?;
    let output = resampler.process_all(&input, frames, None)?;

    AudioBuffer::from_interleaved(&output.take_data(), channels, target_rate)
}

/// Resamples only when the rate actually differs, avoiding a copy in the common case.
pub fn resample_if_needed<'a>(
    buffer: &'a AudioBuffer,
    target_rate: u32,
) -> Result<std::borrow::Cow<'a, AudioBuffer>> {
    if buffer.sample_rate() == target_rate {
        Ok(std::borrow::Cow::Borrowed(buffer))
    } else {
        Ok(std::borrow::Cow::Owned(resample(buffer, target_rate)?))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dsp::gain::rms;

    fn sine(freq: f32, seconds: f64, sample_rate: u32, channels: usize) -> AudioBuffer {
        let frames = (seconds * sample_rate as f64) as usize;
        let channel: Vec<f32> = (0..frames)
            .map(|i| (i as f32 / sample_rate as f32 * freq * std::f32::consts::TAU).sin())
            .collect();
        AudioBuffer::new(vec![channel; channels], sample_rate).unwrap()
    }

    #[test]
    fn matching_rate_is_a_passthrough() {
        let buffer = sine(440.0, 0.1, 48_000, 2);
        assert_eq!(resample(&buffer, 48_000).unwrap(), buffer);
    }

    #[test]
    fn upsampling_preserves_duration_and_level() {
        let buffer = sine(440.0, 1.0, 44_100, 2);
        let resampled = resample(&buffer, 48_000).unwrap();

        assert_eq!(resampled.sample_rate(), 48_000);
        assert_eq!(resampled.channel_count(), 2);
        assert!(
            (resampled.duration() - buffer.duration()).abs() < 0.01,
            "duration drifted: {} vs {}",
            resampled.duration(),
            buffer.duration()
        );
        let level = rms(resampled.channel(0));
        assert!(
            (level - rms(buffer.channel(0))).abs() < 0.05,
            "level {level}"
        );
    }

    #[test]
    fn downsampling_preserves_duration() {
        let buffer = sine(440.0, 1.0, 48_000, 1);
        let resampled = resample(&buffer, 22_050).unwrap();
        assert_eq!(resampled.sample_rate(), 22_050);
        assert!((resampled.duration() - 1.0).abs() < 0.01);
    }

    #[test]
    fn empty_input_stays_empty_at_the_new_rate() {
        let buffer = AudioBuffer::silent(2, 0, 44_100).unwrap();
        let resampled = resample(&buffer, 48_000).unwrap();
        assert!(resampled.is_empty());
        assert_eq!(resampled.sample_rate(), 48_000);
    }

    #[test]
    fn zero_target_rate_is_rejected() {
        assert!(resample(&sine(440.0, 0.1, 48_000, 1), 0).is_err());
    }

    #[test]
    fn conditional_resampling_borrows_when_it_can() {
        let buffer = sine(440.0, 0.1, 48_000, 1);
        let same = resample_if_needed(&buffer, 48_000).unwrap();
        assert!(matches!(same, std::borrow::Cow::Borrowed(_)));
        let converted = resample_if_needed(&buffer, 44_100).unwrap();
        assert!(matches!(converted, std::borrow::Cow::Owned(_)));
        assert_eq!(converted.sample_rate(), 44_100);
    }
}
