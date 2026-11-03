/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

//! One song's whole-track planner evidence, which hosts keep in their own databases by track id.
//! Playback reuses it in place of the beat model; Best Mix reads its `beatModel` summary.
use crate::song::Track;
use earmark::analysis::ChromaFrames;
use serde_json::{Value, json};

/// Bump when the evidence or the beat model changes; older rows are measured again.
pub const VERSION: u64 = 1;

/// Deck fields Best Mix plans with when a song has been measured.
const BEAT_MODEL_FIELDS: [&str; 6] =
    ["bpm", "beatConfidence", "nativeBeatConfidence", "beatModelAgreement", "beatModelBpm", "downbeats"];

pub struct Measured {
    pub audio: u64,
    /// `analysis::measure`'s value plus the bass curve and sub-bass ratio.
    pub deck: Value,
    pub chroma: ChromaFrames,
}

/// FNV-1a over the whole-track mono, so evidence never outlives a different decode of the song.
pub fn audio_key(track: &Track) -> u64 {
    let mut hash = 0xcbf2_9ce4_8422_2325u64 ^ track.mono.len() as u64;
    for sample in &track.mono {
        hash = (hash ^ u64::from(sample.to_bits())).wrapping_mul(0x0100_0000_01b3);
    }
    for (window, offset) in &track.beat_windows {
        hash = (hash ^ window.len() as u64 ^ offset.to_bits()).wrapping_mul(0x0100_0000_01b3);
    }
    hash
}

impl Measured {
    pub fn to_json(&self) -> Value {
        let mut beat_model = serde_json::Map::new();
        beat_model.insert("version".into(), VERSION.into());
        for key in BEAT_MODEL_FIELDS {
            if let Some(value) = self.deck.get(key) { beat_model.insert(key.into(), value.clone()); }
        }
        json!({"version": VERSION, "audio": format!("{:016x}", self.audio), "deck": self.deck,
            "chroma": {"start": self.chroma.start, "step": self.chroma.step, "frames": self.chroma.frames},
            "beatModel": beat_model})
    }

    /// Stored evidence, or None when it is another version or was measured on other audio.
    pub fn from_json(value: &Value, audio: u64) -> Option<Self> {
        if value["version"].as_u64() != Some(VERSION)
            || u64::from_str_radix(value["audio"].as_str()?, 16).ok()? != audio {
            return None;
        }
        let deck = value.get("deck").filter(|deck| deck.is_object())?.clone();
        let chroma = &value["chroma"];
        let frames = chroma["frames"].as_array()?.iter().map(|frame| {
            let frame = frame.as_array().filter(|frame| frame.len() == 12)?;
            let mut out = [0.0f32; 12];
            for (slot, value) in out.iter_mut().zip(frame) { *slot = value.as_f64()? as f32; }
            Some(out)
        }).collect::<Option<Vec<_>>>()?;
        Some(Self { audio, deck, chroma: ChromaFrames {
            start: chroma["start"].as_f64()?, step: chroma["step"].as_f64()?, frames,
        } })
    }
}

/// The edges' downbeats on the bar phase the beat model voted for, when enough of its
/// downbeats land on the edge's beats; otherwise the edge keeps its own.
fn rephase(beats: &[f64], measured: &[f64]) -> Option<Vec<f64>> {
    let mut votes = [0usize; 4];
    for (index, beat) in beats.iter().enumerate() {
        if measured.iter().any(|downbeat| (downbeat - beat).abs() < 0.07) { votes[index % 4] += 1; }
    }
    let total: usize = votes.iter().sum();
    let phase = (0..4).max_by_key(|phase| (votes[*phase], std::cmp::Reverse(*phase)))?;
    (total >= 2 && votes[phase] * 2 > total)
        .then(|| beats.iter().enumerate().filter(|(index, _)| index % 4 == phase).map(|(_, beat)| *beat).collect())
}

/// A Best Mix edge planned with the beat model's verdict from an earlier playback mix:
/// its real confidence and bar phase. None without a measurement of this version.
pub fn beat_model_edge(edge: &Value, beat_model: &Value) -> Option<Value> {
    if beat_model["version"].as_u64() != Some(VERSION) { return None; }
    let confidence = beat_model["beatConfidence"].as_f64()?;
    let mut edge = edge.clone();
    edge["beatConfidence"] = json!(confidence);
    edge["beatModelChecked"] = json!(true);
    let numbers = |value: &Value| value.as_array().map(|values| values.iter().filter_map(Value::as_f64).collect::<Vec<_>>());
    if let (Some(beats), Some(downbeats)) = (numbers(&edge["beats"]), numbers(&beat_model["downbeats"]))
        && let Some(rephased) = rephase(&beats, &downbeats) {
        edge["downbeats"] = json!(rephased);
    }
    Some(edge)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn measured() -> Measured {
        Measured { audio: 0xfeed, deck: json!({"bpm": 120.0, "beatConfidence": 0.8, "downbeats": [0.5, 2.5]}),
            chroma: ChromaFrames { start: 0.0, step: 0.5, frames: vec![[0.25; 12]; 3] } }
    }

    #[test]
    fn evidence_round_trips_only_for_the_same_audio_and_version() {
        let json = measured().to_json();
        let back = Measured::from_json(&json, 0xfeed).unwrap();
        assert_eq!(back.deck, measured().deck);
        assert_eq!(back.chroma, measured().chroma);
        assert!(Measured::from_json(&json, 0xbeef).is_none());
        let mut old = json.clone();
        old["version"] = json!(VERSION + 1);
        assert!(Measured::from_json(&old, 0xfeed).is_none());
        assert_eq!(json["beatModel"]["beatConfidence"], json!(0.8));
    }

    #[test]
    fn measured_bar_phase_moves_the_edge_downbeats() {
        let beats: Vec<f64> = (0..16).map(|i| i as f64 * 0.5).collect();
        let edge = json!({"beats": beats, "downbeats": [0.0, 2.0, 4.0, 6.0], "beatConfidence": 0.5});
        let model = json!({"version": VERSION, "beatConfidence": 0.9, "downbeats": [0.51, 2.49, 4.5, 6.5]});
        let planned = beat_model_edge(&edge, &model).unwrap();
        assert_eq!(planned["downbeats"], json!([0.5, 2.5, 4.5, 6.5]));
        assert_eq!(planned["beatConfidence"], json!(0.9));
        // No downbeat near the edge's beats: the edge keeps its bar phase.
        let far = json!({"version": VERSION, "beatConfidence": 0.4, "downbeats": [100.0]});
        assert_eq!(beat_model_edge(&edge, &far).unwrap()["downbeats"], edge["downbeats"]);
        assert!(beat_model_edge(&edge, &json!({"beatConfidence": 0.9})).is_none());
    }
}
