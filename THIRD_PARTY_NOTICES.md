# Third-party notices

## Discord Social SDK

Orchard uses Discord Social SDK 1.10.19337 for desktop Rich Presence. The
vendored headers and platform libraries are under `vendor/discord_social_sdk`.
Its included open source notices are preserved in
`vendor/discord_social_sdk/License-Notices.txt` and shipped with packages.

## LiteRT

Orchard runs on-device lyric translation with Google LiteRT 2.2.0. The C API
headers from `litert_cc_sdk.zip` and the prebuilt runtimes from
`storage.googleapis.com/litert/binaries/2.2.0` are under `vendor/litert`.
LiteRT is licensed under the Apache License 2.0, preserved in
[`vendor/litert/LICENSE`](vendor/litert/LICENSE) and shipped with packages.

## Lyric translation models

Translation models are not bundled; Orchard downloads one language pack the
first time a song in that language is translated, and checks each file against
a pinned SHA-256.

- Standard Korean, Chinese, Russian, Arabic, Greek, Turkish, French, German,
  Spanish, Italian, Dutch and Catalan packs are the OPUS-MT tiny models by
  Helsinki-NLP (`Helsinki-NLP/opus-mt_tiny_<lang>-eng`), Apache License 2.0,
  fetched unchanged from Hugging Face at pinned commits.
