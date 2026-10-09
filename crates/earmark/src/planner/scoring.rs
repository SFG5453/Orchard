//! Candidate scoring.
//!
//! Every component returns `0.0..=1.0` where 1.0 is ideal, and the total is their weighted mean.
//! Keeping the components separate is what makes a transition decision explainable after the
//! fact — see [`crate::types::diagnostics::CandidateScore`].

use crate::analysis::TrackAnalysis;
use crate::config::EngineConfig;
use crate::types::analysis::RegionFeatures;
use crate::types::beat::BeatAnalysis;
use crate::types::diagnostics::{Candidate, CandidateScore};

/// Loudness gap at which the loudness component bottoms out.
const LOUDNESS_SPAN_DB: f32 = 12.0;
/// Energy trend, in dB per second, at which a wrong-direction trajectory scores half.
const ENERGY_SLOPE_SCALE: f32 = 6.0;
/// Multiplier turning the product of both transient rates into a penalty.
const TRANSIENT_COLLISION_SCALE: f32 = 4.0;
/// Shared low-band dominance at which the collision penalty bottoms out. Two tracks both
/// spending 70% of their energy below the bass crossover cannot be summed cleanly.
const LOW_FREQ_COLLISION_LIMIT: f32 = 0.7;
/// Octaves of spectral centroid difference at which the spectral component bottoms out.
const CENTROID_SPAN_OCTAVES: f32 = 2.0;
/// Octaves away from the preferred beat count at which the duration component bottoms out.
const DURATION_SPAN_OCTAVES: f32 = 2.0;
/// What an unmatched (native tempo) transition scores. Not a failure, just not beat-locked.
const UNMATCHED_TEMPO_SCORE: f32 = 0.35;
/// Weight split between where the phrase boundaries fall and whether the transition length is a
/// whole number of phrases.
const PHRASE_POSITION_WEIGHT: f32 = 0.7;
/// Score for a beat count that does not divide into whole phrases.
const PARTIAL_PHRASE_SCORE: f32 = 0.4;

pub struct ScoringContext<'a> {
    pub outgoing: &'a TrackAnalysis,
    pub incoming: &'a TrackAnalysis,
    pub outgoing_beats: &'a BeatAnalysis,
    pub incoming_beats: &'a BeatAnalysis,
}

/// A scored candidate plus the region features behind the score, so strategy selection does not
/// have to re-aggregate them.
pub struct Evaluation {
    pub score: CandidateScore,
    pub outgoing_region: RegionFeatures,
    pub incoming_region: RegionFeatures,
}

pub fn evaluate(candidate: &Candidate, ctx: &ScoringContext, config: &EngineConfig) -> Evaluation {
    let outgoing_region = ctx.outgoing.region(
        candidate.outgoing_start,
        candidate.outgoing_end(),
        &config.analysis,
    );
    let incoming_region = ctx.incoming.region(
        candidate.incoming_start,
        candidate.incoming_end(),
        &config.analysis,
    );

    let weights = &config.scoring;
    let mut score = CandidateScore {
        beat_alignment: beat_alignment(candidate, ctx),
        phrase_alignment: phrase_alignment(candidate, ctx),
        tempo: tempo(candidate, config),
        spectral: spectral(&outgoing_region, &incoming_region),
        loudness: loudness(&outgoing_region, &incoming_region),
        energy: energy(&outgoing_region, &incoming_region),
        transient: transient(&outgoing_region, &incoming_region),
        low_freq: low_freq(&outgoing_region, &incoming_region),
        duration: duration(candidate, config),
        total: 0.0,
    };

    score.total = (score.beat_alignment * weights.beat_alignment
        + score.phrase_alignment * weights.phrase_alignment
        + score.tempo * weights.tempo
        + score.spectral * weights.spectral
        + score.loudness * weights.loudness
        + score.energy * weights.energy
        + score.transient * weights.transient
        + score.low_freq * weights.low_freq
        + score.duration * weights.duration)
        / weights.total();

    Evaluation {
        score,
        outgoing_region,
        incoming_region,
    }
}

/// How close both entry points sit to an actual beat.
pub fn beat_alignment(candidate: &Candidate, ctx: &ScoringContext) -> f32 {
    let outgoing = beat_closeness(ctx.outgoing_beats, candidate.outgoing_start);
    let incoming = beat_closeness(ctx.incoming_beats, candidate.incoming_start);
    (outgoing + incoming) * 0.5
}

