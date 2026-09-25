//! Sample-accurate parameter automation.
//!
//! A curve is a list of breakpoints over a normalised `0.0..=1.0` timeline, which makes it
//! describable (and testable) without reference to a sample rate or a transition length. The
//! renderer materialises a curve into a per-sample envelope exactly once and reuses it across
//! channels, so no interpolation logic is duplicated in the DSP code.

/// How a segment interpolates from its start breakpoint to the next one.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum CurveShape {
    #[default]
    Linear,
    /// Ease in and out. The natural choice for gain rides that must not sound stepped.
    SmoothStep,
    /// `sin(x * pi/2)`: constant perceived power when paired with [`CurveShape::EqualPowerOut`].
    EqualPowerIn,
    /// `1 - cos(x * pi/2)`.
    EqualPowerOut,
    /// Geometric interpolation between the two values. Used for filter cutoffs, where a linear
    /// sweep from 18 kHz to 400 Hz spends almost all its time inaudibly high.
    Logarithmic,
    /// Geometric like [`CurveShape::Logarithmic`], but flat at both ends and steep through the
    /// middle. The shape of a filter ride: it has to leave and arrive without a lurch, or the
    /// move announces itself at the very moments it is trying to hide.
    Logistic,
}

/// Steepness of [`CurveShape::Logistic`] through its middle. Shallower reads as a linear sweep,
/// steeper as a step.
const LOGISTIC_STEEPNESS: f32 = 6.0;

/// Raw logistic, normalised so the curve reaches exactly 0 and 1 at the edges. Without the
/// normalisation the untouched value is 0.047 at `x = 0`, which would move a cutoff before the
/// transition had begun.
fn logistic(x: f32) -> f32 {
    let raw = |x: f32| 1.0 / (1.0 + (-LOGISTIC_STEEPNESS * (x - 0.5)).exp());
    let low = raw(0.0);
    (raw(x) - low) / (raw(1.0) - low)
}

impl CurveShape {
    /// Reshapes a normalised position. [`CurveShape::Logarithmic`] is value-space, not
    /// position-space, so it passes through unchanged here.
    pub fn shape(self, x: f32) -> f32 {
        let x = x.clamp(0.0, 1.0);
        match self {
            Self::Linear | Self::Logarithmic => x,
            Self::SmoothStep => x * x * (3.0 - 2.0 * x),
            Self::EqualPowerIn => (x * std::f32::consts::FRAC_PI_2).sin(),
            Self::EqualPowerOut => 1.0 - (x * std::f32::consts::FRAC_PI_2).cos(),
            Self::Logistic => logistic(x),
        }
    }

    /// Whether values interpolate geometrically. Frequency is the case that needs it: the ear
    /// hears ratios, so a corner moving from 18 kHz to 200 Hz has to travel in octaves.
    pub fn is_geometric(self) -> bool {
        matches!(self, Self::Logarithmic | Self::Logistic)
    }

