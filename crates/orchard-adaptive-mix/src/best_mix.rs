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

//! Best Mix queue analysis and ordering. The queue worker never initializes GPU models.
use crate::{evidence, song::{ANALYSIS_RATE, Track, WINDOW}};
use earmark::{WholeTrackAnalysis, WholeTrackAnalyzer};
use serde_json::{Value, json};
use std::{sync::mpsc, thread};

/// One mix edge: whole-song tempo, key, loudness and 808 weight, with timed evidence cut to `span`.
fn edge(value: &WholeTrackAnalysis, sub_bass: Option<f64>, span: (f64, f64)) -> Value {
    let within = |time: f64| time >= span.0 && time <= span.1;
    json!({
        "duration": value.duration, "bpm": value.bpm, "beatConfidence": value.beat_confidence,
        "tempoConfidence": value.beat_confidence, "key": value.key,
        "keyConfidence": value.key_confidence, "chroma": value.chroma,
        "loudnessLufs": value.loudness_lufs,
        // Whole-song production weight; the planner's style-clash gate compares it.
        "subBassRatio": sub_bass,
        // Spectral estimate, same as playback analysis. The planner treats it as risk only.
        "vocalProbability": value.vocal_probability,
        "beats": value.beats.iter().copied().filter(|t| within(*t)).collect::<Vec<_>>(),
        "downbeats": value.downbeats.iter().copied().filter(|t| within(*t)).collect::<Vec<_>>(),
        "beatInterval": value.beat_interval,
        "audibleStartTime": value.audible_start_time,
        "contentEndTime": value.content_end_time,
        "pickupConfidence": value.pickup_confidence,
        "energyCurve": value.energy_curve.iter().filter(|p| within(p.time))
            .map(|p| json!({"time":p.time,"energy":p.energy})).collect::<Vec<_>>(),
        "transitionFeatureFrames": value.transition_feature_frames.iter().filter(|f| within(f.time)).map(|f| json!({
            "time": f.time, "energy": f.energy, "low": f.low, "mid": f.mid,
            "high": f.high, "vocal": f.vocal, "novelty": f.novelty,
            "transientDensity": f.transient_density, "stability": f.stability
        })).collect::<Vec<_>>(),
        "structuralBoundaryCandidates": value.structural_boundary_candidates.iter().filter(|b| within(b.time)).map(|b| json!({
            "time": b.time, "observedTime": b.observed_time,
            "confidence": b.confidence, "source": b.source, "noveltyPeak": b.novelty_peak,
            "energyDelta": b.energy_delta, "lowDelta": b.low_delta,
            "vocalDelta": b.vocal_delta, "stabilityBefore": b.stability_before,
            "stabilityAfter": b.stability_after, "downbeatDistance": b.downbeat_distance
        })).collect::<Vec<_>>()
    })
}

/// Whole-song analysis of a downloaded saver file.
#[cfg(feature = "desktop")]
pub fn analyze(path: &str, duration: f64) -> Result<Value, String> {
    analyze_track(&crate::decode::local_song(path)?.track, duration)
}

/// Whole-song analysis, as V2 measured it. Windows alone misread tempo by 3:2 and
/// flatten key detection, and one bad intro should not DJ the entire queue.
/// Returns full `head`/`tail` edges for planning and compact summaries for the shortlist.
pub fn analyze_track(track: &Track, duration: f64) -> Result<Value, String> {
    if !duration.is_finite() || !(2.0..=7200.0).contains(&duration) {
        return Err("Invalid song duration".into());
    }
    // Saver files are complete; a large gap means a truncated or mismatched download.
    if (track.duration - duration).abs() > duration.max(10.0) * 0.1 {
        return Err("Analysis file does not match the song".into());
    }
    let value = WholeTrackAnalyzer::new().map_err(|e| e.to_string())?
        .analyze(&track.mono, ANALYSIS_RATE as f64, track.duration)
        .map_err(|e| e.to_string())?;
    let sub_bass = evidence::sub_bass_ratio(&track.mono, ANALYSIS_RATE);
    let end = value.duration;
    let head = edge(&value, sub_bass, (0.0, end.min(WINDOW)));
    let tail = edge(&value, sub_bass, ((end - WINDOW).max(0.0), end));
    Ok(json!({"headSummary": summary(&head, false), "tailSummary": summary(&tail, true),
              "head": head, "tail": tail}))
}

