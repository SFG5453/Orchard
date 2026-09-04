use std::f32::consts::PI;
use std::sync::{Arc, Mutex, OnceLock};

use anyhow::{Context, Result, bail};
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{FromSample, SampleFormat, SizedSample, Stream, StreamConfig};
use rustfft::{Fft, FftPlanner, num_complex::Complex32};
use serde::{Deserialize, Serialize};

use super::decoder;

#[derive(Clone, Copy, Debug, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AudioDeck {
    Main,
    Next,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AudioSnapshot {
    pub source: String,
    pub loading: bool,
    pub ready: bool,
    pub playing: bool,
    pub position: f64,
    pub duration: f64,
    pub volume: f32,
    pub playback_rate: f32,
    pub error: String,
    pub sample_rate: u32,
    pub samples: Vec<f32>,
    pub spectrum: Vec<f32>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AudioEngineConfig {
    #[serde(default = "yes")]
    enabled: bool,
    #[serde(default)]
    auto_eq_enabled: bool,
    #[serde(default)]
    eq_enabled: bool,
    #[serde(default)]
    gains: Vec<f32>,
    #[serde(default)]
    preamp_db: f32,
    #[serde(default)]
    output_gain_db: f32,
    #[serde(default = "default_q")]
    q: f32,
    #[serde(default)]
    balance: f32,
}

const EQ_FREQUENCIES: [f32; 10] = [
    31.0, 62.0, 125.0, 250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0, 16_000.0,
];
const SPECTRUM_FRAMES: usize = 2_048;
const SPECTRUM_BANDS: usize = 32;

#[derive(Clone, Copy)]
struct EngineState {
    enabled: bool,
    auto_eq_enabled: bool,
    eq_enabled: bool,
    gains: [f32; 10],
    auto_eq_gains: [f32; 10],
    preamp_db: f32,
    output_gain_db: f32,
    q: f32,
    balance: f32,
}

impl Default for EngineState {
    fn default() -> Self {
        Self {
            enabled: true,
            auto_eq_enabled: false,
            eq_enabled: false,
            gains: [0.0; 10],
            auto_eq_gains: [0.0; 10],
            preamp_db: 0.0,
            output_gain_db: 0.0,
            q: default_q(),
            balance: 0.0,
        }
    }
}

impl EngineState {
    fn update(&mut self, config: AudioEngineConfig) {
        self.enabled = config.enabled;
        self.auto_eq_enabled = config.auto_eq_enabled;
        self.eq_enabled = config.eq_enabled && !config.auto_eq_enabled;
        self.gains = array_gains(&config.gains, -12.0, 12.0);
        self.preamp_db = config.preamp_db.clamp(-12.0, 6.0);
        self.output_gain_db = config.output_gain_db.clamp(-24.0, 6.0);
        self.q = config.q.clamp(0.4, 2.4);
        self.balance = config.balance.clamp(-1.0, 1.0);
    }

    fn eq_gains(&self) -> [f32; 10] {
        if self.auto_eq_enabled {
            self.auto_eq_gains
        } else {
            self.gains
        }
    }

    fn uses_eq(&self) -> bool {
        self.enabled && (self.auto_eq_enabled || self.eq_enabled)
    }
}

#[derive(Clone, Copy, Default)]
struct StereoBiquad {
    b0: f32,
    b1: f32,
    b2: f32,
    a1: f32,
    a2: f32,
    z1: [f32; 2],
    z2: [f32; 2],
}

impl StereoBiquad {
    fn peaking(sample_rate: u32, frequency: f32, q: f32, gain_db: f32) -> Self {
        let frequency = frequency.min(sample_rate as f32 * 0.475);
        let omega = 2.0 * PI * frequency / sample_rate as f32;
        let (sin, cos) = omega.sin_cos();
        let amplitude = 10.0_f32.powf(gain_db / 40.0);
        let alpha = sin / (2.0 * q);
        let a0 = 1.0 + alpha / amplitude;
        Self {
            b0: (1.0 + alpha * amplitude) / a0,
            b1: (-2.0 * cos) / a0,
            b2: (1.0 - alpha * amplitude) / a0,
            a1: (-2.0 * cos) / a0,
            a2: (1.0 - alpha / amplitude) / a0,
            z1: [0.0; 2],
            z2: [0.0; 2],
        }
    }

    fn process(&mut self, channel: usize, input: f32) -> f32 {
        let output = input * self.b0 + self.z1[channel];
        self.z1[channel] = input * self.b1 - output * self.a1 + self.z2[channel];
        self.z2[channel] = input * self.b2 - output * self.a2;
        output
    }
}

struct DeckState {
    source: String,
    samples: Arc<Vec<f32>>,
    frame: f64,
    loading: bool,
    playing: bool,
    volume: f32,
    rate: f32,
    track_gain_db: f32,
    filters: [StereoBiquad; 10],
    error: String,
}

impl Default for DeckState {
    fn default() -> Self {
        Self {
            source: String::new(),
            samples: Arc::new(Vec::new()),
            frame: 0.0,
            loading: false,
            playing: false,
            volume: 1.0,
            rate: 1.0,
            track_gain_db: 0.0,
            filters: [StereoBiquad::default(); 10],
            error: String::new(),
        }
    }
}

struct Mixer {
    decks: [DeckState; 2],
    sample_rate: u32,
    engine: EngineState,
    recent_samples: [f32; SPECTRUM_FRAMES],
    recent_cursor: usize,
    recent_filled: usize,
}

pub struct NativeAudio {
    mixer: Arc<Mutex<Mixer>>,
    _stream: Mutex<Stream>,
}

impl NativeAudio {
    pub fn new() -> Result<Self> {
        let host = cpal::default_host();
        let device = host
            .default_output_device()
            .context("no audio output device is available")?;
        let supported = device.default_output_config()?;
        let config: StreamConfig = supported.into();
        let mixer = Arc::new(Mutex::new(Mixer {
            decks: [DeckState::default(), DeckState::default()],
            sample_rate: config.sample_rate,
            engine: EngineState::default(),
            recent_samples: [0.0; SPECTRUM_FRAMES],
            recent_cursor: 0,
            recent_filled: 0,
        }));
        let stream = match supported.sample_format() {
            SampleFormat::F32 => build_stream::<f32>(&device, &config, mixer.clone())?,
            SampleFormat::I16 => build_stream::<i16>(&device, &config, mixer.clone())?,
            SampleFormat::U16 => build_stream::<u16>(&device, &config, mixer.clone())?,
            format => bail!("unsupported audio output format {format:?}"),
        };
        stream.play()?;
        Ok(Self {
            mixer,
            _stream: Mutex::new(stream),
        })
    }

    pub async fn load(&self, deck: AudioDeck, source: String) -> Result<AudioSnapshot, String> {
        let index = deck_index(deck);
        let sample_rate = self.mixer.lock().map_err(lock_error)?.sample_rate;
        {
            let mut mixer = self.mixer.lock().map_err(lock_error)?;
            let state = &mut mixer.decks[index];
            state.source = source.clone();
            state.loading = true;
            state.playing = false;
            state.error.clear();
            state.samples = Arc::new(Vec::new());
            state.frame = 0.0;
        }
        let decoded = tokio::task::spawn_blocking(move || decoder::decode(&source, sample_rate))
            .await
            .map_err(|error| error.to_string())
            .and_then(|result| result.map_err(|error| error.to_string()));
        let mut mixer = self.mixer.lock().map_err(lock_error)?;
        let state = &mut mixer.decks[index];
        state.loading = false;
        match decoded {
            Ok(audio) => {
                state.samples = Arc::new(audio.samples);
                state.error.clear();
                debug_assert_eq!(audio.sample_rate, mixer.sample_rate);
            }
            Err(error) => state.error = error,
        }
        Ok(snapshot(&mixer.decks[index], mixer.sample_rate, Vec::new()))
    }

    pub fn play(&self, deck: AudioDeck) -> Result<(), String> {
        let mut mixer = self.mixer.lock().map_err(lock_error)?;
        let state = &mut mixer.decks[deck_index(deck)];
        if state.samples.is_empty() {
            return Err(if state.error.is_empty() {
                "audio is not ready".into()
            } else {
                state.error.clone()
            });
        }
        state.playing = true;
        Ok(())
    }

    pub fn pause(&self, deck: AudioDeck) -> Result<(), String> {
        self.mixer.lock().map_err(lock_error)?.decks[deck_index(deck)].playing = false;
        Ok(())
    }

    pub fn clear(&self, deck: AudioDeck) -> Result<(), String> {
        let mut mixer = self.mixer.lock().map_err(lock_error)?;
        let mut state = DeckState::default();
        rebuild_filters(&mut state, mixer.engine, mixer.sample_rate);
        mixer.decks[deck_index(deck)] = state;
        Ok(())
    }

    pub fn seek(&self, deck: AudioDeck, seconds: f64) -> Result<(), String> {
        let mut mixer = self.mixer.lock().map_err(lock_error)?;
        let sample_rate = mixer.sample_rate;
        let state = &mut mixer.decks[deck_index(deck)];
        let max_frame = state.samples.len() as f64 / 2.0;
        state.frame = (seconds.max(0.0) * sample_rate as f64).min(max_frame);
        Ok(())
    }

    pub fn set_volume(&self, deck: AudioDeck, volume: f32) -> Result<(), String> {
        self.mixer.lock().map_err(lock_error)?.decks[deck_index(deck)].volume =
            volume.clamp(0.0, 1.0);
        Ok(())
    }

    pub fn set_rate(&self, deck: AudioDeck, rate: f32) -> Result<(), String> {
        self.mixer.lock().map_err(lock_error)?.decks[deck_index(deck)].rate = rate.clamp(0.5, 2.0);
        Ok(())
    }

    pub fn set_engine_config(&self, config: AudioEngineConfig) -> Result<(), String> {
        let mut mixer = self.mixer.lock().map_err(lock_error)?;
        mixer.engine.update(config);
        let engine = mixer.engine;
        let sample_rate = mixer.sample_rate;
        for deck in &mut mixer.decks {
            rebuild_filters(deck, engine, sample_rate);
        }
        Ok(())
    }

    pub fn set_auto_eq_gains(&self, gains: Vec<f32>) -> Result<(), String> {
        let mut mixer = self.mixer.lock().map_err(lock_error)?;
        mixer.engine.auto_eq_gains = array_gains(&gains, -3.0, 3.0);
        let engine = mixer.engine;
        let sample_rate = mixer.sample_rate;
        for deck in &mut mixer.decks {
            rebuild_filters(deck, engine, sample_rate);
        }
        Ok(())
    }

    pub fn set_track_gain(&self, deck: AudioDeck, gain_db: f32) -> Result<(), String> {
        self.mixer.lock().map_err(lock_error)?.decks[deck_index(deck)].track_gain_db =
            gain_db.clamp(-12.0, 12.0);
        Ok(())
    }

    pub fn state(&self, deck: AudioDeck) -> Result<AudioSnapshot, String> {
        let mixer = self.mixer.lock().map_err(lock_error)?;
        let recent = ordered_recent_samples(&mixer);
        let mut state = snapshot(&mixer.decks[deck_index(deck)], mixer.sample_rate, recent);
        drop(mixer);
        state.spectrum = calculate_spectrum(&state.samples);
        Ok(state)
    }
}

fn build_stream<T>(
    device: &cpal::Device,
    config: &StreamConfig,
    mixer: Arc<Mutex<Mixer>>,
) -> Result<Stream>
where
    T: SizedSample + FromSample<f32>,
{
    let channels = config.channels as usize;
    device
        .build_output_stream(
            *config,
            move |output: &mut [T], _| render(output, channels, &mixer),
            move |error| eprintln!("native audio output error: {error}"),
            None,
        )
        .context("could not create the native audio output stream")
}

fn render<T>(output: &mut [T], channels: usize, mixer: &Arc<Mutex<Mixer>>)
where
    T: SizedSample + FromSample<f32>,
{
    let Ok(mut mixer) = mixer.try_lock() else {
        output.fill(T::from_sample(0.0));
        return;
    };
    for frame in output.chunks_mut(channels) {
        let mut left = 0.0;
        let mut right = 0.0;
        let engine = mixer.engine;
        for deck in &mut mixer.decks {
            if !deck.playing {
                continue;
            }
            let index = deck.frame as usize * 2;
            if index + 1 >= deck.samples.len() {
                deck.playing = false;
                continue;
            }
            let fraction = deck.frame.fract() as f32;
            let mut deck_left = interpolate(&deck.samples, index, fraction);
            let mut deck_right = interpolate(&deck.samples, index + 1, fraction);
            if engine.uses_eq() {
                if engine.eq_enabled {
                    let preamp = db_to_gain(engine.preamp_db);
                    deck_left *= preamp;
                    deck_right *= preamp;
                }
                for filter in &mut deck.filters {
                    deck_left = filter.process(0, deck_left);
                    deck_right = filter.process(1, deck_right);
                }
            }
            if engine.enabled {
                if engine.balance > 0.0 {
                    deck_left *= (engine.balance * PI * 0.5).cos();
                } else if engine.balance < 0.0 {
                    deck_right *= (-engine.balance * PI * 0.5).cos();
                }
            }
            let deck_gain = if engine.enabled {
                db_to_gain(deck.track_gain_db)
            } else {
                1.0
            };
            let gain = deck.volume * deck_gain * db_to_gain(engine.output_gain_db);
            left += deck_left * gain;
            right += deck_right * gain;
            deck.frame += deck.rate as f64;
        }
        let left = left.clamp(-1.0, 1.0);
        let right = right.clamp(-1.0, 1.0);
        let cursor = mixer.recent_cursor;
        mixer.recent_samples[cursor] = (left + right) * 0.5;
        mixer.recent_cursor = (cursor + 1) % SPECTRUM_FRAMES;
        mixer.recent_filled = (mixer.recent_filled + 1).min(SPECTRUM_FRAMES);
        for (channel, sample) in frame.iter_mut().enumerate() {
            *sample = T::from_sample(if channel % 2 == 0 { left } else { right });
        }
    }
}

fn interpolate(samples: &[f32], index: usize, fraction: f32) -> f32 {
    let next = samples.get(index + 2).copied().unwrap_or(samples[index]);
    samples[index] + (next - samples[index]) * fraction
}

fn snapshot(deck: &DeckState, sample_rate: u32, samples: Vec<f32>) -> AudioSnapshot {
    AudioSnapshot {
        source: deck.source.clone(),
        loading: deck.loading,
        ready: !deck.samples.is_empty(),
        playing: deck.playing,
        position: deck.frame / sample_rate as f64,
        duration: deck.samples.len() as f64 / 2.0 / sample_rate as f64,
        volume: deck.volume,
        playback_rate: deck.rate,
        error: deck.error.clone(),
        sample_rate,
        samples,
        spectrum: vec![0.0; SPECTRUM_BANDS],
    }
}

fn rebuild_filters(deck: &mut DeckState, engine: EngineState, sample_rate: u32) {
    let gains = engine.eq_gains();
    deck.filters = std::array::from_fn(|index| {
        StereoBiquad::peaking(sample_rate, EQ_FREQUENCIES[index], engine.q, gains[index])
    });
}

fn array_gains(values: &[f32], min: f32, max: f32) -> [f32; 10] {
    std::array::from_fn(|index| values.get(index).copied().unwrap_or(0.0).clamp(min, max))
}

fn db_to_gain(value: f32) -> f32 {
    10.0_f32.powf(value / 20.0)
}

fn ordered_recent_samples(mixer: &Mixer) -> Vec<f32> {
    let mut samples = vec![0.0; SPECTRUM_FRAMES];
    if mixer.recent_filled == 0 {
        return samples;
    }
    let count = mixer.recent_filled;
    let source_start = if count == SPECTRUM_FRAMES {
        mixer.recent_cursor
    } else {
        0
    };
    let destination_start = SPECTRUM_FRAMES - count;
    for offset in 0..count {
        samples[destination_start + offset] =
            mixer.recent_samples[(source_start + offset) % SPECTRUM_FRAMES];
    }
    samples
}

fn calculate_spectrum(samples: &[f32]) -> Vec<f32> {
    static FFT: OnceLock<Arc<dyn Fft<f32>>> = OnceLock::new();
    let fft = FFT.get_or_init(|| {
        let mut planner = FftPlanner::new();
        planner.plan_fft_forward(SPECTRUM_FRAMES)
    });
    let mut buffer = (0..SPECTRUM_FRAMES)
        .map(|index| {
            let window = 0.5 - 0.5 * (2.0 * PI * index as f32 / (SPECTRUM_FRAMES - 1) as f32).cos();
            Complex32::new(samples.get(index).copied().unwrap_or(0.0) * window, 0.0)
        })
        .collect::<Vec<_>>();
    fft.process(&mut buffer);

    let limit = ((SPECTRUM_FRAMES / 2) as f32 * 0.72).floor() as usize;
    (0..SPECTRUM_BANDS)
        .map(|band| {
            let start =
                ((band as f32 / SPECTRUM_BANDS as f32).powf(1.8) * limit as f32).floor() as usize;
            let end = ((((band + 1) as f32 / SPECTRUM_BANDS as f32).powf(1.8) * limit as f32)
                .floor() as usize)
                .max(start + 1);
            let magnitude = buffer[start..end.min(buffer.len() / 2)]
                .iter()
                .map(|value| value.norm() * 4.0 / SPECTRUM_FRAMES as f32)
                .fold(0.0_f32, f32::max);
            let decibels = 20.0 * magnitude.max(0.00001).log10();
            ((decibels + 100.0) / 70.0).clamp(0.0, 1.0)
        })
        .collect()
}

fn deck_index(deck: AudioDeck) -> usize {
    match deck {
        AudioDeck::Main => 0,
        AudioDeck::Next => 1,
    }
}

fn lock_error<T>(error: std::sync::PoisonError<T>) -> String {
    format!("native audio state is poisoned: {error}")
}

const fn yes() -> bool {
    true
}

const fn default_q() -> f32 {
    1.1
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tone(frequency: f32, amplitude: f32, frames: usize, sample_rate: u32) -> Vec<f32> {
        (0..frames)
            .flat_map(|frame| {
                let value =
                    amplitude * (2.0 * PI * frequency * frame as f32 / sample_rate as f32).sin();
                [value, value]
            })
            .collect()
    }

    fn rendered_tone(gain_db: f32) -> Vec<f32> {
        let sample_rate = 48_000;
        let mut deck = DeckState {
            samples: Arc::new(tone(1_000.0, 0.04, 8_192, sample_rate)),
            playing: true,
            ..DeckState::default()
        };
        let mut engine = EngineState {
            eq_enabled: true,
            ..EngineState::default()
        };
        engine.gains[5] = gain_db;
        rebuild_filters(&mut deck, engine, sample_rate);
        let mixer = Arc::new(Mutex::new(Mixer {
            decks: [deck, DeckState::default()],
            sample_rate,
            engine,
            recent_samples: [0.0; SPECTRUM_FRAMES],
            recent_cursor: 0,
            recent_filled: 0,
        }));
        let mut output = vec![0.0_f32; 8_192 * 2];
        render(&mut output, 2, &mixer);
        output
    }

    fn rms(samples: &[f32]) -> f32 {
        (samples.iter().map(|sample| sample * sample).sum::<f32>() / samples.len() as f32).sqrt()
    }

    #[test]
    fn native_equalizer_boosts_its_selected_band() {
        let flat = rendered_tone(0.0);
        let boosted = rendered_tone(12.0);
        assert!(rms(&boosted[2_000..]) > rms(&flat[2_000..]) * 3.0);
    }

    #[test]
    fn spectrum_reports_live_frequency_energy() {
        let stereo = tone(440.0, 0.3, SPECTRUM_FRAMES, 48_000);
        let mono = stereo
            .as_chunks::<2>()
            .0
            .iter()
            .map(|frame| (frame[0] + frame[1]) * 0.5)
            .collect::<Vec<_>>();
        let spectrum = calculate_spectrum(&mono);
        assert_eq!(spectrum.len(), SPECTRUM_BANDS);
        assert!(spectrum.iter().copied().fold(0.0_f32, f32::max) > 0.7);
        assert!(
            calculate_spectrum(&vec![0.0; SPECTRUM_FRAMES])
                .iter()
                .all(|value| *value == 0.0)
        );
    }
}
