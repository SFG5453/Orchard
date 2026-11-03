/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

//! Opt-in model test: cargo test -p orchard-adaptive-mix --test models -- --ignored
use orchard_adaptive_mix::models::{CPU_BEAT, Models, VOCAL};

const SILENCE: &str = "The beat model found fewer than eight beats";

#[test]
#[ignore = "requires ONNX models; the first load uses a WebGPU device when one exists"]
fn nirovi_runs_and_silence_does_not_invent_beats() {
    let models = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../models");
    check_models(Models::new(&models).unwrap());
}

#[test]
#[ignore = "requires ONNX models"]
fn cpu_models_load_from_files_and_run() {
    let models = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../models");
    check_models(Models::from_files(&models.join(CPU_BEAT), &models.join(VOCAL), 2).unwrap());
}

fn check_models(mut models: Models) {
    let silence = vec![0.0f32; 44100 * 5];
    assert_eq!(models.beats(&silence).unwrap_err(), SILENCE);
    // A tone in the presence band, long enough for two model slices.
    let tone: Vec<f32> = (0..44100 * 25).map(|i| 0.3 * (i as f32 * 0.0627).sin()).collect();
    let stereo = earmark::AudioBuffer::new(vec![tone.clone(), tone], 44100).unwrap();
    let curve = models.vocals(&stereo).unwrap();
    assert!(curve.len() > 960 && curve.iter().all(|v| (0.0..=1.0).contains(v)));
}

#[test]
#[ignore = "requires ONNX models"]
fn a_stored_deck_matches_a_fresh_measurement() {
    use orchard_adaptive_mix::{analysis, evidence, prepare, song};
    let models = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../models");
    let mut models = Models::from_files(&models.join(CPU_BEAT), &models.join(VOCAL), 2).unwrap();
    // Decaying 120 BPM clicks, accented on each bar's first beat.
    let rate = 44_100usize;
    let mut stereo = vec![0.0f32; rate * 90 * 2];
    for beat in 0..180 {
        let gain = if beat % 4 == 0 { 0.9 } else { 0.5 };
        for i in 0..2_000 {
            let value = gain * (-(i as f32) / 300.0).exp() * (i as f32 * 0.3).sin();
            let frame = beat * rate / 2 + i;
            stereo[2 * frame] = value;
            stereo[2 * frame + 1] = value;
        }
    }
    let song = song::song_from(&stereo, rate as u32, song::Windows::BOTH).unwrap();
    let fresh = |models: &mut Models, span| {
        let grids: Vec<_> = song.track.beat_windows.iter().map(|(audio, offset)| (models.beats(audio), *offset)).collect();
        let mut value = analysis::track(&song.track, &grids, span).unwrap();
        value["bassCurve"] = evidence::bass_curve(&song.track.mono, song::ANALYSIS_RATE);
        value["subBassRatio"] = evidence::sub_bass_ratio(&song.track.mono, song::ANALYSIS_RATE).into();
        value
    };
    let head = (0.0, 30.0);
    let tail = (60.0, f64::INFINITY);
    let (value, stored) = prepare::deck(Some(&mut models), &song, head, None, None).unwrap();
    assert_eq!(value, fresh(&mut models, head));
    // Hosts keep the evidence as JSON text; the second deck reads it and only cuts a new chroma curve.
    let stored: serde_json::Value = serde_json::from_str(&stored.unwrap().to_string()).unwrap();
    let (value, again) = prepare::deck(Some(&mut models), &song, tail, None, Some(&stored)).unwrap();
    assert_eq!(value, fresh(&mut models, tail));
    assert!(again.is_none());
}
