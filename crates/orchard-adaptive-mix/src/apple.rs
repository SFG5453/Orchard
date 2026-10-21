/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

//! Apple Music's own audio analysis (amp-api `audio-analysis` attributes) in the shapes the
//! beat model and UMX passes produce, for songs played from Apple Music.
use crate::{beats::{Grid, median}, evidence::{STEP, VOCAL_FRAME}, song::WINDOW};
use serde_json::{Value, json};

/// UMX vocal share for no voice, then Apple's very-low to very-high, fitted on 88 replay pairs.
/// Medium lands on the planner's active-voice threshold (share 0.42, activity 0.6).
const VOCAL_SHARE: [f64; 6] = [0.15, 0.2, 0.3, 0.42, 0.5, 0.58];
/// Strength from which a passage counts as sung.
const SUNG: usize = 3;
/// Apple's grids are whole-track and regular; Nirovi's confidence formula tops out here too.
const MAX_CONFIDENCE: f64 = 0.95;
/// Krumhansl-Kessler key profiles, tonic first.
const MAJOR: [f64; 12] = [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88];
const MINOR: [f64; 12] = [6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17];
/// Earmark's key spelling, so Apple keys read like measured ones.
const NAMES: [&str; 12] = ["C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B"];
/// Seconds of an edge a Best Mix summary describes, as `best_mix::MIX_SPAN`.
const MIX_SPAN: f64 = 30.0;

fn seconds(values: &Value) -> Vec<f64> {
    let mut times: Vec<f64> = values.as_array().map(Vec::as_slice).unwrap_or_default().iter()
        .filter_map(Value::as_f64).filter(|t| t.is_finite() && *t >= 0.0).map(|t| t / 1000.0).collect();
    times.sort_by(f64::total_cmp);
    times.dedup();
    times
}

fn interval(beats: &[f64]) -> Option<f64> {
    let gaps: Vec<f64> = beats.windows(2).map(|w| w[1] - w[0]).collect();
    let rough = median(&gaps);
    let kept: Vec<f64> = gaps.iter().copied().filter(|g| (g - rough).abs() <= rough * 0.2).collect();
    let interval = median(if kept.len() >= 4 { &kept } else { &gaps });
    (interval > 0.0).then_some(interval)
}

/// Apple's beat and bar grid as a whole-track beat model opinion, or None when unusable.
pub fn grid(analysis: &Value) -> Option<Grid> {
    let beats = seconds(&analysis["beats"]["beatsInMilliseconds"]);
    if beats.len() < 8 { return None; }
    let interval = interval(&beats)?;
    let bpm = 60.0 / interval;
    if !(40.0..=220.0).contains(&bpm) { return None; }
    let mut downbeats: Vec<f64> = seconds(&analysis["beats"]["barsInMilliseconds"]).iter().map(|bar| {
        let index = beats.partition_point(|t| t < bar).min(beats.len() - 1);
        if index > 0 && bar - beats[index - 1] < beats[index] - bar { beats[index - 1] } else { beats[index] }
    }).collect();
    downbeats.dedup();
    let gaps: Vec<f64> = beats.windows(2).map(|w| w[1] - w[0]).collect();
    let regular = gaps.iter().filter(|g| (**g - interval).abs() <= interval * 0.1).count() as f64 / gaps.len() as f64;
    Some(Grid { beats, downbeats, bpm: bpm as f32, confidence: (0.35 + 0.6 * regular).min(MAX_CONFIDENCE) })
}

fn level(strength: &str) -> usize {
    match strength {
        "very-low" => 1,
        "low" => 2,
        "medium" => 3,
        "high" => 4,
        "very-high" => 5,
        _ => 0,
    }
}

/// (start, end, level) for each vocal span, in seconds.
fn spans(analysis: &Value) -> Vec<(f64, f64, usize)> {
    analysis["vocalActivity"].as_array().map(Vec::as_slice).unwrap_or_default().iter().filter_map(|span| {
        let start = span["startInMilliseconds"].as_f64()? / 1000.0;
        let end = span["endInMilliseconds"].as_f64()? / 1000.0;
        (end > start).then(|| (start, end, level(span["strength"].as_str().unwrap_or(""))))
    }).collect()
}

