/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

//! Kick lock: nudges the incoming cue so both songs' kicks land together.
//! Beat trackers place beats tens of milliseconds off the kick, differently per song;
//! two kicks 30 ms apart sound like one beat dragging behind the other.
//! Drummers call that a flam. Everyone else calls it a bad mix.
use crate::evidence::band;
use earmark::{AudioBuffer, TempoGlide, TransitionPlan};

/// Envelope resolution in seconds.
const HOP: f64 = 0.001;
/// Phase bins per beat, about 2.5 ms at 90 BPM.
const BINS: usize = 256;
/// Beats measured past each end of the overlap, where each side keeps its edge tempo.
const CONTEXT_BEATS: f64 = 8.0;
/// Widest nudge: an eighth of a beat. A best match at the limit is a different pattern.
const LIMIT: i64 = BINS as i64 / 8;
/// The two kick patterns must match this well before the cue moves...
const MIN_MATCH: f64 = 0.5;
/// ...and beat the planned alignment by this much.
const MIN_GAIN: f64 = 0.2;

/// Kick onset strength every `HOP` over `from..to` seconds of `audio`.
struct Onsets {
    values: Vec<f64>,
    origin: f64,
}

impl Onsets {
    fn measure(audio: &AudioBuffer, from: f64, to: f64) -> Self {
        let from = from.max(0.0);
        let to = to.min(audio.duration());
        let mut mono = Vec::new();
        audio.slice_seconds(from, (to - from).max(0.0)).downmix_into(&mut mono);
        let rate = audio.sample_rate() as f64;
        let alpha = 1.0 - (-1.0 / (0.010 * rate)).exp();
        let step = ((HOP * rate).round() as usize).max(1);
        let mut level = 0.0;
        let mut levels = Vec::with_capacity(mono.len() / step + 1);
        for (index, sample) in band(&mono, rate, 35.0, 140.0).into_iter().enumerate() {
            level += alpha * (sample * sample - level);
            if index % step == 0 {
                levels.push((level * 1e4).ln_1p());
            }
        }
        // A kick is a rise in smoothed low-end level over 10 ms.
        let values = (0..levels.len())
            .map(|index| if index >= 10 { (levels[index] - levels[index - 10]).max(0.0) } else { 0.0 })
            .collect();
        Self { values, origin: from }
    }

    fn at(&self, time: f64) -> Option<f64> {
        let index = ((time - self.origin) / HOP).round();
        if index < 0.0 { None } else { self.values.get(index as usize).copied() }
    }
}

/// Source seconds a side has consumed `time` output seconds into the overlap. Before it
/// the side runs at its starting rate; `TempoGlide::offset` covers the rest.
fn consumed(glide: Option<TempoGlide>, ratio: f64, time: f64, duration: f64) -> f64 {
    match glide {
        Some(glide) if time < 0.0 => time * glide.start,
        Some(glide) => glide.offset(time, duration),
        None => time * ratio,
    }
}

/// Mean onset strength per beat-phase bin, centred, or None without a clear kick.
fn profile(sums: &[f64], counts: &[usize]) -> Option<Vec<f64>> {
    let means: Vec<f64> = sums.iter().zip(counts).map(|(sum, &count)| sum / count.max(1) as f64).collect();
    let mean = means.iter().sum::<f64>() / BINS as f64;
    let peak = means.iter().copied().fold(0.0, f64::max);
    (counts.iter().all(|&count| count > 0) && mean > 0.0 && peak > 2.0 * mean)
        .then(|| means.iter().map(|value| value - mean).collect())
}

/// Seconds to move the incoming cue so its kicks land on the outgoing's, or None when
/// the kick patterns do not clearly match.
pub fn kick_lock(outgoing: &AudioBuffer, incoming: &AudioBuffer, plan: &TransitionPlan) -> Option<f64> {
    if plan.beats == 0 || plan.outgoing_bpm.is_nan() || plan.outgoing_bpm <= 0.0 {
        return None;
    }
    let interval = 60.0 / plan.outgoing_bpm as f64;
    let context = CONTEXT_BEATS * interval;
    let duration = plan.duration;
    let glides = plan.tempo_glides();
    let out_ratio = plan.outgoing_tempo_ratio as f64;
    let in_ratio = plan.incoming_tempo_ratio as f64;
    let out_at = |time| plan.outgoing_start + consumed(glides.map(|g| g.0), out_ratio, time, duration);
    let in_at = |time| plan.incoming_start + consumed(glides.map(|g| g.1), in_ratio, time, duration);
    let sides = [
        Onsets::measure(outgoing, out_at(-context) - 0.05, out_at(duration + context) + 0.05),
        Onsets::measure(incoming, in_at(-context) - 0.05, in_at(duration + context) + 0.05),
    ];
    let mut sums = [[0.0; BINS]; 2];
    let mut counts = [[0usize; BINS]; 2];
    let steps = ((duration + 2.0 * context) / HOP) as usize;
    for step in 0..steps {
        let time = -context + step as f64 * HOP;
        // One shared phase: the outgoing's own beat count, which the incoming is matched to.
        let phase = (out_at(time) - plan.outgoing_start) / interval;
        let bin = ((phase.rem_euclid(1.0) * BINS as f64) as usize).min(BINS - 1);
        for (side, source) in [out_at(time), in_at(time)].into_iter().enumerate() {
            if let Some(value) = sides[side].at(source) {
                sums[side][bin] += value;
                counts[side][bin] += 1;
            }
        }
    }
    let left = profile(&sums[0], &counts[0])?;
    let right = profile(&sums[1], &counts[1])?;
    let norm = (left.iter().map(|v| v * v).sum::<f64>() * right.iter().map(|v| v * v).sum::<f64>()).sqrt();
    if norm <= 0.0 {
        return None;
    }
    // Positive shifts mean the incoming kick arrives late.
    let score = |shift: i64| (0..BINS)
        .map(|bin| left[bin] * right[(bin as i64 + shift).rem_euclid(BINS as i64) as usize])
        .sum::<f64>() / norm;
    let best = (-LIMIT..=LIMIT).max_by(|a, b| score(*a).total_cmp(&score(*b)))?;
    // Output seconds to incoming source seconds at the overlap's average incoming rate.
    (best.abs() < LIMIT && score(best) >= MIN_MATCH && score(best) - score(0) >= MIN_GAIN)
        .then(|| best as f64 / BINS as f64 * interval * in_ratio)
}

