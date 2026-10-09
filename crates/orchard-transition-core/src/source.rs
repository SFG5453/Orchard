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

//! One side of the transition: planar PCM plus the beat grid the caller already has.

use earmark::{AudioBuffer, BeatAnalysis, CrossfadeError};

/// Longest overlap worth accepting, as a guard against a caller passing whole tracks. The planner
/// never asks for more than `max_duration`, so anything approaching this is a bug upstream.
pub const MAX_SECONDS: f64 = 90.0;

pub struct Source {
    /// Planar `f32`, one vector per channel.
    pub channels: Vec<Vec<f32>>,
    pub sample_rate: u32,
    pub bpm: f32,
    /// Beat times in seconds, relative to the start of `channels`.
    pub beats: Vec<f64>,
    /// Downbeat times in seconds, relative to the start of `channels`. Candidate placement and
    /// phrase alignment both key off these, so a grid without them scores blind on structure.
    pub downbeats: Vec<f64>,
}

impl Source {
    pub fn validate(&self, label: &str) -> Result<(), String> {
        validate_pcm(&self.channels, self.sample_rate as f64, label)
    }

    pub(crate) fn audio(&self) -> Result<AudioBuffer, CrossfadeError> {
        AudioBuffer::new(self.channels.clone(), self.sample_rate)
    }

    pub(crate) fn grid(&self) -> Result<BeatAnalysis, CrossfadeError> {
        BeatAnalysis::new(self.bpm, self.beats.clone(), self.downbeats.clone())
    }
}

/// Rejects PCM that is empty, ragged, or too large to be an overlap slice.
pub fn validate_pcm(channels: &[Vec<f32>], sample_rate: f64, label: &str) -> Result<(), String> {
    let Some(first) = channels.first() else {
        return Err(format!("{label} PCM has no channels"));
    };
    if first.is_empty() {
        return Err(format!("{label} PCM is empty"));
    }
    if channels.iter().any(|channel| channel.len() != first.len()) {
        return Err(format!("{label} PCM channels have differing lengths"));
    }
    if !sample_rate.is_finite() || sample_rate < 1000.0 {
        return Err(format!("{label} sample rate {sample_rate} is not usable"));
    }
    if first.len() as f64 / sample_rate > MAX_SECONDS {
        return Err(format!(
            "{label} PCM is {:.1}s, beyond the {MAX_SECONDS}s an overlap slice should ever be",
            first.len() as f64 / sample_rate
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ragged_and_empty_pcm_is_rejected() {
        assert!(validate_pcm(&[], 44_100.0, "outgoing").is_err());
        assert!(validate_pcm(&[vec![]], 44_100.0, "outgoing").is_err());
        assert!(validate_pcm(&[vec![0.0; 10], vec![0.0; 9]], 44_100.0, "outgoing").is_err());
        assert!(validate_pcm(&[vec![0.0; 10]], 0.0, "outgoing").is_err());
        assert!(validate_pcm(&[vec![0.0; 10]], 44_100.0, "outgoing").is_ok());
    }

    #[test]
    fn whole_tracks_are_refused_as_overlap_slices() {
        let frames = (44_100.0 * (MAX_SECONDS + 1.0)) as usize;
        assert!(validate_pcm(&[vec![0.0; frames]], 44_100.0, "outgoing").is_err());
    }
}
