/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

//! QuickJS owns musical decisions; Rust only adapts its exact render contract.
use earmark::{AudioBuffer, EngineConfig, SmartCrossfadeEngine, TransitionPlan, TransitionStrategy};
use earmark::planner::strategy;
use orchard_transition_core::{SelectedPlan, apply_selected_shape};
use serde_json::Value;
use crate::repeat::BeatLoop;

/// Looped audio built past the planned overlap; glide stretching reads ahead of it.
const LOOP_MARGIN: f64 = 2.0;

/// Error prefix for a planner refusal; the host keeps the natural track boundary.
pub const NATURAL_BOUNDARY: &str = "Shared planner keeps the natural boundary";

pub fn engine() -> Result<SmartCrossfadeEngine, String> {
    SmartCrossfadeEngine::new(EngineConfig { output_sample_rate: Some(48_000), ..Default::default() })
        .map_err(|e| e.to_string())
}

fn number(value: &Value, key: &str) -> Result<f64, String> {
    value[key].as_f64().filter(|v| v.is_finite()).ok_or_else(|| format!("Shared planner omitted {key}"))
}

/// `ORCHARD_MIX_DUMP=<dir>` records each pair's planner input and decisions for offline replay.
fn dump(input: &Value, live: &Value, native: &Value) {
    let Some(directory) = std::env::var_os("ORCHARD_MIX_DUMP") else { return };
    let id = |track: &Value| track["id"].as_str().unwrap_or("unknown")
        .chars().filter(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_')).collect::<String>();
    let name = format!("{}-{}.json", id(&input["currentTrack"]), id(&input["nextTrack"]));
    let record = serde_json::json!({"input": input, "live": live, "native": native});
    // Diagnostics only: a full disk must not cost the listener their transition.
    let _ = std::fs::write(std::path::Path::new(&directory).join(name), record.to_string());
}

fn fade_span(value: &Value) -> Option<(f64, f64)> {
    Some((value[0].as_f64()?, value[1].as_f64()?))
}

/// `code: detail` for a refusal; hosts key their status on the code before the colon.
fn refusal(live: &Value) -> String {
    let code = live["reason"].as_str().unwrap_or("no overlap");
    match live["explain"]["detail"].as_str() {
        Some(detail) if detail != code => format!("{code}: {detail}"),
        _ => code.to_owned(),
    }
}

/// The selected plan, plus the outgoing window rebuilt with its beat loop when it has one.
pub struct Planned {
    pub plan: TransitionPlan,
    pub looped: Option<(BeatLoop, AudioBuffer)>,
    /// The shared planner's technique name, empty for live fallbacks.
    pub technique: String,
}

pub fn plan(engine: &mut SmartCrossfadeEngine, outgoing: &AudioBuffer, incoming: &AudioBuffer,
            input: &Value, offset: f64) -> Result<Planned, String> {
    // V2 consulted queue context before the WSOLA route; album playthroughs stay gapless.
    let live = orchard_transition_planner::invoke("live", input)?;
    let context = matches!(live["reason"].as_str().unwrap_or(""), "same-album-gapless"
        | "before-gapless-window" | "blocked-speech-or-live" | "short-duration-guard"
        | "smart-analysis-fallback" | "before-smart-analysis-fallback-window");
    let native = if context { serde_json::json!({"ok": false}) }
        else { orchard_transition_planner::invoke("native", input)? };
    dump(input, &live, &native);
    let technique = native["technique"].as_str().unwrap_or_default().to_owned();
    let (plan, looped) = if native["ok"] == true {
        let selected = SelectedPlan {
            outgoing_start: number(&native, "transitionStart")? - offset,
            incoming_start: number(&native, "incomingCueTime")?,
            duration: number(&native, "overlapSeconds")?,
            beats: native["beats"].as_u64().ok_or("Missing shared beat count")? as u32,
            outgoing_bpm: number(&native, "outgoingBpm")?, incoming_bpm: number(&native, "incomingBpm")?,
            target_bpm: number(&native, "targetBpm")?,
            outgoing_tempo_ratio: number(&native, "outgoingTempoRatio")?,
            incoming_tempo_ratio: number(&native, "incomingTempoRatio")?,
            outgoing_pitch_semitones: None, incoming_pitch_semitones: None,
            tempo_ramp: native["tempoRamp"] == true,
            strategy: native["strategy"].as_str().ok_or("Missing shared strategy")?.into(),
            handoff_fraction: native["handoffFraction"].as_f64(),
            bed_position: native["bedPosition"].as_f64(),
            bass_swap_fraction: native["bassSwapFraction"].as_f64(),
            filter_sweep: native["filterSweep"].as_f64(),
            outgoing_fade_span: fade_span(&native["arrangement"]["outgoingFade"]),
            incoming_fade_span: fade_span(&native["arrangement"]["incomingFade"]),
        };
        // Past the loop end the planned outgoing times are loop time, so earmark must see that audio.
        let looped = match BeatLoop::from_plan(&native, offset) {
            Some(beat_loop) => {
                let until = number(&native, "transitionEnd")? - offset + LOOP_MARGIN;
                Some((beat_loop, beat_loop.apply(outgoing, until)?))
            }
            None => None,
        };
        let source = looped.as_ref().map_or(outgoing, |(_, audio)| audio);
        let mut plan = engine.plan_selected(source, incoming, &selected.to_earmark()?)
            .map_err(|e| e.to_string())?;
        apply_selected_shape(&mut plan, &selected);
        (plan, looped)
    } else {
        // Mobile's live adapter selects ordinary fades or an explicit refusal.
        // Both use the same measured beat grid.
        if live["markerVisible"] != true {
            return Err(format!("{NATURAL_BOUNDARY} ({})", refusal(&live)));
        }
        let length = number(input, "duration")?;
        let mut start = number(&live, "transitionStart")?;
        let mut duration = live["fadeSeconds"].as_f64().unwrap_or(0.0);
        if duration <= 0.0 {
            // V2 armed boundary handoffs 250 ms early and used the listener's fade.
            start = (number(&live, "transitionEnd")? - 0.25).max(0.0);
            duration = input["fadeSeconds"].as_f64().unwrap_or(6.0);
        }
        // V2 never faded past the outgoing song's end.
        duration = duration.min((length - start).max(0.05));
        let chosen = if live["transitionStyle"] == "dj_filter" {
            TransitionStrategy::FilteredBlend
        } else { TransitionStrategy::EqualPowerCrossfade };
        (TransitionPlan {
            outgoing_start: start - offset,
            incoming_start: number(&live, "incomingCueTime")?, duration,
            beats: 0, sample_rate: 48_000, channels: 2,
            outgoing_bpm: 0.0, incoming_bpm: 0.0, target_bpm: 0.0,
            outgoing_tempo_ratio: 1.0, incoming_tempo_ratio: 1.0,
            outgoing_pitch_semitones: 0.0, incoming_pitch_semitones: 0.0, tempo_ramp: false,
            outgoing_gain_db: 0.0, incoming_gain_db: 0.0, strategy: chosen,
            fade: strategy::build_fade(chosen, Default::default()),
            filters: strategy::build_filters(chosen, engine.config()), diagnostics: None,
        }, None)
    };
    let source = looped.as_ref().map_or(outgoing, |(_, audio)| audio);
    if plan.outgoing_start < 0.0 || plan.incoming_start < 0.0 || plan.duration > 30.0
        || plan.outgoing_end() > source.duration() + 0.001
        || plan.incoming_start + plan.duration * plan.incoming_tempo_ratio as f64 > incoming.duration() + 0.001 {
        return Err("Shared transition falls outside the decoded audio windows".into());
    }
    // A constant incoming stretch would still be off tempo when native playback resumes.
    if !plan.tempo_ramp && (plan.incoming_tempo_ratio - 1.0).abs() > 1e-6 {
        return Err("Shared transition requires an ongoing incoming tempo change".into());
    }
    Ok(Planned { plan, looped, technique })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn refusals_name_their_code_then_the_reason() {
        let live = json!({"reason": "tempo-distance",
            "explain": {"code": "tempo-distance", "detail": "96 vs 128 BPM, 33.3% apart"}});
        assert_eq!(refusal(&live), "tempo-distance: 96 vs 128 BPM, 33.3% apart");
        // Context refusals carry no explanation.
        assert_eq!(refusal(&json!({"reason": "same-album-gapless"})), "same-album-gapless");
    }

    #[test]
    fn fade_spans_need_both_ends() {
        assert_eq!(fade_span(&json!([0.0, 0.5])), Some((0.0, 0.5)));
        assert_eq!(fade_span(&json!(null)), None);
        assert_eq!(fade_span(&json!([0.2])), None);
    }
}
