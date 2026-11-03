/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

//! Nirovi and Syvenu on a phone NPU, through the C vtable in Android's `liborchard_npu.so`.
//! The NPU graphs take fixed 1500-frame windows of the beat spectrogram.
use std::ffi::c_void;

pub const FRAMES: usize = 1500;
const BINS: usize = 128;
/// Syvenu sees about 5 s each side; inner window edges drop this many frames.
const VOCAL_BORDER: usize = 250;

/// Matches `OrchardNpuVtable` in `mobile/android/app/src/main/cpp/npu/npu.cpp`.
#[repr(C)]
pub struct Vtable {
    run: unsafe extern "C" fn(*mut c_void, i32, *const f32, *mut f32) -> i32,
    close: unsafe extern "C" fn(*mut c_void),
}

/// An open NPU backend holding both models; closed on drop.
pub struct Npu {
    handle: *mut c_void,
    vtable: &'static Vtable,
}

// The native backend serializes runs per model.
unsafe impl Send for Npu {}

impl Npu {
    /// # Safety
    /// `handle` comes from `NpuNative.nativeOpen*` and is owned from here on; `vtable` is
    /// `NpuNative.nativeVtable()`.
    pub unsafe fn from_raw(handle: usize, vtable: usize) -> Option<Self> {
        if handle == 0 || vtable == 0 {
            return None;
        }
        Some(Self { handle: handle as *mut c_void, vtable: unsafe { &*(vtable as *const Vtable) } })
    }

    fn run(&self, model: i32, input: &[f32], output: &mut [f32]) -> Result<(), String> {
        let code = unsafe { (self.vtable.run)(self.handle, model, input.as_ptr(), output.as_mut_ptr()) };
        if code == 0 { Ok(()) } else { Err(format!("NPU run failed ({code})")) }
    }

    /// Beat and downbeat logits for one `[FRAMES, 128]` window.
    pub fn beat(&self, window: &[f32]) -> Result<(Vec<f32>, Vec<f32>), String> {
        if window.len() != FRAMES * BINS {
            return Err("NPU beat window has the wrong size".into());
        }
        let mut out = vec![0.0; FRAMES * 2];
        self.run(0, window, &mut out)?;
        Ok(out.chunks_exact(2).map(|p| (p[0], p[1])).unzip())
    }

    /// Vocal presence for a `[frames, 128]` spectrogram, in overlapping fixed windows.
    pub fn vocal(&self, spec: &[f32], frames: usize) -> Result<Vec<f32>, String> {
        let mut presence = vec![0.0; frames];
        let mut window = vec![0.0; FRAMES * BINS];
        let mut out = vec![0.0; FRAMES];
        let last = frames.saturating_sub(FRAMES);
        let mut start = 0;
        loop {
            let take = (frames - start).min(FRAMES);
            window[..take * BINS].copy_from_slice(&spec[start * BINS..(start + take) * BINS]);
            window[take * BINS..].fill(0.0);
            self.run(1, &window, &mut out)?;
            let from = if start == 0 { 0 } else { VOCAL_BORDER };
            let to = if start == last { take } else { FRAMES - VOCAL_BORDER };
            presence[start + from..start + to].copy_from_slice(&out[from..to]);
            if start == last {
                return Ok(presence);
            }
            start = (start + FRAMES - 2 * VOCAL_BORDER).min(last);
        }
    }
}

impl Drop for Npu {
    fn drop(&mut self) {
        unsafe { (self.vtable.close)(self.handle) }
    }
}
