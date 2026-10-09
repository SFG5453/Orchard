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

//! V2's playback analysis: whole-track Earmark evidence, then Beat This picks bar phase.
use crate::{beats::Grid, song::{ANALYSIS_RATE, Track}};
use earmark::{WholeTrackAnalysis, WholeTrackAnalyzer};
use serde_json::{Value, json};

// Matches the compaction V2's native addon applied before JS saw any value.
fn compact(value: f64) -> f64 { (value * 10_000.0).round() / 10_000.0 }
fn compact_vec(values: &[f64]) -> Vec<f64> { values.iter().copied().map(compact).collect() }

fn to_json(result: &WholeTrackAnalysis) -> Value {
    let curve = |points: &[earmark::analysis::EnergyPoint]| points.iter()
        .map(|p| json!({"time": compact(p.time), "energy": compact(p.energy)})).collect::<Vec<_>>();
    let cues = |cues: &[earmark::analysis::MixCuePoint]| cues.iter()
        .map(|c| json!({"time": compact(c.time), "score": compact(c.score), "type": c.kind}))
        .collect::<Vec<_>>();
    let frames: Vec<_> = result.transition_feature_frames.iter().map(|f| json!({
        "time": compact(f.time), "energy": compact(f.energy), "low": compact(f.low),
        "mid": compact(f.mid), "high": compact(f.high), "vocal": compact(f.vocal),
        "novelty": compact(f.novelty), "transientDensity": compact(f.transient_density),
        "stability": compact(f.stability)})).collect();
    let boundaries: Vec<_> = result.structural_boundary_candidates.iter().map(|b| json!({
        "time": compact(b.time), "observedTime": compact(b.observed_time),
        "confidence": compact(b.confidence), "source": b.source,
        "noveltyPeak": compact(b.novelty_peak), "energyDelta": compact(b.energy_delta),
        "lowDelta": compact(b.low_delta), "vocalDelta": compact(b.vocal_delta),
        "stabilityBefore": compact(b.stability_before),
        "stabilityAfter": compact(b.stability_after),
        "downbeatDistance": compact(b.downbeat_distance)})).collect();
    let mut value = json!({
        "analysisVersion": 13,
        "duration": compact(result.duration), "bpm": compact(result.bpm),
        "beatInterval": compact(result.beat_interval), "firstBeat": compact(result.first_beat),
        "beatConfidence": compact(result.beat_confidence),
        "beats": compact_vec(&result.beats), "downbeats": compact_vec(&result.downbeats),
        "phraseBoundaries": compact_vec(&result.phrase_boundaries),
        "phrases": result.phrases.iter().map(|p| json!({"start": compact(p.start),
            "end": compact(p.end), "type": p.kind, "confidence": compact(p.confidence)}))
            .collect::<Vec<_>>(),
        "key": result.key, "keyConfidence": compact(result.key_confidence),
        "chroma": compact_vec(&result.chroma),
        "audibleStartTime": compact(result.audible_start_time),
        "pickupTime": compact(result.pickup_time),
        "pickupConfidence": compact(result.pickup_confidence),
        "mixInTime": compact(result.mix_in_time),
        "mixInConfidence": compact(result.mix_in_confidence),
        "introEndTime": compact(result.intro_end_time),
        "outroStartTime": compact(result.outro_start_time),
        "contentEndTime": compact(result.content_end_time),
        "mixOutTime": compact(result.mix_out_time),
        "loudnessLufs": compact(result.loudness_lufs), "peakDbfs": compact(result.peak_dbfs),
        "dynamicRangeDb": compact(result.dynamic_range_db),
        "energyCurve": curve(&result.energy_curve),
        "lowEnergyCurve": curve(&result.low_energy_curve),
        "midEnergyCurve": curve(&result.mid_energy_curve),
        "highEnergyCurve": curve(&result.high_energy_curve),
        "vocalActivityMask": compact_vec(&result.vocal_activity_mask),
        "vocalProbability": compact(result.vocal_probability),
        "instrumentalProbability": compact(result.instrumental_probability),
        "mixInCandidates": cues(&result.mix_in_candidates),
        "mixOutCandidates": cues(&result.mix_out_candidates),
        "meter": {"beatsPerBar": result.meter.beats_per_bar,
            "confidence": compact(result.meter.confidence), "source": result.meter.source},
    });
    value["transitionFeatureFrames"] = json!(frames);
    value["structuralBoundaryCandidates"] = json!(boundaries);
    value
}

