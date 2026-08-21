//! The public entry point.

use crate::analysis::Analyzer;
use crate::audio::AudioBuffer;
use crate::config::EngineConfig;
use crate::error::{CrossfadeError, Result};
use crate::planner::constraints::{RegionConstraint, TimeWindow, TransitionConstraints};
use crate::planner::{self, PlanInputs};
use crate::render::Renderer;
use crate::types::beat::BeatAnalysis;
use crate::types::transition::{TransitionOutput, TransitionPlan};

/// Analyses, plans, and renders transitions between decoded tracks.
///
/// The engine holds the reusable machinery — FFT plans, the stretch processor, scratch buffers —
/// so a playlist's worth of transitions costs one set of allocations rather than one per
/// transition. It is `Send` but not `Sync`: give each worker thread its own engine.
pub struct SmartCrossfadeEngine {
    config: EngineConfig,
    analyzer: Analyzer,
    renderer: Renderer,
}

impl SmartCrossfadeEngine {
    pub fn new(config: EngineConfig) -> Result<Self> {
        config.validate()?;
        let analyzer = Analyzer::new(&config.analysis)?;
        Ok(Self {
            config,
            analyzer,
            renderer: Renderer::new(),
        })
    }

    pub fn config(&self) -> &EngineConfig {
        &self.config
    }

    /// Decides what the transition should be, without rendering anything.
    ///
    /// `outgoing_beats` and `incoming_beats` come from an external beat tracker; the engine
    /// never detects them itself.
    pub fn analyze(
        &mut self,
        outgoing: &AudioBuffer,
        incoming: &AudioBuffer,
        outgoing_beats: &BeatAnalysis,
        incoming_beats: &BeatAnalysis,
    ) -> Result<TransitionPlan> {
        self.analyze_constrained(
            outgoing,
            incoming,
            outgoing_beats,
            incoming_beats,
            &TransitionConstraints::NONE,
        )
    }

    /// [`Self::analyze`], restricted to the region the caller nominates.
    ///
    /// For a host that runs its own structural analysis — where a track stops being worth playing,
    /// where the incoming one drops — this is how that knowledge reaches the planner. The engine
    /// still chooses among beat-aligned candidates and still scores them; it just does so inside
    /// the nominated region.
    pub fn analyze_constrained(
        &mut self,
        outgoing: &AudioBuffer,
        incoming: &AudioBuffer,
        outgoing_beats: &BeatAnalysis,
        incoming_beats: &BeatAnalysis,
        constraints: &TransitionConstraints,
    ) -> Result<TransitionPlan> {
        outgoing_beats.validate()?;
        incoming_beats.validate()?;
        constraints.validate()?;
        if outgoing.is_empty() || incoming.is_empty() {
            return Err(CrossfadeError::audio("both tracks must contain audio"));
        }

        let sample_rate = self.output_sample_rate(outgoing, incoming)?;
        let channels = self.output_channels(outgoing, incoming)?;
        let fit =
            planner::candidate::tempo_fit(outgoing_beats.bpm, incoming_beats.bpm, &self.config);
        let timing = &self.config.timing;

        // The outgoing window ends at the track end; the incoming one is extended by the longest
        // allowed transition so a candidate's tail is never read as silence. A constraint can
        // move candidates outside either default window, so the analysed span covers both.
        let outgoing_window = self.analysis_window(
            &constraints.outgoing,
            fit.outgoing_ratio as f64,
            TimeWindow::new(
                outgoing.duration() - timing.outgoing_search_window,
                outgoing.duration(),
            ),
            outgoing.duration(),
        );
        let incoming_default_start = timing.incoming_head_guard.max(0.0);
        let incoming_window = self.analysis_window(
            &constraints.incoming,
            fit.incoming_ratio as f64,
            TimeWindow::new(
                incoming_default_start,
                incoming_default_start + timing.incoming_search_window + timing.max_duration,
            ),
            incoming.duration(),
        );

        let outgoing_analysis = self.analyzer.analyze_window(
            outgoing,
            outgoing_beats,
            outgoing_window.earliest,
            outgoing_window.latest - outgoing_window.earliest,
            &self.config.analysis,
        )?;
        let incoming_analysis = self.analyzer.analyze_window(
            incoming,
            incoming_beats,
            incoming_window.earliest,
            incoming_window.latest - incoming_window.earliest,
            &self.config.analysis,
        )?;

        planner::plan(
            &PlanInputs {
                outgoing,
                incoming,
                outgoing_beats,
                incoming_beats,
                outgoing_analysis: &outgoing_analysis,
                incoming_analysis: &incoming_analysis,
                sample_rate,
                channels,
                constraints,
            },
            &self.config,
        )
    }

