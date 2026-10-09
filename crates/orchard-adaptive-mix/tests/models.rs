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

//! Opt-in hardware test: cargo test -p orchard-adaptive-mix --test models -- --ignored
use orchard_adaptive_mix::models::Models;

#[test]
#[ignore = "requires ONNX models and a hardware GPU; Beat This has no CPU fallback"]
fn beat_this_runs_on_webgpu_and_silence_does_not_invent_beats() {
    let models = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../models");
    let mut models = Models::new(&models).unwrap();
    let silence = vec![0.0f32; 44100 * 5];
    assert_eq!(
        models.beats(&silence).unwrap_err(),
        "Beat This found fewer than eight beats"
    );
}