fn level_at(spans: &[(f64, f64, usize)], time: f64) -> usize {
    spans.iter().filter(|(start, end, _)| time >= *start && time < *end).map(|span| span.2).max().unwrap_or(0)
}

/// Vocal share per UMX frame over `duration` seconds from track time `start`, as
/// `evidence::vocal_frames` measures a render window. None without vocal activity data.
pub fn vocal_frames(analysis: &Value, start: f64, duration: f64) -> Option<Vec<f64>> {
    analysis["vocalActivity"].as_array()?;
    let spans = spans(analysis);
    let count = (duration.max(0.0) / VOCAL_FRAME).floor() as usize + 1;
    Some((0..count).map(|index| VOCAL_SHARE[level_at(&spans, start + index as f64 * VOCAL_FRAME)]).collect())
}

fn key(analysis: &Value) -> Option<(usize, bool)> {
    let main = &analysis["key"]["main"];
    let tonic = main["tonic"].as_str()?;
    let mut letters = tonic.chars();
    let base = match letters.next()?.to_ascii_uppercase() {
        'C' => 0, 'D' => 2, 'E' => 4, 'F' => 5, 'G' => 7, 'A' => 9, 'B' => 11,
        _ => return None,
    };
    let shift: i32 = letters.map(|c| match c { '#' | '♯' => 1, 'b' | '♭' => -1, _ => 0 }).sum();
    let root = (base + shift).rem_euclid(12) as usize;
    Some((root, main["mode"].as_str() != Some("minor")))
}

/// One Best Mix summary from Apple's section values; `tail` reads the song's ending.
fn summary(analysis: &Value, duration: f64, tail: bool, beats: &[f64], confidence: f64, spans: &[(f64, f64, usize)]) -> Value {
    let section = if tail { "ending" } else { "beginning" };
    let span = if tail { ((duration - WINDOW).max(0.0), duration) } else { (0.0, duration.min(WINDOW)) };
    let edge: Vec<f64> = beats.iter().copied().filter(|t| *t >= span.0 && *t <= span.1).collect();
    let bpm = interval(&edge).filter(|_| edge.len() >= 8).map(|i| 60.0 / i)
        .or_else(|| analysis["bpm"][section].as_f64()).filter(|bpm| *bpm > 0.0);
    let (key, chroma) = match key(analysis) {
        Some((root, major)) => {
            let profile = if major { MAJOR } else { MINOR };
            let total: f64 = profile.iter().sum();
            let chroma: Vec<f64> = (0..12).map(|pitch| ((profile[(pitch + 12 - root) % 12] / total) * 1000.0).round() / 1000.0).collect();
            (json!(format!("{} {}", NAMES[root], if major { "major" } else { "minor" })), json!(chroma))
        }
        None => (Value::Null, Value::Null),
    };
    // Share of the mixable part sung at medium or above, sampled on the planner's curve step.
    let mix = if tail { (duration - MIX_SPAN).max(0.0)..duration } else { 0.0..duration.min(MIX_SPAN) };
    let samples: Vec<usize> = (0..).map(|i| mix.start + i as f64 * STEP).take_while(|t| *t < mix.end)
        .map(|t| level_at(spans, t)).collect();
    let sung = samples.iter().filter(|level| **level >= SUNG).count() as f64 / samples.len().max(1) as f64;
    let energy = analysis["energy"][section].as_f64();
    json!({
        "duration": duration, "bpm": bpm, "beatConfidence": confidence, "tempoConfidence": confidence,
        "key": key, "keyConfidence": Value::Null, "chroma": chroma,
        "loudnessLufs": analysis["loudness"][section]["value"].as_f64(),
        "vocalProbability": (sung * 1000.0).round() / 1000.0,
        "subBassRatio": Value::Null,
        "energyCurve": energy.map_or(Value::Null, |energy| json!([{"energy": energy}])),
        "beatlessEdge": 0.0,
        // No spectra to plan a pair from; the sort keeps predicted costs for these edges.
        "plannable": false,
    })
}

