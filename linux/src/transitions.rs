use orchard_transition_core as core;
use serde::{Deserialize, Serialize};

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TransitionSource {
    pub channels: Vec<Vec<f32>>,
    pub sample_rate: f64,
    pub bpm: f64,
    #[serde(default)]
    pub beats: Vec<f64>,
    #[serde(default)]
    pub downbeats: Vec<f64>,
}

#[derive(Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RegionWindow {
    pub start_earliest: Option<f64>,
    pub start_latest: Option<f64>,
    pub end_earliest: Option<f64>,
    pub end_latest: Option<f64>,
}

#[derive(Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TransitionOptions {
    pub outgoing: Option<RegionWindow>,
    pub incoming: Option<RegionWindow>,
    pub beat_lengths: Option<Vec<u32>>,
    pub duck_curve: Option<Vec<f64>>,
    #[serde(default)]
    pub diagnostics: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TransitionResult {
    pub rendered: bool,
    pub rejected: String,
    pub channels: Vec<Vec<f32>>,
    pub sample_rate: f64,
    pub duration: f64,
    pub beats: u32,
    pub strategy: String,
    pub outgoing_start: f64,
    pub incoming_start: f64,
    pub outgoing_resume: f64,
    pub incoming_resume: f64,
    pub outgoing_tempo_ratio: f64,
    pub incoming_tempo_ratio: f64,
    pub target_bpm: f64,
    pub summary: String,
}

pub fn render(
    outgoing: TransitionSource,
    incoming: TransitionSource,
    options: TransitionOptions,
) -> Result<TransitionResult, String> {
    let outgoing = take_source(outgoing, "outgoing")?;
    let incoming = take_source(incoming, "incoming")?;
    let request = core::TransitionRequest {
        outgoing: take_region(options.outgoing),
        incoming: take_region(options.incoming),
        beat_lengths: options.beat_lengths,
        duck_curve: options.duck_curve.filter(|points| !points.is_empty()),
        diagnostics: options.diagnostics,
    };
    Ok(
        match core::render_constrained(&outgoing, &incoming, &request) {
            Ok(result) => TransitionResult::from(result),
            Err(reason) => TransitionResult::refused(reason),
        },
    )
}

fn take_source(source: TransitionSource, label: &str) -> Result<core::Source, String> {
    core::validate_pcm(&source.channels, source.sample_rate, label)?;
    Ok(core::Source {
        channels: source.channels,
        sample_rate: source.sample_rate as u32,
        bpm: source.bpm as f32,
        beats: source.beats,
        downbeats: source.downbeats,
    })
}

fn take_region(region: Option<RegionWindow>) -> core::RegionWindow {
    let region = region.unwrap_or_default();
    core::RegionWindow {
        start_earliest: region.start_earliest,
        start_latest: region.start_latest,
        end_earliest: region.end_earliest,
        end_latest: region.end_latest,
    }
}

impl TransitionResult {
    fn refused(reason: String) -> Self {
        Self {
            rendered: false,
            rejected: reason,
            channels: Vec::new(),
            sample_rate: 0.0,
            duration: 0.0,
            beats: 0,
            strategy: String::new(),
            outgoing_start: 0.0,
            incoming_start: 0.0,
            outgoing_resume: 0.0,
            incoming_resume: 0.0,
            outgoing_tempo_ratio: 1.0,
            incoming_tempo_ratio: 1.0,
            target_bpm: 0.0,
            summary: String::new(),
        }
    }
}

impl From<core::Rendered> for TransitionResult {
    fn from(result: core::Rendered) -> Self {
        Self {
            rendered: true,
            rejected: String::new(),
            channels: result.channels,
            sample_rate: result.sample_rate as f64,
            duration: result.duration,
            beats: result.beats,
            strategy: result.strategy,
            outgoing_start: result.outgoing_start,
            incoming_start: result.incoming_start,
            outgoing_resume: result.outgoing_resume,
            incoming_resume: result.incoming_resume,
            outgoing_tempo_ratio: result.outgoing_tempo_ratio,
            incoming_tempo_ratio: result.incoming_tempo_ratio,
            target_bpm: result.target_bpm,
            summary: result.summary,
        }
    }
}
