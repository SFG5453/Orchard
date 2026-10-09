//! Grid corrections for sub-bass-led mixes, where syncopated 808s and hats can pull a
//! stretch of the grid onto the "and"s or get a half-time groove counted at double tempo.
//! The snare and clap stay on the beat.

use super::onset::peak;
use super::{FPS, median_gap};

/// Half-width of the smoothing window, in beats.
const SPAN: usize = 16;
/// Shortest stretch worth moving, in beats.
const MIN_RUN: usize = 16;
/// Mean log ratio (beat over between-beat) below which a stretch counts as off-beat.
const THRESHOLD: f32 = -0.2;
/// Only grids faster than this may be a half-time groove counted at double tempo.
const HALF_TIME_MIN_BPM: f64 = 112.0;
/// Kick irregularity across tracked beats (coefficient of variation minus the 4-beat
/// autocorrelation) above which the tracked beats are hats.
const HALF_TIME_IRREGULARITY: f32 = 1.3;
/// Log ratio of kick onset on tracked beats over between them that the grid must exceed.
/// Drum and bass at its true tempo hits the kick as hard between beats as on them.
const HALF_TIME_KICK_CONTRAST: f32 = 0.1;

/// Moves stretches whose between-beat points carry more snare-band onset than the
/// beats themselves by half a beat. `grid` is in fractional frames.
pub(crate) fn repair(grid: &mut [f64], snare: &[f32]) {
    let n = grid.len();
    if n < 2 * SPAN + 4 {
        return;
    }
    let mids: Vec<f64> = grid.windows(2).map(|w| 0.5 * (w[0] + w[1])).collect();
    let evidence: Vec<f32> = grid[..n - 1]
        .iter()
        .zip(&mids)
        .map(|(&on, &off)| ((peak(snare, on) + 0.1) / (peak(snare, off) + 0.1)).ln())
        .collect();
    let m = evidence.len();
    let smoothed: Vec<f32> = (0..m)
        .map(|i| {
            let sum: f32 = (0..=2 * SPAN)
                .map(|k| evidence[(i + k).saturating_sub(SPAN).min(m - 1)])
                .sum();
            sum / (2 * SPAN + 1) as f32
        })
        .collect();
    let mut i = 0;
    while i < m {
        if smoothed[i] >= THRESHOLD {
            i += 1;
            continue;
        }
        let start = i;
        while i < m && smoothed[i] < THRESHOLD {
            i += 1;
        }
        if i - start >= MIN_RUN {
            grid[start..i].copy_from_slice(&mids[start..i]);
        }
    }
    grid.sort_by(f64::total_cmp);
}

/// Every other beat of a grid that counts a half-time groove at double tempo, or `None`
/// when the grid already sits at the pulse. The kept half carries more snare and clap.
pub(crate) fn half_time(grid: &[f64], kick: &[f32], snare: &[f32]) -> Option<Vec<f64>> {
    if grid.len() < 32 || 60.0 * FPS / median_gap(grid) <= HALF_TIME_MIN_BPM {
        return None;
    }
    let on: Vec<f32> = grid.iter().map(|&f| peak(kick, f)).collect();
    let between: Vec<f32> = grid
        .windows(2)
        .map(|w| peak(kick, 0.5 * (w[0] + w[1])))
        .collect();
    let level = mean(&on);
    let spread = (on.iter().map(|v| (v - level).powi(2)).sum::<f32>() / on.len() as f32).sqrt();
    let irregularity = spread / (level + 1e-9) - lag_correlation(&on, 4);
    let contrast = ((level + 0.05) / (mean(&between) + 0.05)).ln();
    if irregularity <= HALF_TIME_IRREGULARITY || contrast <= HALF_TIME_KICK_CONTRAST {
        return None;
    }
    let snare_at = |start: usize| -> f32 {
        let hits: Vec<f32> = grid
            .iter()
            .skip(start)
            .step_by(2)
            .map(|&f| peak(snare, f))
            .collect();
        mean(&hits)
    };
    let start = usize::from(snare_at(1) > snare_at(0));
    Some(grid.iter().skip(start).step_by(2).copied().collect())
}

fn mean(values: &[f32]) -> f32 {
    values.iter().sum::<f32>() / values.len().max(1) as f32
}

/// Pearson correlation of `values` with themselves `lag` steps later.
fn lag_correlation(values: &[f32], lag: usize) -> f32 {
    if values.len() <= lag + 4 {
        return 0.0;
    }
    let (a, b) = (&values[..values.len() - lag], &values[lag..]);
    let (ma, mb) = (mean(a), mean(b));
    let (mut ab, mut aa, mut bb) = (0.0, 0.0, 0.0);
    for (x, y) in a.iter().zip(b) {
        let (x, y) = (x - ma, y - mb);
        ab += x * y;
        aa += x * x;
        bb += y * y;
    }
    ab / ((aa * bb).sqrt() + 1e-9)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A 140 BPM grid with onsets placed on chosen beats.
    fn groove(
        kick_beats: impl Fn(usize) -> bool,
        snare_beats: impl Fn(usize) -> bool,
    ) -> (Vec<f64>, Vec<f32>, Vec<f32>) {
        let grid: Vec<f64> = (0..64)
            .map(|i| 10.0 + i as f64 * 60.0 * FPS / 140.0)
            .collect();
        let (mut kick, mut snare) = (vec![0.0f32; 1500], vec![0.0f32; 1500]);
        for (i, &f) in grid.iter().enumerate() {
            if kick_beats(i) {
                kick[f.round() as usize] = 1.0;
            }
            if snare_beats(i) {
                snare[f.round() as usize] = 1.0;
            }
        }
        (grid, kick, snare)
    }

    #[test]
    fn half_time_trap_keeps_the_snare_half() {
        // 70 BPM trap tracked at 140: kick on 1 and the "and" of 2, clap on 2 and 4.
        let (grid, kick, snare) = groove(|i| matches!(i % 8, 0 | 3), |i| i % 4 == 2);
        let half = half_time(&grid, &kick, &snare).expect("half-time groove");
        assert_eq!(half, grid.iter().step_by(2).copied().collect::<Vec<_>>());
    }

    #[test]
    fn four_on_the_floor_stays_put() {
        let (grid, kick, snare) = groove(|_| true, |i| i % 2 == 1);
        assert!(half_time(&grid, &kick, &snare).is_none());
    }
}
