//! Per-frame log densities for the beat-pointer states.

/// Log densities for frames inside a beat, half a beat later, and elsewhere.
pub(crate) struct Emissions {
    pub beat: Vec<f32>,
    pub off: Vec<f32>,
    pub other: Vec<f32>,
}

impl Emissions {
    /// Multiplies every log density.
    pub(crate) fn scale(&mut self, factor: f32) {
        if factor != 1.0 {
            for stream in [&mut self.beat, &mut self.off, &mut self.other] {
                stream.iter_mut().for_each(|v| *v *= factor);
            }
        }
    }
}

/// One onset activation shared by all bands. Every onset that is not called a beat
/// costs the path, so this reading drifts towards the faster of two metrical levels.
pub(crate) fn activation(onset: &[f32], gain: f32, observation_lambda: f32) -> Emissions {
    let mut out = Emissions {
        beat: Vec::with_capacity(onset.len()),
        off: Vec::with_capacity(onset.len()),
        other: Vec::with_capacity(onset.len()),
    };
    for v in onset {
        let p = (1.0 - (-gain * v).exp()).clamp(1e-4, 1.0 - 1e-4);
        out.beat.push(p.ln());
        let rest = ((1.0 - p) / (observation_lambda - 1.0)).ln();
        out.off.push(rest);
        out.other.push(rest);
    }
    out
}

/// Exponential onset densities per band and class (beat, off-beat, other). Off-beat
/// onsets are expected rather than penalized, so this reading leans to the slower level.
pub(crate) fn generative(
    bands: &[Vec<f32>; 4],
    means: &[[f64; 4]; 3],
    floor: f32,
    temperature: f32,
) -> Emissions {
    let frames = bands[0].len();
    let means = means.map(|row| row.map(|m| m.max(1e-3) as f32));
    let mut ll = vec![[0.0f32; 3]; frames];
    for (band, onsets) in bands.iter().enumerate() {
        for (slot, onset) in ll.iter_mut().zip(onsets) {
            for (class, row) in means.iter().enumerate() {
                let mean = row[band];
                let density = (-onset / mean).exp() / mean;
                // The uniform floor keeps one stray hit from steering a whole path.
                slot[class] += ((1.0 - floor) * density + floor * 0.1).ln();
            }
        }
    }
    Emissions {
        beat: ll.iter().map(|v| temperature * v[0]).collect(),
        off: ll.iter().map(|v| temperature * v[1]).collect(),
        other: ll.iter().map(|v| temperature * v[2]).collect(),
    }
}
