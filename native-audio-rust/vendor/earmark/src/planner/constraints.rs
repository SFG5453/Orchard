//! Caller-supplied limits on where a transition may be placed.
//!
//! A host that already knows where a track should be mixed out of — from its own cue points or
//! structural analysis — narrows the search to that region rather than letting the planner choose
//! freely.
//!
//! A constraint *replaces* the configured search window rather than intersecting with it, so a
//! cue that sits outside [`TimingConfig::outgoing_search_window`] is honoured instead of being
//! silently clipped back to the default region. What a constraint can never do is override
//! physical feasibility: a transition still has to fit inside both tracks, land on the supplied
//! beat grid, and respect the configured duration range.
//!
//! Windows are inclusive and expressed in seconds on each track's **own** timeline, which for the
//! end of a transition means source seconds: a stretched side consumes more of its own timeline
//! than the rendered duration.

use crate::config::TimingConfig;
use crate::error::{CrossfadeError, Result};

/// An inclusive span of seconds.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct TimeWindow {
    pub earliest: f64,
    pub latest: f64,
}

impl TimeWindow {
    pub fn new(earliest: f64, latest: f64) -> Self {
        Self { earliest, latest }
    }

    /// A window `tolerance` either side of `center` — the usual way to say "here, give or take a
    /// beat", since a beat grid rarely lands exactly on a structural cue.
    pub fn around(center: f64, tolerance: f64) -> Self {
        let tolerance = tolerance.abs();
        Self::new(center - tolerance, center + tolerance)
    }

    pub fn contains(&self, seconds: f64) -> bool {
        seconds >= self.earliest && seconds <= self.latest
    }

    /// True when no instant satisfies the window, which is how an over-tight intersection reports
    /// itself rather than by erroring.
    pub fn is_empty(&self) -> bool {
        self.latest < self.earliest
    }

    pub fn intersect(&self, other: &Self) -> Self {
        Self::new(
            self.earliest.max(other.earliest),
            self.latest.min(other.latest),
        )
    }

    /// Shifts the window by `offset`, used to turn a constraint on where a transition ends into
    /// one on where it may start.
    fn shifted(&self, earliest: f64, latest: f64) -> Self {
        Self::new(self.earliest + earliest, self.latest + latest)
    }

    fn validate(&self, label: &str) -> Result<()> {
        if !self.earliest.is_finite() || !self.latest.is_finite() {
            return Err(CrossfadeError::config(format!(
                "{label} window must be finite, got {}..={}",
                self.earliest, self.latest
            )));
        }
        if self.is_empty() {
            return Err(CrossfadeError::config(format!(
                "{label} window ends before it begins: {}..={}",
                self.earliest, self.latest
            )));
        }
        Ok(())
    }
}

/// Where a transition may begin and end on one track.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct RegionConstraint {
    pub start_within: Option<TimeWindow>,
    pub end_within: Option<TimeWindow>,
}

impl RegionConstraint {
    /// Constrains nothing. Available in const context so it can be borrowed without a binding.
    pub const NONE: Self = Self {
        start_within: None,
        end_within: None,
    };

    pub fn starting_within(window: TimeWindow) -> Self {
        Self {
            start_within: Some(window),
            end_within: None,
        }
    }

    pub fn ending_within(window: TimeWindow) -> Self {
        Self {
            start_within: None,
            end_within: Some(window),
        }
    }

    pub fn is_unconstrained(&self) -> bool {
        self.start_within.is_none() && self.end_within.is_none()
    }

    /// Whether a candidate spanning `start..start + source_duration` satisfies this constraint.
    pub(crate) fn admits(&self, start: f64, source_duration: f64) -> bool {
        if let Some(window) = &self.start_within
            && !window.contains(start)
        {
            return false;
        }
        if let Some(window) = &self.end_within
            && !window.contains(start + source_duration)
        {
            return false;
        }
        true
    }