    pub fn interpolate(self, from: f32, to: f32, x: f32) -> f32 {
        let x = self.shape(x);
        if self.is_geometric() && from > 0.0 && to > 0.0 {
            return from * (to / from).powf(x);
        }
        from + (to - from) * x
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct AutomationPoint {
    /// Normalised position within the automated span, clamped to `0.0..=1.0`.
    pub position: f32,
    pub value: f32,
    /// Shape of the segment that *leaves* this point. Ignored on the final point.
    pub shape: CurveShape,
}

impl AutomationPoint {
    pub fn new(position: f32, value: f32, shape: CurveShape) -> Self {
        Self {
            position: position.clamp(0.0, 1.0),
            value,
            shape,
        }
    }
}

/// A non-empty, position-sorted breakpoint list.
#[derive(Debug, Clone, PartialEq)]
pub struct AutomationCurve {
    points: Vec<AutomationPoint>,
}

impl AutomationCurve {
    pub fn constant(value: f32) -> Self {
        Self {
            points: vec![AutomationPoint::new(0.0, value, CurveShape::Linear)],
        }
    }

    pub fn ramp(from: f32, to: f32, shape: CurveShape) -> Self {
        Self {
            points: vec![
                AutomationPoint::new(0.0, from, shape),
                AutomationPoint::new(1.0, to, shape),
            ],
        }
    }

    /// Builds a curve from arbitrary breakpoints. Points are sorted by position (stably, so
    /// equal positions keep their given order) and an empty list degrades to a unity constant.
    pub fn from_points(mut points: Vec<AutomationPoint>) -> Self {
        if points.is_empty() {
            return Self::constant(1.0);
        }
        for point in &mut points {
            point.position = point.position.clamp(0.0, 1.0);
        }
        points.sort_by(|a, b| a.position.total_cmp(&b.position));
        Self { points }
    }

    pub fn points(&self) -> &[AutomationPoint] {
        &self.points
    }

    pub fn is_constant(&self) -> bool {
        let first = self.points[0].value;
        self.points.iter().all(|p| p.value == first)
    }

    pub fn value_at(&self, position: f32) -> f32 {
        let index = self
            .points
            .partition_point(|p| p.position <= position)
            .saturating_sub(1);
        self.segment_value(index, position)
    }

    /// Materialises the curve into `out`, one value per sample. Positions span the full
    /// `0.0..=1.0` range, so the last sample always lands on the curve's final value.
    pub fn fill(&self, out: &mut [f32]) {
        match out.len() {
            0 => {}
            1 => out[0] = self.value_at(0.0),
            n => {
                let step = 1.0 / (n - 1) as f32;
                let mut cursor = self.cursor();
                for (i, slot) in out.iter_mut().enumerate() {
                    *slot = cursor.value_at(i as f32 * step);
                }
            }
        }
    }

    pub fn cursor(&self) -> AutomationCursor<'_> {
        AutomationCursor {
            curve: self,
            index: 0,
        }
    }

    fn segment_value(&self, index: usize, position: f32) -> f32 {
        let start = &self.points[index];
        let Some(end) = self.points.get(index + 1) else {
            return start.value;
        };
        let span = end.position - start.position;
        if span <= f32::EPSILON {
            return end.value;
        }
        let local = (position - start.position) / span;
        start.shape.interpolate(start.value, end.value, local)
    }
}

/// Evaluates a curve at non-decreasing positions without re-searching the breakpoint list.
pub struct AutomationCursor<'a> {
    curve: &'a AutomationCurve,
    index: usize,
}

