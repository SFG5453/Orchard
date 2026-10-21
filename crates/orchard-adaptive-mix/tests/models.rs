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
