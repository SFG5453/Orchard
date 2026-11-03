/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */
//! A bass swap's low band on its own fader.
//!
//! Both sides fade across the whole transition and their fades cross at the middle. When the
//! bass changes hands elsewhere, each side's low band follows its own fade warped to cross at
//! the swap, so the bass hands over at the level a middle swap has while the rest fades as planned.

use crate::audio::AudioBuffer;
use crate::config::EngineConfig;
use crate::dsp::automation::AutomationCurve;
use crate::dsp::fade::FadeCurve;
use crate::dsp::filters::{FilterAutomation, FilterKind};
use crate::error::Result;
use crate::render::stage;

/// Split between the low band and the rest, just under where the swap filters pivot.
pub const CROSSOVER_HZ: f32 = 180.0;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Side {
    Outgoing,
    Incoming,
}

/// Maps a transition position so `swap` lands on the middle.
pub fn warp(x: f32, swap: f32) -> f32 {
    let swap = swap.clamp(0.05, 0.95);
    if x <= swap {
        0.5 * x / swap
    } else {
        0.5 + 0.5 * (x - swap) / (1.0 - swap)
    }
}

/// Mixes `stem`'s low band in again under `low - envelope`, so the low band rides its warped
/// fade while the rest of the stem keeps the `envelope` it was already mixed with.
#[allow(clippy::too_many_arguments)]
pub fn hold_low_band(
    mixed: &mut AudioBuffer,
    stem: &AudioBuffer,
    envelope: &[f32],
    side: Side,
    curve: FadeCurve,
    gain: &AutomationCurve,
    swap: f32,
    config: &EngineConfig,
) -> Result<()> {
    let frames = envelope.len();
    if frames < 2 {
        return Ok(());
    }
    let span = (frames - 1) as f32;
    let mut cursor = gain.cursor();
    let correction: Vec<f32> = (0..frames)
        .map(|i| {
            let x = i as f32 / span;
            let fade = match side {
                Side::Outgoing => curve.fade_out(warp(x, swap)),
                Side::Incoming => curve.fade_in(warp(x, swap)),
            };
            fade * cursor.value_at(x) - envelope[i]
        })
        .collect();
    // A Butterworth pass forward and again backward: fourth-order and phase-free, so the low
    // band lines up with the bass already mixed and the split leaves nothing behind.
    let low_pass = FilterAutomation::new(
        FilterKind::LowPass,
        AutomationCurve::constant(CROSSOVER_HZ),
        std::f32::consts::FRAC_1_SQRT_2,
    );
    let mut low = stem.clone();
    stage::apply_filters(&mut low, std::slice::from_ref(&low_pass), config)?;
    low.planar_mut().iter_mut().for_each(|channel| channel.reverse());
    stage::apply_filters(&mut low, std::slice::from_ref(&low_pass), config)?;
    low.planar_mut().iter_mut().for_each(|channel| channel.reverse());
    for (out, source) in mixed.planar_mut().iter_mut().zip(low.planar()) {
        for ((sample, value), delta) in out.iter_mut().zip(source).zip(&correction) {
            *sample += value * delta;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_warp_puts_the_swap_on_the_middle_and_keeps_both_ends() {
        for swap in [0.25, 0.5, 0.75] {
            assert_eq!(warp(0.0, swap), 0.0);
            assert!((warp(swap, swap) - 0.5).abs() < 1e-6);
            assert!((warp(1.0, swap) - 1.0).abs() < 1e-6);
        }
        assert!((warp(0.3, 0.5) - 0.3).abs() < 1e-6);
    }

    #[test]
    fn a_middle_swap_adds_nothing() {
        let rate = 8_000;
        let tone: Vec<f32> = (0..rate).map(|i| (i as f32 * 0.05).sin() * 0.5).collect();
        let stem = AudioBuffer::new(vec![tone], rate).unwrap();
        let mut envelope = vec![0.0; rate as usize];
        FadeCurve::EqualPower.fill_out(&mut envelope);
        let mut mixed = AudioBuffer::silent(1, rate as usize, rate).unwrap();
        let gain = AutomationCurve::constant(1.0);
        hold_low_band(&mut mixed, &stem, &envelope, Side::Outgoing, FadeCurve::EqualPower, &gain, 0.5,
            &EngineConfig::default()).unwrap();
        assert!(mixed.channel(0).iter().all(|sample| sample.abs() < 1e-4));
    }

    #[test]
    fn a_late_swap_keeps_the_outgoing_bass_up_until_it() {
        let rate = 8_000;
        // 60 Hz sits in the low band.
        let bass: Vec<f32> = (0..rate * 4).map(|i| (i as f32 * std::f32::consts::TAU * 60.0 / rate as f32).sin() * 0.5).collect();
        let stem = AudioBuffer::new(vec![bass], rate).unwrap();
        let frames = stem.frames();
        let mut envelope = vec![0.0; frames];
        FadeCurve::EqualPower.fill_out(&mut envelope);
        let mut mixed = AudioBuffer::silent(1, frames, rate).unwrap();
        stage::mix_stem(&mut mixed, &stem, &envelope);
        hold_low_band(&mut mixed, &stem, &envelope, Side::Outgoing, FadeCurve::EqualPower,
            &AutomationCurve::constant(1.0), 0.75, &EngineConfig::default()).unwrap();
        let level = |at: f32| {
            let centre = (at * frames as f32) as usize;
            mixed.channel(0)[centre - 400..centre + 400].iter().map(|s| s.abs()).fold(0.0f32, f32::max)
        };
        // Equal power alone is at 0.38 by 0.75; the held low band is still near the middle's 0.71.
        assert!(level(0.74) > 0.5 * 0.65, "{}", level(0.74));
        assert!(level(0.74) < 0.5 * 0.78, "{}", level(0.74));
    }
}
