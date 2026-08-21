//! End-to-end behaviour through the public API only.

mod common;

use common::{SR, Voice, beats, low_band_energy, rms, sine, sweep, track};
use earmark::config::{AnalysisConfig, TempoTarget};
use earmark::{
    AudioBuffer, BeatAnalysis, CrossfadeError, EngineConfig, SmartCrossfadeEngine,
    TransitionStrategy,
};

fn engine() -> SmartCrossfadeEngine {
    SmartCrossfadeEngine::new(EngineConfig::default()).unwrap()
}

#[test]
fn a_transition_renders_at_the_planned_length_and_position() {
    let mut engine = engine();
    let (outgoing, outgoing_beats) = track(124.0, 60.0, Voice::club(), 2, SR);
    let (incoming, incoming_beats) = track(126.0, 60.0, Voice::club(), 2, SR);

    let (plan, output) = engine
        .transition(&outgoing, &incoming, &outgoing_beats, &incoming_beats)
        .unwrap();

    assert_eq!(output.audio.frames(), plan.frames());
    assert_eq!(output.audio.sample_rate(), SR);
    assert_eq!(output.audio.channel_count(), 2);
    assert!((output.duration() - plan.duration).abs() < 1e-3);

    assert!(plan.outgoing_end() <= outgoing.duration());
    assert!(plan.incoming_end() <= incoming.duration());
    assert!((output.outgoing_resume - plan.outgoing_end()).abs() < 1e-6);
    assert!((output.incoming_resume - plan.incoming_end()).abs() < 1e-6);
}

#[test]
fn the_transition_starts_on_a_downbeat_of_both_tracks() {
    let mut engine = engine();
    let (outgoing, outgoing_beats) = track(124.0, 60.0, Voice::club(), 2, SR);
    let (incoming, incoming_beats) = track(124.0, 60.0, Voice::club(), 2, SR);

    let plan = engine
        .analyze(&outgoing, &incoming, &outgoing_beats, &incoming_beats)
        .unwrap();

    let on_downbeat =
        |grid: &BeatAnalysis, time: f64| grid.downbeats.iter().any(|d| (d - time).abs() < 1e-6);
    assert!(on_downbeat(&outgoing_beats, plan.outgoing_start));
    assert!(on_downbeat(&incoming_beats, plan.incoming_start));
}

#[test]
fn both_tracks_are_present_across_the_whole_transition() {
    let mut engine = engine();
    let (outgoing, outgoing_beats) = track(124.0, 60.0, Voice::club(), 2, SR);
    let (incoming, incoming_beats) = track(124.0, 60.0, Voice::club(), 2, SR);

    let (_, output) = engine
        .transition(&outgoing, &incoming, &outgoing_beats, &incoming_beats)
        .unwrap();

    let channel = output.audio.channel(0);
    let window = channel.len() / 16;
    assert!(rms(&channel[..window]) > 0.02, "transition opens silent");
    assert!(
        rms(&channel[channel.len() - window..]) > 0.02,
        "ends silent"
    );
    assert!(channel.iter().all(|s| s.is_finite()));
    assert!(output.audio.peak() <= 1.0, "output clipped");
}

#[test]
fn a_beatmatched_transition_spans_a_whole_number_of_beats() {
    let mut engine = engine();
    let (outgoing, outgoing_beats) = track(124.0, 60.0, Voice::club(), 2, SR);
    let (incoming, incoming_beats) = track(127.0, 60.0, Voice::club(), 2, SR);

    let plan = engine
        .analyze(&outgoing, &incoming, &outgoing_beats, &incoming_beats)
        .unwrap();
    assert!(plan.strategy.is_beatmatched());

    // Both sides must consume exactly `beats` of their own beats, which is what keeps the two
    // grids locked together for the length of the transition.
    let outgoing_beats_consumed =
        plan.outgoing_source_duration() / (60.0 / plan.outgoing_bpm as f64);
    let incoming_beats_consumed =
        plan.incoming_source_duration() / (60.0 / plan.incoming_bpm as f64);
    assert!((outgoing_beats_consumed - plan.beats as f64).abs() < 1e-3);
    assert!((incoming_beats_consumed - plan.beats as f64).abs() < 1e-3);
}

#[test]
fn half_time_tempos_are_matched_without_stretching() {
    let mut engine = engine();
    let (outgoing, outgoing_beats) = track(174.0, 60.0, Voice::club(), 2, SR);
    let (incoming, incoming_beats) = track(87.0, 60.0, Voice::club(), 2, SR);

    let plan = engine
        .analyze(&outgoing, &incoming, &outgoing_beats, &incoming_beats)
        .unwrap();

    assert!((plan.incoming_tempo_ratio - 1.0).abs() < 1e-4);
    assert!((plan.outgoing_tempo_ratio - 1.0).abs() < 1e-4);
    assert!(plan.strategy.is_beatmatched());
}

