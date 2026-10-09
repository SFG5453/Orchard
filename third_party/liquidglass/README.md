# LiquidGlass attribution

The native glass renderer in `app/src/appearance/liquid_glass.*` and
`app/qml/shaders/liquid_glass.*` adapts LiquidGlass by ybouane:
https://github.com/ybouane/liquidglass

Reference: `src/shaders.ts` and `src/GlassRenderer.ts` at commit
`00aafe50202e916951d6f30d49afa1197ca236a7` (v1.0.3), retrieved 2026-09-27.
Upstream declares the MIT license in `package.json` and its README but ships no
LICENSE file; the standard MIT text is preserved in LICENSE and embedded in the
application at `:/licenses/liquidglass/LICENSE`.

The port keeps the rounded-rect bevel height field, biconvex refraction,
chromatic aberration, Fresnel, multi-light specular, rim stroke and drop
shadow. It replaces the DOM rasterisation, CPU crop and texture upload, and
6 x 2 full-resolution Gaussian passes with a shared scene graph layer, a box
prefilter plus separable Gaussian at reduced resolution on the RHI (three
passes at any radius, skipped when nothing changed), analytic surface normals,
and a single-tap fast path for the flat interior of each panel.
