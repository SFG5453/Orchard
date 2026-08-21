//! Benchmarks for the paths that run per transition.

use criterion::{Criterion, criterion_group, criterion_main};
use std::hint::black_box;

use earmark::analysis::SpectrumAnalyzer;
use earmark::config::{AnalysisConfig, EngineConfig, FilterConfig};
use earmark::dsp::automation::{AutomationCurve, CurveShape};
use earmark::dsp::filters::{FilterAutomation, FilterKind, FilterSweep};
use earmark::dsp::mixer;
use earmark::{AudioBuffer, BeatAnalysis, SmartCrossfadeEngine, TransitionPlan};

const SR: u32 = 44_100;
/// Eight seconds, a typical 16-beat transition at 120 BPM.
const TRANSITION_FRAMES: usize = 8 * SR as usize;

fn sine(freq: f32, frames: usize) -> Vec<f32> {
    (0..frames)
        .map(|i| (i as f32 / SR as f32 * freq * std::f32::consts::TAU).sin())
        .collect()
}

fn beats(bpm: f32, seconds: f64) -> BeatAnalysis {
    let interval = 60.0 / bpm as f64;
    let count = (seconds / interval) as usize;
    let times: Vec<f64> = (0..count).map(|i| i as f64 * interval).collect();
    let downbeats: Vec<f64> = times.iter().step_by(4).copied().collect();
    BeatAnalysis::new(bpm, times, downbeats).unwrap()
}

fn track(bpm: f32, seconds: f64) -> AudioBuffer {
    let frames = (seconds * SR as f64) as usize;
    let beat_frames = ((60.0 / bpm) * SR as f32) as usize;
    let mut channel = vec![0.0f32; frames];
    for (i, sample) in channel.iter_mut().enumerate() {
        let t = i as f32 / SR as f32;
        let beat_phase = (i % beat_frames.max(1)) as f32 / SR as f32;
        *sample = 0.6 * (-beat_phase * 14.0).exp() * (t * 55.0 * std::f32::consts::TAU).sin()
            + 0.2 * (t * 440.0 * std::f32::consts::TAU).sin();
    }
    AudioBuffer::new(vec![channel; 2], SR).unwrap()
}

fn bench_mixing(c: &mut Criterion) {
    let source = sine(440.0, TRANSITION_FRAMES);
    let envelope = sine(0.5, TRANSITION_FRAMES);
    let mut destination = vec![0.0f32; TRANSITION_FRAMES];

    c.bench_function("mix_channel_8s", |b| {
        b.iter(|| {
            mixer::mix_into(
                black_box(&mut destination),
                black_box(&source),
                black_box(&envelope),
            )
        })
    });
}

fn bench_spectrum(c: &mut Criterion) {
    let config = AnalysisConfig::default();
    let mut analyzer = SpectrumAnalyzer::new(&config).unwrap();
    let signal = sine(440.0, 10 * SR as usize);

    c.bench_function("stft_features_10s", |b| {
        b.iter(|| analyzer.analyze(black_box(&signal), SR, 0.0, black_box(&config)))
    });
}

fn bench_automation(c: &mut Criterion) {
    let mut out = vec![0.0f32; TRANSITION_FRAMES];
    let mut group = c.benchmark_group("automation_fill_8s");

    // What the renderer actually evaluates per sample: a gain ride.
    let gain = AutomationCurve::ramp(1.0, 0.5, CurveShape::SmoothStep);
    group.bench_function("gain_ride", |b| b.iter(|| gain.fill(black_box(&mut out))));

    // Worst case: geometric interpolation costs a `powf` per sample. Filter cutoffs use this
    // shape, but only once per coefficient block rather than once per sample.
    let cutoff = AutomationCurve::ramp(18_000.0, 400.0, CurveShape::Logarithmic);
    group.bench_function("log_sweep", |b| b.iter(|| cutoff.fill(black_box(&mut out))));
    group.finish();
}

fn bench_filters(c: &mut Criterion) {
    let config = FilterConfig::default();
    let automation = FilterAutomation::new(
        FilterKind::LowPass,
        AutomationCurve::ramp(18_000.0, 400.0, CurveShape::Logarithmic),
        config.q,
    );
    let sweep = FilterSweep::plan(&automation, TRANSITION_FRAMES, SR, &config).unwrap();
    let mut samples = sine(1_000.0, TRANSITION_FRAMES);

    c.bench_function("filter_sweep_8s", |b| {
        b.iter(|| sweep.apply(black_box(&mut samples)))
    });
    c.bench_function("filter_sweep_plan", |b| {
        b.iter(|| {
            FilterSweep::plan(
                black_box(&automation),
                TRANSITION_FRAMES,
                SR,
                black_box(&config),
            )
            .unwrap()
        })
    });
}

fn bench_planning(c: &mut Criterion) {
    let outgoing = track(124.0, 60.0);
    let incoming = track(126.0, 60.0);
    let outgoing_beats = beats(124.0, 60.0);
    let incoming_beats = beats(126.0, 60.0);
    let mut engine = SmartCrossfadeEngine::new(EngineConfig::default()).unwrap();

    c.bench_function("analyze_and_plan", |b| {
        b.iter(|| {
            engine
                .analyze(
                    black_box(&outgoing),
                    black_box(&incoming),
                    black_box(&outgoing_beats),
                    black_box(&incoming_beats),
                )
                .unwrap()
        })
    });
}

fn bench_rendering(c: &mut Criterion) {
    let outgoing = track(124.0, 60.0);
    let incoming = track(126.0, 60.0);
    let mut engine = SmartCrossfadeEngine::new(EngineConfig::default()).unwrap();
    let plan = engine
        .analyze(
            &outgoing,
            &incoming,
            &beats(124.0, 60.0),
            &beats(126.0, 60.0),
        )
        .unwrap();

    let mut group = c.benchmark_group("render");
    group.bench_function("beatmatched", |b| {
        b.iter(|| {
            engine
                .render(black_box(&outgoing), black_box(&incoming), black_box(&plan))
                .unwrap()
        })
    });

    // Isolates the mixing and filter work from the cost of time stretching.
    let unmatched = TransitionPlan {
        outgoing_tempo_ratio: 1.0,
        incoming_tempo_ratio: 1.0,
        ..plan.clone()
    };
    group.bench_function("native_tempo", |b| {
        b.iter(|| {
            engine
                .render(
                    black_box(&outgoing),
                    black_box(&incoming),
                    black_box(&unmatched),
                )
                .unwrap()
        })
    });
    group.finish();
}

criterion_group!(
    benches,
    bench_mixing,
    bench_spectrum,
    bench_automation,
    bench_filters,
    bench_planning,
    bench_rendering
);
criterion_main!(benches);
