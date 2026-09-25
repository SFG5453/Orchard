//! Data types shared across the analysis, planning, and rendering stages.

pub mod analysis;
pub mod beat;
pub mod diagnostics;
pub mod transition;

pub use analysis::{FeatureTrack, FrameFeatures, LoudnessStats, RegionFeatures};
pub use beat::BeatAnalysis;
pub use diagnostics::{Candidate, CandidateScore, ScoredCandidate, TransitionDiagnostics};
pub use transition::{
    FadePlan, FilterPlan, MAX_SELECTED_TEMPO_RATIO_DEVIATION, SelectedTransition, TransitionOutput,
    TransitionPlan, TransitionStrategy,
};
