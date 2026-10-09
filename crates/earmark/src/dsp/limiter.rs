//! Look-ahead peak limiting.
//!
//! The limiter works offline on a whole buffer: it knows every peak in advance, so it eases into
//! each one and leaves every frame that needs no help at unity. Clairvoyance is cheap when the
//! future is already rendered.

use std::collections::VecDeque;

/// Per-frame peak across `channels`, each sample scaled by `envelope` when one is given.
pub fn frame_peaks(channels: &[Vec<f32>], envelope: Option<&[f32]>, out: &mut Vec<f32>) {
    let frames = channels.first().map_or(0, Vec::len);
    out.clear();
    out.resize(frames, 0.0);
    for channel in channels {
        match envelope {
            Some(envelope) => {
                for ((peak, sample), gain) in out.iter_mut().zip(channel).zip(envelope) {
                    *peak = peak.max((sample * gain).abs());
                }
            }
            None => {
                for (peak, sample) in out.iter_mut().zip(channel) {
                    *peak = peak.max(sample.abs());
                }
            }
        }
    }
}

/// Largest value within `radius` frames either side of each frame.
pub fn held(values: &[f32], radius: usize) -> Vec<f32> {
    let mut out = Vec::with_capacity(values.len());
    let mut window: VecDeque<usize> = VecDeque::new();
    let mut next = 0;
    for index in 0..values.len() {
        let end = (index + radius).min(values.len() - 1);
        while next <= end {
            while window.back().is_some_and(|&last| values[last] <= values[next]) {
                window.pop_back();
            }
            window.push_back(next);
            next += 1;
        }
        while window.front().is_some_and(|&first| first + radius < index) {
            window.pop_front();
        }
        out.push(window.front().map_or(0.0, |&first| values[first]));
    }
    out
}

/// Smooth gain that never exceeds `required` at any frame.
///
/// Each dip is approached over `lookahead` frames (a windowed minimum, then a box average of
/// the same length, so every frame's own requirement sits inside each averaged window) and
/// recovers through a one-pole `release` coefficient. Frames needing no reduction stay at 1.
pub fn lookahead_gain(required: &[f32], lookahead: usize, release: f32) -> Vec<f32> {
    let frames = required.len();
    if frames == 0 {
        return Vec::new();
    }
    let length = lookahead.max(1);
    // minima[k] covers required[k - (length - 1) ..= k], clipped to the buffer.
    let mut minima = Vec::with_capacity(frames + length - 1);
    let mut window: VecDeque<usize> = VecDeque::new();
    let mut next = 0;
    for start in -(length as isize - 1)..frames as isize {
        let end = (start + length as isize - 1).min(frames as isize - 1);
        while next as isize <= end {
            while window.back().is_some_and(|&last| required[last] >= required[next]) {
                window.pop_back();
            }
            window.push_back(next);
            next += 1;
        }
        while window.front().is_some_and(|&first| (first as isize) < start) {
            window.pop_front();
        }
        minima.push(window.front().map_or(1.0, |&first| required[first]));
    }

    let mut gain = Vec::with_capacity(frames);
    let mut sum: f64 = minima[..length].iter().map(|&value| value as f64).sum();
    gain.push(sum / length as f64);
    for index in 1..frames {
        sum += minima[index + length - 1] as f64 - minima[index - 1] as f64;
        gain.push(sum / length as f64);
    }

    let mut level = 1.0f32;
    gain.into_iter()
        .zip(required)
        .map(|(averaged, &limit)| {
            // The running sum can drift a few ulps above a flat requirement.
            let averaged = (averaged as f32).min(limit);
            level = averaged.min(release * level + (1.0 - release) * averaged);
            level
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn peaks_follow_the_loudest_channel_and_the_envelope() {
        let channels = vec![vec![0.5, -0.9, 0.1], vec![-0.7, 0.2, 0.3]];
        let mut out = Vec::new();
        frame_peaks(&channels, None, &mut out);
        assert_eq!(out, vec![0.7, 0.9, 0.3]);
        frame_peaks(&channels, Some(&[1.0, 0.5, 0.0]), &mut out);
        assert_eq!(out, vec![0.7, 0.45, 0.0]);
    }

    #[test]
    fn holding_spreads_each_peak_across_its_radius() {
        let values = [0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.5];
        assert_eq!(held(&values, 1), vec![0.0, 1.0, 1.0, 1.0, 0.0, 0.5, 0.5]);
        assert_eq!(held(&values, 0), values.to_vec());
    }

    #[test]
    fn the_gain_meets_every_requirement_and_eases_in() {
        let mut required = vec![1.0f32; 400];
        required[200] = 0.5;
        required[260] = 0.8;
        let gain = lookahead_gain(&required, 32, 0.99);
        for (g, r) in gain.iter().zip(&required) {
            assert!(*g <= *r + 1e-6, "{g} above {r}");
        }
        assert_eq!(gain[..168], vec![1.0; 168][..], "reduction started before its lookahead");
        // A linear approach: no frame-to-frame step larger than the dip spread over the window.
        let steepest = gain.windows(2).map(|w| (w[1] - w[0]).abs()).fold(0.0, f32::max);
        assert!(steepest <= 0.5 / 32.0 + 1e-6, "step {steepest}");
        assert!(gain[399] > gain[261], "no release after the last peak");
    }

    #[test]
    fn nothing_to_limit_leaves_unity_gain() {
        assert!(lookahead_gain(&[1.0; 64], 8, 0.9).iter().all(|g| *g == 1.0));
        assert!(lookahead_gain(&[], 8, 0.9).is_empty());
    }

    #[test]
    fn a_dip_at_either_edge_is_still_met() {
        let mut required = vec![1.0f32; 50];
        required[0] = 0.25;
        required[49] = 0.5;
        let gain = lookahead_gain(&required, 16, 0.95);
        assert!(gain[0] <= 0.25 + 1e-6);
        assert!(gain[49] <= 0.5 + 1e-6);
    }
}
