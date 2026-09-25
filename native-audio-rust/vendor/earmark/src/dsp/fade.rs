//! Fade shapes for overlapping material.

use crate::dsp::automation::{AutomationCurve, CurveShape};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum FadeCurve {
    Linear,
    /// `sin`/`cos` pair. The sum of squared gains stays at 1.0, which keeps perceived loudness
    /// constant when the two tracks are uncorrelated. Default for overlapping material.
    #[default]
    EqualPower,
    SmoothStep,
}

impl FadeCurve {
    pub fn fade_in(self, x: f32) -> f32 {
        let x = x.clamp(0.0, 1.0);
        match self {
            Self::Linear => x,
            Self::EqualPower => (x * std::f32::consts::FRAC_PI_2).sin(),
            Self::SmoothStep => x * x * (3.0 - 2.0 * x),
        }
    }

    pub fn fade_out(self, x: f32) -> f32 {
        let x = x.clamp(0.0, 1.0);
        match self {
            Self::Linear => 1.0 - x,
            Self::EqualPower => (x * std::f32::consts::FRAC_PI_2).cos(),
            Self::SmoothStep => self.fade_in(1.0 - x),
        }
    }

    /// Sample-accurate fade-in envelope spanning the whole buffer.
    pub fn fill_in(self, out: &mut [f32]) {
        fill(out, |x| self.fade_in(x));
    }

    pub fn fill_out(self, out: &mut [f32]) {
        fill(out, |x| self.fade_out(x));
    }

    pub fn as_curve_in(self) -> AutomationCurve {
        match self {
            Self::Linear => AutomationCurve::ramp(0.0, 1.0, CurveShape::Linear),
            Self::EqualPower => AutomationCurve::ramp(0.0, 1.0, CurveShape::EqualPowerIn),
            Self::SmoothStep => AutomationCurve::ramp(0.0, 1.0, CurveShape::SmoothStep),
        }
    }
}

fn fill(out: &mut [f32], f: impl Fn(f32) -> f32) {
    match out.len() {
        0 => {}
        1 => out[0] = f(1.0),
        n => {
            let step = 1.0 / (n - 1) as f32;
            for (i, slot) in out.iter_mut().enumerate() {
                *slot = f(i as f32 * step);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const CURVES: [FadeCurve; 3] = [
        FadeCurve::Linear,
        FadeCurve::EqualPower,
        FadeCurve::SmoothStep,
    ];

    fn approx(a: f32, b: f32) {
        assert!((a - b).abs() < 1e-5, "{a} != {b}");
    }

    #[test]
    fn every_curve_spans_zero_to_one() {
        for curve in CURVES {
            approx(curve.fade_in(0.0), 0.0);
            approx(curve.fade_in(1.0), 1.0);
            approx(curve.fade_out(0.0), 1.0);
            approx(curve.fade_out(1.0), 0.0);
        }
    }

    #[test]
    fn every_curve_is_monotonic() {
        for curve in CURVES {
            let mut previous = -1.0;
            for i in 0..=100 {
                let value = curve.fade_in(i as f32 / 100.0);
                assert!(value >= previous, "{curve:?} dipped at {i}");
                previous = value;
            }
        }
    }

    #[test]
    fn equal_power_holds_constant_power() {
        for i in 0..=100 {
            let x = i as f32 / 100.0;
            let a = FadeCurve::EqualPower.fade_in(x);
            let b = FadeCurve::EqualPower.fade_out(x);
            approx(a * a + b * b, 1.0);
        }
    }

    #[test]
    fn linear_holds_constant_amplitude() {
        for i in 0..=100 {
            let x = i as f32 / 100.0;
            approx(
                FadeCurve::Linear.fade_in(x) + FadeCurve::Linear.fade_out(x),
                1.0,
            );
        }
    }

    #[test]
    fn smoothstep_is_symmetric_about_the_midpoint() {
        for i in 0..=50 {
            let x = i as f32 / 100.0;
            approx(
                FadeCurve::SmoothStep.fade_in(x),
                1.0 - FadeCurve::SmoothStep.fade_in(1.0 - x),
            );
        }
    }

    #[test]
    fn input_is_clamped() {
        approx(FadeCurve::EqualPower.fade_in(-1.0), 0.0);
        approx(FadeCurve::EqualPower.fade_in(2.0), 1.0);
    }

    #[test]
    fn envelopes_are_sample_accurate() {
        let mut buf = [0.0f32; 3];
        FadeCurve::Linear.fill_in(&mut buf);
        approx(buf[0], 0.0);
        approx(buf[1], 0.5);
        approx(buf[2], 1.0);

        FadeCurve::Linear.fill_out(&mut buf);
        approx(buf[0], 1.0);
        approx(buf[1], 0.5);
        approx(buf[2], 0.0);
    }

    #[test]
    fn curve_conversion_matches_direct_evaluation() {
        for curve in CURVES {
            let automation = curve.as_curve_in();
            for i in 0..=20 {
                let x = i as f32 / 20.0;
                approx(automation.value_at(x), curve.fade_in(x));
            }
        }
    }
}
