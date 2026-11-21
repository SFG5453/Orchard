/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

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
    let env = ort::init().build()?;
    let mut session = Session::builder(&env)?
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
