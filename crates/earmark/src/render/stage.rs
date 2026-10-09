//! Per-stem preparation and the mix-down helpers the renderer drives.

use crate::audio::stretch::TimeStretcher;
use crate::audio::{AudioBuffer, resample};
use crate::config::{EngineConfig, FilterConfig};
use crate::dsp::filters::{FilterAutomation, FilterKind, FilterSweep};
use crate::dsp::gain::{db_to_linear, linear_to_db};
use crate::dsp::{limiter, mixer};
use crate::error::Result;
use crate::types::TempoGlide;

/// Stretch ratios within this of 1.0 are treated as no stretch at all, which skips the
/// stretcher entirely for the common unmatched case.
const RATIO_EPSILON: f32 = 1e-4;

/// Crossfade onto unstretched source where a glide sits at native tempo, so the render joins
/// native playback without a phase jump.
const SPLICE_FRAMES: usize = 1024;

/// Widest shift searched when aligning a glide's native end with its source. Stretched output
/// drifts in phase, and a splice onto a misaligned copy cancels tonal content.
const SPLICE_SEARCH_FRAMES: i64 = 256;

/// Source kept untouched at a glide's native-tempo ends, where the glide is within a fraction
/// of a percent of native speed. Hosts join a little way into the render and match the join on
/// audio, which only lines up exactly against the source itself.
const NATIVE_EDGE_SECONDS: f64 = 0.25;

/// One side of the transition, prepared and ready to mix.
pub struct Stem {
    pub audio: AudioBuffer,
    /// Seconds of source actually consumed, which differs from the planned amount only when a
    /// stretch had to be skipped.
    pub consumed: f64,
}

/// What one side of the transition needs to become, taken from the plan.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct StemRequest {
    /// Position in the source track, in seconds.
    pub start: f64,
    /// Length of the transition in output time.
    pub duration: f64,
    /// Source seconds consumed per output second.
    pub ratio: f32,
    pub semitones: f32,
    pub frames: usize,
    pub sample_rate: u32,
    pub channels: usize,
    /// Time-varying ratio whose average is `ratio`. `None` stretches at `ratio` throughout.
    pub glide: Option<TempoGlide>,
}

/// Extracts one side at the target rate and layout, stretched to exactly `request.frames`.
///
/// A transition too short for the stretcher's latency window is rendered at native tempo rather
/// than failing; [`Stem::consumed`] then reports what was really used, so the caller's resume
/// positions stay honest.
pub fn prepare(
    stretcher: &mut TimeStretcher,
    source: &AudioBuffer,
    request: &StemRequest,
) -> Result<Stem> {
    if let Some(glide) = request.glide.filter(|glide| !glide.is_native())
        && stretcher.can_process(request.frames)
    {
        return prepare_glide(stretcher, source, request, glide);
    }
    let stretching = (request.ratio - 1.0).abs() > RATIO_EPSILON || request.semitones != 0.0;
    let apply_stretch = stretching && stretcher.can_process(request.frames);
    let consumed = if apply_stretch {
        request.duration * request.ratio as f64
    } else {
        request.duration
    };

    let mut slice = source.slice_seconds(request.start, consumed);
    if slice.sample_rate() != request.sample_rate {
        slice = resample::resample(&slice, request.sample_rate)?;
    }
    if slice.channel_count() != request.channels {
        slice = slice.to_channel_count(request.channels)?;
    }

    let audio = if apply_stretch {
        stretcher.process(&slice, request.frames, request.semitones)?
    } else if slice.frames() == request.frames {
        slice
    } else {
        slice.slice(0, request.frames)
    };

    Ok(Stem { audio, consumed })
}

