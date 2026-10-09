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

//! Sentence embeddings for the in-app docs search (models/docs-search).
//! CPU only: help has to work on machines without a usable GPU.
use ort::{session::Session, value::Tensor};
use std::path::Path;

// Size of the BERT position table.
const MAX_TOKENS: usize = 512;

pub struct TextEmbedder {
    session: Session,
}

fn text(error: impl std::fmt::Display) -> String {
    error.to_string()
}

impl TextEmbedder {
    pub fn new(path: &Path) -> Result<Self, String> {
        let session = Session::builder()
            .map_err(text)?
            // One sleeping thread: a docs question must not take a core from playback.
            .with_intra_threads(1)
            .map_err(text)?
            .with_inter_threads(1)
            .map_err(text)?
            .with_intra_op_spinning(false)
            .map_err(text)?
            // Every sequence has its own length, so cached memory patterns never repeat.
            .with_memory_pattern(false)
            .map_err(text)?
            .commit_from_file(path)
            .map_err(|e| format!("Docs model {}: {e}", path.display()))?;
        Ok(Self { session })
    }

    /// Unit-length mean-pooled vector per token sequence ([CLS] ... [SEP] ids).
    // One sequence per run: dynamic int8 quantization scales activations per tensor,
    // so batching would make a vector depend on the sequences next to it.
    // Embeddings should not suffer peer pressure.
    pub fn embed(&mut self, sequences: &[Vec<i64>]) -> Result<Vec<Vec<f32>>, String> {
        sequences.iter().map(|ids| self.embed_one(ids)).collect()
    }

    fn embed_one(&mut self, ids: &[i64]) -> Result<Vec<f32>, String> {
        let n = ids.len();
        if n == 0 || n > MAX_TOKENS {
            return Err(format!("Docs model got {n} tokens, expected 1 to {MAX_TOKENS}"));
        }
        let tensor = |values: Vec<i64>| Tensor::from_array(([1, n], values)).map_err(text);
        let outputs = self
            .session
            .run(ort::inputs![
                "input_ids" => tensor(ids.to_vec())?,
                "attention_mask" => tensor(vec![1; n])?,
                "token_type_ids" => tensor(vec![0; n])?,
            ])
            .map_err(text)?;
        let (shape, hidden) = outputs["last_hidden_state"]
            .try_extract_tensor::<f32>()
            .map_err(text)?;
        let width = shape.last().copied().unwrap_or(0).max(0) as usize;
        if width == 0 || hidden.len() != n * width {
            return Err("Docs model returned an unexpected shape".into());
        }
        let mut vector = vec![0f32; width];
        for token in hidden.chunks_exact(width) {
            for (sum, value) in vector.iter_mut().zip(token) {
                *sum += value;
            }
        }
        let norm = vector.iter().map(|v| v * v).sum::<f32>().sqrt();
        if !norm.is_finite() || norm == 0.0 {
            return Err("Docs model returned an invalid vector".into());
        }
        vector.iter_mut().for_each(|v| *v /= norm);
        Ok(vector)
    }
}

/// Worker request `{"kind":"embed","ids":[[101, ...], ...]}` answered with `{"vectors":[[...], ...]}`.
/// The session loads on first use, so an idle worker pays nothing for it.
pub fn handle(
    slot: &mut Option<TextEmbedder>,
    models: &Path,
    request: &serde_json::Value,
) -> Result<serde_json::Value, String> {
    let mut sequences = Vec::new();
    for row in request["ids"].as_array().ok_or("Missing token ids")? {
        let ids = row
            .as_array()
            .ok_or("Invalid token ids")?
            .iter()
            .map(serde_json::Value::as_i64)
            .collect::<Option<Vec<_>>>()
            .ok_or("Invalid token id")?;
        sequences.push(ids);
    }
    if slot.is_none() {
        *slot = Some(TextEmbedder::new(&models.join("docs-search/model_quantized.onnx"))?);
    }
    let vectors = slot.as_mut().ok_or("Docs model is unavailable")?.embed(&sequences)?;
    Ok(serde_json::json!({ "vectors": vectors }))
}