#[cfg(test)]
mod tests {
    use super::*;
    use earmark::{EngineConfig, TransitionStrategy};
    use earmark::planner::strategy;

    const RATE: u32 = 48_000;

    /// Kicks (decaying 60 Hz) on every beat from `phase`, plus a lighter one on each "and".
    fn kicks(seconds: f64, bpm: f64, phase: f64) -> AudioBuffer {
        let interval = 60.0 / bpm;
        let samples: Vec<f32> = (0..(seconds * RATE as f64) as usize).map(|index| {
            let time = index as f64 / RATE as f64 - phase;
            let beat = time.rem_euclid(interval);
            let half = (time + interval / 2.0).rem_euclid(interval);
            let hit = |since: f64, gain: f64| gain * (-since / 0.06).exp() * (std::f64::consts::TAU * 60.0 * since).sin();
            (hit(beat, 1.0) + hit(half, 0.4)) as f32
        }).collect();
        AudioBuffer::new(vec![samples.clone(), samples], RATE).unwrap()
    }

    fn plan(outgoing_start: f64, incoming_start: f64, bpm: f32, ramp: bool, ratios: (f32, f32)) -> TransitionPlan {
        TransitionPlan {
            outgoing_start, incoming_start, duration: 8.0, beats: 16, sample_rate: RATE, channels: 2,
            outgoing_bpm: bpm, incoming_bpm: bpm, target_bpm: bpm,
            outgoing_tempo_ratio: ratios.0, incoming_tempo_ratio: ratios.1,
            outgoing_pitch_semitones: 0.0, incoming_pitch_semitones: 0.0, tempo_ramp: ramp,
            outgoing_gain_db: 0.0, incoming_gain_db: 0.0, strategy: TransitionStrategy::BassSwap,
            fade: strategy::build_fade(TransitionStrategy::BassSwap, Default::default()),
            filters: strategy::build_filters(TransitionStrategy::BassSwap, &EngineConfig::default()),
            diagnostics: None,
        }
    }

    #[test]
    fn a_late_incoming_kick_moves_the_cue_forward() {
        let outgoing = kicks(40.0, 120.0, 0.0);
        // The incoming's kicks sit 30 ms after its beat grid.
        let incoming = kicks(40.0, 120.0, 0.03);
        let shift = kick_lock(&outgoing, &incoming, &plan(20.0, 10.0, 120.0, false, (1.0, 1.0))).unwrap();
        assert!((shift - 0.03).abs() < 0.004, "{shift}");
    }

    #[test]
    fn locked_kicks_stay_put() {
        let outgoing = kicks(40.0, 120.0, 0.0);
        let incoming = kicks(40.0, 120.0, 0.0);
        assert_eq!(kick_lock(&outgoing, &incoming, &plan(20.0, 10.0, 120.0, false, (1.0, 1.0))), None);
    }

    #[test]
    fn the_lock_follows_a_tempo_glide() {
        // 118 BPM into 122 BPM, with the incoming kicks 25 ms behind its grid.
        let outgoing = kicks(40.0, 118.0, 0.0);
        let incoming = kicks(40.0, 122.0, 0.025);
        let average = (122.0f64 - 118.0) / (122.0f64 / 118.0).ln();
        let ratios = ((average / 118.0) as f32, (average / 122.0) as f32);
        let glide = plan(60.0 / 118.0 * 30.0, 60.0 / 122.0 * 12.0, 118.0, true, ratios);
        let shift = kick_lock(&outgoing, &incoming, &glide).unwrap();
        assert!((shift - 0.025).abs() < 0.004, "{shift}");
    }

    #[test]
    fn silence_has_no_kick_to_lock() {
        let outgoing = kicks(40.0, 120.0, 0.0);
        let silent = AudioBuffer::silent(2, 40 * RATE as usize, RATE).unwrap();
        assert_eq!(kick_lock(&outgoing, &silent, &plan(20.0, 10.0, 120.0, false, (1.0, 1.0))), None);
    }
}
