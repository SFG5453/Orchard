//! Biquad filter automation.
//!
//! A sweep is planned once into a list of coefficient sets, then replayed per channel. Filter
//! state is per channel but the coefficients are shared, so a stereo sweep computes its
//! coefficients once rather than twice.

use biquad::{Biquad, Coefficients, DirectForm2Transposed, Hertz, Type};

use crate::config::FilterConfig;
use crate::dsp::automation::AutomationCurve;
use crate::error::{CrossfadeError, Result};

/// Below this the biquad coefficients become numerically uninteresting for audio use.
const MIN_CUTOFF_HZ: f32 = 10.0;
/// Cutoffs are kept this far below Nyquist to stay clear of the bilinear transform's edge.
const NYQUIST_MARGIN: f32 = 0.45;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum FilterKind {
    LowPass,
    HighPass,
    LowShelf,
    HighShelf,
    Peaking,
}

/// A filter whose cutoff moves over the span of a transition.
#[derive(Debug, Clone, PartialEq)]
pub struct FilterAutomation {
    pub kind: FilterKind,
    /// Cutoff in Hz across the normalised transition timeline.
    pub cutoff: AutomationCurve,
    pub q: f32,
    /// Only meaningful for the shelving and peaking kinds.
    pub gain_db: f32,
    /// How much of the cutoff's planned travel to actually spend, per instant, in `0.0..=1.0`.
    ///
    /// `None` — the default — spends all of it. A host that measures something the engine cannot
    /// (which instants actually need the filter out of the way) supplies a curve here, and the
    /// corner then moves only as far as that measurement justifies. The scaling is geometric from
    /// the cutoff's own starting value, so depth `0.5` is half the *octaves*, not half the hertz.
    ///
    /// Applied as a running maximum: a filter that reopens mid-transition is a sound no engineer
    /// makes, and the wobble is far more audible than the attenuation it would save.
    pub depth: Option<AutomationCurve>,
}

impl FilterAutomation {
    pub fn new(kind: FilterKind, cutoff: AutomationCurve, q: f32) -> Self {
        Self {
            kind,
            cutoff,
            q,
            gain_db: 0.0,
            depth: None,
        }
    }

    pub fn with_gain(mut self, gain_db: f32) -> Self {
        self.gain_db = gain_db;
        self
    }

    pub fn with_depth(mut self, depth: AutomationCurve) -> Self {
        self.depth = Some(depth);
        self
    }

    fn biquad_type(&self) -> Type<f32> {
        match self.kind {
            FilterKind::LowPass => Type::LowPass,
            FilterKind::HighPass => Type::HighPass,
            FilterKind::LowShelf => Type::LowShelf(self.gain_db),
            FilterKind::HighShelf => Type::HighShelf(self.gain_db),
            FilterKind::Peaking => Type::PeakingEQ(self.gain_db),
        }
    }
}

/// Coefficients for one automated filter, sampled at a fixed block rate.
pub struct FilterSweep {
    coefficients: Vec<Coefficients<f32>>,
    block: usize,
}

impl FilterSweep {
    pub fn plan(
        automation: &FilterAutomation,
        frames: usize,
        sample_rate: u32,
        config: &FilterConfig,
    ) -> Result<Self> {
        if automation.q <= 0.0 {
            return Err(CrossfadeError::dsp("filter Q must be positive"));
        }
        let block = config.update_interval.max(1);
        let mut coefficients = Vec::new();
        if frames == 0 {
            return Ok(Self {
                coefficients,
                block,
            });
        }

        let fs = hertz(sample_rate as f32)?;
        let nyquist = sample_rate as f32 * NYQUIST_MARGIN;
        let span = (frames - 1).max(1) as f32;
        let filter_type = automation.biquad_type();
        let mut cursor = automation.cutoff.cursor();
        let origin = automation.cutoff.value_at(0.0);
        let mut depth = automation.depth.as_ref().map(|curve| curve.cursor());
        let mut reached = 0.0f32;

        coefficients.reserve(frames.div_ceil(block));
        for start in (0..frames).step_by(block) {
            let position = start as f32 / span;
            let mut cutoff = cursor.value_at(position);
            if let Some(cursor) = depth.as_mut() {
                reached = reached.max(cursor.value_at(position).clamp(0.0, 1.0));
                cutoff = scale_travel(origin, cutoff, reached);
            }
            let cutoff = cutoff.clamp(MIN_CUTOFF_HZ, nyquist);
            let f0 = hertz(cutoff)?;
            coefficients.push(
                Coefficients::<f32>::from_params(filter_type, fs, f0, automation.q)
                    .map_err(|e| CrossfadeError::dsp(format!("biquad coefficients: {e:?}")))?,
            );
        }
        Ok(Self {
            coefficients,
            block,
        })
    }