/// Best Mix features from Apple's analysis alone, shaped like `best_mix::analyze_track`.
/// No audio is downloaded, so the edges are the summaries themselves.
pub fn best_mix(analysis: &Value, duration: f64) -> Result<Value, String> {
    if !duration.is_finite() || !(2.0..=7200.0).contains(&duration) {
        return Err("Invalid song duration".into());
    }
    let grid = grid(analysis).ok_or("Apple Music has no usable beat grid for this song")?;
    let spans = spans(analysis);
    let head = summary(analysis, duration, false, &grid.beats, grid.confidence, &spans);
    let tail = summary(analysis, duration, true, &grid.beats, grid.confidence, &spans);
    Ok(json!({"headSummary": head, "tailSummary": tail, "head": head, "tail": tail}))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn analysis() -> Value {
        let beats: Vec<f64> = (0..400).map(|i| 250.0 + i as f64 * 500.0).collect();
        let bars: Vec<f64> = beats.iter().skip(1).step_by(4).map(|t| t + 20.0).collect();
        json!({
            "beats": {"beatsInMilliseconds": beats, "barsInMilliseconds": bars},
            "bpm": {"beginning": 120, "ending": 120, "main": 120},
            "key": {"main": {"tonic": "Bb", "mode": "minor"}},
            "loudness": {"beginning": {"value": -9.0}, "ending": {"value": -8.0}},
            "energy": {"beginning": 0.4, "ending": 0.7},
            "vocalActivity": [
                {"startInMilliseconds": 10_000, "endInMilliseconds": 20_000, "strength": "high"},
                {"startInMilliseconds": 185_000, "endInMilliseconds": 199_000, "strength": "very-low"},
            ],
        })
    }

    #[test]
    fn grid_snaps_bars_onto_beats() {
        let grid = grid(&analysis()).unwrap();
        assert!((grid.bpm - 120.0).abs() < 0.01);
        assert_eq!(grid.downbeats[0], 0.75);
        assert!(grid.downbeats.iter().all(|bar| grid.beats.contains(bar)));
        assert_eq!(grid.confidence, MAX_CONFIDENCE);
        assert!(super::grid(&json!({"beats": {"beatsInMilliseconds": [0, 500]}})).is_none());
    }

    #[test]
    fn vocal_frames_follow_strength_on_the_umx_clock() {
        let frames = vocal_frames(&analysis(), 9.0, 2.0).unwrap();
        assert_eq!(frames.len(), (2.0 / VOCAL_FRAME) as usize + 1);
        assert_eq!(frames[0], VOCAL_SHARE[0]);
        assert_eq!(*frames.last().unwrap(), VOCAL_SHARE[4]);
        assert!(vocal_frames(&json!({}), 0.0, 1.0).is_none());
    }

    #[test]
    fn best_mix_summaries_read_each_edge() {
        let features = best_mix(&analysis(), 200.0).unwrap();
        let (head, tail) = (&features["headSummary"], &features["tailSummary"]);
        assert_eq!(head["key"], json!("B♭ minor"));
        assert_eq!(head["chroma"].as_array().unwrap().len(), 12);
        assert!((head["bpm"].as_f64().unwrap() - 120.0).abs() < 0.01);
        assert_eq!(head["loudnessLufs"], json!(-9.0));
        assert_eq!(tail["energyCurve"][0]["energy"], json!(0.7));
        // Ten of the first thirty seconds are sung at high; the tail's whisper does not count.
        assert!((head["vocalProbability"].as_f64().unwrap() - 1.0 / 3.0).abs() < 0.01);
        assert_eq!(tail["vocalProbability"], json!(0.0));
        assert_eq!(tail["plannable"], json!(false));
    }
}