/// Shortlist fields of an edge; the energy curve shrinks to the mean the shortlist reads.
fn summary(edge: &Value, tail: bool) -> Value {
    let mut compact = serde_json::Map::new();
    for key in ["duration", "bpm", "beatConfidence", "tempoConfidence", "key", "keyConfidence",
                "loudnessLufs", "vocalProbability", "subBassRatio"] {
        compact.insert(key.into(), edge[key].clone());
    }
    if let Some(mean) = energy(edge, tail) {
        compact.insert("energyCurve".into(), json!([{"energy": mean}]));
    }
    Value::Object(compact)
}

/// Matches STYLE_CLASH_CONTRAST in transitionFlow.js.
const STYLE_CLASH: f64 = 0.9;

fn number(v: &Value, key: &str) -> f64 {
    v[key].as_f64().unwrap_or(0.0)
}
fn key_index(key: &str) -> Option<(i32, bool)> {
    let mut parts = key.split_whitespace();
    let root = parts.next()?;
    let index = match root {
        "C" => 0,
        "C♯" | "D♭" => 1,
        "D" => 2,
        "D♯" | "E♭" => 3,
        "E" => 4,
        "F" => 5,
        "F♯" | "G♭" => 6,
        "G" => 7,
        "G♯" | "A♭" => 8,
        "A" => 9,
        "A♯" | "B♭" => 10,
        "B" => 11,
        _ => return None,
    };
    Some((index, parts.next() == Some("major")))
}
fn energy(edge: &Value, from_end: bool) -> Option<f64> {
    let points = edge["energyCurve"].as_array()?;
    if points.is_empty() {
        return None;
    }
    let count = ((points.len() as f64 * 0.08).ceil() as usize)
        .clamp(2, 6)
        .min(points.len());
    let slice = if from_end {
        &points[points.len() - count..]
    } else {
        &points[..count]
    };
    Some(slice.iter().map(|p| number(p, "energy")).sum::<f64>() / count as f64)
}
fn legacy_cost(left: &Value, right: &Value) -> Option<f64> {
    let mut weighted = 0.0;
    let mut weight = 0.0;
    let (a, b) = (number(left, "bpm"), number(right, "bpm"));
    if a > 0.0 && b > 0.0 {
        let mut ratio = b / a;
        while ratio > 1.5 {
            ratio /= 2.0;
        }
        while ratio < 0.67 {
            ratio *= 2.0;
        }
        let w = 4.0
            * (number(left, "beatConfidence").max(0.15)
                * number(right, "beatConfidence").max(0.15))
            .sqrt();
        weighted += (ratio.log2().abs() / 1.2_f64.log2()).min(1.5) * w;
        weight += w;
    }
    if let (Some((a, am)), Some((b, bm))) = (
        key_index(left["key"].as_str().unwrap_or("")),
        key_index(right["key"].as_str().unwrap_or("")),
    ) {
        let circle = ((a * 7 - b * 7).rem_euclid(12)).min((b * 7 - a * 7).rem_euclid(12));
        let harmonic = if am == bm {
            match circle {
                0 => 0.0,
                1 => 0.12,
                2 => 0.38,
                _ => (0.55 + circle as f64 * 0.09).min(1.0),
            }
        } else if (am && b == (a + 9) % 12) || (bm && a == (b + 9) % 12) {
            0.05
        } else if a == b {
            0.22
        } else {
            let pitch_distance = (a - b).rem_euclid(12).min((b - a).rem_euclid(12));
            (0.35 + pitch_distance as f64 / 10.0).min(1.0)
        };
        let w = 2.4
            * (number(left, "keyConfidence").max(0.15) * number(right, "keyConfidence").max(0.15))
                .sqrt();
        weighted += harmonic * w;
        weight += w;
    }
    if let (Some(a), Some(b)) = (energy(left, true), energy(right, false)) {
        weighted += ((a - b).abs() / 1.5).min(1.0) * 0.45;
        weight += 0.45;
    }
    if let (Some(a), Some(b)) = (
        left["loudnessLufs"].as_f64(),
        right["loudnessLufs"].as_f64(),
    ) && a > -69.0
        && b > -69.0
    {
        weighted += ((a - b).abs() / 12.0).min(1.0) * 0.55;
        weight += 0.55;
    }
    // Penalize only when both edges are likely sung. Two hooks, one mic.
    let vocal = |edge: &Value| edge["vocalProbability"].as_f64().filter(|v| *v >= 0.0);
    if let (Some(a), Some(b)) = (vocal(left), vocal(right)) {
        weighted += ((a - 0.5) * 2.0).clamp(0.0, 1.0) * ((b - 0.5) * 2.0).clamp(0.0, 1.0) * 0.35;
        weight += 0.35;
    }
    // Playback never blends a clash, so it ranks behind every pair that can blend.
    // Boom-bap and trap can share a queue, just not a crossfade.
    let mut clash = 0.0;
    if let (Some(a), Some(b)) = (left["subBassRatio"].as_f64(), right["subBassRatio"].as_f64()) {
        let contrast = (a - b).abs();
        weighted += (contrast / STYLE_CLASH).min(1.0) * 1.2;
        weight += 1.2;
        if contrast >= STYLE_CLASH {
            clash = 2.0;
        }
    }
    (weight > 0.0).then_some(weighted / weight + clash)
}
fn has_musical(edge: &Value) -> bool {
    number(edge, "bpm") > 0.0 || key_index(edge["key"].as_str().unwrap_or("")).is_some()
}
const FINALISTS: usize = 3;

