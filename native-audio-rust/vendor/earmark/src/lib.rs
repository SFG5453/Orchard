//! Beat-aware smart crossfade engine.
//!
//! Given decoded PCM, this crate can perform whole-track musical analysis. Given two tracks and a
//! beat grid for each, it also works out where and how they should be joined and renders the
//! transition. It is significantly more involved than a fixed-duration crossfade: entry points
//! come from downbeats, lengths come from phrases, tempo is reconciled by time stretching rather
//! than resampling, and the transition style is chosen from measurements of the audio itself.
//!
//! ```no_run
//! use earmark::{AudioBuffer, BeatAnalysis, EngineConfig, SmartCrossfadeEngine};
//!
//! # fn main() -> earmark::Result<()> {
//! # let (outgoing_pcm, incoming_pcm) = (vec![0.0f32; 2], vec![0.0f32; 2]);
//! let outgoing = AudioBuffer::from_interleaved(&outgoing_pcm, 2, 44_100)?;
//! let incoming = AudioBuffer::from_interleaved(&incoming_pcm, 2, 44_100)?;
//!
//! // Beats and downbeats in seconds, from your own beat tracker.
//! let outgoing_beats = BeatAnalysis::new(124.0, vec![0.0, 0.48], vec![0.0])?;
//! let incoming_beats = BeatAnalysis::new(126.0, vec![0.0, 0.47], vec![0.0])?;
//!
//! let mut engine = SmartCrossfadeEngine::new(EngineConfig::default())?;
//! let plan = engine.analyze(&outgoing, &incoming, &outgoing_beats, &incoming_beats)?;
//! let transition = engine.render(&outgoing, &incoming, &plan)?;
//!
//! println!("{}", plan.summary());
//! # Ok(())
//! # }
//! ```
//!
//! # Architecture
//!
//! Three stages, in order, with no feedback between them:
//!
//! ```text
//! ANALYSIS      analysis::Analyzer   measures the search windows
//!     |                              STFT features, phrase grid, R128 loudness
//!     v
//! PLANNING      planner::plan        generates, scores, and selects candidates
//!     |                              produces a TransitionPlan
//!     v
//! RENDERING     render::Renderer     executes the plan; decides nothing
//! ```
//!
//! A [`TransitionPlan`] is a complete description of the transition. The same plan and the same
//! PCM always yield identical samples, and a plan produced by one engine can be rendered by
//! another. Planning never renders, and rendering never re-plans.
//!
//! # Beat metadata
//!
//! [`analysis::WholeTrackAnalyzer`] detects BPM, beat times, and downbeat times from decoded mono
//! PCM. Existing transition APIs continue to accept caller-supplied [`BeatAnalysis`] so hosts can
//! use a model-refined grid without recomputing analysis. Downbeats drive candidate placement and
//! phrase alignment, so a grid without them still works but scores blind on structure.
//!
//! # PCM format
//!
//! [`AudioBuffer`] stores **planar** `f32` — one `Vec<f32>` per channel — because every DSP
//! operation here is per channel, and planar storage keeps those loops as flat slice walks.
//! Interleaved data (what most decoders emit) converts at the boundary with
//! [`AudioBuffer::from_interleaved`] and [`AudioBuffer::to_interleaved`]. Mono, stereo, and
//! arbitrary sample rates are supported; the engine reconciles mismatches between the two tracks
//! internally.
//!
//! # Tempo matching
//!
//! Time stretching is done with Signalsmith Stretch, never by resampling, so tempo and pitch stay
//! independent. Pitch is held constant unless [`config::TempoConfig::preserve_pitch`] is turned
//! off.
//!
//! By default the transition converges on the **incoming** track's tempo, which means the
//! incoming track plays at its native rate and the consumer can resume normal playback the
//! moment the transition ends, with no ongoing stretch. Stretch demand is graded against
//! configurable preferred / acceptable / maximum bands; a pairing that would need more than the
//! maximum is not discarded but demoted to a native-rate transition, and the planner then
//! prefers a style that does not depend on beatmatching. Tempos in a 2:1 relationship are folded
//! into one octave, so an 87 BPM track matches a 174 BPM one at a ratio of 1.0.
//!
//! # Constraining where the transition goes
//!
//! By default the engine searches the windows in [`config::TimingConfig`] and picks the best
//! scoring candidate. A host that runs its own structural analysis — where a track stops being
//! worth playing, where the incoming one drops — passes that knowledge to
//! [`SmartCrossfadeEngine::analyze_constrained`] as a [`TransitionConstraints`], and the engine
//! then chooses among beat-aligned candidates *inside* that region.
//!
//! Constraints name where a transition may begin and where it may **end**, on either track, which
//! is what pins a mix to a cue: "finish on the incoming track's drop" is a constraint on the end,
//! not the start. A constraint replaces the configured search window rather than narrowing it, so
//! a cue outside the default region is honoured. What it cannot do is bend the beat grid — every
//! reachable end is a whole number of bars from a downbeat, so a window narrower than that lattice
//! may legitimately contain nothing, and the engine reports
//! [`CrossfadeError::NoViableTransition`] rather than quietly drifting to the nearest fit.
//!
//! # Transition strategies
//!
//! - [`TransitionStrategy::EqualPowerCrossfade`] — overlapping equal-power fade, no tempo work.
//! - [`TransitionStrategy::BeatmatchedCrossfade`] — the same, with both sides stretched onto a
//!   common tempo.
//! - [`TransitionStrategy::BassSwap`] — beatmatched, and the low band is handed over at a point
//!   near the middle rather than summed, so two kick drums never share the bottom octave.
//!   Chosen when both regions are bass-dominant.
//! - [`TransitionStrategy::FilteredBlend`] — the outgoing track is progressively low-passed away
//!   while the incoming one opens up. Chosen when the two spectra clash.
//! - [`TransitionStrategy::ShortFade`] — for transitions too brief to develop.
//!
//! # Shaping a filter ride from outside
//!
//! A [`TransitionPlan`] is public and fully inspectable, so a host that measures something the
//! engine cannot can shape the render without the crate knowing what the measurement means.
//! Attaching an [`dsp::filters::FilterAutomation::depth`] curve to a planned filter scales how far
//! its corner actually travels, per instant, geometrically from where the sweep began. The curve
//! is applied as a running maximum, so a filter opened partway can never swing back — the wobble
//! is more audible than the attenuation it would save.
//!
//! # Ownership and threading
//!
//! [`SmartCrossfadeEngine`] owns the reusable machinery: the FFT plan, the stretch processor, and
//! the envelope scratch buffers. Audio is always borrowed, never taken — the caller keeps
//! ownership of its decoded tracks. The engine is `Send` but not `Sync`; give each worker thread
//! its own. Analysis and planning are meant to run ahead of playback, while rendering is written
//! to avoid allocation in its inner loops.
//!
//! # Debugging
//!
//! Set [`EngineConfig::collect_diagnostics`] to attach every scored candidate to the plan, then
//! read [`TransitionPlan::summary`] or walk
//! [`types::diagnostics::TransitionDiagnostics::ranked`] to see why one candidate won.

pub mod analysis;
pub mod audio;
pub mod config;
pub mod dsp;
pub mod engine;
pub mod error;
pub mod planner;
pub mod render;
pub mod types;

pub use analysis::{WholeTrackAnalysis, WholeTrackAnalyzer};
pub use audio::AudioBuffer;
pub use config::EngineConfig;
pub use engine::SmartCrossfadeEngine;
pub use error::{CrossfadeError, Result};
pub use planner::constraints::{RegionConstraint, TimeWindow, TransitionConstraints};
pub use types::{
    BeatAnalysis, CandidateScore, MAX_SELECTED_TEMPO_RATIO_DEVIATION, ScoredCandidate,
    SelectedTransition, TransitionDiagnostics, TransitionOutput, TransitionPlan,
    TransitionStrategy,
};
