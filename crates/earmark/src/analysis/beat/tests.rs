use super::*;

/// Kick (decaying 55 Hz) on every beat, a brighter snare-like burst on beats 2 and 4,
/// and a quieter hat on every off-beat. Deterministic noise keeps runs identical.
fn drums(rate: f64, bpm: f64, seconds: f64, first: f64) -> Vec<f32> {
    let interval = 60.0 / bpm;
    let mut seed = 0x2545_f491_u32;
    let mut noise = move || {
        seed ^= seed << 13;
        seed ^= seed >> 17;
        seed ^= seed << 5;
        seed as f32 / u32::MAX as f32 * 2.0 - 1.0
    };
    let mut out = vec![0.0f32; (seconds * rate) as usize];
    let mut hit = |start: f64, kick: f32, snare: f32, hat: f32| {
        let from = (start * rate) as usize;
        for i in 0..(0.12 * rate) as usize {
            let Some(slot) = out.get_mut(from + i) else {
                return;
            };
            let t = i as f64 / rate;
            let body = (std::f64::consts::TAU * 55.0 * t).sin() as f32 * (-t / 0.05).exp() as f32;
            let burst = noise() * (-t / 0.03).exp() as f32;
            let tick = noise() * (-t / 0.008).exp() as f32;
            *slot += kick * body + snare * burst + hat * tick;
        }
    };
    let mut beat = 0;
    while first + beat as f64 * interval < seconds {
        let start = first + beat as f64 * interval;
        let accent = if beat % 4 == 0 { 1.0 } else { 0.55 };
        let snare = if beat % 2 == 1 { 0.5 } else { 0.0 };
        hit(start, accent, snare, 0.1);
        hit(start + interval / 2.0, 0.0, 0.0, 0.15);
        beat += 1;
    }
    out
}

fn track(samples: &[f32], rate: f64) -> BeatTrack {
    let mut frontends = ModelFrontends::new();
    BeatTracker::new(BeatTrackerConfig::default())
        .track_samples(&mut frontends, samples, rate)
        .unwrap()
}

fn nearest(grid: &[f64], time: f64) -> f64 {
    grid.iter()
        .map(|b| (b - time).abs())
        .fold(f64::INFINITY, f64::min)
}

#[test]
fn steady_drums_lock_tempo_and_phase() {
    let first = 0.37;
    let result = track(&drums(22_050.0, 120.0, 30.0, first), 22_050.0);
    assert!((result.bpm - 120.0).abs() < 1.0, "bpm {}", result.bpm);
    for k in 4..56 {
        let truth = first + k as f64 * 0.5;
        assert!(
            nearest(&result.beats, truth) < 0.03,
            "beat {truth}: {:?}",
            result.beats
        );
    }
    assert!(result.confidence > 0.7, "confidence {}", result.confidence);
}

#[test]
fn accented_first_beats_become_downbeats() {
    let first = 0.37;
    let result = track(&drums(22_050.0, 110.0, 30.0, first), 22_050.0);
    assert_eq!(result.beats_per_bar, 4);
    let bar = 4.0 * 60.0 / 110.0;
    let inside: Vec<f64> = result
        .downbeats
        .iter()
        .copied()
        .filter(|t| (4.0..26.0).contains(t))
        .collect();
    assert!(inside.len() >= 8, "{:?}", result.downbeats);
    for downbeat in inside {
        let bars = (downbeat - first) / bar;
        assert!(
            (bars - bars.round()).abs() * bar < 0.04,
            "downbeat {downbeat} is off the bar line"
        );
    }
}

#[test]
fn eleven_khz_analysis_audio_keeps_beat_timing() {
    let first = 0.41;
    let result = track(&drums(11_025.0, 96.0, 30.0, first), 11_025.0);
    assert!((result.bpm - 96.0).abs() < 1.0, "bpm {}", result.bpm);
    let interval = 60.0 / 96.0;
    for k in 4..44 {
        let truth = first + k as f64 * interval;
        assert!(nearest(&result.beats, truth) < 0.03, "beat {truth}");
    }
}

#[test]
fn silence_has_no_beats() {
    let result = track(&vec![0.0; 22_050 * 20], 22_050.0);
    assert!(result.beats.is_empty());
    assert_eq!(result.confidence, 0.0);
}

#[test]
fn caller_beats_get_bar_positions() {
    let first = 0.37;
    let audio = drums(22_050.0, 120.0, 20.0, first);
    let mut frontends = ModelFrontends::new();
    let spectrogram = frontends.beat_spectrogram(&audio, 22_050.0).unwrap();
    let beats: Vec<f64> = (0..38).map(|k| first + k as f64 * 0.5).collect();
    let (downbeats, meter) =
        BeatTracker::new(BeatTrackerConfig::default()).downbeats(&spectrogram.values, &beats);
    assert_eq!(meter, 4);
    assert!(
        downbeats.iter().all(|t| ((t - first) / 2.0)
            .fract()
            .min(1.0 - ((t - first) / 2.0).fract())
            < 0.01),
        "{downbeats:?}"
    );
}

#[test]
fn leading_silence_gets_no_beats_but_the_music_does() {
    let mut audio = vec![0.0f32; 22_050 * 5];
    audio.extend(drums(22_050.0, 120.0, 25.0, 0.0));
    let result = track(&audio, 22_050.0);
    assert!(
        result.beats.first().is_some_and(|b| (4.9..5.2).contains(b)),
        "{:?}",
        &result.beats[..4]
    );
}

#[test]
fn reported_tempo_is_not_quantized_to_whole_frames() {
    // Beat gaps that are not whole 20 ms frames; the median gap is off by up to 1% here.
    for bpm in [83.2, 98.7, 117.3, 128.4, 140.0, 161.0] {
        let result = track(&drums(22_050.0, bpm, 60.0, 0.37), 22_050.0);
        // The pulse may be counted at half or double time; only precision matters here.
        let level = [0.5, 1.0, 2.0]
            .into_iter()
            .map(|k| bpm * k)
            .min_by(|a, b| (a - result.bpm).abs().total_cmp(&(b - result.bpm).abs()))
            .unwrap();
        let error = (result.bpm / level - 1.0).abs() * 100.0;
        assert!(
            error < 0.05,
            "{bpm} BPM reported as {} ({error:.3} %)",
            result.bpm
        );
    }
}