/// One planner session per thread. A step's finalists are independent, so they plan at once.
struct PlannerPool {
    jobs: Vec<mpsc::Sender<(usize, Value)>>,
    results: mpsc::Receiver<(usize, Result<Value, String>)>,
    threads: Vec<thread::JoinHandle<()>>,
}
impl PlannerPool {
    fn new(size: usize) -> Option<Self> {
        let (result_tx, results) = mpsc::channel();
        let (ready_tx, ready) = mpsc::channel();
        let mut senders = Vec::new();
        let mut threads = Vec::new();
        for _ in 0..size {
            let (job_tx, job_rx) = mpsc::channel::<(usize, Value)>();
            let (result_tx, ready_tx) = (result_tx.clone(), ready_tx.clone());
            // QuickJS allows 2 MiB of stack; Windows threads default to 1 MiB.
            let spawned = thread::Builder::new().name("best-mix-planner".into())
                .stack_size(8 * 1024 * 1024)
                .spawn(move || {
                    let Ok(mut planner) = orchard_transition_planner::PlannerSession::new() else {
                        let _ = ready_tx.send(false);
                        return;
                    };
                    let _ = ready_tx.send(true);
                    for (index, input) in job_rx {
                        if result_tx.send((index, planner.invoke("native", &input))).is_err() { break; }
                    }
                });
            let Ok(handle) = spawned else { continue };
            senders.push(job_tx);
            threads.push(handle);
        }
        drop(ready_tx);
        // Keep only workers whose session started; a failed one has already exited.
        let started: Vec<bool> = senders.iter().map(|_| ready.recv().unwrap_or(false)).collect();
        let jobs: Vec<_> = senders.into_iter().zip(started)
            .filter_map(|(sender, ok)| ok.then_some(sender)).collect();
        (!jobs.is_empty()).then_some(Self { jobs, results, threads })
    }
    fn plan(&self, inputs: Vec<Value>) -> Vec<Result<Value, String>> {
        let count = inputs.len();
        let mut output: Vec<Result<Value, String>> =
            (0..count).map(|_| Err("Planner worker stopped".into())).collect();
        let mut sent = 0;
        for (index, input) in inputs.into_iter().enumerate() {
            if self.jobs[index % self.jobs.len()].send((index, input)).is_ok() { sent += 1; }
        }
        for _ in 0..sent {
            let Ok((index, result)) = self.results.recv() else { break };
            output[index] = result;
        }
        output
    }
}
impl Drop for PlannerPool {
    fn drop(&mut self) {
        self.jobs.clear();
        for handle in self.threads.drain(..) { let _ = handle.join(); }
    }
}

