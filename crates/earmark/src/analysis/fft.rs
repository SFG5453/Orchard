/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */
//! Reusable real-FFT workspaces and window functions shared by whole-track analysis.

use std::sync::Arc;

use num_complex::Complex;
use realfft::{FftNum, RealFftPlanner, RealToComplex};

use crate::error::{CrossfadeError, Result};

pub(crate) struct FftWorkspace<T: FftNum = f64> {
    plan: Arc<dyn RealToComplex<T>>,
    input: Vec<T>,
    output: Vec<Complex<T>>,
    scratch: Vec<Complex<T>>,
}

impl<T: FftNum> FftWorkspace<T> {
    pub(crate) fn new(size: usize) -> Self {
        let plan = RealFftPlanner::<T>::new().plan_fft_forward(size);
        let input = plan.make_input_vec();
        let output = plan.make_output_vec();
        let scratch = plan.make_scratch_vec();
        Self {
            plan,
            input,
            output,
            scratch,
        }
    }

    pub(crate) fn size(&self) -> usize {
        self.input.len()
    }

    pub(crate) fn process_windowed(
        &mut self,
        samples: &[f32],
        window: &[T],
    ) -> Result<&[Complex<T>]> {
        if samples.len() != self.input.len() || window.len() != self.input.len() {
            return Err(CrossfadeError::dsp("FFT frame/window length mismatch"));
        }
        for ((slot, sample), weight) in self.input.iter_mut().zip(samples).zip(window) {
            *slot = T::from_f32(*sample).unwrap_or_else(T::zero) * *weight;
        }
        self.plan
            .process_with_scratch(&mut self.input, &mut self.output, &mut self.scratch)
            .map_err(|error| CrossfadeError::dsp(format!("FFT: {error}")))?;
        Ok(&self.output)
    }
}

pub(crate) fn symmetric_hann(size: usize) -> Vec<f64> {
    if size <= 1 {
        return vec![1.0; size];
    }
    (0..size)
        .map(|index| 0.5 - 0.5 * (std::f64::consts::TAU * index as f64 / (size - 1) as f64).cos())
        .collect()
}

pub(crate) fn periodic_hann(size: usize) -> Vec<f64> {
    (0..size)
        .map(|index| 0.5 - 0.5 * (std::f64::consts::TAU * index as f64 / size as f64).cos())
        .collect()
}

/// Reflect padding compatible with `torch.stft(center=True, pad_mode="reflect")`.
pub(crate) fn reflect_pad(samples: &[f32], pad: usize) -> Option<Vec<f32>> {
    if samples.len() <= pad + 1 {
        return None;
    }
    let mut padded = Vec::with_capacity(samples.len() + 2 * pad);
    for index in (1..=pad).rev() {
        padded.push(samples[index]);
    }
    padded.extend_from_slice(samples);
    for index in 1..=pad {
        padded.push(samples[samples.len() - 1 - index]);
    }
    Some(padded)
}
