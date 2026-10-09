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

//! Port of OrchardV2's beatThisTracker.js peak picking and chunk policy.

pub const CHUNK: usize = 1500;
pub const BORDER: usize = 6;
pub const STEP: usize = CHUNK - 2 * BORDER;

pub fn chunks(frames: usize) -> Vec<isize> {
    let mut starts = Vec::new();
    let mut start = -(BORDER as isize);
    while start < frames as isize - BORDER as isize {
        starts.push(start);
        start += STEP as isize;
    }
    if starts.is_empty() {
        starts.push(-(BORDER as isize));
    }
    if frames > STEP {
        *starts.last_mut().unwrap() = frames as isize - (CHUNK - BORDER) as isize;
    }
    while starts.len() > 1
        && starts[starts.len() - 1] - starts[starts.len() - 2] < (2 * BORDER) as isize
    {
        starts.pop();
    }
    starts
}

pub fn peaks(logits: &[f32]) -> Vec<f64> {
    let candidates: Vec<usize> = (0..logits.len())
        .filter(|&i| {
            logits[i] > 0.0
                && logits[i.saturating_sub(3)..(i + 4).min(logits.len())]
                    .iter()
                    .all(|&v| v <= logits[i])
        })
        .collect();
    let mut result = Vec::new();
    let mut i = 0;
    while i < candidates.len() {
        let mut mean = candidates[i] as f64;
        let mut count = 1;
        while i + 1 < candidates.len() && candidates[i + 1] as f64 - mean <= 1.0 {
            i += 1;
            count += 1;
            mean += (candidates[i] as f64 - mean) / count as f64;
        }
        let frame = mean.round() as usize;
        let shift = if frame > 0 && frame + 1 < logits.len() {
            let (l, c, r) = (
                logits[frame - 1] as f64,
                logits[frame] as f64,
                logits[frame + 1] as f64,
            );
            let d = l - 2.0 * c + r;
            if d.abs() > 1e-9 {
                (0.5 * (l - r) / d).clamp(-0.5, 0.5)
            } else {
                0.0
            }
        } else {
            0.0
        };
        result.push(frame as f64 + shift);
        i += 1;
    }
    result
}

pub fn median(values: &[f64]) -> f64 {
    let mut values = values.to_vec();
    values.sort_by(f64::total_cmp);
    values.get(values.len() / 2).copied().unwrap_or(0.0)
}

#[derive(Debug, Clone)]
pub struct Grid {
    pub beats: Vec<f64>,
    pub downbeats: Vec<f64>,
    pub bpm: f32,
    pub confidence: f64,
}

pub fn grid(beat: &[f32], downbeat: &[f32]) -> Result<Grid, String> {
    let positions = peaks(beat);
    let beats: Vec<_> = positions.iter().map(|f| f / 50.0).collect();
    if beats.len() < 8 {
        return Err("Beat This found fewer than eight beats".into());
    }
    let gaps: Vec<_> = beats.windows(2).map(|w| w[1] - w[0]).collect();
    let rough = median(&gaps);
    let kept: Vec<_> = gaps
        .iter()
        .copied()
        .filter(|g| (g - rough).abs() <= rough * 0.2)
        .collect();
    let interval = median(if kept.len() >= 4 { &kept } else { &gaps });
    let bpm = 60.0 / interval;
    if !(40.0..=220.0).contains(&bpm) {
        return Err("Beat This tempo is outside 40–220 BPM".into());
    }
    let mut downbeats: Vec<_> = peaks(downbeat)
        .iter()
        .map(|p| {
            *beats
                .iter()
                .min_by(|a, b| (*a - p / 50.0).abs().total_cmp(&(*b - p / 50.0).abs()))
                .unwrap()
        })
        .collect();
    downbeats.dedup();
    let regular = gaps
        .iter()
        .filter(|g| (**g - rough).abs() <= rough * 0.1)
        .count() as f64
        / gaps.len() as f64;
    let strengths: Vec<_> = positions
        .iter()
        .map(|p| beat[p.round() as usize] as f64)
        .collect();
    let strength = 1.0 / (1.0 + (-(median(&strengths) - 0.5)).exp());
    Ok(Grid {
        beats,
        downbeats,
        bpm: bpm as f32,
        confidence: (0.35 + 0.4 * regular + 0.25 * strength).clamp(0.0, 0.95),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn border_tail_does_not_buy_a_second_inference() {
        assert_eq!(chunks(1488), vec![-6]);
        assert_eq!(chunks(1489), vec![-6]);
        assert_eq!(chunks(3000), vec![-6, 1482, 1506]);
    }
    #[test]
    fn subframe_peaks_and_bar_subset() {
        let mut b = vec![-3.0; 600];
        let mut d = b.clone();
        for i in (25..575).step_by(25) {
            b[i - 1] = 1.0;
            b[i] = 4.0;
            b[i + 1] = 2.0;
            if i % 100 == 25 {
                d[i + 1] = 4.0;
            }
        }
        let g = grid(&b, &d).unwrap();
        assert!((g.bpm - 120.0).abs() < 0.01);
        assert!((g.beats[0] - 0.502).abs() < 1e-6);
        assert!(g.downbeats.iter().all(|v| g.beats.contains(v)));
        assert!(g.confidence > 0.55);
    }
    #[test]
    fn silence_is_not_a_grid() {
        assert!(grid(&[0.0; 600], &[0.0; 600]).is_err());
    }
}