- The standard Japanese pack is
  [`Mitsua/elan-mt-tiny-ja-en`](https://huggingface.co/Mitsua/elan-mt-tiny-ja-en)
  by the ELAN MITSUA Project / Abstract Engine, CC BY-SA 4.0. The converted
  model is distributed under the same license.
- High packs are the OPUS-MT base models by Helsinki-NLP
  (`Helsinki-NLP/opus-mt-<lang>-en`, and `opus-mt-grk-en` for Greek). The
  Chinese and Russian models are CC BY 4.0; the rest are Apache License 2.0.

Orchard converts the Japanese and High packs to int8 LiteRT with
[`scripts/translation`](scripts/translation) and hosts them at
[`SFG545/orchard-lyric-translation`](https://huggingface.co/SFG545/orchard-lyric-translation).

## Lucide Icons

Orchard vendors selected icons from Lucide Static 1.44.0 under
`app/qml/assets/lucide`. The icon source files retain their license marker, and
the complete applicable license text is bundled at
`app/qml/assets/lucide/LICENSE.txt`.

Lucide is licensed under the ISC License. Some Lucide icons are derived from
Feather Icons and are licensed under the MIT License. Copyright and permission
notices for both projects are preserved in the bundled license file.

## Native YouTube player extraction

`orchard-youtube-extractor` uses Oxc 0.149.0 for JavaScript parsing and lexical
binding analysis. Oxc is MIT licensed; its notice is preserved in
[`OXC_LICENSE`](crates/orchard-youtube-extractor/OXC_LICENSE).

The player matchers, built-in names, and initializer policy are based on
YouTube.js 18.0.0 by LuanRT. Its MIT license is preserved in
[`YOUTUBEJS_LICENSE`](crates/orchard-youtube-extractor/YOUTUBEJS_LICENSE).

## AI-generated music detector

The C++ slop detector loads the logistic-regression weights from
[`lofcz/ai-music-detector`](https://huggingface.co/lofcz/ai-music-detector)
(`model.safetensors`, SHA-256
`9a8324d986bf5b2b6c62892c9bf61409ea476602339f29b1f02a3f3dd4939acd`), stored as
raw little-endian floats in
[`models/slop/fakeprint_lr.f32`](models/slop/fakeprint_lr.f32).
The model and its feature extraction are by Matěj Štágl under the MIT License,
preserved in [`slop_model/LICENSE`](app/src/playback/slop_model/LICENSE). The
method follows Afchar et al., "A Fourier Explanation of AI-music Artifacts"
(ISMIR 2025).

## Docs search model

The docs window matches plain-language questions to docs sections with
[`TaylorAI/bge-micro-v2`](https://huggingface.co/TaylorAI/bge-micro-v2), a sentence
embedding model distilled from `BAAI/bge-small-en-v1.5`. Orchard ships the
author's quantized ONNX export unchanged as
[`models/docs-search/model_quantized.onnx`](models/docs-search/model_quantized.onnx)
(SHA-256 `ed65e36025aa94cb74207dab863c85452919ec0ab7df3512092932aa22c9a33a`)
together with the model's `bert-base-uncased` vocabulary
([`vocab.txt`](models/docs-search/vocab.txt)). The model is by Benjamin Anderson
under the MIT License, preserved in [`LICENSE`](models/docs-search/LICENSE).

## Earmark beat and downbeat tracker

The constants in `crates/earmark/src/analysis/beat` (emission means, bar
templates and confidence fits) were fitted on the log-mel spectrograms published
with Beat This (Foscarin, Schlüter and Widmer, "Beat this! Accurate beat
tracking without DBN postprocessing", ISMIR 2024; Zenodo record 13922116, CC BY
4.0) and on the beat and downbeat annotations from
[`CPJKU/beat_this_annotations`](https://github.com/CPJKU/beat_this_annotations)
(MIT License), covering the Ballroom, Hainsworth, HJDB, Candombe, GuitarSet,
Beatles, Filosax, JAAH, TapCorrect, RWC, Harmonix, SIMAC and SMC datasets. GTZAN
was held out for testing. Only the fitted statistics ship with Orchard; no
spectrograms, annotations or audio do.

## FFmpeg (Windows packages)

Windows packages bundle a static `ffmpeg.exe` from BtbN FFmpeg-Builds
(`ffmpeg-n9.0.2-10-g51c4a23d74-win64-gpl-9.0`, release
`autobuild-2026-09-26-13-03`), used by `orchard-adaptive-mix` to decode audio.
This build is licensed under the GNU GPL version 3; its license text ships as
`ffmpeg-LICENSE.txt`. Corresponding source: FFmpeg at
<https://github.com/FFmpeg/FFmpeg/tree/n9.0.2> and the build scripts at
<https://github.com/BtbN/FFmpeg-Builds>.

## SimpMusic (Android full-bleed player)

The mobile player's full-bleed scrim
(`mobile/android/app/src/main/java/dev/sfg/orchard/mobile/ui/components/SmoothScrim.kt`)
is adapted from `smoothScrimBrush` in
[SimpMusic](https://github.com/maxrave-dev/SimpMusic) v2.2.0 by maxrave-dev and
contributors. SimpMusic is licensed under the GNU GPL version 3; the adapted
code is combined with Orchard under section 13 of the GNU GPL v3 and GNU AGPL
v3, and the file keeps SimpMusic's copyright notice. Source:
<https://github.com/maxrave-dev/SimpMusic/tree/v2.2.0>.

## Convx (Android home)

The mobile home's section titles and spotlight carousel
(`mobile/android/app/src/main/java/dev/sfg/orchard/mobile/ui/screens/HomeSpotlight.kt`)
are adapted from `NavigationTitle` and `DailyDiscoverCard` / `dailyDiscoverSection` in
[Convx](https://github.com/cosmictaserdev-creator/Convx) v1.5.2 by the Convx Project
contributors. Convx is licensed under the GNU GPL version 3; the adapted code is
combined with Orchard under section 13 of the GNU GPL v3 and GNU AGPL v3, and the
file keeps Convx's copyright notice. Source:
<https://github.com/cosmictaserdev-creator/Convx/tree/v1.5.2>.

## Convx (Android artist, album and playlist pages)

The mobile detail pages' faded full-bleed hero, shuffle / play / save row, expandable
About block, featured release card and floating back/action chrome with its scroll
scrim are adapted from `AlbumScreen`, `ArtistScreen` / `FeaturedReleaseCard`,
`OnlinePlaylistScreen` and `ExpandableText` in
[Convx](https://github.com/cosmictaserdev-creator/Convx) v1.5.2 by the Convx Project
contributors. Adapted files, each keeping Convx's copyright notice, under
`mobile/android/app/src/main/java/dev/sfg/orchard/mobile/ui/`:

- `components/DetailHero.kt`
- `components/DetailChrome.kt`
- `components/ArtistComponents.kt`
- `screens/ArtistDetail.kt`
- `screens/CollectionHero.kt`

Convx is licensed under the GNU GPL version 3; the adapted code is combined with
Orchard under section 13 of the GNU GPL v3 and GNU AGPL v3. Source:
<https://github.com/cosmictaserdev-creator/Convx/tree/v1.5.2>.

## Orchard Connect libraries

Orchard Connect (`core/native/connect`), shared by the desktop app and the
Android app, links these libraries statically. Each is a git submodule under
`third_party`, and the desktop app bundles each license text under
`:/licenses`.

- [libdatachannel](https://github.com/paullouisageneau/libdatachannel) 0.24.6
  by Paul-Louis Ageneau: WebSocket and WebRTC data channel transports. Mozilla
  Public License 2.0, preserved in
  [`LICENSE`](third_party/libdatachannel/LICENSE). Its unmodified source is in
  that submodule.
- [libjuice](https://github.com/paullouisageneau/libjuice), bundled by
  libdatachannel: ICE. Mozilla Public License 2.0, preserved in
  [`LICENSE`](third_party/libdatachannel/deps/libjuice/LICENSE).
- [usrsctp](https://github.com/sctplab/usrsctp), bundled by libdatachannel: SCTP
  for data channels. BSD 3-Clause, preserved in
  [`LICENSE.md`](third_party/libdatachannel/deps/usrsctp/LICENSE.md).
- [plog](https://github.com/SergiusTheBest/plog), bundled by libdatachannel:
  logging. MIT, preserved in [`LICENSE`](third_party/libdatachannel/deps/plog/LICENSE).
- [Mbed TLS](https://github.com/Mbed-TLS/mbedtls) 3.6.7: DTLS, HKDF, HMAC,
  ChaCha20-Poly1305 and random numbers. Dual-licensed Apache-2.0 OR
  GPL-2.0-or-later; Orchard uses it under Apache-2.0, preserved in
  [`LICENSE`](third_party/mbedtls/LICENSE).
- [JSON for Modern C++](https://github.com/nlohmann/json) 3.12.0 by Niels
  Lohmann: JSON. MIT, preserved in [`LICENSE.MIT`](third_party/json/LICENSE.MIT).