/// Phrase position on both sides, plus whether the length is a whole number of phrases.
pub fn phrase_alignment(candidate: &Candidate, ctx: &ScoringContext) -> f32 {
    let outgoing = ctx
        .outgoing
        .phrase
        .alignment(ctx.outgoing_beats, candidate.outgoing_start);
    let incoming = ctx
        .incoming
        .phrase
        .alignment(ctx.incoming_beats, candidate.incoming_start);
    let position = (outgoing + incoming) * 0.5;

    let fit = if ctx.outgoing.phrase.phrases_in(candidate.beats).is_some() {
        1.0
    } else {
        PARTIAL_PHRASE_SCORE
    };
    position * PHRASE_POSITION_WEIGHT + fit * (1.0 - PHRASE_POSITION_WEIGHT)
}

/// Stretch demand, graded against the preferred / acceptable / maximum bands.
pub fn tempo(candidate: &Candidate, config: &EngineConfig) -> f32 {
    if !candidate.beatmatched {
        return UNMATCHED_TEMPO_SCORE;
    }
    let tempo = &config.tempo;
    let deviation = candidate.max_ratio_deviation();
    if deviation <= tempo.preferred_ratio_deviation {
        1.0
    } else if deviation <= tempo.acceptable_ratio_deviation {
        let span = tempo.acceptable_ratio_deviation - tempo.preferred_ratio_deviation;
        1.0 - 0.4 * progress(deviation - tempo.preferred_ratio_deviation, span)
    } else if deviation <= tempo.max_ratio_deviation {
        let span = tempo.max_ratio_deviation - tempo.acceptable_ratio_deviation;
        0.6 - 0.4 * progress(deviation - tempo.acceptable_ratio_deviation, span)
    } else {
        0.0
    }
}

/// Whether the two regions occupy compatible parts of the spectrum.
pub fn spectral(outgoing: &RegionFeatures, incoming: &RegionFeatures) -> f32 {
    let distance = (outgoing.low - incoming.low).abs()
        + (outgoing.mid - incoming.mid).abs()
        + (outgoing.high - incoming.high).abs();
    let bands = 1.0 - (distance * 0.5).clamp(0.0, 1.0);

    let centroid = if outgoing.centroid > 1.0 && incoming.centroid > 1.0 {
        let octaves = (outgoing.centroid / incoming.centroid).log2().abs();
        1.0 - (octaves / CENTROID_SPAN_OCTAVES).clamp(0.0, 1.0)
    } else {
        0.5
    };

    (bands + centroid) * 0.5
}

/// Level agreement. A large gap means one track would swamp the other mid-fade.
pub fn loudness(outgoing: &RegionFeatures, incoming: &RegionFeatures) -> f32 {
    let difference = (outgoing.rms_db - incoming.rms_db).abs();
    1.0 - (difference / LOUDNESS_SPAN_DB).clamp(0.0, 1.0)
}

/// Rewards an outgoing region that is winding down and an incoming one that is opening up.
pub fn energy(outgoing: &RegionFeatures, incoming: &RegionFeatures) -> f32 {
    let outro = 1.0 / (1.0 + outgoing.energy_slope.max(0.0) / ENERGY_SLOPE_SCALE);
    let intro = 1.0 / (1.0 + (-incoming.energy_slope).max(0.0) / ENERGY_SLOPE_SCALE);
    (outro + intro) * 0.5
}

/// Penalises two busy regions landing on top of each other.
pub fn transient(outgoing: &RegionFeatures, incoming: &RegionFeatures) -> f32 {
    let collision = outgoing.transient_rate * incoming.transient_rate;
    1.0 - (collision * TRANSIENT_COLLISION_SCALE).clamp(0.0, 1.0)
}

/// Penalises two bass-dominant regions, which sum into mud no fade curve can fix.
pub fn low_freq(outgoing: &RegionFeatures, incoming: &RegionFeatures) -> f32 {
    let shared = outgoing.low.min(incoming.low);
    1.0 - (shared / LOW_FREQ_COLLISION_LIMIT).clamp(0.0, 1.0)
}

/// Closeness to the configured preferred length, measured in octaves of beat count.
pub fn duration(candidate: &Candidate, config: &EngineConfig) -> f32 {
    let preferred = config.strategy.preferred_beats.max(1) as f32;
    let octaves = (candidate.beats.max(1) as f32 / preferred).log2().abs();
    1.0 - (octaves / DURATION_SPAN_OCTAVES).clamp(0.0, 1.0)
}