fn median(values: &[f64]) -> f64 {
    if values.is_empty() { return 0.0; }
    let mut sorted = values.to_vec();
    sorted.sort_by(f64::total_cmp);
    sorted[sorted.len() / 2]
}

fn nearest_index(grid: &[f64], time: f64) -> usize {
    let index = grid.partition_point(|t| *t < time).min(grid.len() - 1);
    if index > 0 && (grid[index - 1] - time).abs() <= (grid[index] - time).abs() { index - 1 } else { index }
}

fn nearest_distance(grid: &[f64], time: f64) -> f64 {
    if grid.is_empty() { return f64::INFINITY; }
    (grid[nearest_index(grid, time)] - time).abs()
}

// Position from time, so missed or extra peaks never flip every later phase.
fn metrical_phases(grid: &[f64], interval: f64, count: usize) -> Vec<Vec<f64>> {
    let mut phases = vec![Vec::new(); count];
    let origin = grid.first().copied().unwrap_or(0.0);
    for &time in grid {
        let position = ((time - origin) / interval).round().max(0.0) as usize;
        phases[position % count].push(time);
    }
    phases
}

/// Port of V2's `refineBeatsWithModel`: the native grid keeps its beats and the model
/// votes on bar phase. `None` means the model had no usable opinion.
fn refine(raw: &Value, windows: &[(Result<Grid, String>, f64)]) -> Option<Value> {
    let native: Vec<f64> = raw["beats"].as_array()?.iter().filter_map(Value::as_f64).collect();
    let native_bpm = raw["bpm"].as_f64().unwrap_or(0.0);
    let native_confidence = raw["beatConfidence"].as_f64().unwrap_or(0.0);
    if native.len() < 8 || native_bpm <= 0.0 { return None; }
    let interval = 60.0 / native_bpm;
    let mut votes = [0usize; 4];
    let mut agreements = Vec::new();
    let (mut model_confidence, mut model_bpm) = (0.0f64, 0.0f64);
    for (found, offset) in windows {
        let Ok(found) = found else { continue };
        let bpm = found.bpm as f64;
        let ratio = bpm / native_bpm;
        let octaves = ratio.log2().round() as i32;
        // 3:2 and 4:3 readings are two views of one rhythm: no opinion either way.
        if (ratio / 2f64.powi(octaves) - 1.0).abs() > 0.06 { continue; }
        let shifted: Vec<f64> = found.beats.iter().map(|t| t + offset).collect();
        let fold = 1usize << octaves.unsigned_abs();
        let phase_distances: Vec<Vec<f64>> = if octaves > 0 && fold > 1 {
            metrical_phases(&shifted, 60.0 / bpm, fold).into_iter()
                .map(|phase| phase.iter().map(|t| (native[nearest_index(&native, *t)] - t).abs()).collect())
                .collect()
        } else if octaves < 0 && fold > 1 {
            metrical_phases(&native, interval, fold).into_iter()
                .map(|folded| shifted.iter().map(|t| nearest_distance(&folded, *t)).collect())
                .collect()
        } else {
            vec![shifted.iter().map(|t| (native[nearest_index(&native, *t)] - t).abs()).collect()]
        };
        let distances = phase_distances.into_iter()
            .reduce(|best, candidate| if median(&candidate) < median(&best) { candidate } else { best })
            .unwrap_or_default();
        let shared = if octaves < 0 { interval * fold as f64 } else { interval };
        let agreement = if distances.is_empty() { 1.0 } else { median(&distances) / shared };
        agreements.push(agreement);
        model_confidence = model_confidence.max(found.confidence);
        if model_bpm == 0.0 { model_bpm = bpm; }
        if agreement > 0.3 { continue; }
        for downbeat in &found.downbeats {
            votes[nearest_index(&native, downbeat + offset) % 4] += 1;
        }
    }
    let best = agreements.iter().copied().reduce(f64::min)?;
    if best > 0.3 {
        // Octave-compatible tempo on misaligned beats: one grid is on the offbeat.
        return Some(json!({"beatConfidence": native_confidence.min(0.45),
            "nativeBeatConfidence": native_confidence, "beatModelChecked": true,
            "beatModelAgreement": best, "beatModelBpm": model_bpm}));
    }
    let total: usize = votes.iter().sum();
    let winner = (0..4).max_by_key(|i| (votes[*i], std::cmp::Reverse(*i))).unwrap();
    let mut merged = json!({"nativeBeatConfidence": native_confidence,
        "beatConfidence": native_confidence.max(model_confidence), "beatModelChecked": true,
        "beatModelAgreement": best, "beatModelBpm": model_bpm});
    // Thin votes confirm the beats but cannot overturn the native bar offset.
    if total >= 4 && votes[winner] * 2 > total {
        merged["downbeats"] = json!(native.iter().enumerate()
            .filter(|(i, _)| i % 4 == winner).map(|(_, t)| *t).collect::<Vec<_>>());
    }
    Some(merged)
}

