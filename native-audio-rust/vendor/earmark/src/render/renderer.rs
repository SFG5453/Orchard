//! Plan execution.
//!
//! The renderer makes no decisions. It reads a [`TransitionPlan`], prepares both sides to a
//! common rate and layout, applies the planned filters and envelopes, and sums the result.

use crate::audio::AudioBuffer;
use crate::audio::stretch::TimeStretcher;
use crate::config::EngineConfig;
use crate::dsp::automation::AutomationCurve;
use crate::error::{CrossfadeError, Result};
use crate::render::stage;
use crate::types::transition::{TransitionOutput, TransitionPlan};

/// Owns the stretch processor and the envelope scratch buffers across renders.
#[derive(Default)]
pub struct Renderer {
    stretcher: Option<TimeStretcher>,
    envelope: Vec<f32>,
    gain: Vec<f32>,
}

impl Renderer {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn render(
        &mut self,
        outgoing: &AudioBuffer,
        incoming: &AudioBuffer,
        plan: &TransitionPlan,
        config: &EngineConfig,
    ) -> Result<TransitionOutput> {
        let frames = plan.frames();
        if frames == 0 || plan.duration <= 0.0 {
            return Err(CrossfadeError::config(
                "transition plan has no duration to render",
            ));
        }
        if plan.channels == 0 || plan.sample_rate == 0 {
            return Err(CrossfadeError::config(
                "transition plan must name a channel count and sample rate",
            ));
        }

        let template = stage::StemRequest {
            start: plan.outgoing_start,
            duration: plan.duration,
            ratio: plan.outgoing_tempo_ratio,
            semitones: plan.outgoing_pitch_semitones,
            frames,
            sample_rate: plan.sample_rate,
            channels: plan.channels,
        };
        let stretcher = self.stretcher_for(plan)?;
        let mut outgoing_stem = stage::prepare(stretcher, outgoing, &template)?;
        let mut incoming_stem = stage::prepare(
            stretcher,
            incoming,
            &stage::StemRequest {
                start: plan.incoming_start,
                ratio: plan.incoming_tempo_ratio,
                semitones: plan.incoming_pitch_semitones,
                ..template
            },
        )?;

        stage::apply_filters(&mut outgoing_stem.audio, &plan.filters.outgoing, config)?;
        stage::apply_filters(&mut incoming_stem.audio, &plan.filters.incoming, config)?;

        let mut mixed = AudioBuffer::silent(plan.channels, frames, plan.sample_rate)?;

        self.build_envelope(frames, |envelope| {
            plan.fade.outgoing_curve.fill_out(envelope)
        });
        self.apply_gain_curve(&plan.fade.outgoing_gain, frames);
        stage::mix_stem(&mut mixed, &outgoing_stem.audio, &self.envelope);

        self.build_envelope(frames, |envelope| {
            plan.fade.incoming_curve.fill_in(envelope)
        });
        self.apply_gain_curve(&plan.fade.incoming_gain, frames);
        stage::mix_stem(&mut mixed, &incoming_stem.audio, &self.envelope);

        let ceiling_trim_db = stage::trim_to_ceiling(&mut mixed, config);

        Ok(TransitionOutput {
            audio: mixed,
            outgoing_consumed: outgoing_stem.consumed,
            incoming_consumed: incoming_stem.consumed,
            outgoing_resume: plan.outgoing_start + outgoing_stem.consumed,
            incoming_resume: plan.incoming_start + incoming_stem.consumed,
            ceiling_trim_db,
        })
    }

    /// Reuses the stretch processor unless the plan changes its channel count or sample rate.
    fn stretcher_for(&mut self, plan: &TransitionPlan) -> Result<&mut TimeStretcher> {
        let matches = self
            .stretcher
            .as_ref()
            .is_some_and(|s| s.matches(plan.channels, plan.sample_rate));
        if !matches {
            self.stretcher = Some(TimeStretcher::new(plan.channels, plan.sample_rate)?);
        }
        Ok(self
            .stretcher
            .as_mut()
            .expect("stretcher was just installed"))
    }

    fn build_envelope(&mut self, frames: usize, fill: impl FnOnce(&mut [f32])) {
        self.envelope.clear();
        self.envelope.resize(frames, 0.0);
        fill(&mut self.envelope);
    }

