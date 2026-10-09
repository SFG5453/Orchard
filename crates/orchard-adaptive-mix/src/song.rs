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

//! One decoded song, built from the decoder's native-rate stereo on every platform.
//! Desktop (FFmpeg) and Android (MediaCodec/libopus) only decode; every conversion after
//! that is this file, so both feed the planner the same numbers.
use earmark::AudioBuffer;
use earmark::audio::resample::{StreamResampler, resample};
use std::collections::VecDeque;

/// Beat This windows and the whole-track mono are cut from 44.1 kHz, as in V2.
pub const RATE: u32 = 44_100;
/// V2's analysis rate: whole-track evidence is measured on 11,025 Hz mono.
pub const ANALYSIS_RATE: u32 = 11_025;
/// V2's Beat This window, sized to one model inference.
pub const BEAT_WINDOW: f64 = 29.76;
/// Render windows: the outgoing tail and incoming head a mix can use.
pub const WINDOW: f64 = 60.0;
/// Rate of the rendered overlap and its source windows.
pub const RENDER_RATE: u32 = 48_000;
const MAX_TRACK_SECONDS: f64 = 2.0 * 60.0 * 60.0;

pub struct Track {
    /// Equal-weight box downmix at `ANALYSIS_RATE`, as V2's analysis worker produced.
    pub mono: Vec<f32>,
    pub duration: f64,
    /// Head and tail Beat This windows (44.1 kHz mono) with their start offsets.
    /// A short track has one.
    pub beat_windows: Vec<(Vec<f32>, f64)>,
}

/// A decoded song: analysis input plus the render windows a mix may use.
pub struct Song {
    pub track: Track,
    /// First `WINDOW` seconds at `RENDER_RATE`; empty unless kept.
    pub head: AudioBuffer,
    /// Last `WINDOW` seconds at `RENDER_RATE`, starting at `tail_offset`; empty unless kept.
    pub tail: AudioBuffer,
    pub tail_offset: f64,
    /// The decoder's native rate: the frame clock of a player fed the same stream.
    pub rate: u32,
}

/// Which render windows a decode keeps: a mix reads the outgoing tail and incoming head.
#[derive(Clone, Copy)]
pub struct Windows {
    pub head: bool,
    pub tail: bool,
}

impl Windows {
    pub const NONE: Self = Self { head: false, tail: false };
    pub const BOTH: Self = Self { head: true, tail: true };
}

/// Streams interleaved stereo at the decoder's rate. Memory stays bounded by the windows,
/// so a two-hour mix tape costs what a three-minute single does.
pub struct SongBuilder {
    native: u32,
    frames: usize,
    resampler: StreamResampler,
    scratch: Vec<f32>,
    // 44.1 kHz mono bookkeeping, as V2 cut it.
    rate_frames: usize,
    group: usize,
    sum: f64,
    mono: Vec<f32>,
    beat_head: Vec<f32>,
    beat_tail: VecDeque<f32>,
    beat_window: usize,
    // Native-rate stereo render windows.
    windows: Windows,
    window: usize,
    head: Vec<f32>,
    tail: VecDeque<f32>,
}

impl SongBuilder {
    /// Best Mix keeps no windows; it only needs the analysis.
    pub fn new(native_rate: u32, windows: Windows) -> Result<Self, String> {
        if !(8_000..=384_000).contains(&native_rate) {
            return Err("Unsupported decoded sample rate".into());
        }
        let beat_window = (BEAT_WINDOW * RATE as f64) as usize;
        let window = (WINDOW * native_rate as f64) as usize;
        Ok(Self {
            native: native_rate,
            frames: 0,
            resampler: StreamResampler::new(native_rate, RATE).map_err(|e| e.to_string())?,
            scratch: Vec::new(),
            rate_frames: 0,
            group: 0,
            sum: 0.0,
            mono: Vec::new(),
            beat_head: Vec::new(),
            beat_tail: VecDeque::with_capacity(beat_window),
            beat_window,
            windows,
            window,
            head: Vec::new(),
            tail: VecDeque::new(),
        })
    }

    /// Appends interleaved stereo frames at the native rate.
    pub fn push(&mut self, stereo: &[f32]) -> Result<(), String> {
        if !stereo.len().is_multiple_of(2) {
            return Err("Decoded audio must be interleaved stereo".into());
        }
        if stereo.iter().any(|v| !v.is_finite()) {
            return Err("Decoded audio contains nonfinite samples".into());
        }
        self.frames += stereo.len() / 2;
        if self.frames as f64 > MAX_TRACK_SECONDS * self.native as f64 {
            return Err("Audio decode exceeded its bounded track length".into());
        }
        if self.windows.head {
            let keep = (self.window * 2).saturating_sub(self.head.len()).min(stereo.len());
            self.head.extend_from_slice(&stereo[..keep]);
        }
        if self.windows.tail {
            self.tail.extend(stereo);
            let excess = self.tail.len().saturating_sub(self.window * 2);
            self.tail.drain(..excess);
        }
        let mono: Vec<f32> = stereo.as_chunks::<2>().0.iter().map(|[l, r]| (l + r) * 0.5).collect();
        let mut resampled = std::mem::take(&mut self.scratch);
        resampled.clear();
        self.resampler.push(&mono, &mut resampled).map_err(|e| e.to_string())?;
        self.accept(&resampled);
        self.scratch = resampled;
        Ok(())
    }