impl AutomationCursor<'_> {
    pub fn value_at(&mut self, position: f32) -> f32 {
        let points = &self.curve.points;
        while self.index + 1 < points.len() && points[self.index + 1].position <= position {
            self.index += 1;
        }
        self.curve.segment_value(self.index, position)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn approx(a: f32, b: f32) {
        assert!((a - b).abs() < 1e-5, "{a} != {b}");
    }

    #[test]
    fn constant_curve_is_flat_everywhere() {
        let curve = AutomationCurve::constant(0.25);
        approx(curve.value_at(0.0), 0.25);
        approx(curve.value_at(0.5), 0.25);
        approx(curve.value_at(1.0), 0.25);
        assert!(curve.is_constant());
    }

    #[test]
    fn linear_ramp_hits_endpoints_and_midpoint() {
        let curve = AutomationCurve::ramp(0.0, 1.0, CurveShape::Linear);
        approx(curve.value_at(0.0), 0.0);
        approx(curve.value_at(0.5), 0.5);
        approx(curve.value_at(1.0), 1.0);
    }

    #[test]
    fn logarithmic_ramp_is_geometric() {
        let curve = AutomationCurve::ramp(100.0, 10_000.0, CurveShape::Logarithmic);
        approx(curve.value_at(0.5), 1_000.0);
    }

    #[test]
    fn logarithmic_falls_back_to_linear_across_zero() {
        let curve = AutomationCurve::ramp(0.0, 100.0, CurveShape::Logarithmic);
        approx(curve.value_at(0.5), 50.0);
    }

    #[test]
    fn multi_segment_curve_respects_breakpoints() {
        let curve = AutomationCurve::from_points(vec![
            AutomationPoint::new(0.0, 1.0, CurveShape::Linear),
            AutomationPoint::new(0.5, 0.0, CurveShape::Linear),
            AutomationPoint::new(1.0, 1.0, CurveShape::Linear),
        ]);
        approx(curve.value_at(0.25), 0.5);
        approx(curve.value_at(0.5), 0.0);
        approx(curve.value_at(0.75), 0.5);
    }

    #[test]
    fn unsorted_points_are_ordered() {
        let curve = AutomationCurve::from_points(vec![
            AutomationPoint::new(1.0, 2.0, CurveShape::Linear),
            AutomationPoint::new(0.0, 0.0, CurveShape::Linear),
        ]);
        approx(curve.value_at(0.5), 1.0);
    }

    #[test]
    fn cursor_matches_direct_lookup() {
        let curve = AutomationCurve::from_points(vec![
            AutomationPoint::new(0.0, 0.0, CurveShape::SmoothStep),
            AutomationPoint::new(0.3, 1.0, CurveShape::Linear),
            AutomationPoint::new(0.9, 0.2, CurveShape::EqualPowerIn),
            AutomationPoint::new(1.0, 0.0, CurveShape::Linear),
        ]);
        let mut cursor = curve.cursor();
        for i in 0..=100 {
            let pos = i as f32 / 100.0;
            approx(cursor.value_at(pos), curve.value_at(pos));
        }
    }

    #[test]
    fn fill_spans_the_whole_range() {
        let curve = AutomationCurve::ramp(0.0, 1.0, CurveShape::Linear);
        let mut buf = [0.0f32; 5];
        curve.fill(&mut buf);
        approx(buf[0], 0.0);
        approx(buf[2], 0.5);
        approx(buf[4], 1.0);
    }

    #[test]
    fn fill_handles_degenerate_lengths() {
        let curve = AutomationCurve::ramp(0.25, 0.75, CurveShape::Linear);
        curve.fill(&mut []);
        let mut single = [0.0f32; 1];
        curve.fill(&mut single);
        approx(single[0], 0.25);
    }

    #[test]
    fn zero_width_segment_takes_the_later_value() {
        let curve = AutomationCurve::from_points(vec![
            AutomationPoint::new(0.5, 1.0, CurveShape::Linear),
            AutomationPoint::new(0.5, 0.0, CurveShape::Linear),
        ]);
        approx(curve.value_at(0.5), 0.0);
    }

    #[test]
    fn equal_power_shapes_are_complementary() {
        for i in 0..=10 {
            let x = i as f32 / 10.0;
            let a = CurveShape::EqualPowerIn.shape(x);
            let b = CurveShape::EqualPowerIn.shape(1.0 - x);
            approx(a * a + b * b, 1.0);
        }
    }

    #[test]
    fn the_logistic_reaches_both_ends_exactly() {
        // The un-normalised logistic is 0.047 at zero, which would move a cutoff before the
        // transition started; the normalisation is what makes the ends hold still.
        approx(CurveShape::Logistic.shape(0.0), 0.0);
        approx(CurveShape::Logistic.shape(1.0), 1.0);
        approx(CurveShape::Logistic.shape(0.5), 0.5);
    }

    #[test]
    fn the_logistic_is_monotonic_and_steepest_in_the_middle() {
        let at = |x: f32| CurveShape::Logistic.shape(x);
        let mut previous = -1.0;
        for step in 0..=100 {
            let value = at(step as f32 / 100.0);
            assert!(value > previous, "logistic went backwards at {step}");
            previous = value;
        }
        let middle = at(0.55) - at(0.45);
        let edge = at(0.10) - at(0.0);
        assert!(
            middle > edge * 2.0,
            "middle slope {middle} should dominate the edge slope {edge}"
        );
    }

    #[test]
    fn geometric_shapes_travel_in_octaves() {
        // Halfway from 18 kHz to 200 Hz is the geometric mean, not the arithmetic one.
        let midpoint = CurveShape::Logistic.interpolate(18_000.0, 200.0, 0.5);
        approx(midpoint, (18_000.0f32 * 200.0).sqrt());
        assert!(CurveShape::Logarithmic.is_geometric());
        assert!(CurveShape::Logistic.is_geometric());
        assert!(!CurveShape::Linear.is_geometric());
    }

    #[test]
    fn a_logistic_ramp_starts_and_finishes_where_it_should() {
        let curve = AutomationCurve::ramp(18_000.0, 200.0, CurveShape::Logistic);
        approx(curve.value_at(0.0), 18_000.0);
        approx(curve.value_at(1.0), 200.0);
        // Still high a fifth of the way in: the opening of the ride has to be inaudible.
        assert!(curve.value_at(0.2) > 9_000.0);
    }
}