    /// Folds a gain ride into the fade envelope already in `self.envelope`, so each channel
    /// multiplies by a single pre-computed curve.
    fn apply_gain_curve(&mut self, curve: &AutomationCurve, frames: usize) {
        if curve.is_constant() {
            let value = curve.value_at(0.0);
            if value != 1.0 {
                for slot in &mut self.envelope {
                    *slot *= value;
                }
            }
            return;
        }
        self.gain.clear();
        self.gain.resize(frames, 1.0);
        curve.fill(&mut self.gain);
        for (slot, gain) in self.envelope.iter_mut().zip(&self.gain) {
            *slot *= *gain;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::LoudnessConfig;
    use crate::dsp::fade::FadeCurve;
    use crate::dsp::gain::{db_to_linear, rms};
    use crate::types::transition::{FadePlan, FilterPlan, TransitionStrategy};

    const SR: u32 = 48_000;

    fn tone(freq: f32, seconds: f64, amplitude: f32, channels: usize, rate: u32) -> AudioBuffer {
        let frames = (seconds * rate as f64) as usize;
        let channel: Vec<f32> = (0..frames)
            .map(|i| amplitude * (i as f32 / rate as f32 * freq * std::f32::consts::TAU).sin())
            .collect();
        AudioBuffer::new(vec![channel; channels], rate).unwrap()
    }

    fn plan(duration: f64) -> TransitionPlan {
        TransitionPlan {
            outgoing_start: 1.0,
            incoming_start: 0.5,
            duration,
            beats: 16,
            sample_rate: SR,
            channels: 2,
            outgoing_bpm: 120.0,
            incoming_bpm: 120.0,
            target_bpm: 120.0,
            outgoing_tempo_ratio: 1.0,
            incoming_tempo_ratio: 1.0,
            outgoing_pitch_semitones: 0.0,
            incoming_pitch_semitones: 0.0,
            outgoing_gain_db: 0.0,
            incoming_gain_db: 0.0,
            strategy: TransitionStrategy::EqualPowerCrossfade,
            fade: FadePlan::default(),
            filters: FilterPlan::default(),
            diagnostics: None,
        }
    }

    #[test]
    fn output_shape_follows_the_plan() {
        let mut renderer = Renderer::new();
        let output = renderer
            .render(
                &tone(220.0, 10.0, 0.5, 2, SR),
                &tone(440.0, 10.0, 0.5, 2, SR),
                &plan(4.0),
                &EngineConfig::default(),
            )
            .unwrap();

        assert_eq!(output.audio.frames(), (4.0 * SR as f64) as usize);
        assert_eq!(output.audio.channel_count(), 2);
        assert_eq!(output.audio.sample_rate(), SR);
        assert!((output.duration() - 4.0).abs() < 1e-6);
    }

    #[test]
    fn both_tracks_are_audible_across_the_transition() {
        let mut renderer = Renderer::new();
        let output = renderer
            .render(
                &tone(220.0, 10.0, 0.5, 2, SR),
                &tone(440.0, 10.0, 0.5, 2, SR),
                &plan(4.0),
                &EngineConfig::default(),
            )
            .unwrap();

        let channel = output.audio.channel(0);
        let window = channel.len() / 20;
        assert!(rms(&channel[..window]) > 0.2, "transition starts silent");
        assert!(rms(&channel[channel.len() - window..]) > 0.2, "ends silent");
        assert!(output.audio.peak() <= 1.0);
    }

    #[test]
    fn resume_positions_account_for_stretch() {
        let mut renderer = Renderer::new();
        let mut plan = plan(4.0);
        plan.outgoing_tempo_ratio = 1.05;

        let output = renderer
            .render(
                &tone(220.0, 20.0, 0.5, 2, SR),
                &tone(440.0, 20.0, 0.5, 2, SR),
                &plan,
                &EngineConfig::default(),
            )
            .unwrap();

        assert!((output.outgoing_consumed - 4.0 * 1.05).abs() < 1e-6);
        assert!((output.incoming_consumed - 4.0).abs() < 1e-6);
        assert!((output.outgoing_resume - (1.0 + 4.2)).abs() < 1e-6);
        assert!((output.incoming_resume - 4.5).abs() < 1e-6);
    }

    #[test]
    fn mismatched_rates_and_layouts_are_reconciled() {
        let mut renderer = Renderer::new();
        let output = renderer
            .render(
                &tone(220.0, 10.0, 0.5, 1, 44_100),
                &tone(440.0, 10.0, 0.5, 2, SR),
                &plan(4.0),
                &EngineConfig::default(),
            )
            .unwrap();

        assert_eq!(output.audio.channel_count(), 2);
        assert_eq!(output.audio.sample_rate(), SR);
        assert!(output.audio.peak() > 0.1);
    }

    #[test]
    fn the_ceiling_holds_the_mix_down() {
        let mut renderer = Renderer::new();
        let config = EngineConfig::default();
        let loud = tone(220.0, 10.0, 1.0, 2, SR);
        let output = renderer.render(&loud, &loud, &plan(4.0), &config).unwrap();

        assert!(output.ceiling_trim_db < 0.0, "no trim was applied");
        assert!(output.audio.peak() <= db_to_linear(config.loudness.ceiling_db) + 1e-4);
    }

    #[test]
    fn clipping_protection_can_be_disabled() {
        let mut renderer = Renderer::new();
        let config = EngineConfig {
            loudness: LoudnessConfig {
                prevent_clipping: false,
                ..LoudnessConfig::default()
            },
            ..EngineConfig::default()
        };
        let loud = tone(220.0, 10.0, 1.0, 2, SR);
        let output = renderer.render(&loud, &loud, &plan(4.0), &config).unwrap();
        assert_eq!(output.ceiling_trim_db, 0.0);
    }

    #[test]
    fn gain_curves_reach_their_endpoints() {
        let mut renderer = Renderer::new();
        let mut plan = plan(4.0);
        plan.fade.outgoing_curve = FadeCurve::Linear;
        plan.fade.incoming_curve = FadeCurve::Linear;
        plan.fade.incoming_gain = AutomationCurve::constant(0.0);

        let output = renderer
            .render(
                &tone(220.0, 10.0, 0.5, 2, SR),
                &tone(440.0, 10.0, 0.5, 2, SR),
                &plan,
                &EngineConfig::default(),
            )
            .unwrap();

        // With the incoming side muted, the tail of a linear fade-out must be near silent.
        let channel = output.audio.channel(0);
        let tail = &channel[channel.len() - channel.len() / 50..];
        assert!(rms(tail) < 0.02, "tail level {}", rms(tail));
    }

    #[test]
    fn filters_reach_the_rendered_audio() {
        let mut renderer = Renderer::new();
        let config = EngineConfig::default();
        let bright = tone(6_000.0, 10.0, 0.8, 2, SR);

        // Mute the incoming side and keep the fades identical, so the only difference between
        // the two renders is the filter plan itself.
        let mut unfiltered = plan(4.0);
        unfiltered.strategy = TransitionStrategy::FilteredBlend;
        unfiltered.fade.incoming_gain = AutomationCurve::constant(0.0);
        let mut filtered = unfiltered.clone();
        filtered.filters = crate::planner::strategy::build_filters(filtered.strategy, &config);

        let plain = renderer
            .render(&bright, &bright, &unfiltered, &config)
            .unwrap();
        let swept = renderer
            .render(&bright, &bright, &filtered, &config)
            .unwrap();

        let window = |buffer: &AudioBuffer| {
            let channel = buffer.channel(0);
            rms(&channel[channel.len() * 3 / 4..])
        };
        let (open, closed) = (window(&plain.audio), window(&swept.audio));
        assert!(
            open > closed * 8.0,
            "low-pass did not close: {open} vs {closed}"
        );
    }

    #[test]
    fn rendering_is_deterministic() {
        let mut renderer = Renderer::new();
        let outgoing = tone(220.0, 10.0, 0.5, 2, SR);
        let incoming = tone(440.0, 10.0, 0.5, 2, SR);
        let mut plan = plan(4.0);
        plan.outgoing_tempo_ratio = 1.04;

        let config = EngineConfig::default();
        let first = renderer
            .render(&outgoing, &incoming, &plan, &config)
            .unwrap();
        let second = renderer
            .render(&outgoing, &incoming, &plan, &config)
            .unwrap();
        assert_eq!(first, second);
    }

    #[test]
    fn a_region_past_the_end_renders_as_silence_rather_than_failing() {
        let mut renderer = Renderer::new();
        let mut plan = plan(4.0);
        plan.outgoing_start = 100.0;

        let output = renderer
            .render(
                &tone(220.0, 10.0, 0.5, 2, SR),
                &tone(440.0, 10.0, 0.5, 2, SR),
                &plan,
                &EngineConfig::default(),
            )
            .unwrap();
        assert_eq!(output.audio.frames(), (4.0 * SR as f64) as usize);
    }

    #[test]
    fn a_plan_with_no_duration_is_rejected() {
        let mut renderer = Renderer::new();
        let config = EngineConfig::default();
        let outgoing = tone(220.0, 10.0, 0.5, 2, SR);

        assert!(
            renderer
                .render(&outgoing, &outgoing, &plan(0.0), &config)
                .is_err()
        );

        let mut broken = plan(4.0);
        broken.channels = 0;
        assert!(
            renderer
                .render(&outgoing, &outgoing, &broken, &config)
                .is_err()
        );
    }
}
