/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

//! Isolated worker: a driver/model failure must not take music playback with it.
use orchard_adaptive_mix::{
    best_mix,
    models::Models,
    prepare::{Request, prepare},
    text_embed::{self, TextEmbedder},
};
use std::{
    io::{self, BufRead, Write},
    path::PathBuf,
};

fn main() {
    // QuickJS allows the planner 2 MiB of stack; Windows gives the main thread 1 MiB.
    // Stack overflow is not a transition style.
    let worker = std::thread::Builder::new()
        .name("adaptive-mix".into())
        .stack_size(8 * 1024 * 1024)
        .spawn(run)
        .expect("spawn adaptive mix thread");
    let _ = worker.join();
}

fn run() {
    let mut args = std::env::args().skip(1);
    let directory = PathBuf::from(args.next().unwrap_or_else(|| "models".into()));
    let mut models: Option<Models> = None;
    let mut embedder: Option<TextEmbedder> = None;
    let stdin = io::stdin();
    let mut input = stdin.lock();
    loop {
        let mut line = String::new();
        let Ok(size) = input.read_line(&mut line) else { break };
        if size == 0 { break; }
        if line.len() > 512 * 1024 {
            break;
        }
        let mut pcm = Vec::new();
        let result: Result<serde_json::Value, String> = (|| {
            // Queue sorting is CPU-only and must never pay for model startup.
            if line.trim_start().starts_with('{') {
                let value: serde_json::Value = serde_json::from_str(&line).map_err(|e| e.to_string())?;
                match value["kind"].as_str() {
                    Some("bestMixAnalyze") => return best_mix::analyze(
                        value["path"].as_str().ok_or("Missing analysis file")?,
                        value["duration"].as_f64().ok_or("Missing song duration")?),
                    Some("bestMixSortLazy") => {
                        let summaries = value["summaries"].as_array().ok_or("Missing summaries")?;
                        let order = best_mix::sort_lazy(summaries, &value["initial"], |left, right| {
                            println!("{}", serde_json::json!({"needPair":[left.map(|i|i as i64).unwrap_or(-1),right]}));
                            io::stdout().flush().map_err(|e| e.to_string())?;
                            let mut reply = String::new();
                            if input.read_line(&mut reply).map_err(|e| e.to_string())? == 0
                                || reply.len() > 512 * 1024 {
                                return Err("Best Mix pair response is unavailable".into());
                            }
                            let pair: serde_json::Value = serde_json::from_str(&reply).map_err(|e| e.to_string())?;
                            if let Some(error) = pair["error"].as_str() { return Err(error.into()); }
                            Ok((pair["left"].clone(), pair["right"].clone()))
                        })?;
                        return Ok(serde_json::json!({"order":order}));
                    }
                    // Docs questions run in their own worker instance, so no Best Mix job ever delays one.
                    Some("embed") => return text_embed::handle(&mut embedder, &directory, &value),
                    _ => {}
                }
            }
            if models.is_none() {
                models = Some(Models::new(&directory)?);
            }
            if line.trim() == "probe" {
                return Ok(serde_json::json!({"ready":true}));
            }
            let request: Request = serde_json::from_str(&line).map_err(|e| e.to_string())?;
            let (prepared, samples) = prepare(models.as_mut().unwrap(), request)?;
            let mut value = serde_json::to_value(prepared).map_err(|e| e.to_string())?;
            pcm = samples.iter().flat_map(|sample| sample.to_le_bytes()).collect();
            value["bytes"] = pcm.len().into();
            Ok(value)
        })();
        let response = match result {
            Ok(value) => value,
            Err(error) => {
                pcm.clear();
                serde_json::json!({"error":error})
            }
        };
        // Raw PCM follows the header line; "bytes" gives its length. No temp files were harmed.
        let mut stdout = io::stdout().lock();
        let _ = writeln!(stdout, "{response}");
        let _ = stdout.write_all(&pcm);
        let _ = stdout.flush();
    }
}