    /// Span of a track that has to be measured: the default search window, widened to cover
    /// anything a constraint newly brings into reach.
    fn analysis_window(
        &self,
        region: &RegionConstraint,
        ratio: f64,
        default: TimeWindow,
        duration: f64,
    ) -> TimeWindow {
        let timing = &self.config.timing;
        let window = match region.start_bounds(timing, ratio) {
            Some(bounds) => TimeWindow::new(
                bounds.earliest.min(default.earliest),
                (bounds.latest + timing.max_duration * ratio).max(default.latest),
            ),
            None => default,
        };
        TimeWindow::new(window.earliest.max(0.0), window.latest.min(duration))
    }

    /// Renders a plan. Deterministic: the same plan and the same PCM always produce the same
    /// samples.
    pub fn render(
        &mut self,
        outgoing: &AudioBuffer,
        incoming: &AudioBuffer,
        plan: &TransitionPlan,
    ) -> Result<TransitionOutput> {
        self.renderer.render(outgoing, incoming, plan, &self.config)
    }

    /// Convenience wrapper over [`Self::analyze`] followed by [`Self::render`].
    pub fn transition(
        &mut self,
        outgoing: &AudioBuffer,
        incoming: &AudioBuffer,
        outgoing_beats: &BeatAnalysis,
        incoming_beats: &BeatAnalysis,
    ) -> Result<(TransitionPlan, TransitionOutput)> {
        let plan = self.analyze(outgoing, incoming, outgoing_beats, incoming_beats)?;
        let output = self.render(outgoing, incoming, &plan)?;
        Ok((plan, output))
    }

    fn output_sample_rate(&self, outgoing: &AudioBuffer, incoming: &AudioBuffer) -> Result<u32> {
        if let Some(rate) = self.config.output_sample_rate {
            return Ok(rate);
        }
        if outgoing.sample_rate() != incoming.sample_rate() && !self.config.allow_resampling {
            return Err(CrossfadeError::SampleRateMismatch {
                outgoing: outgoing.sample_rate(),
                incoming: incoming.sample_rate(),
            });
        }
        // The incoming track's rate wins, so whatever plays after the transition needs no
        // further conversion.
        Ok(incoming.sample_rate())
    }

