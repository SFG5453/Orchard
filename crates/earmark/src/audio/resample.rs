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
    // Per channel through the streaming path: rubato 5.0's `process_all` trims its delay by
    // moving only `delay` frames, leaving the rest of the first chunk unshifted.
    let channels = buffer.planar().iter().map(|channel| {
        let mut stream = StreamResampler::new(buffer.sample_rate(), target_rate)?;
        let mut out = Vec::with_capacity(
            (channel.len() as f64 * target_rate as f64 / buffer.sample_rate() as f64) as usize + 1);
        stream.push(channel, &mut out)?;
        stream.finish(&mut out)?;
        Ok(out)
    }).collect::<Result<Vec<_>>>()?;
    AudioBuffer::new(channels, target_rate)
}

/// Mono resampler fed in pieces, so a whole song never sits in memory at its decoded rate.
/// Output is the same signal [`resample`] produces: delay trimmed, length `ceil(ratio × input)`.
pub struct StreamResampler {
    inner: Option<Fft<f32>>,
    ratio: f64,
    pending: Vec<f32>,
    scratch: Vec<Vec<f32>>,
    trim: usize,
    consumed: usize,
    produced: usize,
}

impl StreamResampler {
    pub fn new(from: u32, to: u32) -> Result<Self> {
        if from == 0 || to == 0 {
            return Err(CrossfadeError::audio("sample rates must be non-zero"));
        }
        let inner = (from != to)
            .then(|| Fft::<f32>::new(from as usize, to as usize, CHUNK_FRAMES, 1, FixedSync::Input))
            .transpose()?;
        let (trim, scratch) = inner.as_ref().map_or((0, Vec::new()), |fft| {
            (fft.output_delay(), vec![vec![0.0; fft.output_frames_max()]])
        });
        Ok(Self {
            inner,
            ratio: to as f64 / from as f64,
            pending: Vec::new(),
            scratch,
            trim,
            consumed: 0,
            produced: 0,
        })
    }

    /// Appends the resampled part of `input` that is ready to `out`.
    pub fn push(&mut self, input: &[f32], out: &mut Vec<f32>) -> Result<()> {
        self.consumed += input.len();
        if self.inner.is_none() {
            out.extend_from_slice(input);
            return Ok(());
        }
        self.pending.extend_from_slice(input);
        let chunk = self.inner.as_ref().unwrap().input_frames_next();
        let mut start = 0;
        while self.pending.len() - start >= chunk {
            self.run(start, None, out)?;
            start += chunk;
        }
        self.pending.drain(..start);
        Ok(())
    }

    /// Flushes the tail and pads with the resampler's own silence to the exact length.
    pub fn finish(mut self, out: &mut Vec<f32>) -> Result<()> {
        if self.inner.is_none() {
            return Ok(());
        }
        let expected = (self.ratio * self.consumed as f64).ceil() as usize;
        if !self.pending.is_empty() {
            let left = self.pending.len();
            self.run(0, Some(left), out)?;
        }
        while self.produced < expected {
            let before = self.produced;
            self.run(0, Some(0), out)?;
            if self.produced == before && self.trim == 0 {
                break;
            }
        }
        out.truncate(out.len() - (self.produced - expected.min(self.produced)));
        Ok(())
    }

    fn run(&mut self, start: usize, partial: Option<usize>, out: &mut Vec<f32>) -> Result<()> {
        let fft = self.inner.as_mut().unwrap();
        let chunk = fft.input_frames_next();
        let end = if partial.is_some() { self.pending.len() } else { start + chunk };
        let mut piece = self.pending[start..end].to_vec();
        piece.resize(chunk, 0.0);
        let input = [piece];
        let adapter = SequentialSliceOfVecs::new(&input, 1, chunk)
            .map_err(|e| CrossfadeError::dsp(format!("resampler input adapter: {e}")))?;
        let capacity = self.scratch[0].len();
        let mut output = SequentialSliceOfVecs::new_mut(&mut self.scratch, 1, capacity)
            .map_err(|e| CrossfadeError::dsp(format!("resampler output adapter: {e}")))?;
        let indexing = rubato::Indexing {
            input_offset: 0,
            output_offset: 0,
            partial_len: partial,
            active_channels_mask: None,
        };
        let (_, written) = fft.process_into_buffer(&adapter, &mut output, Some(&indexing))?;
        let skip = self.trim.min(written);
        self.trim -= skip;
        out.extend_from_slice(&self.scratch[0][skip..written]);
        self.produced += written - skip;
        Ok(())
    }
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
    fn streaming_matches_the_whole_clip_and_the_true_signal() {
        let buffer = sine(440.0, 1.3, 48_000, 1);
        let whole = resample(&buffer, 44_100).unwrap();
        let mut stream = StreamResampler::new(48_000, 44_100).unwrap();
        let mut out = Vec::new();
        // Odd piece sizes, as decoders hand them over.
        for piece in buffer.channel(0).chunks(777) {
            stream.push(piece, &mut out).unwrap();
        }
        stream.finish(&mut out).unwrap();
        assert_eq!(out, whole.channel(0));
        // The opening chunk included: a delay trimmed wrong shows up there first.
        let worst = out[64..out.len() - 2048].iter().enumerate()
            .map(|(i, v)| (v - ((i + 64) as f32 / 44_100.0 * 440.0 * std::f32::consts::TAU).sin()).abs())
            .fold(0.0, f32::max);
        assert!(worst < 1e-3, "resampled sine is off by {worst}");
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
