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

//! Beat loops: the outgoing's last clean bars repeat under a mix whose beat would stop.
//! The oldest trick in the booth: when the record runs dry, play the good part again.
use earmark::AudioBuffer;
use serde_json::Value;

/// Seams sit this far before the loop's downbeat so the crossfade never smears a kick.
const LEAD: f64 = 0.02;
/// Crossfade across each seam.
const SEAM: f64 = 0.008;

/// Bars `start..end` in render-window seconds that repeat once playback reaches `end`.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct BeatLoop {
    pub start: f64,
    pub end: f64,
    pub beats: u32,
}

impl BeatLoop {
    /// The planner's `outgoingLoop` (track seconds) on a render window starting at `offset`.
    pub fn from_plan(native: &Value, offset: f64) -> Option<Self> {
        let value = &native["outgoingLoop"];
        let (start, end) = (value["start"].as_f64()?, value["end"].as_f64()?);
        let beats = value["beats"].as_u64().unwrap_or(0) as u32;
        (start - offset >= LEAD && end - start > 0.25)
            .then_some(Self { start: start - offset, end: end - offset, beats })
    }

    /// Window time heard at loop time `time`; the planner scores the same timeline.
    pub fn source(self, time: f64) -> f64 {
        if time < self.end { time } else { self.start + (time - self.end) % (self.end - self.start) }
    }

    /// `audio` up to `until` seconds of loop time, the bars repeating from `end`.
    pub fn apply(self, audio: &AudioBuffer, until: f64) -> Result<AudioBuffer, String> {
        let rate = audio.sample_rate() as f64;
        let length = self.end - self.start;
        let first_seam = self.end - LEAD;
        let frames = (until.max(0.0) * rate).round() as usize;
        let seam = ((SEAM * rate).round() as usize).max(1);
        let channels = audio.planar().iter().map(|channel| {
            let read = |time: f64| channel.get((time * rate).round() as usize).copied().unwrap_or(0.0);
            (0..frames).map(|frame| {
                let time = frame as f64 / rate;
                if time < first_seam {
                    return read(time);
                }
                // Each pass ends where the song would have carried on; fade from that into the loop.
                let into = (time - first_seam) % length;
                let looped = read(self.start - LEAD + into);
                let step = (into * rate) as usize;
                if step >= seam {
                    return looped;
                }
                let weight = (step as f32 + 0.5) / seam as f32;
                read(self.end - LEAD + into) * (1.0 - weight) + looped * weight
            }).collect()
        }).collect();
        AudioBuffer::new(channels, audio.sample_rate()).map_err(|e| e.to_string())
    }

    /// Per-frame measurements taken on the plain window, read along the loop timeline.
    pub fn remap(self, values: &[f64], frame_seconds: f64, until: f64) -> Vec<f64> {
        let count = (until / frame_seconds).ceil().max(0.0) as usize;
        (0..count).filter_map(|index| {
            let source = self.source(index as f64 * frame_seconds);
            values.get((source / frame_seconds).round() as usize).copied()
        }).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ramp(seconds: f64, rate: u32) -> AudioBuffer {
        let samples: Vec<f32> = (0..(seconds * rate as f64) as usize).map(|i| i as f32).collect();
        AudioBuffer::new(vec![samples.clone(), samples], rate).unwrap()
    }

    #[test]
    fn the_loop_repeats_its_bars_after_the_end() {
        let rate = 1000;
        let beat_loop = BeatLoop { start: 2.0, end: 4.0, beats: 4 };
        let looped = beat_loop.apply(&ramp(10.0, rate), 9.0).unwrap();
        let channel = looped.channel(0);
        assert_eq!(looped.frames(), 9000);
        // Untouched before the first seam, then each pass replays 2.0 s onward.
        assert_eq!(channel[3000], 3000.0);
        assert_eq!(channel[4100], 2100.0);
        assert_eq!(channel[6100], 2100.0);
        assert_eq!(channel[8500], 2500.0);
        assert!((beat_loop.source(6.1) - 2.1).abs() < 1e-9);
    }

    #[test]
    fn seams_crossfade_from_the_continuing_audio() {
        let rate = 1000;
        let looped = BeatLoop { start: 2.0, end: 4.0, beats: 4 }.apply(&ramp(10.0, rate), 6.0).unwrap();
        let channel = looped.channel(0);
        // The seam starts 20 ms early: 3980 continues as 3980, then fades toward 1980.
        let first = channel[3980];
        assert!(first < 3980.0 && first > 1980.0);
        assert!(channel[3984] < first);
        assert_eq!(channel[3990], 1990.0);
    }

    #[test]
    fn remapped_frames_follow_the_loop() {
        let values: Vec<f64> = (0..100).map(f64::from).collect();
        let beat_loop = BeatLoop { start: 2.0, end: 4.0, beats: 4 };
        let mapped = beat_loop.remap(&values, 0.1, 6.0);
        assert_eq!(mapped.len(), 60);
        assert_eq!(mapped[30], 30.0);
        assert_eq!(mapped[45], 25.0);
    }

    #[test]
    fn plans_name_loops_in_track_time() {
        let native = serde_json::json!({"outgoingLoop": {"start": 212.5, "end": 218.0, "beats": 8}});
        let beat_loop = BeatLoop::from_plan(&native, 170.0).unwrap();
        assert_eq!(beat_loop, BeatLoop { start: 42.5, end: 48.0, beats: 8 });
        assert!(BeatLoop::from_plan(&serde_json::json!({"outgoingLoop": null}), 0.0).is_none());
    }
}
