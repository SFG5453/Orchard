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

//! Docs search embedder against the shipped model. CPU only, so it runs anywhere.
use orchard_adaptive_mix::text_embed::{TextEmbedder, handle};
use std::path::{Path, PathBuf};

// Token ids from the Hugging Face tokenizer (bert-base-uncased vocabulary).
const QUEUE: [i64; 8] = [101, 2129, 2079, 1045, 2330, 1996, 24240, 102]; // how do I open the queue
const PANEL: [i64; 6] = [101, 2330, 1996, 24240, 5997, 102]; // Open the queue panel
const BREAD: [i64; 8] = [101, 2129, 2079, 1045, 8670, 3489, 7852, 102]; // how do I bake bread

// First eight dimensions from onnxruntime 1.30 (Python) on the same graph, one sequence per run.
const QUEUE_HEAD: [f32; 8] = [-0.038064, -0.072301, 0.041868, -0.025152, -0.110205, 0.030775, 0.044846, 0.004089];
const PANEL_HEAD: [f32; 8] = [-0.066221, -0.025981, 0.032935, -0.004313, -0.064628, 0.06716, 0.008626, 0.025527];

fn models() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../models")
}

fn embedder() -> TextEmbedder {
    TextEmbedder::new(&models().join("docs-search/model_quantized.onnx")).expect("docs model loads")
}

fn dot(a: &[f32], b: &[f32]) -> f32 {
    a.iter().zip(b).map(|(x, y)| x * y).sum()
}

#[test]
fn vectors_match_the_reference_runtime() {
    let vectors = embedder().embed(&[QUEUE.to_vec(), PANEL.to_vec()]).expect("embeds");
    for (vector, head) in vectors.iter().zip([QUEUE_HEAD, PANEL_HEAD]) {
        assert_eq!(vector.len(), 384);
        for (got, want) in vector.iter().zip(head) {
            assert!((got - want).abs() < 2e-3, "got {got}, want {want}");
        }
    }
}

#[test]
fn vectors_have_unit_length() {
    for vector in embedder().embed(&[QUEUE.to_vec(), BREAD.to_vec()]).expect("embeds") {
        assert!((dot(&vector, &vector) - 1.0).abs() < 1e-4);
    }
}

#[test]
fn related_text_scores_above_unrelated_text() {
    let v = embedder().embed(&[QUEUE.to_vec(), PANEL.to_vec(), BREAD.to_vec()]).expect("embeds");
    let related = dot(&v[0], &v[1]);
    let unrelated = dot(&v[0], &v[2]);
    assert!(related > unrelated + 0.15, "related {related}, unrelated {unrelated}");
}

#[test]
fn a_vector_does_not_depend_on_its_neighbours() {
    let mut model = embedder();
    let alone = model.embed(&[QUEUE.to_vec()]).expect("embeds");
    let among = model.embed(&[BREAD.to_vec(), QUEUE.to_vec(), PANEL.to_vec()]).expect("embeds");
    assert_eq!(alone[0], among[1]);
}

#[test]
fn rejects_sequences_the_model_cannot_take() {
    let mut model = embedder();
    assert!(model.embed(&[vec![]]).is_err());
    assert!(model.embed(&[vec![101; 513]]).is_err());
}

#[test]
fn worker_request_returns_one_vector_per_sequence() {
    let mut slot = None;
    let reply = handle(&mut slot, &models(), &serde_json::json!({"kind": "embed", "ids": [QUEUE, PANEL]}))
        .expect("answers");
    let vectors = reply["vectors"].as_array().expect("vector list");
    assert_eq!(vectors.len(), 2);
    assert_eq!(vectors[0].as_array().map(Vec::len), Some(384));
    assert!(handle(&mut slot, &models(), &serde_json::json!({"kind": "embed"})).is_err());
    assert!(handle(&mut slot, &models(), &serde_json::json!({"ids": [["x"]]})).is_err());
}