fn beat_closeness(beats: &BeatAnalysis, time: f64) -> f32 {
    let tolerance = beats.beat_interval() * 0.5;
    if !tolerance.is_finite() || tolerance <= 0.0 {
        return 0.0;
    }
    let distance = beats.distance_to_beat(time);
    if !distance.is_finite() {
        return 0.0;
    }
    (1.0 - distance / tolerance).max(0.0) as f32
}

fn progress(value: f32, span: f32) -> f32 {
    if span <= f32::EPSILON {
        1.0
    } else {
        (value / span).clamp(0.0, 1.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::analysis::PhraseGrid;
    use crate::config::AnalysisConfig;
    use crate::types::analysis::FeatureTrack;

    fn grid(bpm: f32, bars: usize) -> BeatAnalysis {
        let interval = 60.0 / bpm as f64;
        let beats: Vec<f64> = (0..bars * 4).map(|i| i as f64 * interval).collect();
        let downbeats: Vec<f64> = beats.iter().step_by(4).copied().collect();
        BeatAnalysis::new(bpm, beats, downbeats).unwrap()
    }

    fn analysis(beats: &BeatAnalysis) -> TrackAnalysis {
        TrackAnalysis {
            features: FeatureTrack::default(),
            phrase: PhraseGrid::detect(beats, &AnalysisConfig::default()),
            window_start: 0.0,
            window_end: 300.0,
            duration: 300.0,
        }
    }

    fn candidate(outgoing_start: f64, beats: u32, ratio: f32) -> Candidate {
        Candidate {
            outgoing_start,
            incoming_start: 0.0,
            beats,
            duration: beats as f64 * 0.5,
            target_bpm: 120.0,
            outgoing_tempo_ratio: ratio,
            incoming_tempo_ratio: 1.0,
            beatmatched: true,
        }
    }

    fn region(low: f32, mid: f32, high: f32, centroid: f32) -> RegionFeatures {
        RegionFeatures {
            rms: 0.5,
            rms_db: -6.0,
            low,
            mid,
            high,
            centroid,
            ..RegionFeatures::default()
        }
    }

    #[test]
    fn on_beat_entries_score_highest() {
        let beats = grid(120.0, 16);
        let outgoing = analysis(&beats);
        let ctx = ScoringContext {
            outgoing: &outgoing,
            incoming: &outgoing,
            outgoing_beats: &beats,
            incoming_beats: &beats,
        };
        assert!((beat_alignment(&candidate(4.0, 16, 1.0), &ctx) - 1.0).abs() < 1e-6);

        // 4.25s is exactly between two beats at 120 BPM, so the outgoing side contributes
        // nothing while the incoming side (at 0.0s) is still perfectly on the grid.
        let half_aligned = beat_alignment(&candidate(4.25, 16, 1.0), &ctx);
        assert!((half_aligned - 0.5).abs() < 1e-6, "{half_aligned}");
        assert!(beat_alignment(&candidate(4.15, 16, 1.0), &ctx) < half_aligned + 0.3);
    }

    #[test]
    fn phrase_starts_beat_mid_phrase_bars() {
        let beats = grid(120.0, 16);
        let outgoing = analysis(&beats);
        let ctx = ScoringContext {
            outgoing: &outgoing,
            incoming: &outgoing,
            outgoing_beats: &beats,
            incoming_beats: &beats,
        };
        let on_phrase = phrase_alignment(&candidate(8.0, 16, 1.0), &ctx);
        let off_phrase = phrase_alignment(&candidate(10.0, 16, 1.0), &ctx);
        assert!(on_phrase > off_phrase, "{on_phrase} vs {off_phrase}");
    }

    #[test]
    fn partial_phrase_lengths_score_lower_than_whole_ones() {
        let beats = grid(120.0, 16);
        let outgoing = analysis(&beats);
        let ctx = ScoringContext {
            outgoing: &outgoing,
            incoming: &outgoing,
            outgoing_beats: &beats,
            incoming_beats: &beats,
        };
        assert!(
            phrase_alignment(&candidate(8.0, 16, 1.0), &ctx)
                > phrase_alignment(&candidate(8.0, 12, 1.0), &ctx)
        );
    }

    #[test]
    fn tempo_grading_follows_the_configured_bands() {
        let config = EngineConfig::default();
        assert_eq!(tempo(&candidate(0.0, 16, 1.0), &config), 1.0);
        assert_eq!(tempo(&candidate(0.0, 16, 1.04), &config), 1.0);

        let midway = tempo(&candidate(0.0, 16, 1.06), &config);
        assert!((midway - 0.8).abs() < 1e-4, "{midway}");

        let acceptable = tempo(&candidate(0.0, 16, 1.10), &config);
        assert!((acceptable - 0.4).abs() < 1e-4, "{acceptable}");

        assert_eq!(tempo(&candidate(0.0, 16, 1.3), &config), 0.0);
    }

    #[test]
    fn unmatched_candidates_get_a_fixed_tempo_score() {
        let config = EngineConfig::default();
        let mut candidate = candidate(0.0, 16, 1.0);
        candidate.beatmatched = false;
        assert_eq!(tempo(&candidate, &config), UNMATCHED_TEMPO_SCORE);
    }

    #[test]
    fn matching_spectra_score_above_clashing_ones() {
        let warm = region(0.6, 0.3, 0.1, 400.0);
        let bright = region(0.1, 0.3, 0.6, 6_000.0);
        assert!(spectral(&warm, &warm) > 0.95);
        assert!(spectral(&warm, &bright) < 0.4);
    }

    #[test]
    fn loudness_falls_off_with_the_gap() {
        let loud = RegionFeatures {
            rms_db: -6.0,
            ..RegionFeatures::default()
        };
        let quiet = RegionFeatures {
            rms_db: -18.0,
            ..RegionFeatures::default()
        };
        assert_eq!(loudness(&loud, &loud), 1.0);
        assert!(loudness(&loud, &quiet) < 0.01);
    }

    #[test]
    fn a_fading_outro_into_a_building_intro_scores_best() {
        let outro = RegionFeatures {
            energy_slope: -3.0,
            ..RegionFeatures::default()
        };
        let intro = RegionFeatures {
            energy_slope: 3.0,
            ..RegionFeatures::default()
        };
        assert!((energy(&outro, &intro) - 1.0).abs() < 1e-6);
        assert!(energy(&intro, &outro) < 0.7);
    }

    #[test]
    fn busy_regions_colliding_are_penalised() {
        let busy = RegionFeatures {
            transient_rate: 0.5,
            ..RegionFeatures::default()
        };
        let calm = RegionFeatures {
            transient_rate: 0.0,
            ..RegionFeatures::default()
        };
        assert_eq!(transient(&calm, &busy), 1.0);
        assert_eq!(transient(&busy, &busy), 0.0);
    }

    #[test]
    fn shared_bass_dominance_is_penalised() {
        let heavy = region(0.8, 0.15, 0.05, 200.0);
        let light = region(0.1, 0.5, 0.4, 2_000.0);
        assert_eq!(low_freq(&heavy, &heavy), 0.0);
        assert!(low_freq(&heavy, &light) > 0.8);
    }

    #[test]
    fn the_preferred_length_scores_highest() {
        let config = EngineConfig::default();
        assert_eq!(duration(&candidate(0.0, 16, 1.0), &config), 1.0);
        assert!(duration(&candidate(0.0, 8, 1.0), &config) < 1.0);
        assert!(duration(&candidate(0.0, 4, 1.0), &config) == 0.0);
    }

    #[test]
    fn the_total_is_a_weighted_mean_in_range() {
        let beats = grid(120.0, 16);
        let outgoing = analysis(&beats);
        let ctx = ScoringContext {
            outgoing: &outgoing,
            incoming: &outgoing,
            outgoing_beats: &beats,
            incoming_beats: &beats,
        };
        let evaluation = evaluate(&candidate(8.0, 16, 1.0), &ctx, &EngineConfig::default());
        assert!((0.0..=1.0).contains(&evaluation.score.total));
        assert!(evaluation.score.total > 0.0);
    }

    #[test]
    fn an_empty_beat_grid_scores_zero_alignment() {
        let beats = BeatAnalysis::new(120.0, vec![], vec![]).unwrap();
        let outgoing = analysis(&beats);
        let ctx = ScoringContext {
            outgoing: &outgoing,
            incoming: &outgoing,
            outgoing_beats: &beats,
            incoming_beats: &beats,
        };
        assert_eq!(beat_alignment(&candidate(4.0, 16, 1.0), &ctx), 0.0);
    }
}
