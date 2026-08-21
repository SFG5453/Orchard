//! Aggregation of frame features into a single description of a transition region.

use crate::config::AnalysisConfig;
use crate::dsp::gain::linear_to_db;
use crate::types::analysis::{FrameFeatures, RegionFeatures};

pub fn aggregate(frames: &[FrameFeatures], config: &AnalysisConfig) -> RegionFeatures {
    if frames.is_empty() {
        return RegionFeatures::silent();
    }
    let count = frames.len() as f32;

    let rms = (frames.iter().map(|f| f.rms * f.rms).sum::<f32>() / count).sqrt();
    let low: f32 = frames.iter().map(|f| f.low).sum();
    let mid: f32 = frames.iter().map(|f| f.mid).sum();
    let high: f32 = frames.iter().map(|f| f.high).sum();
    let total = low + mid + high;

    let (low, mid, high) = if total > f32::EPSILON {
        (low / total, mid / total, high / total)
    } else {
        (1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0)
    };

    // Weighting the centroid by band energy keeps near-silent frames from dragging it around.
    let weight: f32 = frames.iter().map(|f| f.band_energy()).sum();
    let centroid = if weight > f32::EPSILON {
        frames
            .iter()
            .map(|f| f.centroid * f.band_energy())
            .sum::<f32>()
            / weight
    } else {
        0.0
    };

    RegionFeatures {
        rms,
        rms_db: linear_to_db(rms),
        low,
        mid,
        high,
        centroid,
        flux: frames.iter().map(|f| f.flux).sum::<f32>() / count,
        transient_rate: transient_rate(frames, config.transient_sigma),
        energy_slope: energy_slope(frames),
    }
}

/// Share of frames whose spectral flux stands out from the region's own distribution. Being
/// relative rather than absolute keeps the measure meaningful at any playback level.
pub fn transient_rate(frames: &[FrameFeatures], sigma: f32) -> f32 {
    if frames.len() < 2 {
        return 0.0;
    }
    let count = frames.len() as f32;
    let mean = frames.iter().map(|f| f.flux).sum::<f32>() / count;
    let variance = frames
        .iter()
        .map(|f| (f.flux - mean) * (f.flux - mean))
        .sum::<f32>()
        / count;
    let deviation = variance.sqrt();
    if deviation <= f32::EPSILON {
        return 0.0;
    }
    let threshold = mean + sigma * deviation;
    frames.iter().filter(|f| f.flux > threshold).count() as f32 / count
}

/// Least-squares trend of the loudness envelope in dB per second. Negative means the region is
/// winding down, which is what an outro should be doing.
pub fn energy_slope(frames: &[FrameFeatures]) -> f32 {
    if frames.len() < 2 {
        return 0.0;
    }
    let count = frames.len() as f64;
    let origin = frames[0].time;
    let times: Vec<f64> = frames.iter().map(|f| f.time - origin).collect();
    let levels: Vec<f64> = frames
        .iter()
        .map(|f| linear_to_db(f.rms).max(-90.0) as f64)
        .collect();

    let mean_time = times.iter().sum::<f64>() / count;
    let mean_level = levels.iter().sum::<f64>() / count;
    let mut covariance = 0.0;
    let mut variance = 0.0;
    for (time, level) in times.iter().zip(&levels) {
        let dt = time - mean_time;
        covariance += dt * (level - mean_level);
        variance += dt * dt;
    }
    if variance <= f64::EPSILON {
        0.0
    } else {
        (covariance / variance) as f32
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frames(count: usize, build: impl Fn(usize) -> FrameFeatures) -> Vec<FrameFeatures> {
        (0..count).map(build).collect()
    }

    fn steady(count: usize) -> Vec<FrameFeatures> {
        frames(count, |i| FrameFeatures {
            time: i as f64 * 0.01,
            rms: 0.5,
            low: 1.0,
            mid: 2.0,
            high: 1.0,
            centroid: 1_000.0,
            flux: 0.1,
        })
    }

    #[test]
    fn empty_regions_report_silence() {
        let region = aggregate(&[], &AnalysisConfig::default());
        assert_eq!(region.rms, 0.0);
        assert!(region.rms_db < -100.0);
        assert_eq!(region.transient_rate, 0.0);
    }

    #[test]
    fn band_shares_are_normalised() {
        let region = aggregate(&steady(10), &AnalysisConfig::default());
        assert!((region.low + region.mid + region.high - 1.0).abs() < 1e-6);
        assert!((region.mid - 0.5).abs() < 1e-6);
        assert!((region.centroid - 1_000.0).abs() < 1e-3);
    }

    #[test]
    fn rms_is_a_power_average() {
        let mixed = frames(2, |i| FrameFeatures {
            time: i as f64 * 0.01,
            rms: if i == 0 { 0.0 } else { 1.0 },
            ..FrameFeatures::default()
        });
        let region = aggregate(&mixed, &AnalysisConfig::default());
        assert!((region.rms - std::f32::consts::FRAC_1_SQRT_2).abs() < 1e-6);
    }

    #[test]
    fn a_steady_region_has_no_transients() {
        assert_eq!(transient_rate(&steady(20), 1.5), 0.0);
        assert_eq!(transient_rate(&steady(1), 1.5), 0.0);
    }

    #[test]
    fn isolated_flux_spikes_are_counted() {
        let mut spiky = steady(20);
        spiky[5].flux = 5.0;
        let rate = transient_rate(&spiky, 1.5);
        assert!((rate - 0.05).abs() < 1e-6, "rate {rate}");
    }

    #[test]
    fn a_fading_region_slopes_downward() {
        let fading = frames(20, |i| FrameFeatures {
            time: i as f64 * 0.1,
            rms: 1.0 * 0.9f32.powi(i as i32),
            ..FrameFeatures::default()
        });
        let slope = energy_slope(&fading);
        assert!(slope < -5.0, "slope {slope}");
    }

    #[test]
    fn a_building_region_slopes_upward() {
        let rising = frames(20, |i| FrameFeatures {
            time: i as f64 * 0.1,
            rms: 0.01 * 1.2f32.powi(i as i32),
            ..FrameFeatures::default()
        });
        assert!(energy_slope(&rising) > 5.0);
    }

    #[test]
    fn a_flat_region_has_no_slope() {
        assert!(energy_slope(&steady(20)).abs() < 1e-3);
        assert_eq!(energy_slope(&steady(1)), 0.0);
    }

    #[test]
    fn simultaneous_frames_do_not_divide_by_zero() {
        let stacked = frames(5, |_| FrameFeatures {
            time: 1.0,
            rms: 0.5,
            ..FrameFeatures::default()
        });
        assert_eq!(energy_slope(&stacked), 0.0);
    }

    #[test]
    fn silent_frames_split_bands_evenly() {
        let silent = frames(5, |i| FrameFeatures {
            time: i as f64 * 0.01,
            ..FrameFeatures::default()
        });
        let region = aggregate(&silent, &AnalysisConfig::default());
        assert!((region.low - 1.0 / 3.0).abs() < 1e-6);
        assert_eq!(region.centroid, 0.0);
    }
}
