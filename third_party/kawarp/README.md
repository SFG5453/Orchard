# Kawarp attribution

The native artwork preparation and shaders in `app/src/appearance/kawarp_*`
and `app/qml/shaders/kawarp.*` adapt Kawarp by Better Lyrics:
https://github.com/better-lyrics/kawarp

Reference: https://raw.githubusercontent.com/better-lyrics/kawarp/refs/heads/master/packages/core/src/index.ts
Retrieved 2026-09-13. The upstream MIT license is preserved verbatim in LICENSE
and embedded in the application at `:/licenses/kawarp/LICENSE`.

This port prepares the 128 × 128 tinted artwork and five bilinear Kawase blur
passes in C++ on a worker thread only when artwork changes. Qt's scene graph
renders the original simplex domain warp, crossfade, saturation, vignette and
dithering in one GPU pass, without an intermediate full-resolution warp buffer.
Visual defaults match Orchard v2's KawarpArtworkBackground.