/// Beat This confidence on a grid it agrees with (beats::grid caps it here). Playback
/// lifts Earmark's grid to this whenever Beat This agrees with it.
const CONFIRMED_BEAT_CONFIDENCE: f64 = 0.95;

/// Plans each pair the way playback will: tempo glide on, beats confirmed. Sorting cannot
/// run Beat This, so it assumes the confirmation playback gets for most songs.
fn plan_input(left: &Value, right: &Value) -> Value {
    let confirmed = |edge: &Value| {
        let mut edge = edge.clone();
        edge["beatConfidence"] = json!(number(&edge, "beatConfidence").max(CONFIRMED_BEAT_CONFIDENCE));
        edge
    };
    json!({"analysis":confirmed(left),"nextAnalysis":confirmed(right),
        "duration":number(left,"duration"),"nextDuration":number(right,"duration"),
        "tempoRamp":true})
}
fn pair_cost(plan: Result<Value, String>, prefilter: f64) -> f64 {
    let Ok(plan) = plan else {
        return 40.0 + prefilter;
    };
    let pair = &plan["pairPlan"];
    let class = match pair["transitionClass"]
        .as_str()
        .unwrap_or("normal_boundary")
    {
        "full_beatmatched" => 0.0,
        "conservative_beatmatched" => 1.0,
        "silence_trim" => 3.0,
        _ => 4.0,
    };
    class * 10.0
        + (1.0 - number(pair, "confidence").clamp(0.0, 1.0)) * 2.0
        + (1.0 - number(&pair["diagnostics"]["selected"], "quality").clamp(0.0, 1.0))
        + prefilter * 0.01
}
/// Sort from compact summaries; fetch full edge analyses only for shortlisted pairs.
pub fn sort_lazy<F>(
    summaries: &[Value],
    initial: &Value,
    mut fetch_pair: F,
) -> Result<Vec<usize>, String>
where
    F: FnMut(Option<usize>, usize) -> Result<(Value, Value), String>,
{
    let planner = PlannerPool::new(FINALISTS);
    let mut output = Vec::with_capacity(summaries.len());
    let mut position = 0;
    let mut previous = initial;
    let mut previous_index = None;
    while position < summaries.len() {
        if !has_musical(&summaries[position]["head"]) {
            output.push(position);
            previous = &Value::Null;
            previous_index = None;
            position += 1;
            continue;
        }
        let start = position;
        while position < summaries.len() && has_musical(&summaries[position]["head"]) {
            position += 1;
        }
        let mut remaining: Vec<usize> = (start..position).collect();
        while !remaining.is_empty() {
            if !has_musical(previous) {
                let selected = remaining.remove(0);
                output.push(selected);
                previous = &summaries[selected]["tail"];
                previous_index = Some(selected);
                continue;
            }
            let mut candidates: Vec<_> = remaining
                .iter()
                .copied()
                .map(|i| {
                    (
                        i,
                        legacy_cost(previous, &summaries[i]["head"]).unwrap_or(f64::INFINITY),
                    )
                })
                .collect();
            candidates.sort_by(|a, b| a.1.total_cmp(&b.1).then(a.0.cmp(&b.0)));
            // One planner context per finalist serves the whole sort. Each retains
            // only two pair results, so memory stays bounded. Three decks, one booth.
            let finalists = &candidates[..candidates.len().min(FINALISTS)];
            let evaluations: Vec<_> = if let Some(planner) = planner.as_ref() {
                // Fetches stay sequential: the host answers one pair request at a time.
                let mut inputs = Vec::with_capacity(finalists.len());
                for &(i, _) in finalists {
                    let (left, right) = fetch_pair(previous_index, i)?;
                    inputs.push(plan_input(&left, &right));
                }
                finalists.iter().zip(planner.plan(inputs))
                    .map(|(&(i, prefilter), plan)| (i, pair_cost(plan, prefilter), prefilter))
                    .collect()
            } else {
                finalists.iter().map(|&(i, prefilter)| (i, prefilter, prefilter)).collect()
            };
            let selected = evaluations
                .into_iter()
                .min_by(|a, b| {
                    a.1.total_cmp(&b.1)
                        .then(a.2.total_cmp(&b.2))
                        .then(a.0.cmp(&b.0))
                })
                .map(|x| x.0)
                .unwrap_or(remaining[0]);
            remaining.retain(|&i| i != selected);
            output.push(selected);
            previous = &summaries[selected]["tail"];
            previous_index = Some(selected);
        }
    }
    Ok(output)
}

