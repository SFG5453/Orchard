//! Tempo from beat times: the median slope of straight lines fitted to windows of beats.

/// Beats per fitted window and the distance between window starts.
const WINDOW: usize = 32;
const STEP: usize = 16;
/// Fewest beats a window can fit.
const MIN_BEATS: usize = 16;

/// BPM of the dominant pulse, or `None` when there are too few beats to fit one window.
/// Beats sit on 20 ms frames, so the median gap is a whole-frame statistic and beat matching
/// drifts at the rate of its error. A line through a window of beats is far finer, and the
/// median over windows ignores sections that run at another tempo.
pub(super) fn fitted_bpm(beats: &[f64]) -> Option<f64> {
    let mut slopes = Vec::new();
    let mut start = 0;
    loop {
        slopes.extend(window_slope(
            &beats[start..(start + WINDOW).min(beats.len())],
        ));
        start += STEP;
        if start + WINDOW > beats.len() {
            break;
        }
    }
    median(&mut slopes).map(|seconds| 60.0 / seconds)
}

/// Seconds per beat of one window.
fn window_slope(beats: &[f64]) -> Option<f64> {
    if beats.len() < MIN_BEATS {
        return None;
    }
    let mut gaps: Vec<f64> = beats.windows(2).map(|pair| pair[1] - pair[0]).collect();
    let typical = median(&mut gaps).filter(|gap| *gap > 0.0)?;
    // Numbered by gap so a missed beat does not bend the line.
    let mut numbers = vec![0.0f64];
    for pair in beats.windows(2) {
        let last = numbers[numbers.len() - 1];
        numbers.push(last + ((pair[1] - pair[0]) / typical).round().max(1.0));
    }
    line_slope(beats, &numbers)
}

/// Least-squares slope of time against beat number; beats far off the line leave the fit.
fn line_slope(times: &[f64], numbers: &[f64]) -> Option<f64> {
    let mut keep = vec![true; times.len()];
    let mut slope = None;
    // Three rounds of fit and clip: a rushing drummer loses the vote.
    for _ in 0..3 {
        let (t, n): (Vec<f64>, Vec<f64>) = times
            .iter()
            .zip(numbers)
            .zip(&keep)
            .filter(|(_, kept)| **kept)
            .map(|((t, n), _)| (*t, *n))
            .unzip();
        if t.len() < 8 {
            break;
        }
        let count = t.len() as f64;
        let (mean_t, mean_n) = (t.iter().sum::<f64>() / count, n.iter().sum::<f64>() / count);
        let variance: f64 = n.iter().map(|v| (v - mean_n).powi(2)).sum();
        if variance <= 0.0 {
            break;
        }
        let covariance: f64 = n
            .iter()
            .zip(&t)
            .map(|(a, b)| (a - mean_n) * (b - mean_t))
            .sum();
        let fitted = covariance / variance;
        slope = Some(fitted);
        let intercept = mean_t - fitted * mean_n;
        let residuals: Vec<f64> = times
            .iter()
            .zip(numbers)
            .map(|(t, n)| t - (intercept + fitted * n))
            .collect();
        let mut inside: Vec<f64> = residuals
            .iter()
            .zip(&keep)
            .filter(|(_, kept)| **kept)
            .map(|(r, _)| *r)
            .collect();
        let centre = median(&mut inside)?;
        let mut deviations: Vec<f64> = inside.iter().map(|r| (r - centre).abs()).collect();
        let limit = 3.0 * (1.4826 * median(&mut deviations)? + 1e-9);
        for (flag, r) in keep.iter_mut().zip(&residuals) {
            *flag = (r - centre).abs() < limit;
        }
    }
    slope.filter(|seconds| seconds.is_finite() && *seconds > 0.0)
}

fn median(values: &mut [f64]) -> Option<f64> {
    if values.is_empty() {
        return None;
    }
    values.sort_by(f64::total_cmp);
    let middle = values.len() / 2;
    Some(if values.len() % 2 == 1 {
        values[middle]
    } else {
        0.5 * (values[middle - 1] + values[middle])
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Beats of a steady pulse after the tracker's 20 ms frame snapping.
    fn snapped(bpm: f64, count: usize) -> Vec<f64> {
        (0..count)
            .map(|k| ((0.37 + k as f64 * 60.0 / bpm) * 50.0).round() / 50.0)
            .collect()
    }

    fn error(estimate: f64, bpm: f64) -> f64 {
        (estimate / bpm - 1.0).abs() * 100.0
    }

    #[test]
    fn frame_snapped_beats_give_a_precise_tempo() {
        for bpm in [83.2, 98.7, 117.3, 128.4, 140.0, 161.0] {
            let beats = snapped(bpm, 300);
            let gaps: Vec<f64> = beats.windows(2).map(|pair| pair[1] - pair[0]).collect();
            let coarse = 60.0 / median(&mut gaps.clone()).unwrap();
            let fitted = fitted_bpm(&beats).unwrap();
            assert!(error(fitted, bpm) < 0.05, "{bpm}: {fitted}");
            assert!(
                error(fitted, bpm) <= error(coarse, bpm) + 1e-9,
                "{bpm}: {fitted} vs {coarse}"
            );
        }
    }

    #[test]
    fn missed_beats_do_not_bend_the_tempo() {
        let beats: Vec<f64> = snapped(122.0, 300)
            .into_iter()
            .enumerate()
            .filter(|(k, _)| k % 9 != 4)
            .map(|(_, beat)| beat)
            .collect();
        assert!(error(fitted_bpm(&beats).unwrap(), 122.0) < 0.05);
    }

    #[test]
    fn the_longer_section_sets_the_tempo() {
        // A beat switch: 200 beats at 90 BPM, then 80 at 128.
        let mut beats = snapped(90.0, 200);
        let resume = beats[beats.len() - 1] + 60.0 / 128.0;
        beats.extend((0..80).map(|k| resume + k as f64 * 60.0 / 128.0));
        assert!(error(fitted_bpm(&beats).unwrap(), 90.0) < 0.1);
    }

    #[test]
    fn short_runs_have_no_tempo() {
        assert!(fitted_bpm(&snapped(120.0, 12)).is_none());
        assert!(fitted_bpm(&[]).is_none());
    }
}