    /// Runs one channel through the sweep. State starts clean, so channels stay independent.
    pub fn apply(&self, samples: &mut [f32]) {
        let Some(first) = self.coefficients.first() else {
            return;
        };
        let mut filter = DirectForm2Transposed::<f32>::new(*first);
        for (chunk, coefficients) in samples.chunks_mut(self.block).zip(&self.coefficients) {
            filter.update_coefficients(*coefficients);
            for sample in chunk {
                *sample = filter.run(*sample);
            }
        }
    }

    pub fn block_count(&self) -> usize {
        self.coefficients.len()
    }
}

/// Moves `amount` of the way from `origin` to `target` in octaves rather than hertz, so a
/// half-spent sweep sits at the geometric midpoint the ear would call halfway.
fn scale_travel(origin: f32, target: f32, amount: f32) -> f32 {
    if origin <= 0.0 || target <= 0.0 {
        return origin + (target - origin) * amount;
    }
    origin * (target / origin).powf(amount)
}

fn hertz(value: f32) -> Result<Hertz<f32>> {
    Hertz::<f32>::from_hz(value)
        .map_err(|e| CrossfadeError::dsp(format!("invalid frequency {value}: {e:?}")))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dsp::automation::{AutomationPoint, CurveShape};
    use crate::dsp::gain::rms;

    const SR: u32 = 48_000;

    fn sine(freq: f32, frames: usize) -> Vec<f32> {
        (0..frames)
            .map(|i| (i as f32 / SR as f32 * freq * std::f32::consts::TAU).sin())
            .collect()
    }

    fn sweep(kind: FilterKind, from: f32, to: f32, frames: usize) -> FilterSweep {
        let automation = FilterAutomation::new(
            kind,
            AutomationCurve::ramp(from, to, CurveShape::Logarithmic),
            std::f32::consts::FRAC_1_SQRT_2,
        );
        FilterSweep::plan(&automation, frames, SR, &FilterConfig::default()).unwrap()
    }

    #[test]
    fn lowpass_passes_below_and_rejects_above_cutoff() {
        let frames = SR as usize / 2;
        let sweep = sweep(FilterKind::LowPass, 1_000.0, 1_000.0, frames);

        let mut low = sine(100.0, frames);
        sweep.apply(&mut low);
        let mut high = sine(10_000.0, frames);
        sweep.apply(&mut high);

        assert!(rms(&low) > 0.6, "low band was attenuated: {}", rms(&low));
        assert!(rms(&high) < 0.05, "high band survived: {}", rms(&high));
    }

    #[test]
    fn highpass_is_the_mirror_image() {
        let frames = SR as usize / 2;
        let sweep = sweep(FilterKind::HighPass, 1_000.0, 1_000.0, frames);

        let mut low = sine(100.0, frames);
        sweep.apply(&mut low);
        let mut high = sine(10_000.0, frames);
        sweep.apply(&mut high);

        assert!(rms(&low) < 0.05);
        assert!(rms(&high) > 0.6);
    }

    #[test]
    fn a_sweep_attenuates_progressively() {
        let frames = SR as usize;
        let sweep = sweep(FilterKind::LowPass, 18_000.0, 200.0, frames);
        let mut signal = sine(4_000.0, frames);
        sweep.apply(&mut signal);

        let head = rms(&signal[..frames / 8]);
        let tail = rms(&signal[frames - frames / 8..]);
        assert!(head > tail * 10.0, "head {head} tail {tail}");
    }

    #[test]
    fn channels_do_not_share_state() {
        let frames = 4_096;
        let sweep = sweep(FilterKind::LowPass, 500.0, 500.0, frames);
        let mut left = sine(200.0, frames);
        let mut right = left.clone();
        sweep.apply(&mut left);
        sweep.apply(&mut right);
        assert_eq!(left, right);
    }

    #[test]
    fn cutoff_is_clamped_to_the_usable_range() {
        let frames = 1_024;
        let sweep = sweep(FilterKind::LowPass, 0.0, 10_000_000.0, frames);
        let mut signal = sine(1_000.0, frames);
        sweep.apply(&mut signal);
        assert!(signal.iter().all(|s| s.is_finite()));
    }

    #[test]
    fn block_count_follows_the_update_interval() {
        let config = FilterConfig::default();
        let automation =
            FilterAutomation::new(FilterKind::LowPass, AutomationCurve::constant(1_000.0), 1.0);
        let sweep = FilterSweep::plan(&automation, 1_000, SR, &config).unwrap();
        assert_eq!(
            sweep.block_count(),
            1_000usize.div_ceil(config.update_interval)
        );
    }

    #[test]
    fn empty_input_plans_nothing() {
        let automation =
            FilterAutomation::new(FilterKind::LowPass, AutomationCurve::constant(500.0), 1.0);
        let sweep = FilterSweep::plan(&automation, 0, SR, &FilterConfig::default()).unwrap();
        assert_eq!(sweep.block_count(), 0);
        sweep.apply(&mut []);
    }

    #[test]
    fn non_positive_q_is_rejected() {
        let automation =
            FilterAutomation::new(FilterKind::LowPass, AutomationCurve::constant(500.0), 0.0);
        assert!(FilterSweep::plan(&automation, 128, SR, &FilterConfig::default()).is_err());
    }

    fn depth_sweep(depth: AutomationCurve, frames: usize) -> FilterSweep {
        let automation = FilterAutomation::new(
            FilterKind::LowPass,
            AutomationCurve::ramp(18_000.0, 200.0, CurveShape::Logistic),
            std::f32::consts::FRAC_1_SQRT_2,
        )
        .with_depth(depth);
        FilterSweep::plan(&automation, frames, SR, &FilterConfig::default()).unwrap()
    }

    #[test]
    fn zero_depth_leaves_the_signal_alone() {
        let frames = SR as usize / 2;
        let mut swept = sine(4_000.0, frames);
        depth_sweep(AutomationCurve::constant(0.0), frames).apply(&mut swept);

        // The corner never leaves 18 kHz, so a 4 kHz tone passes essentially untouched.
        assert!(
            rms(&swept) > 0.6,
            "depth 0 attenuated the signal: {}",
            rms(&swept)
        );
    }

    #[test]
    fn full_depth_matches_an_unscaled_sweep() {
        let frames = SR as usize / 2;
        let plain = FilterSweep::plan(
            &FilterAutomation::new(
                FilterKind::LowPass,
                AutomationCurve::ramp(18_000.0, 200.0, CurveShape::Logistic),
                std::f32::consts::FRAC_1_SQRT_2,
            ),
            frames,
            SR,
            &FilterConfig::default(),
        )
        .unwrap();

        let mut a = sine(4_000.0, frames);
        let mut b = a.clone();
        plain.apply(&mut a);
        depth_sweep(AutomationCurve::constant(1.0), frames).apply(&mut b);
        assert_eq!(a, b);
    }

    #[test]
    fn partial_depth_lands_between_the_two() {
        let frames = SR as usize / 2;
        let energy = |depth: f32| {
            let mut signal = sine(4_000.0, frames);
            depth_sweep(AutomationCurve::constant(depth), frames).apply(&mut signal);
            rms(&signal)
        };
        let (open, half, closed) = (energy(0.0), energy(0.5), energy(1.0));
        assert!(
            closed < half && half < open,
            "expected closed {closed} < half {half} < open {open}"
        );
    }

    #[test]
    fn depth_is_a_running_maximum_so_the_filter_never_reopens() {
        let frames = SR as usize / 2;
        // Peaks early, then collapses back to nothing.
        let dipping = AutomationCurve::from_points(vec![
            AutomationPoint::new(0.0, 1.0, CurveShape::Linear),
            AutomationPoint::new(0.5, 1.0, CurveShape::Linear),
            AutomationPoint::new(0.51, 0.0, CurveShape::Linear),
            AutomationPoint::new(1.0, 0.0, CurveShape::Linear),
        ]);

        let mut dipped = sine(4_000.0, frames);
        depth_sweep(dipping, frames).apply(&mut dipped);
        let mut held = sine(4_000.0, frames);
        depth_sweep(AutomationCurve::constant(1.0), frames).apply(&mut held);

        let tail = frames - frames / 8..;
        assert!(
            (rms(&dipped[tail.clone()]) - rms(&held[tail])).abs() < 1e-6,
            "the dip reopened the filter instead of holding its deepest point"
        );
    }

    #[test]
    fn depth_is_clamped_to_a_sane_range() {
        let frames = 8_192;
        let wild = AutomationCurve::from_points(vec![
            AutomationPoint::new(0.0, -5.0, CurveShape::Linear),
            AutomationPoint::new(1.0, 12.0, CurveShape::Linear),
        ]);
        let mut signal = sine(1_000.0, frames);
        depth_sweep(wild, frames).apply(&mut signal);
        assert!(signal.iter().all(|s| s.is_finite()));
    }

    #[test]
    fn travel_is_scaled_in_octaves_not_hertz() {
        assert!((scale_travel(18_000.0, 200.0, 0.5) - (18_000.0f32 * 200.0).sqrt()).abs() < 1.0);
        assert_eq!(scale_travel(18_000.0, 200.0, 0.0), 18_000.0);
        assert!((scale_travel(18_000.0, 200.0, 1.0) - 200.0).abs() < 1e-3);
    }

    #[test]
    fn shelf_gain_reaches_the_filter() {
        let frames = SR as usize / 2;
        let automation = FilterAutomation::new(
            FilterKind::LowShelf,
            AutomationCurve::constant(500.0),
            std::f32::consts::FRAC_1_SQRT_2,
        )
        .with_gain(-12.0);
        let sweep = FilterSweep::plan(&automation, frames, SR, &FilterConfig::default()).unwrap();
        let mut signal = sine(60.0, frames);
        sweep.apply(&mut signal);
        assert!(rms(&signal) < 0.4, "shelf did not cut: {}", rms(&signal));
    }
}