    fn accept(&mut self, samples: &[f32]) {
        let ratio = (RATE / ANALYSIS_RATE) as usize;
        for &sample in samples {
            self.sum += sample as f64;
            self.group += 1;
            if self.group == ratio {
                self.mono.push((self.sum / ratio as f64) as f32);
                (self.group, self.sum) = (0, 0.0);
            }
            // Head keeps up to two windows so a short song becomes one window, as in V2.
            if self.beat_head.len() < self.beat_window * 2 { self.beat_head.push(sample); }
            if self.beat_tail.len() == self.beat_window { self.beat_tail.pop_front(); }
            self.beat_tail.push_back(sample);
        }
        self.rate_frames += samples.len();
    }

    pub fn finish(mut self) -> Result<Song, String> {
        let mut rest = Vec::new();
        let resampler = std::mem::replace(&mut self.resampler,
            StreamResampler::new(RATE, RATE).map_err(|e| e.to_string())?);
        resampler.finish(&mut rest).map_err(|e| e.to_string())?;
        self.accept(&rest);
        if self.frames < self.native as usize {
            return Err("Could not decode the analysis track".into());
        }
        let duration = self.frames as f64 / self.native as f64;
        let beat_windows = if self.rate_frames <= self.beat_window * 2 {
            vec![(self.beat_head, 0.0)]
        } else {
            let offset = (self.rate_frames - self.beat_window) as f64 / RATE as f64;
            self.beat_head.truncate(self.beat_window);
            vec![(self.beat_head, 0.0), (self.beat_tail.into_iter().collect(), offset)]
        };
        let render = |samples: &[f32]| -> Result<AudioBuffer, String> {
            let native = AudioBuffer::from_interleaved(samples, 2, self.native).map_err(|e| e.to_string())?;
            resample(&native, RENDER_RATE).map_err(|e| e.to_string())
        };
        let tail: Vec<f32> = self.tail.into_iter().collect();
        let (head, tail) = (render(&self.head)?, render(&tail)?);
        let tail_offset = self.frames.saturating_sub(self.window) as f64 / self.native as f64;
        Ok(Song { track: Track { mono: self.mono, duration, beat_windows }, head, tail, tail_offset, rate: self.native })
    }
}

/// Builds a song from interleaved stereo already in memory.
pub fn song_from(stereo: &[f32], native_rate: u32, windows: Windows) -> Result<Song, String> {
    let mut builder = SongBuilder::new(native_rate, windows)?;
    // Decoder-sized pieces, so the result matches a streamed decode exactly.
    for piece in stereo.chunks(8192) { builder.push(piece)?; }
    builder.finish()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn stereo(seconds: f64, rate: u32) -> Vec<f32> {
        let frames = (seconds * rate as f64) as usize;
        (0..frames).flat_map(|_| [1.0f32, 0.0]).collect()
    }

    #[test]
    fn whole_track_matches_v2_box_downmix_and_windows() {
        let short = song_from(&stereo(40.0, RATE), RATE, Windows::NONE).unwrap();
        assert_eq!(short.track.mono.len(), 40 * ANALYSIS_RATE as usize);
        assert!(short.track.mono.iter().all(|v| *v == 0.5));
        assert_eq!(short.track.beat_windows.len(), 1);
        let long = song_from(&stereo(90.0, RATE), RATE, Windows::BOTH).unwrap();
        assert_eq!(long.track.beat_windows.len(), 2);
        assert!((long.track.beat_windows[0].0.len() as f64 / RATE as f64 - BEAT_WINDOW).abs() < 1e-3);
        assert!((long.track.beat_windows[1].1 - (90.0 - BEAT_WINDOW)).abs() < 1e-3);
        assert!((long.tail_offset - 30.0).abs() < 1e-9);
        assert!((long.head.duration() - WINDOW).abs() < 1e-3);
        assert_eq!(long.head.sample_rate(), RENDER_RATE);
    }

    #[test]
    fn native_48k_lands_on_the_same_timeline() {
        let song = song_from(&stereo(90.0, 48_000), 48_000, Windows::BOTH).unwrap();
        assert!((song.track.duration - 90.0).abs() < 1e-9);
        // The resampler's edges ring; the body is the 0.5 the box downmix promises.
        let mono = &song.track.mono;
        assert!((mono.len() as f64 - 90.0 * ANALYSIS_RATE as f64).abs() <= 1.0);
        assert!(mono[1000..mono.len() - 1000].iter().all(|v| (v - 0.5).abs() < 1e-3));
        assert_eq!(song.tail.frames(), (WINDOW * 48_000.0) as usize);
    }

    #[test]
    fn rejects_odd_and_tiny_input() {
        assert!(SongBuilder::new(48_000, Windows::NONE).unwrap().push(&[0.0; 3]).is_err());
        assert!(song_from(&[0.0; 200], 48_000, Windows::NONE).is_err());
    }
}