    /// The widest span of transition starts that could satisfy this constraint.
    ///
    /// Anchors are picked before any candidate exists, so a constraint on where a transition
    /// *ends* has to be turned into one on where it may start. The shortest allowed transition
    /// gives the latest usable start and the longest gives the earliest, so the two bounds come
    /// from opposite ends of the duration range. `ratio` scales rendered duration into this
    /// track's own timeline.
    pub(crate) fn start_bounds(&self, timing: &TimingConfig, ratio: f64) -> Option<TimeWindow> {
        let from_end = self
            .end_within
            .map(|w| w.shifted(-timing.max_duration * ratio, -timing.min_duration * ratio));
        match (self.start_within, from_end) {
            (Some(start), Some(end)) => Some(start.intersect(&end)),
            (Some(start), None) => Some(start),
            (None, Some(end)) => Some(end),
            (None, None) => None,
        }
    }

    fn validate(&self, label: &str) -> Result<()> {
        if let Some(window) = &self.start_within {
            window.validate(&format!("{label} start"))?;
        }
        if let Some(window) = &self.end_within {
            window.validate(&format!("{label} end"))?;
        }
        Ok(())
    }
}

/// Limits applied to one planning call. The default constrains nothing.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct TransitionConstraints {
    pub outgoing: RegionConstraint,
    pub incoming: RegionConstraint,
    /// Restricts transition lengths to this subset of [`TimingConfig::allowed_beat_lengths`].
    /// Lengths the configuration does not already allow are ignored rather than added.
    pub beat_lengths: Option<Vec<u32>>,
}

impl TransitionConstraints {
    /// Constrains nothing — what [`SmartCrossfadeEngine::analyze`] plans against.
    ///
    /// [`SmartCrossfadeEngine::analyze`]: crate::SmartCrossfadeEngine::analyze
    pub const NONE: Self = Self {
        outgoing: RegionConstraint::NONE,
        incoming: RegionConstraint::NONE,
        beat_lengths: None,
    };

    pub fn is_unconstrained(&self) -> bool {
        self.outgoing.is_unconstrained()
            && self.incoming.is_unconstrained()
            && self.beat_lengths.is_none()
    }

    pub(crate) fn admits_beats(&self, beats: u32) -> bool {
        self.beat_lengths
            .as_ref()
            .is_none_or(|allowed| allowed.contains(&beats))
    }

    pub fn validate(&self) -> Result<()> {
        self.outgoing.validate("outgoing")?;
        self.incoming.validate("incoming")?;
        if self.beat_lengths.as_ref().is_some_and(|b| b.is_empty()) {
            return Err(CrossfadeError::config(
                "beat_lengths constraint is empty, which admits no transition",
            ));
        }
        Ok(())
    }

