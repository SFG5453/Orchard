//! PCM representation.
//!
//! Audio is stored **planar** as one `Vec<f32>` per channel. Every operation in this crate —
//! fades, filter sweeps, gain rides, mixing — is per channel, so planar storage keeps the inner
//! loops as flat slice walks with no stride arithmetic. Interleaving happens only at the two
//! edges that require it: the public conversion helpers and the Signalsmith stretch boundary.

use crate::dsp::{gain, mixer};
use crate::error::{CrossfadeError, Result};

#[derive(Debug, Clone, PartialEq)]
pub struct AudioBuffer {
    channels: Vec<Vec<f32>>,
    sample_rate: u32,
}

impl AudioBuffer {
    pub fn new(channels: Vec<Vec<f32>>, sample_rate: u32) -> Result<Self> {
        if channels.is_empty() {
            return Err(CrossfadeError::audio("buffer needs at least one channel"));
        }
        if sample_rate == 0 {
            return Err(CrossfadeError::audio("sample rate must be non-zero"));
        }
        let frames = channels[0].len();
        if channels.iter().any(|c| c.len() != frames) {
            return Err(CrossfadeError::audio("channels differ in length"));
        }
        Ok(Self {
            channels,
            sample_rate,
        })
    }

    pub fn silent(channel_count: usize, frames: usize, sample_rate: u32) -> Result<Self> {
        Self::new(vec![vec![0.0; frames]; channel_count.max(1)], sample_rate)
    }

    pub fn from_interleaved(data: &[f32], channel_count: usize, sample_rate: u32) -> Result<Self> {
        if channel_count == 0 {
            return Err(CrossfadeError::audio("channel count must be non-zero"));
        }
        if !data.len().is_multiple_of(channel_count) {
            return Err(CrossfadeError::audio(format!(
                "interleaved length {} is not divisible by {channel_count} channels",
                data.len()
            )));
        }
        let frames = data.len() / channel_count;
        let mut channels = vec![Vec::with_capacity(frames); channel_count];
        for frame in data.chunks_exact(channel_count) {
            for (channel, sample) in channels.iter_mut().zip(frame) {
                channel.push(*sample);
            }
        }
        Self::new(channels, sample_rate)
    }

    pub fn to_interleaved(&self) -> Vec<f32> {
        let mut out = vec![0.0; self.frames() * self.channel_count()];
        self.write_interleaved(&mut out);
        out
    }

    /// Interleaves into an existing buffer, which must hold `frames * channels` samples.
    /// Preferred over [`AudioBuffer::to_interleaved`] on paths that run more than once.
    pub fn write_interleaved(&self, out: &mut [f32]) {
        let channel_count = self.channel_count();
        for (index, channel) in self.channels.iter().enumerate() {
            for (frame, sample) in channel.iter().enumerate() {
                let slot = frame * channel_count + index;
                if slot < out.len() {
                    out[slot] = *sample;
                }
            }
        }
    }

    pub fn channel_count(&self) -> usize {
        self.channels.len()
    }

    pub fn frames(&self) -> usize {
        self.channels[0].len()
    }

    pub fn sample_rate(&self) -> u32 {
        self.sample_rate
    }

    pub fn duration(&self) -> f64 {
        self.frames() as f64 / self.sample_rate as f64
    }

    pub fn is_empty(&self) -> bool {
        self.frames() == 0
    }

    pub fn channel(&self, index: usize) -> &[f32] {
        &self.channels[index]
    }

    pub fn channel_mut(&mut self, index: usize) -> &mut [f32] {
        &mut self.channels[index]
    }

    pub fn planar(&self) -> &[Vec<f32>] {
        &self.channels
    }

    pub fn planar_mut(&mut self) -> &mut [Vec<f32>] {
        &mut self.channels
    }

    pub fn into_planar(self) -> Vec<Vec<f32>> {
        self.channels
    }

    /// Frame index for a time in seconds, saturating at zero.
    pub fn frame_index(&self, seconds: f64) -> usize {
        if seconds <= 0.0 {
            0
        } else {
            (seconds * self.sample_rate as f64).round() as usize
        }
    }

    /// Copies `frames` starting at `start_frame`. Reads past the end yield silence, so callers
    /// can request a fixed-length region without bounds juggling.
    pub fn slice(&self, start_frame: usize, frames: usize) -> Self {
        let channels = self
            .channels
            .iter()
            .map(|channel| {
                let mut out = vec![0.0; frames];
                if start_frame < channel.len() {
                    let available = (channel.len() - start_frame).min(frames);
                    out[..available]
                        .copy_from_slice(&channel[start_frame..start_frame + available]);
                }
                out
            })
            .collect();
        Self {
            channels,
            sample_rate: self.sample_rate,
        }
    }

    pub fn slice_seconds(&self, start: f64, duration: f64) -> Self {
        let frames = (duration.max(0.0) * self.sample_rate as f64).round() as usize;
        self.slice(self.frame_index(start), frames)
    }

    /// Sums to mono into a caller-owned buffer, so repeated analysis passes do not reallocate.
    pub fn downmix_into(&self, out: &mut Vec<f32>) {
        mixer::downmix_into(out, &self.channels, self.frames());
    }

