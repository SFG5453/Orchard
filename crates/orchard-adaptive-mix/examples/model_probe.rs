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

//! Reproducible model QA on the same CPU session settings as playback.
use ort::{session::Session, value::Tensor};
use std::{env, fs, path::Path, time::Instant};
fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<_> = env::args().collect();
    if args.len() != 4 {
        return Err("usage: model_probe model.onnx input.f32 output.f32".into());
    }
    let bytes = fs::read(&args[2])?;
    if bytes.len() != 2 * 2049 * 960 * 4 {
        return Err("expected [1,2,2049,960] float32".into());
    }
    let values: Vec<_> = bytes
        .as_chunks::<4>()
        .0
        .iter()
        .map(|b| f32::from_le_bytes(*b))
        .collect();
    let mut session = Session::builder()?
        .with_intra_threads(2)?
        .with_inter_threads(1)?
        .with_config_entry("session.intra_op.allow_spinning", "0")?
        .commit_from_file(Path::new(&args[1]))?;
    let input = Tensor::from_array(([1, 2, 2049, 960], values))?;
    let started = Instant::now();
    let outputs = session.run(ort::inputs!["mix_magnitude"=>input])?;
    let (_, values) = outputs["target_magnitude"].try_extract_tensor::<f32>()?;
    if values.iter().any(|v| !v.is_finite()) {
        return Err("nonfinite output".into());
    }
    eprintln!(
        "CPU inference: {:?}; {} output values",
        started.elapsed(),
        values.len()
    );
    fs::write(
        &args[3],
        values
            .iter()
            .flat_map(|v| v.to_le_bytes())
            .collect::<Vec<_>>(),
    )?;
    Ok(())
}