#[test]
fn incompatible_tempos_avoid_aggressive_stretching() {
    let mut engine = engine();
    let (outgoing, outgoing_beats) = track(96.0, 60.0, Voice::club(), 2, SR);
    let (incoming, incoming_beats) = track(140.0, 60.0, Voice::bright(), 2, SR);

    let (plan, output) = engine
        .transition(&outgoing, &incoming, &outgoing_beats, &incoming_beats)
        .unwrap();

    assert_eq!(plan.outgoing_tempo_ratio, 1.0);
    assert_eq!(plan.incoming_tempo_ratio, 1.0);
    assert!(!plan.strategy.is_beatmatched());
    assert!(output.audio.peak() > 0.0);
}

#[test]
fn two_bass_heavy_tracks_get_a_bass_swap_that_avoids_summing_the_low_end() {
    let mut engine = engine();
    let (outgoing, outgoing_beats) = track(124.0, 60.0, Voice::club(), 2, SR);
    let (incoming, incoming_beats) = track(125.0, 60.0, Voice::club(), 2, SR);

    let (plan, output) = engine
        .transition(&outgoing, &incoming, &outgoing_beats, &incoming_beats)
        .unwrap();
    assert_eq!(plan.strategy, TransitionStrategy::BassSwap);
    assert!(!plan.filters.outgoing.is_empty());
    assert!(!plan.filters.incoming.is_empty());

    // Mid-transition, one track owns the low band rather than both contributing to it.
    let channel = output.audio.channel(0);
    let middle = &channel[channel.len() * 3 / 8..channel.len() * 5 / 8];
    let solo_low = low_band_energy(&outgoing.channel(0)[..middle.len()], 120.0, SR);
    let mixed_low = low_band_energy(middle, 120.0, SR);
    assert!(
        mixed_low < solo_low * 1.5,
        "low band piled up: {mixed_low} vs {solo_low}"
    );
}

#[test]
fn clashing_spectra_get_a_filtered_blend() {
    let mut engine = engine();
    let (outgoing, outgoing_beats) = track(124.0, 60.0, Voice::club(), 2, SR);
    let (incoming, incoming_beats) = track(125.0, 60.0, Voice::bright(), 2, SR);

    let plan = engine
        .analyze(&outgoing, &incoming, &outgoing_beats, &incoming_beats)
        .unwrap();
    assert_eq!(plan.strategy, TransitionStrategy::FilteredBlend);
    assert!(!plan.filters.outgoing.is_empty());
}

#[test]
fn loudness_differences_are_compensated_gradually() {
    let mut engine = engine();
    let (mut outgoing, outgoing_beats) = track(124.0, 60.0, Voice::club(), 2, SR);
    let (mut incoming, incoming_beats) = track(124.0, 60.0, Voice::club(), 2, SR);
    outgoing.apply_gain(1.0);
    incoming.apply_gain(0.2);

    let plan = engine
        .analyze(&outgoing, &incoming, &outgoing_beats, &incoming_beats)
        .unwrap();

    assert!(
        plan.outgoing_gain_db < -1.0,
        "no trim: {}",
        plan.outgoing_gain_db
    );
    assert!(plan.outgoing_gain_db >= -engine.config().loudness.max_gain_db);
    // Applied as a ride from unity, not as a step at the boundary.
    assert!((plan.fade.outgoing_gain.value_at(0.0) - 1.0).abs() < 1e-6);
    assert!(plan.fade.outgoing_gain.value_at(1.0) < 1.0);
}

#[test]
fn mismatched_rates_and_channel_layouts_are_reconciled() {
    let mut engine = engine();
    let (outgoing, outgoing_beats) = track(124.0, 60.0, Voice::club(), 1, 48_000);
    let (incoming, incoming_beats) = track(124.0, 60.0, Voice::club(), 2, SR);

    let (plan, output) = engine
        .transition(&outgoing, &incoming, &outgoing_beats, &incoming_beats)
        .unwrap();

    assert_eq!(plan.sample_rate, SR);
    assert_eq!(plan.channels, 2);
    assert_eq!(output.audio.sample_rate(), SR);
    assert_eq!(output.audio.channel_count(), 2);
    assert!(output.audio.peak() > 0.0);
}

#[test]
fn noise_and_sweeps_are_handled_without_producing_garbage() {
    let mut engine = engine();
    let frames = (40.0 * SR as f64) as usize;
    let noisy = AudioBuffer::new(vec![common::noise(7, frames); 2], SR).unwrap();
    let swept = AudioBuffer::new(vec![sweep(80.0, 8_000.0, 40.0, SR); 2], SR).unwrap();

    let (_, output) = engine
        .transition(&noisy, &swept, &beats(120.0, 40.0), &beats(120.0, 40.0))
        .unwrap();

    assert!(output.audio.channel(0).iter().all(|s| s.is_finite()));
    assert!(output.audio.peak() <= 1.0);
}

