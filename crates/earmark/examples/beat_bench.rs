//! Runs the beat tracker over directories of log-mel `.npy` spectrograms and writes JSON.
//! Usage: beat_bench <out.json> <dir>... [key=value ...]
use earmark::analysis::{BeatTracker, BeatTrackerConfig};
use std::{
    fs,
    path::PathBuf,
    sync::atomic::{AtomicUsize, Ordering},
    time::Instant,
};

fn read_npy(path: &PathBuf) -> Vec<f32> {
    let bytes = fs::read(path).unwrap();
    let header_len = u16::from_le_bytes([bytes[8], bytes[9]]) as usize;
    let header = std::str::from_utf8(&bytes[10..10 + header_len]).unwrap();
    let data = &bytes[10 + header_len..];
    if header.contains("'|u1'") {
        data.iter().map(|&v| v as f32 / 25.0).collect()
    } else if header.contains("'<f4'") {
        data.as_chunks::<4>()
            .0
            .iter()
            .map(|c| f32::from_le_bytes(*c))
            .collect()
    } else {
        panic!("unsupported dtype in {}: {header}", path.display());
    }
}

fn apply(config: &mut BeatTrackerConfig, key: &str, value: &str) {
    let f = || value.parse::<f64>().unwrap();
    let list = || {
        value
            .split(',')
            .map(|x| x.parse::<f64>().unwrap())
            .collect::<Vec<f64>>()
    };
    match key {
        "min_bpm" => config.min_bpm = f(),
        "max_bpm" => config.max_bpm = f(),
        "transition_lambda" => config.transition_lambda = f(),
        "observation_lambda" => config.observation_lambda = f(),
        "activation_gain" => config.activation_gain = f(),
        "prior_bpm" => config.prior_bpm = f(),
        "prior_octaves" => config.prior_octaves = f(),
        "prior_weight" => config.prior_weight = f(),
        "band_weights" => config.band_weights.copy_from_slice(&list()),
        "slow_beat" => config.slow_emission_means[0].copy_from_slice(&list()),
        "slow_off" => config.slow_emission_means[1].copy_from_slice(&list()),
        "slow_other" => config.slow_emission_means[2].copy_from_slice(&list()),
        "slow_floor" => config.slow_emission_floor = f(),
        "slow_temperature" => config.slow_emission_temperature = f(),
        "slow_prior_bpm" => config.slow_prior_bpm = f(),
        "slow_prior_weight" => config.slow_prior_weight = f(),
        "kick_threshold" => config.kick_threshold = f(),
        "sub_bass_threshold" => config.sub_bass_threshold = f(),
        "heavy_band_weights" => config.heavy_band_weights.copy_from_slice(&list()),
        "heavy_beat" => config.heavy_slow_emission_means[0].copy_from_slice(&list()),
        "heavy_off" => config.heavy_slow_emission_means[1].copy_from_slice(&list()),
        "bar_stay" => config.bar_stay = f(),
        "bar_spread_floor" => config.bar_spread_floor = f(),
        "bar_triple_prior" => config.bar_triple_prior = f(),
        "decode_oversample" => config.decode_oversample = f() as usize,
        _ => panic!("unknown key {key}"),
    }
}

/// mir_eval-style F-measure: events before 5 s dropped, one-to-one matches within 70 ms.
fn f_measure(reference: &[f64], estimate: &[f64]) -> f64 {
    let r: Vec<f64> = reference.iter().copied().filter(|t| *t >= 5.0).collect();
    let e: Vec<f64> = estimate.iter().copied().filter(|t| *t >= 5.0).collect();
    if r.is_empty() || e.is_empty() {
        return 0.0;
    }
    let (mut i, mut j, mut hits) = (0, 0, 0);
    while i < r.len() && j < e.len() {
        if (r[i] - e[j]).abs() <= 0.07 {
            hits += 1;
            i += 1;
            j += 1;
        } else if e[j] < r[i] {
            j += 1;
        } else {
            i += 1;
        }
    }
    let p = hits as f64 / e.len() as f64;
    let q = hits as f64 / r.len() as f64;
    if hits == 0 {
        0.0
    } else {
        2.0 * p * q / (p + q)
    }
}

/// Beat times and downbeat flags from a `.beats` annotation file.
fn annotation(root: &str, dataset: &str, name: &str) -> Option<(Vec<f64>, Vec<f64>, bool)> {
    let text =
        fs::read_to_string(format!("{root}/{dataset}/annotations/beats/{name}.beats")).ok()?;
    let (mut beats, mut downs, mut has_downs) = (Vec::new(), Vec::new(), false);
    for line in text.lines() {
        let mut parts = line.split_whitespace();
        let Some(t) = parts.next().and_then(|v| v.parse::<f64>().ok()) else {
            continue;
        };
        beats.push(t);
        if let Some(pos) = parts.next().and_then(|v| v.parse::<f64>().ok()) {
            has_downs = true;
            if pos == 1.0 {
                downs.push(t);
            }
        }
    }
    Some((beats, downs, has_downs))
}

