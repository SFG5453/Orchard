use anyhow::{Context, Result, bail};
use ffmpeg_next as ffmpeg;

pub struct DecodedAudio {
    pub samples: Vec<f32>,
    pub sample_rate: u32,
}

pub fn decode(source: &str, output_rate: u32) -> Result<DecodedAudio> {
    ffmpeg::init().context("FFmpeg initialization failed")?;
    let mut options = ffmpeg::Dictionary::new();
    options.set(
        "user_agent",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131 Safari/537.36",
    );
    options.set("referer", "https://music.youtube.com/");
    options.set("headers", "Origin: https://music.youtube.com/\r\n");
    let mut input = ffmpeg::format::input_with_dictionary(source, options)
        .with_context(|| format!("FFmpeg could not open {source}"))?;
    let stream = input
        .streams()
        .best(ffmpeg::media::Type::Audio)
        .context("media has no audio stream")?;
    let stream_index = stream.index();
    let context = ffmpeg::codec::context::Context::from_parameters(stream.parameters())?;
    let mut decoder = context.decoder().audio()?;
    let input_layout = decoder.channel_layout();
    let input_layout = if input_layout.is_empty() {
        ffmpeg::ChannelLayout::default(decoder.channels() as i32)
    } else {
        input_layout
    };
    let mut resampler = ffmpeg::software::resampling::Context::get(
        decoder.format(),
        input_layout,
        decoder.rate(),
        ffmpeg::format::Sample::F32(ffmpeg::format::sample::Type::Packed),
        ffmpeg::ChannelLayout::STEREO,
        output_rate,
    )?;
    let mut samples = Vec::new();

    for (packet_stream, packet) in input.packets() {
        if packet_stream.index() != stream_index {
            continue;
        }
        decoder.send_packet(&packet)?;
        receive_frames(&mut decoder, &mut resampler, &mut samples)?;
    }
    decoder.send_eof()?;
    receive_frames(&mut decoder, &mut resampler, &mut samples)?;
    if samples.is_empty() {
        bail!("FFmpeg decoded no audio samples");
    }
    Ok(DecodedAudio {
        samples,
        sample_rate: output_rate,
    })
}

fn receive_frames(
    decoder: &mut ffmpeg::decoder::Audio,
    resampler: &mut ffmpeg::software::resampling::Context,
    samples: &mut Vec<f32>,
) -> Result<()> {
    let mut decoded = ffmpeg::frame::Audio::empty();
    while decoder.receive_frame(&mut decoded).is_ok() {
        let mut converted = ffmpeg::frame::Audio::empty();
        resampler.run(&decoded, &mut converted)?;
        samples.extend_from_slice(converted.plane::<f32>(0));
    }
    Ok(())
}
