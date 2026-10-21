/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

//! Opt-in model test: cargo test -p orchard-adaptive-mix --test models -- --ignored
use orchard_adaptive_mix::models::{CPU_BEAT, Models, VOCAL};

const SILENCE: &str = "The beat model found fewer than eight beats";

#[test]
#[ignore = "requires ONNX models; the first load uses a WebGPU device when one exists"]
fn nirovi_runs_and_silence_does_not_invent_beats() {
    let models = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../models");
    let silence = vec![0.0f32; 44100 * 5];
    let mut desktop = Models::new(&models).unwrap();
    assert_eq!(desktop.beats(&silence).unwrap_err(), SILENCE);
    let beat = std::fs::read(models.join(CPU_BEAT)).unwrap();
    let vocal = std::fs::read(models.join(VOCAL)).unwrap();
    let mut cpu = Models::from_memory(&beat, &vocal, 2).unwrap();
    assert_eq!(cpu.beats(&silence).unwrap_err(), SILENCE);
    // A tone in the presence band, long enough for two model slices.
    let tone: Vec<f32> = (0..44100 * 25).map(|i| 0.3 * (i as f32 * 0.0627).sin()).collect();
    let stereo = earmark::AudioBuffer::new(vec![tone.clone(), tone], 44100).unwrap();
    for models in [&mut desktop, &mut cpu] {
        let curve = models.vocals(&stereo).unwrap();
        assert!(curve.len() > 960 && curve.iter().all(|v| (0.0..=1.0).contains(v)));
    }
}
