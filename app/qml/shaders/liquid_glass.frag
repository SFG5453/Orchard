// Adapted from LiquidGlass. Copyright (c) 2026 ybouane.
// SPDX-License-Identifier: MIT
// See third_party/liquidglass/LICENSE (also bundled at :/licenses/liquidglass/LICENSE).
#version 440

layout(location = 0) in vec2 vLocal;  // device px from the panel centre, y down
layout(location = 1) in vec2 vUV;     // 0..1 across the panel
layout(location = 0) out vec4 fragColor;
layout(std140, binding = 0) uniform buf {
    mat4 mvp;
    vec4 quad;
    vec4 panel;
    vec4 optics;
    vec4 finish;
    vec4 shadow;
    vec4 sharpRect;
    vec4 blurRect;
    vec4 blurClamp;
    float opacity;
};
layout(binding = 1) uniform sampler2D sharpSource;
layout(binding = 2) uniform sampler2D blurSource;

float roundedRectSdf(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

// Analytic outward SDF gradient; replaces upstream's four extra SDF taps per pixel.
vec2 roundedRectGradient(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    vec2 g = q.x > 0.0 && q.y > 0.0 ? normalize(q) : (q.x > q.y ? vec2(1.0, 0.0) : vec2(0.0, 1.0));
    return g * vec2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
}

// Half-circle bevel: steep at the rim, flat once the depth reaches zR.
float bevelHeight(float d, float zR) {
    d = clamp(d, 0.0, zR);
    return sqrt(d * (2.0 * zR - d));
}

vec3 grade(vec3 col, float depth) {
    col *= 1.0 + finish.z;
    float lum = dot(col, vec3(0.299, 0.587, 0.114));
    col = mix(vec3(lum), col, 1.0 + finish.y);
    col = mix(col, col * vec3(0.92, 0.95, 1.05), finish.w);
    return col * (1.0 + 0.06 * depth);
}

// Multi-light Blinn-Phong against a (0, 0, 1) viewer; the compiler folds the light vectors.
float specular(vec3 N) {
    vec3 h1 = normalize(normalize(vec3(0.4, 0.7, 1.0)) + vec3(0.0, 0.0, 1.0));
    vec3 h2 = normalize(normalize(vec3(-0.3, -0.5, 1.0)) + vec3(0.0, 0.0, 1.0));
    vec3 l3 = normalize(vec3(0.1, 0.3, 1.0));
    vec3 h4 = normalize(normalize(vec3(0.0, 0.9, 0.4)) + vec3(0.0, 0.0, 1.0));
    return (pow(max(dot(N, h1), 0.0), 90.0)
          + pow(max(dot(N, h2), 0.0), 50.0) * 0.3
          + pow(max(dot(N, l3), 0.0), 6.0) * 0.1
          + pow(max(dot(N, h4), 0.0), 120.0) * 0.6) * optics.w;
}

void main() {
    float dpr = shadow.w;
    vec2 halfSize = panel.xy * 0.5;
    float maxD = min(halfSize.x, halfSize.y);
    float r = min(panel.z, maxD);
    float sdf = roundedRectSdf(vLocal, halfSize, r);

    vec4 shade = vec4(0.0);
    if (shadow.x > 0.0 && sdf > -1.5 * dpr) {
        float d = max(roundedRectSdf(vLocal - vec2(0.0, shadow.z), halfSize, r) - dpr, 0.0);
        float spread = max(shadow.y, 1.0);
        float outer = exp(-d * d / (spread * spread)) * 0.65;
        float contact = exp(-2.0 * d / spread) * 0.35;
        shade = vec4(0.0, 0.0, 0.0, (outer + contact) * shadow.x);
    }

    float mask = 1.0 - smoothstep(-1.5 * dpr, 0.5 * dpr, sdf);
    if (mask <= 0.0) {
        fragColor = shade * opacity;
        return;
    }

    // Clamped to the half extent so the bevel plateaus before the centre instead of creasing there.
    float zR = max(min(panel.w, maxD), 1.0);
    float inside = -sdf;
    float e = 2.0 * dpr;
    float border = 1.5 * dpr;
    vec2 pxToUv = 1.0 / panel.xy;

    // Flat interior: past the bevel, rim band, glow and stroke the lens is a plain pane (N = z,
    // no Fresnel, no dispersion), so one blurred tap reproduces the full path. Most pixels land here.
    if (inside > max(max(zR + e, maxD * 0.35), max(5.0 * dpr, border + dpr))) {
        vec2 uv = vUV - vLocal / max(halfSize, vec2(1.0)) * optics.x * 4.0 * dpr * pxToUv;
        vec3 col = texture(blurSource, clamp(blurRect.xy + uv * blurRect.zw, blurClamp.xy, blurClamp.zw)).rgb;
        col = grade(col, 1.0) + (optics.w > 0.0 ? specular(vec3(0.0, 0.0, 1.0)) : 0.0);
        fragColor = vec4(clamp(col, 0.0, 1.0), 1.0) * opacity;
        return;
    }

    float edge = 1.0 - smoothstep(0.0, maxD * 0.35, inside);

    // Central difference over 2px keeps upstream's bounded slope at the rim, where the analytic one is infinite.
    float h = bevelHeight(inside, zR);
    float slope = (bevelHeight(inside + e, zR) - bevelHeight(inside - e, zR)) / (2.0 * e);
    vec2 hGrad = -roundedRectGradient(vLocal, halfSize, r) * slope;
    vec3 N = normalize(vec3(-hGrad, 1.0));
    float depth = smoothstep(0.0, zR, inside);

    // Biconvex lens: entry and exit refraction plus a thickness term, then a gentle pull toward the centre.
    const float refractPower = 1.0 - 1.0 / 1.5;
    vec2 refractPx = hGrad * refractPower * (2.0 + 0.5 * h / zR) * optics.x * 30.0 * dpr;
    refractPx -= vLocal / max(halfSize, vec2(1.0)) * optics.x * 4.0 * dpr * depth;
    vec2 uv = vUV + refractPx * pxToUv;

    // Sharp taps may land outside the panel (a shared backdrop has real pixels there); blurred taps
    // stay inside the chain's used region because the texture slack past it is stale.
    vec2 su = sharpRect.xy + uv * sharpRect.zw;
    vec2 bu = blurRect.xy + uv * blurRect.zw;
    vec3 sharp;
    vec3 blurred;
    if (optics.y > 0.0) {
        vec2 ca = N.xy * optics.y * 36.0 * dpr * (edge * 0.7 + 0.3) * pxToUv;
        vec2 sca = ca * sharpRect.zw;
        vec2 bca = ca * blurRect.zw;
        sharp = vec3(texture(sharpSource, su + sca).r, texture(sharpSource, su).g, texture(sharpSource, su - sca).b);
        blurred = vec3(texture(blurSource, clamp(bu + bca, blurClamp.xy, blurClamp.zw)).r,
                       texture(blurSource, clamp(bu, blurClamp.xy, blurClamp.zw)).g,
                       texture(blurSource, clamp(bu - bca, blurClamp.xy, blurClamp.zw)).b);
    } else {
        sharp = texture(sharpSource, su).rgb;
        blurred = texture(blurSource, clamp(bu, blurClamp.xy, blurClamp.zw)).rgb;
    }
    // The rim leans toward the sharp capture so the lensing reads through the frost.
    vec3 col = mix(blurred, sharp, edge * 0.15);

    col = grade(col, depth);

    float fresnel = pow(1.0 - abs(N.z), 4.0) * finish.x;

    float spec = optics.w > 0.0 ? specular(N) : 0.0;

    float stroke = smoothstep(-border - dpr, -border, sdf) * (1.0 - smoothstep(-dpr, 0.0, sdf));
    stroke *= 0.4 + 0.6 * (0.5 - 0.5 * vLocal.y / halfSize.y);
    float rim = edge * optics.z * 0.22;
    float glow = (1.0 - smoothstep(0.0, 5.0 * dpr, inside)) * optics.z * 0.15;
    float environment = (N.y * 0.5 + 0.5) * fresnel * 0.08;

    vec3 fin = col + spec + rim + glow + stroke * optics.z * 0.55 + environment;
    fin = clamp(mix(fin, vec3(1.0), fresnel * 0.2), 0.0, 1.0);

    // Premultiplied glass over its own shadow, which only shows through the AA fringe.
    fragColor = (vec4(fin * mask, mask) + shade * (1.0 - mask)) * opacity;
}
