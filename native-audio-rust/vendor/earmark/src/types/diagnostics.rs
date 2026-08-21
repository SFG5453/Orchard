//! Inspectable record of why the planner chose what it chose.
//!
//! Diagnostics are opt-in via [`crate::config::EngineConfig::collect_diagnostics`]. Rendering
//! never reads them.

use crate::types::transition::TransitionStrategy;

/// One possible transition, before scoring.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Candidate {
    pub outgoing_start: f64,
    pub incoming_start: f64,
    /// Transition length in beats at the target tempo.
    pub beats: u32,
    pub duration: f64,
    pub target_bpm: f32,
    pub outgoing_tempo_ratio: f32,
    pub incoming_tempo_ratio: f32,
    /// False when the tempos were too far apart to match and the candidate was demoted to
    /// playing both sides at their native rate.
    pub beatmatched: bool,
}

impl Candidate {
    /// Largest stretch either side is asked for, as a deviation from 1.0.
    pub fn max_ratio_deviation(&self) -> f32 {
        let out = (self.outgoing_tempo_ratio - 1.0).abs();
        let inc = (self.incoming_tempo_ratio - 1.0).abs();
        out.max(inc)
    }

    pub fn outgoing_end(&self) -> f64 {
        self.outgoing_start + self.duration * self.outgoing_tempo_ratio as f64
    }

    pub fn incoming_end(&self) -> f64 {
        self.incoming_start + self.duration * self.incoming_tempo_ratio as f64
    }
}

/// Per-component breakdown. Every field is in `0.0..=1.0`, where 1.0 is ideal.
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct CandidateScore {
    pub total: f32,
    pub beat_alignment: f32,
    pub phrase_alignment: f32,
    pub tempo: f32,
    pub spectral: f32,
    pub loudness: f32,
    pub energy: f32,
    pub transient: f32,
    pub low_freq: f32,
    pub duration: f32,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ScoredCandidate {
    pub candidate: Candidate,
    pub score: CandidateScore,
    pub strategy: TransitionStrategy,
}

/// Full candidate pool with the winner's index.
#[derive(Debug, Clone, PartialEq)]
pub struct TransitionDiagnostics {
    /// Scored candidates in generation order, which is deterministic for a given input.
    pub candidates: Vec<ScoredCandidate>,
    pub selected_candidate: usize,
    /// Candidates discarded before scoring because they did not fit the audio or the config.
    pub rejected: usize,
}

impl TransitionDiagnostics {
    pub fn selected(&self) -> Option<&ScoredCandidate> {
        self.candidates.get(self.selected_candidate)
    }

    /// Candidates ordered best-first. Ties keep generation order, so the ranking is stable.
    pub fn ranked(&self) -> Vec<&ScoredCandidate> {
        let mut ranked: Vec<&ScoredCandidate> = self.candidates.iter().collect();
        ranked.sort_by(|a, b| b.score.total.total_cmp(&a.score.total));
        ranked
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn candidate(ratio: f32) -> Candidate {
        Candidate {
            outgoing_start: 100.0,
            incoming_start: 10.0,
            beats: 16,
            duration: 8.0,
            target_bpm: 128.0,
            outgoing_tempo_ratio: ratio,
            incoming_tempo_ratio: 1.0,
            beatmatched: true,
        }
    }

    fn scored(total: f32) -> ScoredCandidate {
        ScoredCandidate {
            candidate: candidate(1.0),
            score: CandidateScore {
                total,
                ..CandidateScore::default()
            },
            strategy: TransitionStrategy::EqualPowerCrossfade,
        }
    }

    #[test]
    fn ratio_deviation_takes_the_larger_side() {
        assert!((candidate(1.05).max_ratio_deviation() - 0.05).abs() < 1e-6);
        assert!((candidate(0.9).max_ratio_deviation() - 0.1).abs() < 1e-6);
    }

    #[test]
    fn candidate_ends_account_for_stretch() {
        let candidate = candidate(1.5);
        assert!((candidate.outgoing_end() - 112.0).abs() < 1e-9);
        assert!((candidate.incoming_end() - 18.0).abs() < 1e-9);
    }

    #[test]
    fn selected_resolves_the_winner() {
        let diagnostics = TransitionDiagnostics {
            candidates: vec![scored(0.4), scored(0.9)],
            selected_candidate: 1,
            rejected: 3,
        };
        assert!((diagnostics.selected().unwrap().score.total - 0.9).abs() < 1e-6);
    }

    #[test]
    fn selected_is_none_when_out_of_range() {
        let diagnostics = TransitionDiagnostics {
            candidates: vec![],
            selected_candidate: 0,
            rejected: 0,
        };
        assert!(diagnostics.selected().is_none());
    }

    #[test]
    fn ranking_is_best_first() {
        let diagnostics = TransitionDiagnostics {
            candidates: vec![scored(0.2), scored(0.8), scored(0.5)],
            selected_candidate: 1,
            rejected: 0,
        };
        let ranked = diagnostics.ranked();
        assert!((ranked[0].score.total - 0.8).abs() < 1e-6);
        assert!((ranked[2].score.total - 0.2).abs() < 1e-6);
    }
}
