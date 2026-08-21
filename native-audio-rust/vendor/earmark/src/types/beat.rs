//! Beat metadata supplied by the caller.
//!
//! This crate never detects beats. The times below come from an external beat tracker (Beat
//! This ONNX in Orchard's case) and are treated as ground truth.

use crate::error::{CrossfadeError, Result};

/// Beat grid for a single track. All times are in seconds from the start of the track.
///
/// `downbeats` is expected to be a subset of `beats`, but nothing breaks if it is not — the
/// planner only ever asks the grid for the nearest entry.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct BeatAnalysis {
    pub bpm: f32,
    pub beats: Vec<f64>,
    pub downbeats: Vec<f64>,
}

impl BeatAnalysis {
    /// Builds a validated grid. Use this rather than the struct literal when the data comes
    /// straight off a model.
    pub fn new(bpm: f32, beats: Vec<f64>, downbeats: Vec<f64>) -> Result<Self> {
        let analysis = Self {
            bpm,
            beats,
            downbeats,
        };
        analysis.validate()?;
        Ok(analysis)
    }

    pub fn validate(&self) -> Result<()> {
        if !self.bpm.is_finite() || self.bpm <= 0.0 {
            return Err(CrossfadeError::beats(format!(
                "bpm must be finite and positive, got {}",
                self.bpm
            )));
        }
        check_monotonic(&self.beats, "beats")?;
        check_monotonic(&self.downbeats, "downbeats")
    }

    pub fn beat_interval(&self) -> f64 {
        60.0 / self.bpm as f64
    }

    pub fn is_empty(&self) -> bool {
        self.beats.is_empty()
    }

    /// Index of the last beat at or before `time`.
    pub fn beat_index_at(&self, time: f64) -> Option<usize> {
        index_at(&self.beats, time)
    }

    /// Index of the last downbeat at or before `time`.
    pub fn downbeat_index_at(&self, time: f64) -> Option<usize> {
        index_at(&self.downbeats, time)
    }

    pub fn nearest_beat(&self, time: f64) -> Option<f64> {
        nearest_index(&self.beats, time).map(|i| self.beats[i])
    }

    pub fn nearest_downbeat(&self, time: f64) -> Option<f64> {
        nearest_index(&self.downbeats, time).map(|i| self.downbeats[i])
    }

    pub fn nearest_beat_index(&self, time: f64) -> Option<usize> {
        nearest_index(&self.beats, time)
    }

    pub fn nearest_downbeat_index(&self, time: f64) -> Option<usize> {
        nearest_index(&self.downbeats, time)
    }

    /// Distance in seconds to the closest beat, or infinity when the grid is empty.
    pub fn distance_to_beat(&self, time: f64) -> f64 {
        self.nearest_beat(time)
            .map_or(f64::INFINITY, |beat| (beat - time).abs())
    }

    pub fn distance_to_downbeat(&self, time: f64) -> f64 {
        self.nearest_downbeat(time)
            .map_or(f64::INFINITY, |beat| (beat - time).abs())
    }

    pub fn beats_in(&self, start: f64, end: f64) -> &[f64] {
        slice_in(&self.beats, start, end)
    }

    pub fn downbeats_in(&self, start: f64, end: f64) -> &[f64] {
        slice_in(&self.downbeats, start, end)
    }

    /// Beats per bar inferred from downbeat spacing, falling back to 4 when there is not enough
    /// evidence. Uses the median spacing so a single mis-detected downbeat cannot skew it.
    pub fn beats_per_bar(&self) -> u32 {
        const DEFAULT: u32 = 4;
        if self.downbeats.len() < 3 {
            return DEFAULT;
        }
        let mut spacings: Vec<f64> = self.downbeats.windows(2).map(|w| w[1] - w[0]).collect();
        spacings.sort_by(f64::total_cmp);
        let median = spacings[spacings.len() / 2];
        let interval = self.beat_interval();
        if interval <= 0.0 {
            return DEFAULT;
        }
        let bars = (median / interval).round();
        if (1.0..=16.0).contains(&bars) {
            bars as u32
        } else {
            DEFAULT
        }
    }
}

fn check_monotonic(times: &[f64], label: &str) -> Result<()> {
    let mut previous = f64::NEG_INFINITY;
    for (i, &time) in times.iter().enumerate() {
        if !time.is_finite() || time < 0.0 {
            return Err(CrossfadeError::beats(format!(
                "{label}[{i}] must be finite and non-negative, got {time}"
            )));
        }
        if time <= previous {
            return Err(CrossfadeError::beats(format!(
                "{label} must be strictly increasing, but [{i}] = {time} follows {previous}"
            )));
        }
        previous = time;
    }
    Ok(())
}

fn index_at(times: &[f64], time: f64) -> Option<usize> {
    times.partition_point(|t| *t <= time).checked_sub(1)
}

fn nearest_index(times: &[f64], time: f64) -> Option<usize> {
    if times.is_empty() {
        return None;
    }
    let after = times.partition_point(|t| *t < time);
    match (after.checked_sub(1), after < times.len()) {
        (Some(lo), true) => Some(if time - times[lo] <= times[after] - time {
            lo
        } else {
            after
        }),
        (Some(lo), false) => Some(lo),
        (None, true) => Some(after),
        (None, false) => None,
    }
}

