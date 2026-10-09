//! Beat-pointer Viterbi decoding over the state space of Krebs, Böck & Widmer (ISMIR 2015).
//!
//! Inside a beat the pointer only advances, so a state's score is the score of the beat
//! start plus a sum of emissions. Prefix sums make that sum O(1), and the recursion only
//! visits beat starts: O(frames × tempo transitions) instead of O(frames × states).

/// Every tempo is an integer beat interval in frames. A beat walks its interval one
/// frame at a time and may change tempo only when it wraps to the next beat.
pub(crate) struct BeatStateSpace {
    intervals: Vec<usize>,
    /// Per destination tempo: (source tempo, log transition probability).
    incoming: Vec<Vec<(usize, f32)>>,
}

/// Phase ranges of one beat, as offsets from its first frame.
#[derive(Clone, Copy)]
struct Regions {
    interval: usize,
    /// End of the on-beat span, which starts at the beat itself.
    inside: usize,
    /// The off-beat span, half a beat later.
    off_lo: usize,
    off_hi: usize,
}

impl Regions {
    fn new(interval: usize, observation_lambda: f32) -> Self {
        let inside = ((interval as f32 / observation_lambda).ceil() as usize).clamp(1, interval);
        let off_lo = (interval / 2).max(inside);
        let off_hi = (off_lo + inside).min(interval);
        Self {
            interval,
            inside,
            off_lo,
            off_hi,
        }
    }
}

/// Running sums of the three emission streams, with `pad` silent frames in front so a
/// beat may start before the first frame.
struct Prefix {
    pad: usize,
    beat: Vec<f64>,
    off: Vec<f64>,
    other: Vec<f64>,
}

impl Prefix {
    fn new(pad: usize, log_beat: &[f32], log_off: &[f32], log_other: &[f32]) -> Self {
        let sums = |values: &[f32]| {
            let mut out = vec![0.0f64; pad + values.len() + 1];
            for (i, v) in values.iter().enumerate() {
                out[pad + i + 1] = out[pad + i] + *v as f64;
            }
            out
        };
        Self {
            pad,
            beat: sums(log_beat),
            off: sums(log_off),
            other: sums(log_other),
        }
    }

    /// Emissions for phases `[1, upto)` of a beat starting at frame `start`.
    fn beat_sum(&self, start: isize, r: &Regions, upto: usize) -> f64 {
        let base = (start + self.pad as isize) as usize;
        let range = |sums: &[f64], lo: usize, hi: usize| {
            let hi = hi.min(upto);
            if hi > lo {
                sums[base + hi] - sums[base + lo]
            } else {
                0.0
            }
        };
        range(&self.beat, 1, r.inside)
            + range(&self.other, r.inside, r.off_lo)
            + range(&self.off, r.off_lo, r.off_hi)
            + range(&self.other, r.off_hi, r.interval)
    }

    fn beat_at(&self, frame: isize) -> f64 {
        let i = (frame + self.pad as isize) as usize;
        self.beat[i + 1] - self.beat[i]
    }
}

impl BeatStateSpace {
    pub(crate) fn new(min_interval: usize, max_interval: usize, lambda: f64) -> Self {
        let intervals: Vec<usize> =
            (min_interval.max(2)..=max_interval.max(min_interval)).collect();
        let mut incoming = vec![Vec::new(); intervals.len()];
        for (from, &a) in intervals.iter().enumerate() {
            let weights: Vec<f64> = intervals
                .iter()
                .map(|&b| (-lambda * (b as f64 / a as f64 - 1.0).abs()).exp())
                .collect();
            let total: f64 = weights.iter().filter(|w| **w > 1e-9).sum();
            for (to, w) in weights.iter().enumerate() {
                if *w > 1e-9 {
                    incoming[to].push((from, (w / total).ln() as f32));
                }
            }
        }
        Self {
            intervals,
            incoming,
        }
    }

    pub(crate) fn intervals(&self) -> &[usize] {
        &self.intervals
    }