    /// Adapts the channel count. Mono fans out, multi-channel folds down, and matching layouts
    /// are returned untouched. Anything else is an error rather than a guess.
    pub fn to_channel_count(&self, target: usize) -> Result<Self> {
        let current = self.channel_count();
        if target == 0 {
            return Err(CrossfadeError::audio("channel count must be non-zero"));
        }
        if target == current {
            return Ok(self.clone());
        }
        if current == 1 {
            return Self::new(vec![self.channels[0].clone(); target], self.sample_rate);
        }
        if target == 1 {
            let mut mono = Vec::new();
            self.downmix_into(&mut mono);
            return Self::new(vec![mono], self.sample_rate);
        }
        Err(CrossfadeError::audio(format!(
            "cannot map {current} channels onto {target}"
        )))
    }

    pub fn apply_gain(&mut self, linear: f32) {
        for channel in &mut self.channels {
            gain::apply_gain(channel, linear);
        }
    }

    pub fn peak(&self) -> f32 {
        self.channels
            .iter()
            .fold(0.0f32, |acc, c| acc.max(gain::peak(c)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn stereo() -> AudioBuffer {
        AudioBuffer::new(vec![vec![1.0, 2.0, 3.0], vec![-1.0, -2.0, -3.0]], 48_000).unwrap()
    }

    #[test]
    fn reports_shape_and_duration() {
        let buffer = stereo();
        assert_eq!(buffer.channel_count(), 2);
        assert_eq!(buffer.frames(), 3);
        assert_eq!(buffer.sample_rate(), 48_000);
        assert!((buffer.duration() - 3.0 / 48_000.0).abs() < 1e-12);
    }

    #[test]
    fn interleaving_round_trips() {
        let buffer = stereo();
        let interleaved = buffer.to_interleaved();
        assert_eq!(interleaved, vec![1.0, -1.0, 2.0, -2.0, 3.0, -3.0]);
        let restored = AudioBuffer::from_interleaved(&interleaved, 2, 48_000).unwrap();
        assert_eq!(restored, buffer);
    }

    #[test]
    fn rejects_malformed_construction() {
        assert!(AudioBuffer::new(vec![], 48_000).is_err());
        assert!(AudioBuffer::new(vec![vec![0.0]], 0).is_err());
        assert!(AudioBuffer::new(vec![vec![0.0, 0.0], vec![0.0]], 48_000).is_err());
        assert!(AudioBuffer::from_interleaved(&[1.0, 2.0, 3.0], 2, 48_000).is_err());
        assert!(AudioBuffer::from_interleaved(&[1.0], 0, 48_000).is_err());
    }

    #[test]
    fn slicing_copies_the_requested_window() {
        let sliced = stereo().slice(1, 2);
        assert_eq!(sliced.channel(0), &[2.0, 3.0]);
        assert_eq!(sliced.channel(1), &[-2.0, -3.0]);
    }

    #[test]
    fn slicing_past_the_end_pads_with_silence() {
        let sliced = stereo().slice(2, 4);
        assert_eq!(sliced.channel(0), &[3.0, 0.0, 0.0, 0.0]);
        let beyond = stereo().slice(99, 2);
        assert_eq!(beyond.channel(0), &[0.0, 0.0]);
    }

    #[test]
    fn slicing_by_seconds_uses_the_sample_rate() {
        let buffer = AudioBuffer::silent(1, 480, 48_000).unwrap();
        let sliced = buffer.slice_seconds(0.005, 0.001);
        assert_eq!(sliced.frames(), 48);
        assert_eq!(buffer.frame_index(0.005), 240);
        assert_eq!(buffer.frame_index(-1.0), 0);
    }

    #[test]
    fn channel_mapping_fans_out_and_folds_down() {
        let mono = AudioBuffer::new(vec![vec![0.5, 1.0]], 48_000).unwrap();
        let widened = mono.to_channel_count(2).unwrap();
        assert_eq!(widened.channel_count(), 2);
        assert_eq!(widened.channel(1), &[0.5, 1.0]);

        let folded = stereo().to_channel_count(1).unwrap();
        assert_eq!(folded.channel(0), &[0.0, 0.0, 0.0]);

        assert_eq!(stereo().to_channel_count(2).unwrap(), stereo());
        assert!(stereo().to_channel_count(0).is_err());
        assert!(
            AudioBuffer::silent(3, 4, 48_000)
                .unwrap()
                .to_channel_count(2)
                .is_err()
        );
    }

    #[test]
    fn gain_and_peak_cover_every_channel() {
        let mut buffer = stereo();
        assert_eq!(buffer.peak(), 3.0);
        buffer.apply_gain(0.5);
        assert_eq!(buffer.channel(0), &[0.5, 1.0, 1.5]);
        assert_eq!(buffer.peak(), 1.5);
    }

    #[test]
    fn downmix_reuses_the_output_buffer() {
        let mut mono = Vec::new();
        stereo().downmix_into(&mut mono);
        assert_eq!(mono, vec![0.0, 0.0, 0.0]);
        stereo().downmix_into(&mut mono);
        assert_eq!(mono.len(), 3);
    }

    #[test]
    fn silent_buffers_are_empty_but_valid() {
        let buffer = AudioBuffer::silent(2, 0, 44_100).unwrap();
        assert!(buffer.is_empty());
        assert_eq!(buffer.to_interleaved(), Vec::<f32>::new());
    }
}