fn prepare_glide(
    stretcher: &mut TimeStretcher,
    source: &AudioBuffer,
    request: &StemRequest,
    glide: TempoGlide,
) -> Result<Stem> {
    let duration = request.duration;
    let consumed = glide.offset(duration, duration);
    let rate = request.sample_rate as f64;
    // Real audio on both sides of the span; glides stay well under 2x, so one context drains.
    let context = stretcher.context_frames() as f64 / rate;
    let first = source.frame_index(request.start - context);
    let lead = request.start - first as f64 / source.sample_rate() as f64;
    let span = ((lead + consumed + context) * source.sample_rate() as f64).ceil() as usize;
    let mut slice = source.slice(first, span);
    if slice.sample_rate() != request.sample_rate {
        slice = resample::resample(&slice, request.sample_rate)?;
    }
    if slice.channel_count() != request.channels {
        slice = slice.to_channel_count(request.channels)?;
    }

    let origin = lead * rate;
    let mut audio = stretcher.process_mapped(
        &slice,
        origin,
        request.frames,
        request.semitones,
        |frame| glide.offset(frame as f64 / rate, duration) * rate,
    )?;
    let splice = SPLICE_FRAMES.min(request.frames / 2);
    let hold = ((NATIVE_EDGE_SECONDS * rate) as usize).min(request.frames / 4);
    if (glide.start - 1.0).abs() < 1e-9 {
        // The stretcher was seeded from the source, so its output is still in phase with it.
        splice_start(&mut audio, &slice, origin.round() as usize, hold, splice);
    }
    let mut consumed = consumed;
    if (glide.end - 1.0).abs() < 1e-9 {
        let end = (origin + consumed * rate).round() as i64;
        let aligned = aligned_end(&audio, &slice, end, hold, splice);
        splice_end(&mut audio, &slice, aligned, hold, splice);
        // Native playback resumes where the splice actually landed.
        consumed += (aligned - end) as f64 / rate;
    }
    Ok(Stem { audio, consumed })
}

/// Plays the source from `head` untouched for `hold` frames, then fades into the stretched
/// render over `splice` frames.
fn splice_start(
    audio: &mut AudioBuffer,
    source: &AudioBuffer,
    head: usize,
    hold: usize,
    splice: usize,
) {
    for channel in 0..audio.channel_count() {
        let raw = source.channel(channel);
        let out = audio.channel_mut(channel);
        for (index, sample) in out.iter_mut().take(hold + splice).enumerate() {
            let weight = index
                .checked_sub(hold)
                .map_or(0.0, |step| (step as f32 + 0.5) / splice as f32);
            let native = raw.get(head + index).copied().unwrap_or(0.0);
            *sample = native + (*sample - native) * weight;
        }
    }
}

/// Source frame near `end` whose audio best matches the render's crossfade region: the
/// `splice` frames before its untouched last `hold`.
fn aligned_end(
    audio: &AudioBuffer,
    source: &AudioBuffer,
    end: i64,
    hold: usize,
    splice: usize,
) -> i64 {
    let frames = audio.frames();
    let score = |lag: i64| -> Option<f64> {
        let from = end + lag - (hold + splice) as i64;
        if from < 0 || end + lag > source.frames() as i64 {
            return None;
        }
        let (mut dot, mut energy) = (0.0f64, 0.0f64);
        for channel in 0..audio.channel_count() {
            let out = &audio.channel(channel)[frames - hold - splice..frames - hold];
            let raw = &source.channel(channel)[from as usize..(from as usize + splice)];
            for (rendered, native) in out.iter().zip(raw) {
                dot += (*rendered as f64) * (*native as f64);
                energy += (*native as f64) * (*native as f64);
            }
        }
        Some(if energy > 1e-12 { dot / energy.sqrt() } else { 0.0 })
    };
    // Ties keep the planned position, so silence never shifts the handoff.
    let mut best = (score(0).unwrap_or(f64::MIN), 0);
    for distance in 1..=SPLICE_SEARCH_FRAMES {
        for lag in [-distance, distance] {
            if let Some(value) = score(lag)
                && value > best.0 + 1e-9
            {
                best = (value, lag);
            }
        }
    }
    end + best.1
}

/// Fades the render into the source ending at frame `end` over `splice` frames, then plays the
/// source untouched for the last `hold` frames.
fn splice_end(audio: &mut AudioBuffer, source: &AudioBuffer, end: i64, hold: usize, splice: usize) {
    let frames = audio.frames();
    for channel in 0..audio.channel_count() {
        let raw = source.channel(channel);
        let out = audio.channel_mut(channel);
        for step in 0..hold + splice {
            let weight = step
                .checked_sub(hold)
                .map_or(0.0, |step| (step as f32 + 0.5) / splice as f32);
            let native = usize::try_from(end - 1 - step as i64)
                .ok()
                .and_then(|index| raw.get(index))
                .copied()
                .unwrap_or(0.0);
            let frame = frames - 1 - step;
            out[frame] = native + (out[frame] - native) * weight;
        }
    }
}