fn slice_in(times: &[f64], start: f64, end: f64) -> &[f64] {
    let from = times.partition_point(|t| *t < start);
    let to = times.partition_point(|t| *t <= end);
    &times[from..to.max(from)]
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 120 BPM, four-to-the-floor, downbeat every four beats.
    fn grid(bars: usize) -> BeatAnalysis {
        let beats: Vec<f64> = (0..bars * 4).map(|i| i as f64 * 0.5).collect();
        let downbeats: Vec<f64> = beats.iter().step_by(4).copied().collect();
        BeatAnalysis::new(120.0, beats, downbeats).unwrap()
    }

    #[test]
    fn beat_interval_follows_bpm() {
        assert!((grid(4).beat_interval() - 0.5).abs() < 1e-12);
    }

    #[test]
    fn beat_index_finds_the_preceding_beat() {
        let grid = grid(4);
        assert_eq!(grid.beat_index_at(0.0), Some(0));
        assert_eq!(grid.beat_index_at(0.4), Some(0));
        assert_eq!(grid.beat_index_at(0.5), Some(1));
        assert_eq!(grid.beat_index_at(100.0), Some(15));
    }

    #[test]
    fn beat_index_before_the_grid_is_none() {
        let analysis = BeatAnalysis::new(120.0, vec![1.0, 1.5], vec![1.0]).unwrap();
        assert_eq!(analysis.beat_index_at(0.5), None);
    }

    #[test]
    fn nearest_beat_picks_the_closer_side() {
        let grid = grid(4);
        assert_eq!(grid.nearest_beat(0.6), Some(0.5));
        assert_eq!(grid.nearest_beat(0.9), Some(1.0));
        assert_eq!(grid.nearest_beat(-5.0), Some(0.0));
        assert_eq!(grid.nearest_beat(500.0), Some(7.5));
    }

    #[test]
    fn nearest_downbeat_ignores_off_beats() {
        let grid = grid(4);
        assert_eq!(grid.nearest_downbeat(2.4), Some(2.0));
        assert_eq!(grid.nearest_downbeat(3.5), Some(4.0));
    }

    #[test]
    fn nearest_lookups_report_their_index() {
        let grid = grid(4);
        assert_eq!(grid.nearest_beat_index(0.6), Some(1));
        assert_eq!(grid.nearest_downbeat_index(3.5), Some(2));
        assert_eq!(grid.nearest_downbeat_index(-1.0), Some(0));

        let empty = BeatAnalysis::default();
        assert_eq!(empty.nearest_beat_index(1.0), None);
        assert_eq!(empty.nearest_downbeat_index(1.0), None);
    }

    #[test]
    fn distances_report_infinity_on_an_empty_grid() {
        let empty = BeatAnalysis::new(120.0, vec![], vec![]).unwrap();
        assert!(empty.distance_to_beat(1.0).is_infinite());
        assert!(empty.distance_to_downbeat(1.0).is_infinite());
        assert!(empty.is_empty());
    }

    #[test]
    fn range_queries_are_inclusive_of_both_ends() {
        let grid = grid(4);
        assert_eq!(grid.beats_in(1.0, 2.0), &[1.0, 1.5, 2.0]);
        assert_eq!(grid.downbeats_in(2.0, 6.0), &[2.0, 4.0, 6.0]);
        assert!(grid.beats_in(100.0, 200.0).is_empty());
        assert!(grid.beats_in(2.0, 1.0).is_empty());
    }

    #[test]
    fn beats_per_bar_is_inferred_from_downbeat_spacing() {
        assert_eq!(grid(8).beats_per_bar(), 4);

        let beats: Vec<f64> = (0..24).map(|i| i as f64 * 0.5).collect();
        let downbeats: Vec<f64> = beats.iter().step_by(3).copied().collect();
        let waltz = BeatAnalysis::new(120.0, beats, downbeats).unwrap();
        assert_eq!(waltz.beats_per_bar(), 3);
    }

    #[test]
    fn beats_per_bar_defaults_without_evidence() {
        let sparse = BeatAnalysis::new(120.0, vec![0.0, 0.5], vec![0.0]).unwrap();
        assert_eq!(sparse.beats_per_bar(), 4);
    }

    #[test]
    fn validation_rejects_bad_tempo() {
        assert!(BeatAnalysis::new(0.0, vec![], vec![]).is_err());
        assert!(BeatAnalysis::new(f32::NAN, vec![], vec![]).is_err());
        assert!(BeatAnalysis::new(-120.0, vec![], vec![]).is_err());
    }

    #[test]
    fn validation_rejects_unsorted_or_duplicate_beats() {
        assert!(BeatAnalysis::new(120.0, vec![1.0, 0.5], vec![]).is_err());
        assert!(BeatAnalysis::new(120.0, vec![1.0, 1.0], vec![]).is_err());
        assert!(BeatAnalysis::new(120.0, vec![0.0, f64::NAN], vec![]).is_err());
        assert!(BeatAnalysis::new(120.0, vec![-1.0], vec![]).is_err());
        assert!(BeatAnalysis::new(120.0, vec![0.0, 1.0], vec![2.0, 1.0]).is_err());
    }
}
