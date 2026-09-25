//! Decibel conversions and static gain application.

/// Anything at or below this is treated as digital silence rather than an ever-deeper negative.
pub const SILENCE_DB: f32 = -120.0;

pub fn db_to_linear(db: f32) -> f32 {
    if db <= SILENCE_DB {
        0.0
    } else {
        10f32.powf(db / 20.0)
    }
}

pub fn linear_to_db(linear: f32) -> f32 {
    let magnitude = linear.abs();
    if magnitude <= 1e-9 {
        SILENCE_DB
    } else {
        20.0 * magnitude.log10()
    }
}

pub fn apply_gain(samples: &mut [f32], gain: f32) {
    if gain == 1.0 {
        return;
    }
    for sample in samples {
        *sample *= gain;
    }
}

pub fn rms(samples: &[f32]) -> f32 {
    if samples.is_empty() {
        return 0.0;
    }
    let sum: f64 = samples.iter().map(|s| (*s as f64) * (*s as f64)).sum();
    (sum / samples.len() as f64).sqrt() as f32
}

pub fn peak(samples: &[f32]) -> f32 {
    samples.iter().fold(0.0f32, |acc, s| acc.max(s.abs()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn approx(a: f32, b: f32) {
        assert!((a - b).abs() < 1e-4, "{a} != {b}");
    }

    #[test]
    fn unity_gain_round_trips() {
        approx(db_to_linear(0.0), 1.0);
        approx(linear_to_db(1.0), 0.0);
    }

    #[test]
    fn six_db_is_roughly_double() {
        approx(db_to_linear(6.0206), 2.0);
        approx(linear_to_db(2.0), 6.0206);
    }

    #[test]
    fn conversions_are_inverses() {
        for db in [-48.0, -12.0, -3.0, 0.0, 3.0, 9.0] {
            approx(linear_to_db(db_to_linear(db)), db);
        }
    }

    #[test]
    fn silence_clamps_instead_of_diverging() {
        assert_eq!(db_to_linear(-200.0), 0.0);
        assert_eq!(linear_to_db(0.0), SILENCE_DB);
    }

    #[test]
    fn gain_scales_every_sample() {
        let mut samples = [1.0, -0.5, 0.25];
        apply_gain(&mut samples, 2.0);
        approx(samples[0], 2.0);
        approx(samples[1], -1.0);
        approx(samples[2], 0.5);
    }

    #[test]
    fn rms_of_full_scale_sine_is_root_half() {
        let samples: Vec<f32> = (0..1000)
            .map(|i| (i as f32 / 1000.0 * std::f32::consts::TAU * 10.0).sin())
            .collect();
        approx(rms(&samples), std::f32::consts::FRAC_1_SQRT_2);
    }

    #[test]
    fn peak_ignores_sign() {
        approx(peak(&[0.1, -0.9, 0.4]), 0.9);
        approx(peak(&[]), 0.0);
    }
}
