//! Time stretching and pitch shifting via Signalsmith Stretch.
//!
//! Tempo and pitch are independent: a stretch ratio changes duration without touching pitch
//! unless a semitone offset is asked for explicitly.

use signalsmith_stretch::Stretch;

use crate::audio::AudioBuffer;
use crate::error::{CrossfadeError, Result};

/// Owns a configured stretch processor plus its interleave scratch buffers.
///
/// Construction allocates the underlying FFT machinery, so the engine keeps one of these alive
/// and reuses it across renders with the same channel count and sample rate.
pub struct TimeStretcher {
    stretch: Stretch,
    channels: usize,
    sample_rate: u32,
    input: Vec<f32>,
    output: Vec<f32>,
}

impl TimeStretcher {
    pub fn new(channels: usize, sample_rate: u32) -> Result<Self> {
        if channels == 0 {
            return Err(CrossfadeError::audio("channel count must be non-zero"));
        }
        if sample_rate == 0 {
            return Err(CrossfadeError::audio("sample rate must be non-zero"));
        }
        Ok(Self {
            stretch: Stretch::preset_default(channels as u32, sample_rate),
            channels,
            sample_rate,
            input: Vec::new(),
            output: Vec::new(),
        })
    }

    pub fn matches(&self, channels: usize, sample_rate: u32) -> bool {
        self.channels == channels && self.sample_rate == sample_rate
    }

    /// Shortest output the underlying algorithm can render in one shot. Below this it has no
    /// room to resolve its own latency, and [`TimeStretcher::process`] refuses the request.
    pub fn min_output_frames(&self) -> usize {
        self.stretch.output_latency() * 2
    }

    pub fn can_process(&self, output_frames: usize) -> bool {
        output_frames >= self.min_output_frames()
    }

    /// Renders `source` into exactly `output_frames`, stretching by whatever ratio that implies.
    ///
    /// `semitones` shifts pitch on top of the stretch; pass `0.0` to keep pitch unchanged.
    pub fn process(
        &mut self,
        source: &AudioBuffer,
        output_frames: usize,
        semitones: f32,
    ) -> Result<AudioBuffer> {
        if source.channel_count() != self.channels || source.sample_rate() != self.sample_rate {
            return Err(CrossfadeError::audio(format!(
                "stretcher configured for {} channels at {} Hz, got {} at {}",
                self.channels,
                self.sample_rate,
                source.channel_count(),
                source.sample_rate()
            )));
        }
        if source.is_empty() || output_frames == 0 {
            return AudioBuffer::silent(self.channels, output_frames, self.sample_rate);
        }
        if !self.can_process(output_frames) {
            return Err(CrossfadeError::dsp(format!(
                "output of {output_frames} frames is below the stretcher minimum of {}",
                self.min_output_frames()
            )));
        }

        self.input.clear();
        self.input.resize(source.frames() * self.channels, 0.0);
        source.write_interleaved(&mut self.input);

        self.output.clear();
        self.output.resize(output_frames * self.channels, 0.0);

        self.stretch.reset();
        self.stretch.set_transpose_factor_semitones(semitones, None);
        if !self.stretch.exact(&self.input, &mut self.output) {
            return Err(CrossfadeError::dsp(
                "signalsmith stretch rejected the requested block sizes",
            ));
        }

        AudioBuffer::from_interleaved(&self.output, self.channels, self.sample_rate)
    }
}

/// Semitone offset that makes pitch follow tempo, the way a turntable does.
pub fn semitones_for_ratio(ratio: f32) -> f32 {
    if ratio <= 0.0 {
        0.0
    } else {
        12.0 * ratio.log2()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dsp::gain::rms;

    const SR: u32 = 48_000;

    fn sine(freq: f32, frames: usize, channels: usize) -> AudioBuffer {
        let channel: Vec<f32> = (0..frames)
            .map(|i| (i as f32 / SR as f32 * freq * std::f32::consts::TAU).sin())
            .collect();
        AudioBuffer::new(vec![channel; channels], SR).unwrap()
    }

    #[test]
    fn output_length_is_exactly_what_was_asked_for() {
        let mut stretcher = TimeStretcher::new(2, SR).unwrap();
        let source = sine(440.0, SR as usize, 2);
        let stretched = stretcher.process(&source, SR as usize / 2, 0.0).unwrap();
        assert_eq!(stretched.frames(), SR as usize / 2);
        assert_eq!(stretched.channel_count(), 2);
        assert_eq!(stretched.sample_rate(), SR);
    }

    #[test]
    fn stretching_preserves_signal_level() {
        let mut stretcher = TimeStretcher::new(1, SR).unwrap();
        let source = sine(440.0, SR as usize, 1);
        let stretched = stretcher
            .process(&source, (SR as f64 * 1.05) as usize, 0.0)
            .unwrap();
        let level = rms(stretched.channel(0));
        assert!(
            (level - rms(source.channel(0))).abs() < 0.15,
            "level {level}"
        );
        assert!(stretched.channel(0).iter().all(|s| s.is_finite()));
    }

    #[test]
    fn a_reused_stretcher_is_deterministic() {
        let mut stretcher = TimeStretcher::new(1, SR).unwrap();
        let source = sine(220.0, SR as usize / 2, 1);
        let first = stretcher.process(&source, SR as usize / 2, 0.0).unwrap();
        let second = stretcher.process(&source, SR as usize / 2, 0.0).unwrap();
        assert_eq!(first, second);
    }

    #[test]
    fn configuration_mismatch_is_rejected() {
        let mut stretcher = TimeStretcher::new(2, SR).unwrap();
        assert!(stretcher.process(&sine(440.0, 4096, 1), 4096, 0.0).is_err());
        assert!(
            stretcher
                .process(&AudioBuffer::silent(2, 4096, 44_100).unwrap(), 4096, 0.0)
                .is_err()
        );
        assert!(!stretcher.matches(1, SR));
        assert!(stretcher.matches(2, SR));
    }

    #[test]
    fn empty_input_yields_silence() {
        let mut stretcher = TimeStretcher::new(2, SR).unwrap();
        let empty = AudioBuffer::silent(2, 0, SR).unwrap();
        let rendered = stretcher.process(&empty, 128, 0.0).unwrap();
        assert_eq!(rendered.frames(), 128);
        assert_eq!(rendered.peak(), 0.0);
    }

    #[test]
    fn output_below_the_latency_floor_is_refused() {
        let mut stretcher = TimeStretcher::new(1, SR).unwrap();
        let frames = stretcher.min_output_frames();
        assert!(frames > 0);
        assert!(!stretcher.can_process(frames - 1));
        assert!(
            stretcher
                .process(&sine(440.0, 4096, 1), frames - 1, 0.0)
                .is_err()
        );
    }

    #[test]
    fn invalid_configuration_is_rejected_at_construction() {
        assert!(TimeStretcher::new(0, SR).is_err());
        assert!(TimeStretcher::new(2, 0).is_err());
    }

    #[test]
    fn turntable_pitch_follows_the_ratio() {
        assert!((semitones_for_ratio(2.0) - 12.0).abs() < 1e-5);
        assert!((semitones_for_ratio(0.5) + 12.0).abs() < 1e-5);
        assert_eq!(semitones_for_ratio(1.0), 0.0);
        assert_eq!(semitones_for_ratio(0.0), 0.0);
    }
}