    /// Most likely beat frames. `log_beat`, `log_off` and `log_other` are per-frame log
    /// densities for the first `1 / observation_lambda` of a beat, the same span starting
    /// half a beat later, and everything else. `tempo_bonus` is added each time a beat
    /// starts at that tempo.
    pub(crate) fn decode(
        &self,
        log_beat: &[f32],
        log_off: &[f32],
        log_other: &[f32],
        observation_lambda: f32,
        tempo_bonus: &[f32],
    ) -> Vec<usize> {
        let frames = log_beat.len();
        let tempi = self.intervals.len();
        if frames == 0 || tempi == 0 {
            return Vec::new();
        }
        let regions: Vec<Regions> = self
            .intervals
            .iter()
            .map(|&i| Regions::new(i, observation_lambda))
            .collect();
        let longest = *self.intervals.iter().max().unwrap_or(&1);
        let prefix = Prefix::new(longest, log_beat, log_off, log_other);
        // Score of starting a beat at tempo j on frame s, kept for the last `ring` frames.
        // Starts at or before frame 0 are free: the track may open anywhere in a beat.
        let ring = longest + 1;
        let slot = |frame: isize| (frame + ring as isize * 2) as usize % ring;
        let mut start = vec![0.0f64; ring * tempi];
        for s in -(longest as isize)..=0 {
            let opening = if s == 0 { prefix.beat_at(0) } else { 0.0 };
            start[slot(s) * tempi..][..tempi].fill(opening);
        }
        let mut back = vec![0u16; frames * tempi];
        let mut ending = vec![0.0f64; tempi];
        for t in 1..frames as isize {
            // Each tempo's beat ending on frame t - 1, scored once for every destination.
            for (j, r) in regions.iter().enumerate() {
                let s = t - r.interval as isize;
                ending[j] = start[slot(s) * tempi + j] + prefix.beat_sum(s, r, r.interval);
            }
            let emission = prefix.beat_at(t);
            let row = slot(t) * tempi;
            for (i, sources) in self.incoming.iter().enumerate() {
                let mut best = f64::NEG_INFINITY;
                let mut from = i;
                for &(j, log_p) in sources {
                    let value = ending[j] + log_p as f64;
                    if value > best {
                        best = value;
                        from = j;
                    }
                }
                start[row + i] = best + emission + tempo_bonus[i] as f64;
                back[t as usize * tempi + i] = from as u16;
            }
        }
        // Best final state: any tempo, any phase of the beat in progress on the last frame.
        let last = frames as isize - 1;
        let (mut tempo, mut first, mut best) = (0, last, f64::NEG_INFINITY);
        for (i, r) in regions.iter().enumerate() {
            for phase in 0..r.interval {
                let s = last - phase as isize;
                let value = start[slot(s) * tempi + i] + prefix.beat_sum(s, r, phase + 1);
                if value > best {
                    (tempo, first, best) = (i, s, value);
                }
            }
        }
        let mut beats = Vec::new();
        while first >= 0 {
            beats.push(first as usize);
            if first == 0 {
                break;
            }
            let from = back[first as usize * tempi + tempo] as usize;
            first -= self.intervals[from] as isize;
            tempo = from;
        }
        beats.reverse();
        beats
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Frame-by-frame Viterbi over every (tempo, phase) state, for checking `decode`.
    fn dense(
        space: &BeatStateSpace,
        beat: &[f32],
        off: &[f32],
        other: &[f32],
        lambda: f32,
        bonus: &[f32],
    ) -> Vec<usize> {
        let frames = beat.len();
        let tempi = space.intervals.len();
        let offsets: Vec<usize> = space
            .intervals
            .iter()
            .scan(0, |acc, &i| {
                let at = *acc;
                *acc += i;
                Some(at)
            })
            .collect();
        let states: usize = space.intervals.iter().sum();
        let regions: Vec<Regions> = space
            .intervals
            .iter()
            .map(|&i| Regions::new(i, lambda))
            .collect();
        let obs = |r: &Regions, phase: usize, t: usize| -> f64 {
            if phase < r.inside {
                beat[t] as f64
            } else if phase >= r.off_lo && phase < r.off_hi {
                off[t] as f64
            } else {
                other[t] as f64
            }
        };
        let mut previous = vec![0.0f64; states];
        for (tempo, r) in regions.iter().enumerate() {
            for phase in 0..r.interval {
                previous[offsets[tempo] + phase] = obs(r, phase, 0);
            }
        }
        let mut back = vec![0usize; frames * tempi];
        let mut next = vec![0.0f64; states];
        for t in 1..frames {
            for (tempo, r) in regions.iter().enumerate() {
                let (mut best, mut source) = (f64::NEG_INFINITY, tempo);
                for &(from, log_p) in &space.incoming[tempo] {
                    let value = previous[offsets[from] + space.intervals[from] - 1] + log_p as f64;
                    if value > best {
                        (best, source) = (value, from);
                    }
                }
                next[offsets[tempo]] = best + beat[t] as f64 + bonus[tempo] as f64;
                back[t * tempi + tempo] = source;
                for phase in 1..r.interval {
                    next[offsets[tempo] + phase] =
                        previous[offsets[tempo] + phase - 1] + obs(r, phase, t);
                }
            }
            std::mem::swap(&mut previous, &mut next);
        }
        let state = (0..states).fold(0, |b, s| if previous[s] > previous[b] { s } else { b });
        let mut tempo = offsets.partition_point(|&o| o <= state) - 1;
        let mut phase = state - offsets[tempo];
        let mut t = frames - 1;
        let mut beats = Vec::new();
        while t >= phase {
            let start = t - phase;
            beats.push(start);
            if start == 0 {
                break;
            }
            tempo = back[start * tempi + tempo];
            phase = space.intervals[tempo] - 1;
            t = start - 1;
        }
        beats.reverse();
        beats
    }

    #[test]
    fn decodes_a_steady_pulse() {
        let space = BeatStateSpace::new(13, 55, 100.0);
        let frames = 1000;
        let activation: Vec<f32> = (0..frames)
            .map(|t| if t % 25 == 7 { 0.9 } else { 0.02 })
            .collect();
        let log_beat: Vec<f32> = activation.iter().map(|p| p.ln()).collect();
        let log_other: Vec<f32> = activation.iter().map(|p| ((1.0 - p) / 15.0).ln()).collect();
        let bonus = vec![0.0; space.intervals().len()];
        let beats = space.decode(&log_beat, &log_other, &log_other, 16.0, &bonus);
        assert!(beats.len() >= 38, "{beats:?}");
        assert!(beats.iter().skip(1).all(|b| b % 25 == 7), "{beats:?}");
    }

    // Prefix sums reorder the additions, so allow for a rare near-tie resolving the other way.
    #[test]
    fn matches_the_dense_decoder() {
        let mut seed = 0x9e37_79b9_u32;
        let mut noise = move || {
            seed ^= seed << 13;
            seed ^= seed >> 17;
            seed ^= seed << 5;
            seed as f32 / u32::MAX as f32
        };
        let space = BeatStateSpace::new(13, 40, 140.0);
        let bonus: Vec<f32> = space
            .intervals()
            .iter()
            .map(|&i| -0.01 * (i as f32 - 25.0).abs())
            .collect();
        for case in 0..20 {
            let frames = 300 + case * 37;
            let period = 14.0 + case as f32 * 1.3;
            let beat: Vec<f32> = (0..frames)
                .map(|t| {
                    let pulse = if (t as f32 % period) < 1.0 { 0.8 } else { 0.05 };
                    (pulse + 0.3 * noise()).min(0.99).ln()
                })
                .collect();
            let off: Vec<f32> = (0..frames).map(|_| (0.05 + 0.1 * noise()).ln()).collect();
            let other: Vec<f32> = (0..frames).map(|_| (0.05 + 0.1 * noise()).ln()).collect();
            let fast = space.decode(&beat, &off, &other, 16.0, &bonus);
            let slow = dense(&space, &beat, &off, &other, 16.0, &bonus);
            let shared = fast.iter().filter(|b| slow.contains(b)).count();
            assert!(
                shared * 100 >= slow.len() * 95,
                "case {case}: {fast:?} vs {slow:?}"
            );
        }
    }
}