pub fn apply_filters(
    audio: &mut AudioBuffer,
    filters: &[FilterAutomation],
    config: &EngineConfig,
) -> Result<()> {
    if filters.is_empty() {
        return Ok(());
    }
    let frames = audio.frames();
    let sample_rate = audio.sample_rate();
    for automation in filters {
        let sweep = FilterSweep::plan(automation, frames, sample_rate, &config.filters)?;
        for channel in audio.planar_mut() {
            sweep.apply(channel);
        }
    }
    Ok(())
}

/// Which end of a stem meets native playback.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Join {
    /// Native playback runs up to the first frame (the outgoing side).
    Start,
    /// Native playback resumes after the last frame (the incoming side).
    End,
}

/// Crossfades a filtered stem to its `dry` source across the span where every filter rests
/// open at `join`.
///
/// An open filter still turns phase near its corner (a 20 Hz high-pass shifts 50 Hz by about
/// 34 degrees), so leaving it in place steps the bass waveform when native playback takes over.
/// A filter that is still closed at the join keeps its effect.
pub fn release_filters(
    filtered: &mut AudioBuffer,
    dry: &AudioBuffer,
    filters: &[FilterAutomation],
    join: Join,
    config: &FilterConfig,
) {
    let frames = filtered.frames();
    if filters.is_empty() || frames == 0 || dry.frames() != frames {
        return;
    }
    let at = if join == Join::Start { 0.0 } else { 1.0 };
    if !filters
        .iter()
        .all(|filter| rests_open(filter, filter.cutoff.value_at(at), config))
    {
        return;
    }
    let open = match join {
        Join::Start => filters.iter().map(|f| f.cutoff.holds_until()).fold(1.0, f32::min),
        Join::End => 1.0 - filters.iter().map(|f| f.cutoff.settles_at()).fold(0.0, f32::max),
    };
    let span = ((open as f64 * frames as f64).round() as usize)
        .max(SPLICE_FRAMES)
        .min(frames);
    for (out, source) in filtered.planar_mut().iter_mut().zip(dry.planar()) {
        for step in 0..span {
            // Dry weight: 1 at the join, 0 where the filter starts to move.
            let weight = 1.0 - step as f32 / span as f32;
            let frame = if join == Join::Start { step } else { frames - 1 - step };
            out[frame] = source[frame] * weight + out[frame] * (1.0 - weight);
        }
    }
}

fn rests_open(filter: &FilterAutomation, cutoff: f32, config: &FilterConfig) -> bool {
    match filter.kind {
        FilterKind::HighPass => cutoff <= config.highpass_min_hz * 1.01,
        FilterKind::LowPass => cutoff >= config.lowpass_max_hz * 0.99,
        FilterKind::LowShelf | FilterKind::HighShelf | FilterKind::Peaking => {
            filter.gain_db.abs() < 1e-3
        }
    }
}

/// `mixed += stem * envelope`, across every channel.
pub fn mix_stem(mixed: &mut AudioBuffer, stem: &AudioBuffer, envelope: &[f32]) {
    for channel in 0..mixed.channel_count() {
        mixer::mix_into(mixed.channel_mut(channel), stem.channel(channel), envelope);
    }
}

