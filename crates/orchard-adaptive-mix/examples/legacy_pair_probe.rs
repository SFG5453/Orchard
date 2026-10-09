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


use earmark::{AudioBuffer, BeatAnalysis, EngineConfig, SmartCrossfadeEngine, TransitionConstraints, RegionConstraint, TimeWindow};
use orchard_adaptive_mix::{models::Models, analysis, decode};
use std::{path::Path, process::Command, io::Write};
fn audio(name: &str, start: f64) -> AudioBuffer {
 let output=Command::new("ffmpeg").args(["-v","error","-ss",&start.to_string(),"-i",name,"-t","60","-ac","2","-ar","44100","-f","f32le","pipe:1"]).output().unwrap();
 assert!(output.status.success());
 let samples: Vec<f32>=output.stdout.chunks_exact(4).map(|s|f32::from_le_bytes(s.try_into().unwrap())).collect();
 AudioBuffer::from_interleaved(&samples,2,44100).unwrap()
}
fn main() {
 let out=audio("/tmp/orchard-pair-debug/WQ8T8aVBv0A.wav",132.0);
 let into=audio("/tmp/orchard-pair-debug/2a8PgqWrc_4.wav",0.0);
 let mut models=Models::new(Path::new("models")).unwrap();
 let mono=|b:&AudioBuffer|->Vec<f32>{b.channel(0).iter().zip(b.channel(1)).map(|(l,r)|(l+r)*0.5).collect()};
 let a=models.beats(&mono(&out)).unwrap();let b=models.beats(&mono(&into)).unwrap();
 let mut whole=|name:&str|{let output=Command::new("ffmpeg").args(["-v","error","-i",name,"-ac","2","-c:a","pcm_f32le","-f","wav","pipe:1"]).output().unwrap();
  let track=decode::song_from_wav(output.stdout.as_slice(),orchard_adaptive_mix::song::Windows::NONE).unwrap().track;
  let grids:Vec<_>=track.beat_windows.iter().map(|(w,o)|(models.beats(w),*o)).collect();
  analysis::track(&track,&grids).unwrap()};
 let (current,next)=(whole("/tmp/orchard-pair-debug/WQ8T8aVBv0A.wav"),whole("/tmp/orchard-pair-debug/2a8PgqWrc_4.wav"));
 let input=serde_json::json!({"analysis":current,"nextAnalysis":next,"duration":192,"nextDuration":272,"mode":"smart","currentTrack":{"id":"out","title":"Midnight Sun","durationSeconds":192},"nextTrack":{"id":"in","title":"STARGAZING","durationSeconds":272}});
 std::fs::write("/tmp/orchard-pair-debug/analysis.json", input.to_string()).unwrap();
 let mut config=EngineConfig{output_sample_rate:Some(48000),..Default::default()};
 config.tempo.preferred_ratio_deviation=0.02;config.tempo.acceptable_ratio_deviation=0.04;config.tempo.max_ratio_deviation=0.04;
 let mut engine=SmartCrossfadeEngine::new(config).unwrap();let end=out.duration();
 let constraints=TransitionConstraints{outgoing:RegionConstraint{start_within:Some(TimeWindow::new(0.0,end-1.0)),end_within:Some(TimeWindow::new(end-4.0,end))},incoming:RegionConstraint{start_within:Some(TimeWindow::new(0.0,20.0)),end_within:None},beat_lengths:Some(vec![8,16,32])};
 let plan=engine.analyze_constrained(&out,&into,&BeatAnalysis::new(a.bpm,a.beats,a.downbeats).unwrap(),&BeatAnalysis::new(b.bpm,b.beats,b.downbeats).unwrap(),&constraints).unwrap();
 println!("{plan:#?}");
 let rendered=engine.render(&out,&into,&plan).unwrap();
 let mut file=std::fs::File::create("/tmp/orchard-pair-debug/old.f32").unwrap();
 for v in rendered.audio.to_interleaved(){file.write_all(&v.to_le_bytes()).unwrap();}
}