    /// Human-readable form for the error raised when constraints leave nothing to score.
    pub(crate) fn describe(&self) -> String {
        let mut parts = Vec::new();
        let mut describe = |label: &str, region: &RegionConstraint| {
            if let Some(w) = &region.start_within {
                parts.push(format!(
                    "{label} start in {:.2}..={:.2}s",
                    w.earliest, w.latest
                ));
            }
            if let Some(w) = &region.end_within {
                parts.push(format!(
                    "{label} end in {:.2}..={:.2}s",
                    w.earliest, w.latest
                ));
            }
        };
        describe("outgoing", &self.outgoing);
        describe("incoming", &self.incoming);
        if let Some(beats) = &self.beat_lengths {
            parts.push(format!("beat lengths {beats:?}"));
        }
        if parts.is_empty() {
            "unconstrained".to_string()
        } else {
            parts.join(", ")
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn timing() -> TimingConfig {
        TimingConfig {
            min_duration: 2.0,
            max_duration: 10.0,
            ..TimingConfig::default()
        }
    }

    #[test]
    fn a_window_around_a_cue_spans_the_tolerance_either_side() {
        let window = TimeWindow::around(191.0, 0.5);
        assert!(window.contains(190.5));
        assert!(window.contains(191.5));
        assert!(!window.contains(190.4));
        assert!(!window.contains(191.6));
    }

    #[test]
    fn intersection_can_leave_nothing() {
        let a = TimeWindow::new(0.0, 5.0);
        let b = TimeWindow::new(10.0, 12.0);
        assert!(a.intersect(&b).is_empty());
        assert!(!a.intersect(&TimeWindow::new(4.0, 8.0)).is_empty());
    }

    #[test]
    fn an_end_constraint_admits_only_candidates_finishing_inside_it() {
        let region = RegionConstraint::ending_within(TimeWindow::around(100.0, 0.25));
        assert!(region.admits(92.0, 8.0));
        assert!(!region.admits(92.0, 4.0));
    }

    #[test]
    fn a_start_constraint_ignores_where_the_candidate_finishes() {
        let region = RegionConstraint::starting_within(TimeWindow::new(10.0, 20.0));
        assert!(region.admits(15.0, 3.0));
        assert!(region.admits(15.0, 300.0));
        assert!(!region.admits(21.0, 3.0));
    }

    #[test]
    fn both_windows_must_be_satisfied_together() {
        let region = RegionConstraint {
            start_within: Some(TimeWindow::new(10.0, 20.0)),
            end_within: Some(TimeWindow::new(25.0, 30.0)),
        };
        assert!(region.admits(20.0, 6.0));
        assert!(!region.admits(20.0, 20.0));
        assert!(!region.admits(5.0, 22.0));
    }

    #[test]
    fn an_unconstrained_region_admits_everything() {
        let region = RegionConstraint::default();
        assert!(region.is_unconstrained());
        assert!(region.admits(0.0, 0.0));
        assert!(region.admits(1_000.0, 500.0));
        assert!(region.start_bounds(&timing(), 1.0).is_none());
    }

    #[test]
    fn end_bounds_become_start_bounds_using_the_duration_range() {
        // The longest transition gives the earliest start, the shortest the latest.
        let region = RegionConstraint::ending_within(TimeWindow::new(100.0, 101.0));
        let bounds = region.start_bounds(&timing(), 1.0).unwrap();
        assert!((bounds.earliest - 90.0).abs() < 1e-9);
        assert!((bounds.latest - 99.0).abs() < 1e-9);
    }

    #[test]
    fn the_tempo_ratio_scales_derived_start_bounds() {
        let region = RegionConstraint::ending_within(TimeWindow::new(100.0, 100.0));
        let bounds = region.start_bounds(&timing(), 1.5).unwrap();
        assert!((bounds.earliest - 85.0).abs() < 1e-9);
        assert!((bounds.latest - 97.0).abs() < 1e-9);
    }

    #[test]
    fn start_and_end_bounds_intersect_when_both_are_given() {
        let region = RegionConstraint {
            start_within: Some(TimeWindow::new(95.0, 120.0)),
            end_within: Some(TimeWindow::new(100.0, 101.0)),
        };
        let bounds = region.start_bounds(&timing(), 1.0).unwrap();
        assert!((bounds.earliest - 95.0).abs() < 1e-9);
        assert!((bounds.latest - 99.0).abs() < 1e-9);
    }

    #[test]
    fn beat_lengths_restrict_but_never_extend() {
        let constraints = TransitionConstraints {
            beat_lengths: Some(vec![16, 32]),
            ..TransitionConstraints::default()
        };
        assert!(constraints.admits_beats(16));
        assert!(!constraints.admits_beats(8));
        assert!(TransitionConstraints::default().admits_beats(8));
    }

    #[test]
    fn inverted_and_empty_constraints_are_rejected() {
        let inverted = TransitionConstraints {
            outgoing: RegionConstraint::ending_within(TimeWindow::new(10.0, 5.0)),
            ..TransitionConstraints::default()
        };
        assert!(inverted.validate().is_err());

        let empty = TransitionConstraints {
            beat_lengths: Some(Vec::new()),
            ..TransitionConstraints::default()
        };
        assert!(empty.validate().is_err());

        let infinite = TransitionConstraints {
            incoming: RegionConstraint::starting_within(TimeWindow::new(0.0, f64::INFINITY)),
            ..TransitionConstraints::default()
        };
        assert!(infinite.validate().is_err());

        assert!(TransitionConstraints::default().validate().is_ok());
    }

    #[test]
    fn the_description_names_every_active_constraint() {
        assert_eq!(TransitionConstraints::default().describe(), "unconstrained");
        let constraints = TransitionConstraints {
            outgoing: RegionConstraint::ending_within(TimeWindow::new(190.0, 192.0)),
            incoming: RegionConstraint::starting_within(TimeWindow::new(8.0, 9.0)),
            beat_lengths: Some(vec![16]),
        };
        let text = constraints.describe();
        assert!(text.contains("outgoing end in 190.00..=192.00s"));
        assert!(text.contains("incoming start in 8.00..=9.00s"));
        assert!(text.contains("beat lengths [16]"));
    }
}