    fn output_channels(&self, outgoing: &AudioBuffer, incoming: &AudioBuffer) -> Result<usize> {
        let (a, b) = (outgoing.channel_count(), incoming.channel_count());
        match (a, b) {
            (a, b) if a == b => Ok(a),
            (1, other) | (other, 1) => Ok(other),
            _ => Err(CrossfadeError::ChannelMismatch {
                outgoing: a,
                incoming: b,
            }),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SR: u32 = 44_100;

    fn beats(bpm: f32, seconds: f64) -> BeatAnalysis {
        let interval = 60.0 / bpm as f64;
        let count = (seconds / interval) as usize;
        let times: Vec<f64> = (0..count).map(|i| i as f64 * interval).collect();
        let downbeats: Vec<f64> = times.iter().step_by(4).copied().collect();
        BeatAnalysis::new(bpm, times, downbeats).unwrap()
    }

    fn track(bpm: f32, seconds: f64, tone: f32, channels: usize, rate: u32) -> AudioBuffer {
        let frames = (seconds * rate as f64) as usize;
        let interval = ((60.0 / bpm) * rate as f32) as usize;
        let mut channel = vec![0.0f32; frames];
        for (i, sample) in channel.iter_mut().enumerate() {
            let phase = i as f32 / rate as f32;
            let beat_phase = (i % interval.max(1)) as f32 / rate as f32;
            let kick = (-beat_phase * 10.0).exp();
            *sample = 0.5 * kick * (phase * 55.0 * std::f32::consts::TAU).sin()
                + 0.2 * (phase * tone * std::f32::consts::TAU).sin();
        }
        AudioBuffer::new(vec![channel; channels], rate).unwrap()
    }

    fn engine() -> SmartCrossfadeEngine {
        SmartCrossfadeEngine::new(EngineConfig::default()).unwrap()
    }

    #[test]
    fn a_full_transition_produces_audio_of_the_planned_length() {
        let mut engine = engine();
        let outgoing = track(120.0, 60.0, 440.0, 2, SR);
        let incoming = track(122.0, 60.0, 660.0, 2, SR);

        let (plan, output) = engine
            .transition(
                &outgoing,
                &incoming,
                &beats(120.0, 60.0),
                &beats(122.0, 60.0),
            )
            .unwrap();

        assert_eq!(output.audio.frames(), plan.frames());
        assert_eq!(output.audio.sample_rate(), SR);
        assert_eq!(output.audio.channel_count(), 2);
        assert!(output.audio.peak() > 0.05);
    }

    #[test]
    fn analysis_and_rendering_are_both_deterministic() {
        let mut engine = engine();
        let outgoing = track(120.0, 60.0, 440.0, 2, SR);
        let incoming = track(124.0, 60.0, 660.0, 2, SR);
        let out_beats = beats(120.0, 60.0);
        let in_beats = beats(124.0, 60.0);

        let first = engine
            .transition(&outgoing, &incoming, &out_beats, &in_beats)
            .unwrap();
        let second = engine
            .transition(&outgoing, &incoming, &out_beats, &in_beats)
            .unwrap();

        assert_eq!(first.0, second.0);
        assert_eq!(first.1, second.1);
    }

    #[test]
    fn a_plan_can_be_rendered_by_a_different_engine() {
        let outgoing = track(120.0, 60.0, 440.0, 2, SR);
        let incoming = track(121.0, 60.0, 660.0, 2, SR);
        let plan = engine()
            .analyze(
                &outgoing,
                &incoming,
                &beats(120.0, 60.0),
                &beats(121.0, 60.0),
            )
            .unwrap();

        let a = engine().render(&outgoing, &incoming, &plan).unwrap();
        let b = engine().render(&outgoing, &incoming, &plan).unwrap();
        assert_eq!(a, b);
    }

    #[test]
    fn the_incoming_rate_and_widest_layout_win() {
        let mut engine = engine();
        let outgoing = track(120.0, 60.0, 440.0, 1, 48_000);
        let incoming = track(120.0, 60.0, 660.0, 2, SR);

        let plan = engine
            .analyze(
                &outgoing,
                &incoming,
                &beats(120.0, 60.0),
                &beats(120.0, 60.0),
            )
            .unwrap();
        assert_eq!(plan.sample_rate, SR);
        assert_eq!(plan.channels, 2);
    }

    #[test]
    fn a_forced_output_rate_overrides_both_tracks() {
        let config = EngineConfig {
            output_sample_rate: Some(48_000),
            ..EngineConfig::default()
        };
        let mut engine = SmartCrossfadeEngine::new(config).unwrap();
        let outgoing = track(120.0, 60.0, 440.0, 2, SR);

        let plan = engine
            .analyze(
                &outgoing,
                &outgoing,
                &beats(120.0, 60.0),
                &beats(120.0, 60.0),
            )
            .unwrap();
        assert_eq!(plan.sample_rate, 48_000);

        let output = engine.render(&outgoing, &outgoing, &plan).unwrap();
        assert_eq!(output.audio.sample_rate(), 48_000);
    }

    #[test]
    fn rate_mismatches_can_be_made_an_error() {
        let config = EngineConfig {
            allow_resampling: false,
            ..EngineConfig::default()
        };
        let mut engine = SmartCrossfadeEngine::new(config).unwrap();
        let error = engine
            .analyze(
                &track(120.0, 60.0, 440.0, 2, 48_000),
                &track(120.0, 60.0, 660.0, 2, SR),
                &beats(120.0, 60.0),
                &beats(120.0, 60.0),
            )
            .unwrap_err();
        assert!(matches!(error, CrossfadeError::SampleRateMismatch { .. }));
    }

    #[test]
    fn unmappable_channel_layouts_are_an_error() {
        let mut engine = engine();
        let error = engine
            .analyze(
                &AudioBuffer::silent(3, SR as usize * 60, SR).unwrap(),
                &track(120.0, 60.0, 660.0, 2, SR),
                &beats(120.0, 60.0),
                &beats(120.0, 60.0),
            )
            .unwrap_err();
        assert!(matches!(error, CrossfadeError::ChannelMismatch { .. }));
    }

    #[test]
    fn invalid_inputs_are_rejected() {
        let mut engine = engine();
        let good = track(120.0, 60.0, 440.0, 2, SR);
        let empty = AudioBuffer::silent(2, 0, SR).unwrap();

        assert!(
            engine
                .analyze(&empty, &good, &beats(120.0, 60.0), &beats(120.0, 60.0))
                .is_err()
        );

        let bad_beats = BeatAnalysis {
            bpm: 120.0,
            beats: vec![2.0, 1.0],
            downbeats: vec![],
        };
        assert!(
            engine
                .analyze(&good, &good, &bad_beats, &beats(120.0, 60.0))
                .is_err()
        );
    }

    #[test]
    fn an_invalid_config_is_rejected_at_construction() {
        let config = EngineConfig {
            analysis: crate::config::AnalysisConfig {
                fft_size: 999,
                ..crate::config::AnalysisConfig::default()
            },
            ..EngineConfig::default()
        };
        assert!(SmartCrossfadeEngine::new(config).is_err());
    }

    #[test]
    fn diagnostics_explain_the_selection() {
        let config = EngineConfig {
            collect_diagnostics: true,
            ..EngineConfig::default()
        };
        let mut engine = SmartCrossfadeEngine::new(config).unwrap();
        let outgoing = track(120.0, 60.0, 440.0, 2, SR);
        let incoming = track(123.0, 60.0, 660.0, 2, SR);

        let plan = engine
            .analyze(
                &outgoing,
                &incoming,
                &beats(120.0, 60.0),
                &beats(123.0, 60.0),
            )
            .unwrap();
        let diagnostics = plan.diagnostics.as_ref().unwrap();

        assert!(diagnostics.candidates.len() > 1);
        assert!(diagnostics.selected().is_some());
        assert!(plan.summary().contains("tempo compatibility"));
    }
}
