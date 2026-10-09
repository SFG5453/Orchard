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

use earmark::AudioBuffer;
use orchard_adaptive_mix::planner;
use serde_json::Value;
use std::process::Command;

fn tone(hz: f64) -> AudioBuffer {
    let samples: Vec<f32> = (0..120 * 8000).map(|i|
        (0.2 * (std::f64::consts::TAU * hz * i as f64 / 8000.0).sin()) as f32).collect();
    AudioBuffer::new(vec![samples.clone(), samples], 8000).unwrap()
}

fn amplitude(samples: &[f32], hz: f64) -> f64 {
    let mut re = 0.0;
    let mut im = 0.0;
    for (i, value) in samples.iter().enumerate() {
        let phase = std::f64::consts::TAU * hz * i as f64 / 48000.0;
        re += *value as f64 * phase.cos(); im += *value as f64 * phase.sin();
    }
    2.0 * re.hypot(im) / samples.len() as f64
}

#[test]
fn shared_native_and_live_plans_render_both_songs_and_handoff_at_native_rate() {
    let reference = Command::new("node").arg(concat!(env!("CARGO_MANIFEST_DIR"),
        "/../orchard-transition-planner/tests/node-reference.mjs")).output().unwrap();
    assert!(reference.status.success());
    let cases: Vec<Value> = serde_json::from_slice(&reference.stdout).unwrap();
    let outgoing = tone(440.0);
    let incoming = tone(1760.0);
    for name in ["safe@0", "tempo-shift@0", "distant-tempo@0", "bass-swap@0"] {
        let case = cases.iter().find(|c| c["name"] == name && c["method"] == "native").unwrap();
        let mut engine = planner::engine().unwrap();
        let plan = planner::plan(&mut engine, &outgoing, &incoming, &case["input"], 0.0).unwrap().plan;
        if case["expected"]["ok"] == true {
            assert!((plan.outgoing_start - case["expected"]["transitionStart"].as_f64().unwrap()).abs() < 1e-6);
            assert!((plan.incoming_start - case["expected"]["incomingCueTime"].as_f64().unwrap()).abs() < 1e-6);
        }
        let rendered = engine.render(&outgoing, &incoming, &plan).unwrap();
        assert!((rendered.incoming_resume - plan.incoming_start - plan.duration).abs() < 1e-5);
        let audio = rendered.audio.channel(0);
        let mid = &audio[audio.len()/2 - 1200..audio.len()/2 + 1200];
        let out_hz = 440.0; // WSOLA preserves pitch while changing outgoing tempo.
        assert!(amplitude(mid, out_hz) > 0.025, "{name}: outgoing absent");
        assert!(amplitude(mid, 1760.0) > 0.04, "{name}: incoming absent in overlap");
        let tail = &audio[audio.len()-2400..];
        assert!(amplitude(tail, 1760.0) > 0.12, "{name}: incoming absent at handoff");
        assert!(audio.iter().all(|v| v.is_finite()));
    }
}

#[test]
fn a_glide_plan_hands_the_incoming_side_back_at_native_speed() {
    let reference = Command::new("node").arg(concat!(env!("CARGO_MANIFEST_DIR"),
        "/../orchard-transition-planner/tests/node-reference.mjs")).output().unwrap();
    assert!(reference.status.success());
    let cases: Vec<Value> = serde_json::from_slice(&reference.stdout).unwrap();
    let case = cases.iter().find(|c| c["name"] == "tempo-shift@0" && c["method"] == "native").unwrap();
    let mut input = case["input"].clone();
    input["tempoRamp"] = true.into();
    let (outgoing, incoming) = (tone(440.0), tone(1760.0));
    let mut engine = planner::engine().unwrap();
    let plan = planner::plan(&mut engine, &outgoing, &incoming, &input, 0.0).unwrap().plan;
    assert!(plan.tempo_ramp);
    assert!(plan.incoming_tempo_ratio < 1.0);

    let rendered = engine.render(&outgoing, &incoming, &plan).unwrap();
    // The native splice may shift the handoff by up to 256 frames to stay in phase.
    assert!((rendered.incoming_resume - plan.incoming_end()).abs() < 6e-3);
    // The host cues the incoming player one overlap before the handoff.
    assert!(rendered.incoming_resume - plan.duration >= 0.0);
    let audio = rendered.audio.channel(0);
    let tail = &audio[audio.len() - 2400..];
    assert!(amplitude(tail, 1760.0) > 0.12, "incoming absent at handoff");
    assert!(audio.iter().all(|v| v.is_finite()));
}
