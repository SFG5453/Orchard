# Orchard Artwork Converter

This dependency-free Node service downloads a bounded Apple Music artwork MP4,
converts its first eight seconds to a looping animated GIF with FFmpeg, and
caches the result by source URL and encoding version. It starts at 512px and
24 fps, then progressively reduces palette size and dimensions while preserving
a high frame rate until the animation stays within the Discord compatibility
target. Artwork with unusually complex motion can progressively step down to
288px at 12 fps rather than failing conversion. It never falls back to a still
image. It listens on `127.0.0.1:8791`;
nginx exposes only
`/api/orchard-artwork/convert` to the Worker.

The bearer token is read from `/usr/local/etc/orchard-artwork/token`. Keep that
file owned by root with mode `600`, and use the same value for the Worker's
`CONVERTER_TOKEN` secret.

The service accepts only direct `.mp4` URLs from `mvod.itunes.apple.com`, follows
only redirects that remain on that host, limits downloads to 32 MiB, and runs at
most two conversions concurrently. `ARTWORK_TARGET_OUTPUT_BYTES` controls the
adaptive encoding target and defaults to 9.95 MB.