fn json_list(values: &[f64]) -> String {
    let items: Vec<String> = values.iter().map(|v| format!("{v:.4}")).collect();
    format!("[{}]", items.join(","))
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let out = PathBuf::from(&args[0]);
    let mut config = BeatTrackerConfig::default();
    let annotations = std::env::var("BEAT_ANNOTATIONS").ok();
    // Simulates 11.025 kHz analysis audio: mel bands at or above this index read silent.
    let oracle = std::env::var("BEAT_ORACLE").is_ok();
    let cutoff: usize = std::env::var("BEAT_CUTOFF_BAND")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(128);
    let mut files = Vec::new();
    for arg in &args[1..] {
        if let Some((k, v)) = arg.split_once('=') {
            apply(&mut config, k, v);
        } else {
            let dir = PathBuf::from(arg);
            let dataset = dir.file_name().unwrap().to_string_lossy().to_string();
            let mut found: Vec<PathBuf> = fs::read_dir(&dir)
                .unwrap()
                .filter_map(|e| e.ok().map(|e| e.path()))
                .filter(|p| p.extension().is_some_and(|e| e == "npy"))
                .collect();
            found.sort();
            files.extend(found.into_iter().map(|p| (dataset.clone(), p)));
        }
    }
    let tracker = BeatTracker::new(config);
    let next = AtomicUsize::new(0);
    let scores = std::sync::Mutex::new(Vec::<(String, f64, f64)>::new());
    let threads = std::thread::available_parallelism()
        .map_or(4, |n| n.get())
        .min(12);
    let started = Instant::now();
    let mut rows: Vec<(usize, String)> = std::thread::scope(|scope| {
        let handles: Vec<_> = (0..threads).map(|_| scope.spawn(|| {
            let mut rows = Vec::new();
            loop {
                let i = next.fetch_add(1, Ordering::Relaxed);
                let Some((dataset, path)) = files.get(i) else { break };
                let mut values = read_npy(path);
                if cutoff < 128 {
                    for row in values.as_chunks_mut::<128>().0 {
                        row[cutoff..].fill(0.0);
                    }
                }
                let mut track = tracker.track(&values);
                // Oracle mode: annotated beats in, bar model alone measured.
                if oracle
                    && let Some((beats, _, _)) = annotations.as_deref().and_then(|root| annotation(root, dataset, &path.file_stem().unwrap().to_string_lossy()))
                {
                    let (downbeats, meter) = tracker.downbeats(&values, &beats);
                    track.beats = beats;
                    track.downbeats = downbeats;
                    track.beats_per_bar = meter;
                }
                let name = path.file_stem().unwrap().to_string_lossy().to_string();
                if let Some((beats, downs, has_downs)) = annotations.as_deref().and_then(|root| annotation(root, dataset, &name)) {
                    let down = if has_downs { f_measure(&downs, &track.downbeats) } else { f64::NAN };
                    scores.lock().unwrap().push((dataset.clone(), f_measure(&beats, &track.beats), down));
                }
                rows.push((i, format!("\"{name}\": {{\"dataset\": \"{dataset}\", \"beats\": {}, \"downbeats\": {}, \"bpm\": {:.3}, \"meter\": {}, \"confidence\": {:.4}, \"regularity\": {:.4}, \"support\": {:.4}, \"agreement\": {:.4}, \"bar_margin\": {:.4}}}",
                    json_list(&track.beats), json_list(&track.downbeats), track.bpm, track.beats_per_bar,
                    track.confidence, track.regularity, track.support, track.agreement, track.bar_margin)));
            }
            rows
        })).collect();
        handles
            .into_iter()
            .flat_map(|h| h.join().unwrap())
            .collect()
    });
    rows.sort_by_key(|r| r.0);
    let body: Vec<String> = rows.into_iter().map(|r| r.1).collect();
    fs::write(&out, format!("{{{}}}", body.join(",\n"))).unwrap();
    eprintln!(
        "{} tracks in {:.2}s",
        files.len(),
        started.elapsed().as_secs_f64()
    );
    let scores = scores.into_inner().unwrap();
    let mut datasets: Vec<String> = scores.iter().map(|s| s.0.clone()).collect();
    datasets.sort();
    datasets.dedup();
    let mut line = Vec::new();
    for ds in &datasets {
        let rows: Vec<_> = scores.iter().filter(|s| &s.0 == ds).collect();
        let beat = rows.iter().map(|r| r.1).sum::<f64>() / rows.len() as f64;
        let downs: Vec<f64> = rows.iter().map(|r| r.2).filter(|v| v.is_finite()).collect();
        let down = if downs.is_empty() {
            f64::NAN
        } else {
            downs.iter().sum::<f64>() / downs.len() as f64
        };
        line.push(format!("{ds} {:.1}/{:.1}", beat * 100.0, down * 100.0));
    }
    if !line.is_empty() {
        println!("{}", line.join("  "));
    }
}