/// Whole-track analysis for one playback deck, shaped like V2's cached analysis.
pub fn track(track: &Track, grids: &[(Result<Grid, String>, f64)]) -> Result<Value, String> {
    let measured = WholeTrackAnalyzer::new().map_err(|e| e.to_string())?
        .analyze(&track.mono, ANALYSIS_RATE as f64, track.duration).map_err(|e| e.to_string())?;
    let mut value = to_json(&measured);
    let bpm = value["bpm"].as_f64().unwrap_or(0.0);
    if !(40.0..=240.0).contains(&bpm) {
        return Err("Native audio analysis returned an invalid BPM".into());
    }
    // Windows always arrive on the playback path, so the pass is recorded even on no opinion.
    let refined = refine(&value, grids).unwrap_or_else(|| json!({"beatModelChecked": true}));
    for (key, field) in refined.as_object().unwrap() { value[key] = field.clone(); }
    let tempo_confidence = value["beatConfidence"].clone();
    value["analyzedBpm"] = json!(bpm);
    value["analyzedTempoConfidence"] = tempo_confidence;
    value["analysisSource"] = json!("local-native");
    value["bpmSource"] = json!("local-native");
    Ok(value)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn grid(beats: Vec<f64>, downbeats: Vec<f64>, bpm: f32) -> Result<Grid, String> {
        Ok(Grid { beats, downbeats, bpm, confidence: 0.8 })
    }

    fn native() -> Value {
        let beats: Vec<f64> = (0..64).map(|i| i as f64 * 0.5).collect();
        let downbeats: Vec<f64> = beats.iter().step_by(4).copied().collect();
        json!({"bpm": 120.0, "beatConfidence": 0.6, "beats": beats, "downbeats": downbeats})
    }

    #[test]
    fn model_moves_bar_phase_but_keeps_native_beats() {
        // Model hears beat one on native index 1, in a window starting at 10 s.
        let beats: Vec<f64> = (0..20).map(|i| i as f64 * 0.5).collect();
        let downbeats: Vec<f64> = (0..5).map(|i| 0.5 + i as f64 * 2.0).collect();
        let merged = refine(&native(), &[(grid(beats, downbeats, 120.0), 10.0)]).unwrap();
        assert_eq!(merged["downbeats"][0], json!(0.5));
        assert_eq!(merged["beatConfidence"], json!(0.8));
        assert!(merged.get("beats").is_none());
    }

    #[test]
    fn double_time_model_is_compared_at_the_native_level() {
        let beats: Vec<f64> = (0..40).map(|i| i as f64 * 0.25).collect();
        let downbeats: Vec<f64> = (0..5).map(|i| i as f64 * 2.0).collect();
        let merged = refine(&native(), &[(grid(beats, downbeats, 240.0), 0.0)]).unwrap();
        assert!(merged["beatModelAgreement"].as_f64().unwrap() < 0.01);
        assert_eq!(merged["downbeats"][1], json!(2.0));
    }

    #[test]
    fn offbeat_model_demotes_and_metrical_ratio_has_no_opinion() {
        let offbeat: Vec<f64> = (0..20).map(|i| 0.25 + i as f64 * 0.5).collect();
        let merged = refine(&native(), &[(grid(offbeat, vec![0.25], 120.0), 0.0)]).unwrap();
        assert_eq!(merged["beatConfidence"], json!(0.45));
        assert!(merged.get("downbeats").is_none());
        let triplet: Vec<f64> = (0..30).map(|i| i as f64 / 3.0).collect();
        assert!(refine(&native(), &[(grid(triplet, vec![], 180.0), 0.0)]).is_none());
    }
}
