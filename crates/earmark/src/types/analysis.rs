//! Deterministic audio features the planner reasons about.
//!
//! These are plain data. The code that computes them lives in [`crate::analysis`].

use crate::dsp::gain::linear_to_db;

/// Features for one STFT frame.
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct FrameFeatures {
    /// Frame centre, in seconds from the start of the *track* (not the analysed region).
    pub time: f64,
    pub rms: f32,
    /// Magnitude sums for the three bands split at the configured crossovers.
    pub low: f32,
    pub mid: f32,
    pub high: f32,
    /// Spectral centroid in Hz.
    pub centroid: f32,
    /// Positive spectral flux against the previous frame.
    pub flux: f32,
}

impl FrameFeatures {
    pub fn band_energy(&self) -> f32 {
        self.low + self.mid + self.high
    }

    /// Band shares that sum to 1.0, or an even split when the frame is silent.
    pub fn band_ratios(&self) -> [f32; 3] {
        let total = self.band_energy();
        if total <= f32::EPSILON {
            [1.0 / 3.0; 3]
        } else {
            [self.low / total, self.mid / total, self.high / total]
        }
    }
}

/// Evenly spaced frame features covering one analysed span of a track.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct FeatureTrack {
    /// Time of the first frame, in seconds from the start of the track.
    pub start_time: f64,
    pub hop_seconds: f64,
    pub frames: Vec<FrameFeatures>,
}

impl FeatureTrack {
    pub fn is_empty(&self) -> bool {
        self.frames.is_empty()
    }

    pub fn end_time(&self) -> f64 {
        self.frames.last().map_or(self.start_time, |f| f.time)
    }

    /// Frames whose centres fall in `[start, end]`.
    pub fn range(&self, start: f64, end: f64) -> &[FrameFeatures] {
        if self.frames.is_empty() || end < start {
            return &[];
        }
        let from = self.frames.partition_point(|f| f.time < start);
        let to = self.frames.partition_point(|f| f.time <= end);
        &self.frames[from..to.max(from)]
    }
}

/// Aggregated description of one transition region, and the unit the scorer works in.
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct RegionFeatures {
    pub rms: f32,
    pub rms_db: f32,
    /// Band shares summing to 1.0.
    pub low: f32,
    pub mid: f32,
    pub high: f32,
    pub centroid: f32,
    pub flux: f32,
    /// Share of frames carrying a transient, in `0.0..=1.0`.
    pub transient_rate: f32,
    /// Trend of the energy envelope in dB per second. Negative means the region is winding down.
    pub energy_slope: f32,
}

impl RegionFeatures {
    /// Aggregate of a silent or missing region.
    pub fn silent() -> Self {
        Self {
            rms_db: linear_to_db(0.0),
            low: 1.0 / 3.0,
            mid: 1.0 / 3.0,
            high: 1.0 / 3.0,
            ..Self::default()
        }
    }
}

/// Perceived loudness of a region, measured with EBU R128.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct LoudnessStats {
    pub lufs: f32,
    pub peak: f32,
    /// Set when the region was too short or too quiet for a gated measurement and the value is
    /// an RMS-derived estimate instead.
    pub estimated: bool,
}

impl Default for LoudnessStats {
    fn default() -> Self {
        Self {
            lufs: -70.0,
            peak: 0.0,
            estimated: true,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn track() -> FeatureTrack {
        FeatureTrack {
            start_time: 10.0,
            hop_seconds: 0.5,
            frames: (0..5)
                .map(|i| FrameFeatures {
                    time: 10.0 + i as f64 * 0.5,
                    rms: i as f32,
                    ..FrameFeatures::default()
                })
                .collect(),
        }
    }

    #[test]
    fn band_ratios_normalise_to_one() {
        let frame = FrameFeatures {
            low: 2.0,
            mid: 1.0,
            high: 1.0,
            ..FrameFeatures::default()
        };
        let [low, mid, high] = frame.band_ratios();
        assert!((low - 0.5).abs() < 1e-6);
        assert!((low + mid + high - 1.0).abs() < 1e-6);
    }

    #[test]
    fn silent_frames_split_bands_evenly() {
        let ratios = FrameFeatures::default().band_ratios();
        assert!((ratios.iter().sum::<f32>() - 1.0).abs() < 1e-6);
        assert!((ratios[0] - ratios[2]).abs() < 1e-6);
    }

    #[test]
    fn range_selects_inclusive_bounds() {
        let track = track();
        let selected = track.range(10.5, 11.5);
        assert_eq!(selected.len(), 3);
        assert!((selected[0].time - 10.5).abs() < 1e-9);
    }

    #[test]
    fn range_outside_the_track_is_empty() {
        let track = track();
        assert!(track.range(100.0, 200.0).is_empty());
        assert!(track.range(11.0, 10.0).is_empty());
        assert!(FeatureTrack::default().range(0.0, 1.0).is_empty());
    }

    #[test]
    fn end_time_tracks_the_last_frame() {
        assert!((track().end_time() - 12.0).abs() < 1e-9);
        assert_eq!(FeatureTrack::default().end_time(), 0.0);
    }

    #[test]
    fn silent_region_reports_floor_loudness() {
        let region = RegionFeatures::silent();
        assert!(region.rms_db < -100.0);
        assert!((region.low + region.mid + region.high - 1.0).abs() < 1e-6);
    }
}
