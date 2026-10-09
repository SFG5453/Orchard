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

//! Bounded FFmpeg decoding through the host's authenticated loopback stream proxy.
//! FFmpeg only decodes; `song` does every conversion, exactly as on Android.
use crate::song::{Song, SongBuilder, Windows};
use std::{
    io::Read,
    process::{Command, Stdio},
};

fn require_loopback(url: &str) -> Result<(), String> {
    // No remote URL or arbitrary FFmpeg protocol crosses this process boundary.
    // The bouncer accepts exactly one guest: the Qt stream proxy.
    let loopback = url
        .strip_prefix("http://127.0.0.1:")
        .and_then(|rest| rest.split_once('/'))
        .and_then(|(port, _)| port.parse::<u16>().ok())
        .is_some_and(|port| port != 0);
    if !loopback || url.contains(['\r', '\n']) {
        return Err("Analysis requires an Orchard loopback stream".into());
    }
    Ok(())
}

/// Stream a whole song once, keeping the analysis input and the render windows asked for.
pub fn song(url: &str, windows: Windows) -> Result<Song, String> {
    require_loopback(url)?;
    decode(&["-rw_timeout", "15000000", "-protocol_whitelist", "http,tcp", "-i", url], windows)
}

/// Decode a downloaded saver file for Best Mix, which needs no render windows.
pub fn local_song(path: &str) -> Result<Song, String> {
    let file = std::path::Path::new(path);
    if !file.is_file() || std::fs::metadata(file).map_err(|e| e.to_string())?.len() > 32 * 1024 * 1024 {
        return Err("Invalid local analysis file".into());
    }
    // File protocol only, so a crafted path cannot reach the network.
    decode(&["-protocol_whitelist", "file", "-i", path], Windows::NONE)
}

fn decode(input: &[&str], windows: Windows) -> Result<Song, String> {
    let executable = std::env::var_os("ORCHARD_FFMPEG").unwrap_or_else(|| "ffmpeg".into());
    // WAV carries the decoder's own rate; no -ar, so FFmpeg's resampler never touches it.
    let mut child = Command::new(executable)
        .args(["-nostdin", "-v", "error"])
        .args(input)
        .args(["-vn", "-ac", "2", "-c:a", "pcm_f32le", "-f", "wav", "pipe:1"])
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| format!("Cannot start audio decoder: {e}"))?;
    let result = song_from_wav(child.stdout.take().unwrap(), windows);
    if result.is_err() { let _ = child.kill(); }
    let status = child.wait().map_err(|e| e.to_string())?;
    let song = result?;
    if !status.success() { return Err("Could not decode the analysis track".into()); }
    Ok(song)
}

fn read_exact(reader: &mut impl Read, bytes: &mut [u8]) -> Result<(), String> {
    reader.read_exact(bytes).map_err(|_| "Could not decode the analysis track".to_string())
}

/// Stereo float WAV from a pipe. Streamed headers carry no usable sizes, so the data
/// chunk runs to end of stream.
pub fn song_from_wav(mut reader: impl Read, windows: Windows) -> Result<Song, String> {
    let mut riff = [0u8; 12];
    read_exact(&mut reader, &mut riff)?;
    if &riff[..4] != b"RIFF" || &riff[8..] != b"WAVE" {
        return Err("Decoder did not produce WAV".into());
    }
    let mut rate = 0u32;
    loop {
        let mut header = [0u8; 8];
        read_exact(&mut reader, &mut header)?;
        let size = u32::from_le_bytes(header[4..].try_into().unwrap()) as usize;
        match &header[..4] {
            b"fmt " => {
                if !(16..=64).contains(&size) { return Err("Invalid WAV format chunk".into()); }
                let mut format = vec![0u8; size + size % 2];
                read_exact(&mut reader, &mut format)?;
                let code = u16::from_le_bytes([format[0], format[1]]);
                let channels = u16::from_le_bytes([format[2], format[3]]);
                let bits = u16::from_le_bytes([format[14], format[15]]);
                rate = u32::from_le_bytes(format[4..8].try_into().unwrap());
                // 3 is IEEE float; 0xFFFE is the extensible wrapper FFmpeg may use.
                if !matches!(code, 3 | 0xFFFE) || channels != 2 || bits != 32 {
                    return Err("Decoder produced an unexpected sample format".into());
                }
            }
            b"data" => break,
            _ => {
                if size > 1 << 20 { return Err("Invalid WAV chunk".into()); }
                let mut skip = vec![0u8; size + size % 2];
                read_exact(&mut reader, &mut skip)?;
            }
        }
    }
    let mut builder = SongBuilder::new(rate, windows)?;
    let mut buffer = vec![0u8; 1 << 16];
    let mut carry = Vec::new();
    let mut samples = Vec::new();
    loop {
        let read = match reader.read(&mut buffer) {
            Ok(0) => break,
            Ok(read) => read,
            Err(_) => return Err("Could not decode the analysis track".into()),
        };
        carry.extend_from_slice(&buffer[..read]);
        let whole = carry.len() / 8 * 8;
        samples.clear();
        samples.extend(carry[..whole].as_chunks::<4>().0.iter().map(|b| f32::from_le_bytes(*b)));
        builder.push(&samples)?;
        carry.drain(..whole);
    }
    builder.finish()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn wav(seconds: f64, rate: u32) -> Vec<u8> {
        let mut bytes = b"RIFF\xff\xff\xff\xffWAVEfmt \x10\0\0\0\x03\0\x02\0".to_vec();
        bytes.extend(rate.to_le_bytes());
        bytes.extend((rate * 8).to_le_bytes());
        bytes.extend([8, 0, 32, 0]);
        bytes.extend(b"LIST\x04\0\0\0INFOdata\xff\xff\xff\xff");
        let frames = (seconds * rate as f64) as usize;
        bytes.extend((0..frames).flat_map(|_| [1.0f32, 0.0]).flat_map(f32::to_le_bytes));
        bytes
    }

    #[test]
    fn streamed_wav_reaches_the_song_builder_at_its_own_rate() {
        let song = song_from_wav(wav(40.0, 48_000).as_slice(), Windows::NONE).unwrap();
        assert!((song.track.duration - 40.0).abs() < 1e-9);
        assert!(song_from_wav(&b"RIFF0000WAVX"[..], Windows::NONE).is_err());
    }

    #[test]
    fn rejects_external_protocols_and_disguised_authorities_before_decoding() {
        for url in [
            "https://example.com/song",
            "file:///tmp/song",
            "http://127.0.0.1:80@example.com/song",
            "http://127.0.0.1:0/song",
        ] {
            assert_eq!(song(url, Windows::BOTH).err().unwrap(), "Analysis requires an Orchard loopback stream");
        }
    }
}