#[test]
fn a_pure_tone_transition_stays_within_the_ceiling() {
    let mut engine = engine();
    let loud = AudioBuffer::new(vec![sine(220.0, 40.0, SR); 2], SR).unwrap();
    let (_, output) = engine
        .transition(&loud, &loud, &beats(120.0, 40.0), &beats(120.0, 40.0))
        .unwrap();

    let ceiling = 10f32.powf(engine.config().loudness.ceiling_db / 20.0);
    assert!(output.audio.peak() <= ceiling + 1e-4);
}

#[test]
fn tempo_targets_change_which_side_is_stretched() {
    let (outgoing, outgoing_beats) = track(120.0, 60.0, Voice::club(), 2, SR);
    let (incoming, incoming_beats) = track(124.0, 60.0, Voice::club(), 2, SR);

    let plan_for = |target| {
        let config = EngineConfig {
            tempo: earmark::config::TempoConfig {
                target,
                ..earmark::config::TempoConfig::default()
            },
            ..EngineConfig::default()
        };
        SmartCrossfadeEngine::new(config)
            .unwrap()
            .analyze(&outgoing, &incoming, &outgoing_beats, &incoming_beats)
            .unwrap()
    };

    let onto_incoming = plan_for(TempoTarget::Incoming);
    assert!((onto_incoming.incoming_tempo_ratio - 1.0).abs() < 1e-5);
    assert!(onto_incoming.outgoing_tempo_ratio > 1.0);

    let onto_outgoing = plan_for(TempoTarget::Outgoing);
    assert!((onto_outgoing.outgoing_tempo_ratio - 1.0).abs() < 1e-5);
    assert!(onto_outgoing.incoming_tempo_ratio < 1.0);

    let midpoint = plan_for(TempoTarget::Midpoint);
    assert!(midpoint.outgoing_tempo_ratio > 1.0 && midpoint.incoming_tempo_ratio < 1.0);

    let none = plan_for(TempoTarget::None);
    assert_eq!(none.outgoing_tempo_ratio, 1.0);
    assert_eq!(none.incoming_tempo_ratio, 1.0);
}

#[test]
fn invalid_inputs_are_reported_rather_than_guessed_at() {
    let mut engine = engine();
    let (good, good_beats) = track(124.0, 60.0, Voice::club(), 2, SR);

    let empty = AudioBuffer::silent(2, 0, SR).unwrap();
    assert!(matches!(
        engine.analyze(&empty, &good, &good_beats, &good_beats),
        Err(CrossfadeError::InvalidAudio(_))
    ));

    let unsorted = BeatAnalysis {
        bpm: 124.0,
        beats: vec![1.0, 0.5],
        downbeats: vec![],
    };
    assert!(matches!(
        engine.analyze(&good, &good, &unsorted, &good_beats),
        Err(CrossfadeError::InvalidBeatAnalysis(_))
    ));

    let (tiny, tiny_beats) = track(124.0, 1.0, Voice::club(), 2, SR);
    assert!(matches!(
        engine.analyze(&tiny, &good, &tiny_beats, &good_beats),
        Err(CrossfadeError::NoViableTransition(_))
    ));

    let surround = AudioBuffer::silent(6, SR as usize * 30, SR).unwrap();
    assert!(matches!(
        engine.analyze(&surround, &good, &good_beats, &good_beats),
        Err(CrossfadeError::ChannelMismatch { .. })
    ));
}

#[test]
fn an_invalid_configuration_is_refused_up_front() {
    let bad_fft = EngineConfig {
        analysis: AnalysisConfig {
            fft_size: 3_000,
            ..AnalysisConfig::default()
        },
        ..EngineConfig::default()
    };
    assert!(matches!(
        SmartCrossfadeEngine::new(bad_fft),
        Err(CrossfadeError::UnsupportedConfiguration(_))
    ));

    let no_lengths = EngineConfig {
        timing: earmark::config::TimingConfig {
            allowed_beat_lengths: vec![],
            ..earmark::config::TimingConfig::default()
        },
        ..EngineConfig::default()
    };
    assert!(SmartCrossfadeEngine::new(no_lengths).is_err());
}

#[test]
fn a_grid_without_downbeats_still_produces_a_transition() {
    let mut engine = engine();
    let (outgoing, outgoing_beats) = track(124.0, 60.0, Voice::club(), 2, SR);
    let (incoming, _) = track(124.0, 60.0, Voice::club(), 2, SR);

    let beats_only = BeatAnalysis::new(124.0, outgoing_beats.beats.clone(), vec![]).unwrap();
    let (plan, output) = engine
        .transition(&outgoing, &incoming, &beats_only, &beats_only)
        .unwrap();

    assert!(plan.duration > 0.0);
    assert_eq!(output.audio.frames(), plan.frames());
}