pub fn sort(features: &[Value], initial: &Value) -> Vec<usize> {
    sort_lazy(features, initial, |left, right| {
        let left = left
            .map(|i| features[i]["tail"].clone())
            .unwrap_or_else(|| initial.clone());
        Ok((left, features[right]["head"].clone()))
    })
    .unwrap_or_else(|_| (0..features.len()).collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn half_time_and_relative_major_minor_are_compatible() {
        let left = json!({"bpm": 140.0, "key": "C major", "beatConfidence": 0.8,
            "keyConfidence": 0.8});
        let good = json!({"bpm": 70.0, "key": "A minor", "beatConfidence": 0.8,
            "keyConfidence": 0.8});
        let bad = json!({"bpm": 95.0, "key": "F♯ major", "beatConfidence": 0.8,
            "keyConfidence": 0.8});
        assert!(legacy_cost(&left, &good).unwrap() < legacy_cost(&left, &bad).unwrap());
    }

    #[test]
    fn overlapping_vocals_cost_more() {
        let edge = |vocal: f64| json!({"bpm": 120.0, "beatConfidence": 0.8, "vocalProbability": vocal});
        let sung = edge(0.9);
        assert!(legacy_cost(&sung, &edge(0.2)).unwrap() < legacy_cost(&sung, &edge(0.9)).unwrap());
    }

    #[test]
    fn style_clash_ranks_behind_a_looser_tempo_match() {
        let edge = |bpm: f64, sub: f64| json!({"bpm": bpm, "beatConfidence": 0.8, "subBassRatio": sub});
        let trap = edge(140.0, 0.4);
        let boom_bap = edge(140.0, -0.6);
        let drifting_trap = edge(150.0, 0.3);
        assert!(legacy_cost(&trap, &drifting_trap).unwrap() < legacy_cost(&trap, &boom_bap).unwrap());
        // Unmeasured songs keep the tempo/key/energy cost.
        assert_eq!(legacy_cost(&trap, &json!({"bpm": 140.0, "beatConfidence": 0.8})), Some(0.0));
    }

    #[test]
    fn finalists_plan_like_playback_after_beat_this() {
        let input = plan_input(&json!({"duration": 200.0, "beatConfidence": 0.38}),
                               &json!({"duration": 180.0, "beatConfidence": 0.97}));
        assert_eq!(input["analysis"]["beatConfidence"], json!(CONFIRMED_BEAT_CONFIDENCE));
        assert_eq!(input["nextAnalysis"]["beatConfidence"], json!(0.97));
        assert_eq!(input["tempoRamp"], json!(true));
    }

    #[test]
    fn unavailable_analysis_keeps_its_queue_position() {
        let known = json!({"head":{"bpm":120.0},"tail":{"bpm":120.0}});
        let missing = Value::Null;
        assert_eq!(
            sort(&[known.clone(), missing, known], &Value::Null),
            vec![0, 1, 2]
        );
    }

    #[test]
    fn lazy_sort_reads_only_finalist_pairs() {
        let edge = json!({"duration":150.0,"bpm":120.0,"key":"C major",
            "beatConfidence":0.8,"keyConfidence":0.8,
            "energyCurve":[{"time":0.0,"energy":0.5}]});
        let summaries = vec![json!({"head":edge,"tail":edge}); 8];
        let mut requests = 0;
        let order = sort_lazy(&summaries, &edge, |_, _| {
            requests += 1;
            Ok((edge.clone(), edge.clone()))
        })
        .unwrap();
        assert_eq!(order.len(), summaries.len());
        assert!(requests > 0 && requests <= 3 * summaries.len());
    }
}