/// Limits only the peaks that summing the two sides adds.
///
/// `outgoing` and `incoming` are each side's per-frame peak as mixed. The sum may reach the
/// ceiling or either side's own nearby peak, whichever is higher: a hot master keeps its level,
/// and the render meets native playback at unity gain on both ends. A whole-mix trim would
/// step the level at both joins. Returns the deepest reduction in dB, or zero.
pub fn limit_sum(
    mixed: &mut AudioBuffer,
    outgoing: &[f32],
    incoming: &[f32],
    config: &EngineConfig,
) -> f32 {
    let loudness = &config.loudness;
    if !loudness.prevent_clipping || mixed.is_empty() {
        return 0.0;
    }
    let rate = mixed.sample_rate() as f32;
    let lookahead = ((loudness.limiter_lookahead_ms / 1000.0 * rate).round() as usize).max(1);
    let ceiling = db_to_linear(loudness.ceiling_db);
    let outgoing = limiter::held(outgoing, lookahead);
    let incoming = limiter::held(incoming, lookahead);
    let mut peaks = Vec::new();
    limiter::frame_peaks(mixed.planar(), None, &mut peaks);
    let side = |levels: &[f32], frame: usize| levels.get(frame).copied().unwrap_or(0.0);
    let mut limited = false;
    let required: Vec<f32> = peaks
        .iter()
        .enumerate()
        .map(|(frame, &peak)| {
            let allowed = ceiling
                .max(side(&outgoing, frame))
                .max(side(&incoming, frame));
            if peak > allowed {
                limited = true;
                allowed / peak
            } else {
                1.0
            }
        })
        .collect();
    if !limited {
        return 0.0;
    }
    let release = (-1.0 / (loudness.limiter_release_ms / 1000.0 * rate)).exp();
    let gain = limiter::lookahead_gain(&required, lookahead, release);
    for channel in mixed.planar_mut() {
        mixer::apply_envelope(channel, &gain);
    }
    linear_to_db(gain.iter().copied().fold(1.0, f32::min))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::LoudnessConfig;
    use crate::dsp::automation::{AutomationCurve, AutomationPoint, CurveShape};
    use crate::dsp::gain::rms;

    const SR: u32 = 48_000;

    fn tone(freq: f32, seconds: f64, amplitude: f32, channels: usize, rate: u32) -> AudioBuffer {
        let frames = (seconds * rate as f64) as usize;
        let channel: Vec<f32> = (0..frames)
            .map(|i| amplitude * (i as f32 / rate as f32 * freq * std::f32::consts::TAU).sin())
            .collect();
        AudioBuffer::new(vec![channel; channels], rate).unwrap()
    }

    fn stretcher() -> TimeStretcher {
        TimeStretcher::new(2, SR).unwrap()
    }

    fn request(duration: f64, ratio: f32, frames: usize) -> StemRequest {
        StemRequest {
            start: 1.0,
            duration,
            ratio,
            semitones: 0.0,
            frames,
            sample_rate: SR,
            channels: 2,
            glide: None,
        }
    }

    #[test]
    fn preparation_hits_the_requested_frame_count() {
        let mut stretcher = stretcher();
        let frames = 2 * SR as usize;
        let stem = prepare(
            &mut stretcher,
            &tone(440.0, 10.0, 0.5, 2, SR),
            &request(2.0, 1.0, frames),
        )
        .unwrap();
        assert_eq!(stem.audio.frames(), frames);
        assert!((stem.consumed - 2.0).abs() < 1e-9);
    }

    #[test]
    fn a_stretched_stem_consumes_more_source() {
        let mut stretcher = stretcher();
        let frames = 2 * SR as usize;
        let stem = prepare(
            &mut stretcher,
            &tone(440.0, 10.0, 0.5, 2, SR),
            &request(2.0, 1.06, frames),
        )
        .unwrap();
        assert_eq!(stem.audio.frames(), frames);
        assert!((stem.consumed - 2.0 * 1.06f32 as f64).abs() < 1e-9);
        assert!(stem.consumed > 2.0);
    }

    #[test]
    fn a_transition_too_short_to_stretch_falls_back_to_native_tempo() {
        let mut stretcher = stretcher();
        let frames = stretcher.min_output_frames() - 1;
        let duration = frames as f64 / SR as f64;
        let stem = prepare(
            &mut stretcher,
            &tone(440.0, 10.0, 0.5, 2, SR),
            &request(duration, 1.06, frames),
        )
        .unwrap();
        assert_eq!(stem.audio.frames(), frames);
        assert!((stem.consumed - duration).abs() < 1e-9);
    }

    #[test]
    fn rate_and_layout_are_converted_during_preparation() {
        let mut stretcher = stretcher();
        let frames = SR as usize;
        let stem = prepare(
            &mut stretcher,
            &tone(440.0, 10.0, 0.5, 1, 44_100),
            &request(1.0, 1.0, frames),
        )
        .unwrap();
        assert_eq!(stem.audio.frames(), frames);
        assert_eq!(stem.audio.channel_count(), 2);
        assert_eq!(stem.audio.sample_rate(), SR);
    }

    #[test]
    fn filters_are_applied_to_every_channel() {
        let config = EngineConfig::default();
        let mut audio = tone(8_000.0, 1.0, 0.8, 2, SR);
        let before = rms(audio.channel(1));

        apply_filters(
            &mut audio,
            &[FilterAutomation::new(
                FilterKind::LowPass,
                AutomationCurve::ramp(500.0, 500.0, CurveShape::Logarithmic),
                config.filters.q,
            )],
            &config,
        )
        .unwrap();

        assert!(rms(audio.channel(0)) < before * 0.1);
        assert_eq!(audio.channel(0), audio.channel(1));
    }

    #[test]
    fn an_empty_filter_list_is_a_no_op() {
        let config = EngineConfig::default();
        let mut audio = tone(1_000.0, 0.5, 0.5, 2, SR);
        let original = audio.clone();
        apply_filters(&mut audio, &[], &config).unwrap();
        assert_eq!(audio, original);
    }

    #[test]
    fn open_filters_hand_the_join_back_to_the_dry_source() {
        let config = EngineConfig::default();
        let dry = tone(45.0, 2.0, 0.5, 2, SR);
        let filters = [FilterAutomation::new(
            FilterKind::HighPass,
            AutomationCurve::from_points(vec![
                AutomationPoint::new(0.0, 180.0, CurveShape::Logarithmic),
                AutomationPoint::new(0.6, 20.0, CurveShape::Linear),
                AutomationPoint::new(1.0, 20.0, CurveShape::Linear),
            ]),
            config.filters.q,
        )];
        let mut audio = dry.clone();
        apply_filters(&mut audio, &filters, &config).unwrap();
        let filtered = audio.clone();
        release_filters(&mut audio, &dry, &filters, Join::End, &config.filters);

        let last = audio.frames() - 1;
        assert_eq!(audio.channel(0)[last], dry.channel(0)[last]);
        let tail = |buffer: &AudioBuffer| buffer.channel(0)[last - 480..].to_vec();
        let error = |a: &[f32], b: &[f32]| {
            a.iter().zip(b).map(|(x, y)| (x - y).abs()).fold(0.0, f32::max)
        };
        assert!(error(&tail(&filtered), &tail(&dry)) > 0.05, "the tone should expose the corner");
        assert!(error(&tail(&audio), &tail(&dry)) < 0.02);
        // The sweep itself is untouched.
        let middle = audio.frames() * 3 / 10;
        assert_eq!(audio.channel(0)[middle], filtered.channel(0)[middle]);
    }

    #[test]
    fn a_filter_still_closed_at_the_join_keeps_its_effect() {
        let config = EngineConfig::default();
        let dry = tone(45.0, 1.0, 0.5, 2, SR);
        let filters = [FilterAutomation::new(
            FilterKind::HighPass,
            AutomationCurve::ramp(20.0, 180.0, CurveShape::Logarithmic),
            config.filters.q,
        )];
        let mut audio = dry.clone();
        apply_filters(&mut audio, &filters, &config).unwrap();
        let filtered = audio.clone();
        release_filters(&mut audio, &dry, &filters, Join::End, &config.filters);
        assert_eq!(audio, filtered);
        // The same ride is open where it starts, so that end releases.
        release_filters(&mut audio, &dry, &filters, Join::Start, &config.filters);
        assert_eq!(audio.channel(0)[0], dry.channel(0)[0]);
    }

    #[test]
    fn mixing_accumulates_both_stems() {
        let mut mixed = AudioBuffer::silent(2, 4, SR).unwrap();
        let stem = AudioBuffer::new(vec![vec![1.0; 4], vec![1.0; 4]], SR).unwrap();
        mix_stem(&mut mixed, &stem, &[0.5; 4]);
        mix_stem(&mut mixed, &stem, &[0.25; 4]);
        assert_eq!(mixed.channel(0), &[0.75; 4]);
        assert_eq!(mixed.channel(1), &[0.75; 4]);
    }

    /// Sums two sides at unity and returns the mix with each side's peaks.
    fn summed(a: &AudioBuffer, b: &AudioBuffer) -> (AudioBuffer, Vec<f32>, Vec<f32>) {
        let mut mixed = AudioBuffer::silent(2, a.frames(), SR).unwrap();
        let unity = vec![1.0; a.frames()];
        mix_stem(&mut mixed, a, &unity);
        mix_stem(&mut mixed, b, &unity);
        let (mut left, mut right) = (Vec::new(), Vec::new());
        limiter::frame_peaks(a.planar(), None, &mut left);
        limiter::frame_peaks(b.planar(), None, &mut right);
        (mixed, left, right)
    }

    #[test]
    fn the_limiter_only_engages_where_summing_overshoots() {
        let config = EngineConfig::default();
        let (mut quiet, a, b) = summed(&tone(440.0, 0.5, 0.3, 2, SR), &tone(440.0, 0.5, 0.3, 2, SR));
        let untouched = quiet.clone();
        assert_eq!(limit_sum(&mut quiet, &a, &b, &config), 0.0);
        assert_eq!(quiet, untouched);

        let (mut loud, a, b) = summed(&tone(440.0, 0.5, 0.6, 2, SR), &tone(440.0, 0.5, 0.6, 2, SR));
        assert!(limit_sum(&mut loud, &a, &b, &config) < 0.0);
        assert!(loud.peak() <= db_to_linear(config.loudness.ceiling_db) + 1e-4);
    }

    #[test]
    fn a_side_already_past_the_ceiling_keeps_its_level() {
        let config = EngineConfig::default();
        let hot = tone(440.0, 0.5, 1.5, 2, SR);
        let (mut mixed, a, b) = summed(&hot, &AudioBuffer::silent(2, hot.frames(), SR).unwrap());
        assert_eq!(limit_sum(&mut mixed, &a, &b, &config), 0.0);
        assert_eq!(mixed, hot);
    }

    #[test]
    fn silence_is_never_limited() {
        let config = EngineConfig::default();
        let mut silent = AudioBuffer::silent(2, 128, SR).unwrap();
        assert_eq!(limit_sum(&mut silent, &[0.0; 128], &[0.0; 128], &config), 0.0);
    }

    #[test]
    fn the_limiter_can_be_disabled() {
        let config = EngineConfig {
            loudness: LoudnessConfig {
                prevent_clipping: false,
                ..LoudnessConfig::default()
            },
            ..EngineConfig::default()
        };
        let (mut loud, a, b) = summed(&tone(440.0, 0.5, 0.9, 2, SR), &tone(440.0, 0.5, 0.9, 2, SR));
        assert_eq!(limit_sum(&mut loud, &a, &b, &config), 0.0);
        assert!(loud.peak() > 1.0);
    }

    /// A 5 ms, 2 kHz burst on every beat from time zero.
    fn clicks(bpm: f64, seconds: f64) -> AudioBuffer {
        let frames = (seconds * SR as f64) as usize;
        let interval = 60.0 / bpm;
        let channel: Vec<f32> = (0..frames)
            .map(|i| {
                let time = i as f64 / SR as f64;
                let since = time - (time / interval).floor() * interval;
                if since < 0.005 {
                    (0.8 * (since * 2_000.0 * std::f64::consts::TAU).sin()) as f32
                } else {
                    0.0
                }
            })
            .collect();
        AudioBuffer::new(vec![channel; 2], SR).unwrap()
    }

    fn onsets(audio: &AudioBuffer) -> Vec<f64> {
        let samples = audio.channel(0);
        let threshold = 0.5 * samples.iter().fold(0.0f32, |peak, s| peak.max(s.abs()));
        let mut found = Vec::new();
        let mut quiet = usize::MAX;
        for (index, sample) in samples.iter().enumerate() {
            if sample.abs() >= threshold {
                if quiet > SR as usize / 10 {
                    found.push(index as f64 / SR as f64);
                }
                quiet = 0;
            } else {
                quiet = quiet.saturating_add(1);
            }
        }
        found
    }

    fn glided(source: &AudioBuffer, start: f64, duration: f64, glide: TempoGlide) -> Stem {
        let request = StemRequest {
            start,
            duration,
            ratio: glide.average() as f32,
            semitones: 0.0,
            frames: (duration * SR as f64).round() as usize,
            sample_rate: SR,
            channels: 2,
            glide: Some(glide),
        };
        prepare(&mut stretcher(), source, &request).unwrap()
    }

    #[test]
    fn glided_sides_stay_on_one_beat_grid() {
        let (from, to, beats) = (120.0f64, 130.0, 16.0);
        let log_mean = (to - from) / (to / from).ln();
        let duration = beats * 60.0 / log_mean;
        let (outgoing_glide, incoming_glide) = TempoGlide::pair(log_mean / from, log_mean / to);
        let outgoing = glided(&clicks(from, 40.0), 10.0, duration, outgoing_glide);
        let incoming = glided(&clicks(to, 40.0), 10.0 * 60.0 / to, duration, incoming_glide);
        assert!((outgoing.consumed - beats * 60.0 / from).abs() < 1e-9);
        let search = SPLICE_SEARCH_FRAMES as f64 / SR as f64;
        assert!((incoming.consumed - beats * 60.0 / to).abs() <= search);

        // Beat 16 belongs to native playback; the aligned splice may pull its onset a hair early.
        let within = |audio: &AudioBuffer| -> Vec<f64> {
            onsets(audio).into_iter().filter(|time| *time < duration - 0.01).collect()
        };
        let (outgoing, incoming) = (within(&outgoing.audio), within(&incoming.audio));
        assert_eq!(outgoing.len(), beats as usize, "{outgoing:?}");
        assert_eq!(incoming.len(), beats as usize, "{incoming:?}");
        for (beat, (left, right)) in outgoing.iter().zip(&incoming).enumerate() {
            // Beat `k` lands where the shared tempo has covered k beats.
            let tempo = from + (to - from) * beat as f64 / beats;
            let expected = 60.0 * beats / (to - from) * (tempo / from).ln();
            assert!((left - expected).abs() < 0.01, "outgoing beat {beat}: {left} vs {expected}");
            assert!((right - expected).abs() < 0.01, "incoming beat {beat}: {right} vs {expected}");
        }
    }

    #[test]
    fn a_glide_opens_and_closes_on_the_untouched_source() {
        let log_mean = 10.0 / (130.0f64 / 120.0).ln();
        let (outgoing_glide, incoming_glide) =
            TempoGlide::pair(log_mean / 120.0, log_mean / 130.0);
        let source = tone(1_760.0, 20.0, 0.5, 2, SR);
        let edge = (NATIVE_EDGE_SECONDS * SR as f64) as usize;

        let opening = glided(&source, 3.0, 6.0, outgoing_glide);
        let head = source.frame_index(3.0);
        assert_eq!(opening.audio.channel(0)[..edge], source.channel(0)[head..head + edge]);

        let closing = glided(&source, 3.0, 6.0, incoming_glide);
        let resume = source.frame_index(3.0 + closing.consumed);
        let last = closing.audio.frames();
        assert_eq!(
            closing.audio.channel(0)[last - edge..],
            source.channel(0)[resume - edge..resume]
        );
    }

    #[test]
    fn a_glide_hands_off_on_the_unstretched_source_in_phase() {
        let log_mean = 10.0 / (130.0f64 / 120.0).ln();
        let (_, incoming_glide) = TempoGlide::pair(log_mean / 120.0, log_mean / 130.0);
        for hz in [330.0, 1_760.0, 4_100.0] {
            let source = tone(hz, 20.0, 0.5, 2, SR);
            let stem = glided(&source, 3.0, 6.0, incoming_glide);
            let resume = source.frame_index(3.0 + stem.consumed);
            let last = stem.audio.frames() - 1;
            let native = source.channel(0)[resume - 1];
            assert!((stem.audio.channel(0)[last] - native).abs() < 0.01, "{hz} Hz");
            // A splice onto an out-of-phase copy would dip well below the tone's level.
            let tail = rms(&stem.audio.channel(0)[last + 1 - 2 * SPLICE_FRAMES..]);
            assert!((tail - rms(source.channel(0))).abs() < 0.03, "{hz} Hz tail {tail}");
        }
    }
}
