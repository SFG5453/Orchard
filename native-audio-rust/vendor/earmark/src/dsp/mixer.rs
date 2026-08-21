//! Channel mixing primitives.
//!
//! These take a pre-computed per-sample envelope rather than a curve, so a transition evaluates
//! its automation once and reuses the result across every channel.

/// Multiplies `samples` by `envelope` in place. Extra samples beyond the envelope are left alone.
pub fn apply_envelope(samples: &mut [f32], envelope: &[f32]) {
    for (sample, gain) in samples.iter_mut().zip(envelope) {
        *sample *= *gain;
    }
}

/// `dst += src * envelope`, over the length all three share.
pub fn mix_into(dst: &mut [f32], src: &[f32], envelope: &[f32]) {
    let len = dst.len().min(src.len()).min(envelope.len());
    for i in 0..len {
        dst[i] += src[i] * envelope[i];
    }
}

/// `dst += src * gain`, over the length both share.
pub fn add_scaled(dst: &mut [f32], src: &[f32], gain: f32) {
    let len = dst.len().min(src.len());
    if gain == 1.0 {
        for i in 0..len {
            dst[i] += src[i];
        }
    } else {
        for i in 0..len {
            dst[i] += src[i] * gain;
        }
    }
}

/// Sums the channels into `out` with `1/channels` weighting. Used to feed the mono analysis path.
pub fn downmix_into(out: &mut Vec<f32>, channels: &[Vec<f32>], frames: usize) {
    out.clear();
    out.resize(frames, 0.0);
    if channels.is_empty() {
        return;
    }
    let scale = 1.0 / channels.len() as f32;
    for channel in channels {
        let len = frames.min(channel.len());
        for i in 0..len {
            out[i] += channel[i] * scale;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn approx(a: f32, b: f32) {
        assert!((a - b).abs() < 1e-6, "{a} != {b}");
    }

    #[test]
    fn envelope_scales_in_place() {
        let mut samples = [1.0, 1.0, 1.0];
        apply_envelope(&mut samples, &[0.0, 0.5, 1.0]);
        approx(samples[0], 0.0);
        approx(samples[1], 0.5);
        approx(samples[2], 1.0);
    }

    #[test]
    fn mix_accumulates_rather_than_replacing() {
        let mut dst = [0.25, 0.25];
        mix_into(&mut dst, &[1.0, 1.0], &[0.5, 0.25]);
        approx(dst[0], 0.75);
        approx(dst[1], 0.5);
    }

    #[test]
    fn mixing_stops_at_the_shortest_slice() {
        let mut dst = [0.0, 0.0, 0.0];
        mix_into(&mut dst, &[1.0, 1.0], &[1.0, 1.0, 1.0]);
        approx(dst[2], 0.0);
    }

    #[test]
    fn add_scaled_handles_unity_and_scaled_paths() {
        let mut dst = [1.0, 1.0];
        add_scaled(&mut dst, &[1.0, 1.0], 1.0);
        approx(dst[0], 2.0);
        add_scaled(&mut dst, &[1.0, 1.0], 0.5);
        approx(dst[0], 2.5);
    }

    #[test]
    fn stereo_downmix_averages_channels() {
        let channels = vec![vec![1.0, 0.0], vec![0.0, 1.0]];
        let mut out = Vec::new();
        downmix_into(&mut out, &channels, 2);
        approx(out[0], 0.5);
        approx(out[1], 0.5);
    }

    #[test]
    fn downmix_pads_short_channels_with_silence() {
        let channels = vec![vec![1.0]];
        let mut out = Vec::new();
        downmix_into(&mut out, &channels, 3);
        assert_eq!(out.len(), 3);
        approx(out[1], 0.0);
    }

    #[test]
    fn downmix_of_no_channels_is_silence() {
        let mut out = Vec::new();
        downmix_into(&mut out, &[], 2);
        assert_eq!(out, vec![0.0, 0.0]);
    }
}
