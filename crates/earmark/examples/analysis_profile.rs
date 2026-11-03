//! Time and peak heap of whole-track analysis, plus a digest of its output so speed work can
//! show it changed nothing. Input is raw little-endian f32 mono, e.g.
//! `ffmpeg -i song.m4a -ac 1 -ar 11025 -f f32le song.f32`.
//! Usage: analysis_profile <rate> <file.f32>...

use std::alloc::{GlobalAlloc, Layout, System};
use std::hash::{DefaultHasher, Hash, Hasher};
use std::sync::atomic::{AtomicUsize, Ordering::Relaxed};
use std::time::Instant;

use earmark::WholeTrackAnalyzer;

struct Counting;
static LIVE: AtomicUsize = AtomicUsize::new(0);
static PEAK: AtomicUsize = AtomicUsize::new(0);

unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let live = LIVE.fetch_add(layout.size(), Relaxed) + layout.size();
        PEAK.fetch_max(live, Relaxed);
        unsafe { System.alloc(layout) }
    }
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        LIVE.fetch_sub(layout.size(), Relaxed);
        unsafe { System.dealloc(ptr, layout) }
    }
    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, size: usize) -> *mut u8 {
        if size > layout.size() {
            let live = LIVE.fetch_add(size - layout.size(), Relaxed) + size - layout.size();
            PEAK.fetch_max(live, Relaxed);
        } else {
            LIVE.fetch_sub(layout.size() - size, Relaxed);
        }
        unsafe { System.realloc(ptr, layout, size) }
    }
}

#[global_allocator]
static GLOBAL: Counting = Counting;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let rate: f64 = args[0].parse().expect("rate");
    let mut analyzer = WholeTrackAnalyzer::new().unwrap();
    let (mut total_ms, mut minutes, mut worst_peak) = (0.0, 0.0, 0usize);
    println!("{:>6} {:>8} {:>8} {:>8}  digest", "min", "ms", "peak MB", "bpm");
    for path in &args[1..] {
        let bytes = std::fs::read(path).unwrap();
        let samples: Vec<f32> =
            bytes.as_chunks::<4>().0.iter().map(|b| f32::from_le_bytes(*b)).collect();
        drop(bytes);
        let length = samples.len() as f64 / rate / 60.0;
        // Heap above what the caller already holds (the decoded samples).
        let base = LIVE.load(Relaxed);
        PEAK.store(base, Relaxed);
        let start = Instant::now();
        let result = analyzer.analyze(&samples, rate, 0.0).unwrap();
        let ms = start.elapsed().as_secs_f64() * 1e3;
        let peak = PEAK.load(Relaxed) - base;
        let mut hasher = DefaultHasher::new();
        format!("{result:?}").hash(&mut hasher);
        println!(
            "{length:6.2} {ms:8.1} {:8.1} {:8.2}  {:016x}",
            peak as f64 / 1e6,
            result.bpm,
            hasher.finish()
        );
        total_ms += ms;
        minutes += length;
        worst_peak = worst_peak.max(peak);
    }
    println!(
        "{:.1} ms per audio minute, worst peak heap {:.1} MB",
        total_ms / minutes,
        worst_peak as f64 / 1e6
    );
}
