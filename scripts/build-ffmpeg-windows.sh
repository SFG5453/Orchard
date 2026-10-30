#!/usr/bin/env bash
set -euo pipefail
tool_prefix=x86_64-w64-mingw32-
if [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* ]]; then
  export PATH="/mingw64/bin:/usr/bin:$PATH"
  tool_prefix=
fi

source_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
build_root="${1:?usage: build-ffmpeg-windows.sh BUILD_DIRECTORY}"
mkdir -p "$build_root"
build_root="$(cd -- "$build_root" && pwd)"
prefix="$build_root/install"
cc="${tool_prefix}gcc"
ar="${tool_prefix}ar"
ranlib="${tool_prefix}ranlib"
for tool in "$cc" "$ar" "$ranlib" cmake ninja make pkg-config nasm; do
  command -v "$tool" >/dev/null || { echo "Missing FFmpeg build tool: $tool" >&2; exit 1; }
done
cc="$(command -v "$cc")"
ar="$(command -v "$ar")"
ranlib="$(command -v "$ranlib")"
if [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* ]]; then
  cc="$(cygpath -m "$cc.exe")"
  ar="$(cygpath -m "$ar.exe")"
  ranlib="$(cygpath -m "$ranlib.exe")"
fi
test -f "$source_root/third_party/ffmpeg/configure"
test -f "$source_root/third_party/libwebp/CMakeLists.txt"

cmake --fresh -S "$source_root/third_party/libwebp" -B "$build_root/webp" -G Ninja \
  -DCMAKE_SYSTEM_NAME=Windows -DCMAKE_C_COMPILER:FILEPATH="$cc" \
  -DCMAKE_AR:FILEPATH="$ar" -DCMAKE_RANLIB:FILEPATH="$ranlib" \
  -DCMAKE_INSTALL_LIBDIR=lib -DCMAKE_BUILD_TYPE=MinSizeRel -DCMAKE_INSTALL_PREFIX="$prefix" \
  -DBUILD_SHARED_LIBS=OFF -DWEBP_LINK_STATIC=ON \
  -DWEBP_BUILD_ANIM_UTILS=OFF -DWEBP_BUILD_CWEBP=OFF -DWEBP_BUILD_DWEBP=OFF \
  -DWEBP_BUILD_GIF2WEBP=OFF -DWEBP_BUILD_IMG2WEBP=OFF -DWEBP_BUILD_VWEBP=OFF \
  -DWEBP_BUILD_WEBPINFO=OFF -DWEBP_BUILD_WEBPMUX=OFF -DWEBP_BUILD_EXTRAS=OFF
cmake --build "$build_root/webp"
cmake --install "$build_root/webp"

mkdir -p "$build_root/ffmpeg"
cd -- "$build_root/ffmpeg"
export PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig"
export PKG_CONFIG_PATH=
bash "$source_root/third_party/ffmpeg/configure" \
  --prefix="$prefix" --target-os=mingw32 --arch=x86_64 --enable-cross-compile \
  --cross-prefix="$tool_prefix" --cc="$cc" --ar="$ar" --ranlib="$ranlib" --pkg-config=pkg-config \
  --pkg-config-flags=--static --extra-ldflags=-static \
  --disable-autodetect --disable-everything --disable-shared --enable-static \
  --enable-small --disable-checkasm --disable-debug --disable-doc --disable-ffplay --disable-avdevice \
  --enable-ffmpeg --enable-ffprobe --enable-libwebp --enable-zlib --enable-schannel \
  --enable-protocol=file,pipe,http,https,tcp,tls,crypto,data \
  --enable-demuxer=mov,hls,mpegts,matroska,mp3,flac,ogg,wav,aiff,asf,aac,ape,wv,image2,image2pipe,gif,png_pipe,jpeg_pipe,webp_pipe,bmp_pipe \
  --enable-decoder=h264,hevc,vp8,vp9,mpeg4,gif,png,mjpeg,webp,bmp,aac,aac_latm,mp3,mp3float,flac,alac,opus,vorbis,wmav1,wmav2,wmapro,wmalossless,ape,wavpack,pcm_u8,pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_s32be,pcm_f32le,pcm_f32be,pcm_f64le,pcm_f64be \
  --enable-parser=h264,hevc,vp8,vp9,mpeg4video,aac,aac_latm,mpegaudio,flac,opus,vorbis,png,mjpeg,webp,gif \
  --enable-encoder=libwebp,libwebp_anim,png,mjpeg,mpeg4,pcm_f32le \
  --enable-muxer=webp,image2,mp4,wav \
  --enable-filter=scale,format,fps,transpose,hflip,vflip,rotate,aresample,aformat,anull,null,trim,atrim,setpts,asetpts \
  --enable-bsf=aac_adtstoasc
make -j"${NUMBER_OF_PROCESSORS:-4}"
make install
cat "$source_root/third_party/ffmpeg/COPYING.LGPLv2.1" \
  "$source_root/third_party/libwebp/COPYING" > "$prefix/bin/ffmpeg-LICENSE.txt"
