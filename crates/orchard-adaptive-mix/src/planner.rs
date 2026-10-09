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

/// The selected plan, plus the outgoing window rebuilt with its beat loop when it has one.
pub struct Planned {
    pub plan: TransitionPlan,
    pub looped: Option<(BeatLoop, AudioBuffer)>,
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
        // Neither is a CPU-inference fallback; both use the same measured GPU grid.
        if live["markerVisible"] != true {
            return Err(format!("{NATURAL_BOUNDARY} ({})",
                live["reason"].as_str().unwrap_or("no overlap")));
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
    Ok(Planned { plan, looped })
}
