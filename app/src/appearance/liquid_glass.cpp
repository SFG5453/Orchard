// Adapted from LiquidGlass. Copyright (c) 2026 ybouane.
// SPDX-License-Identifier: MIT
// See third_party/liquidglass/LICENSE (also bundled at :/licenses/liquidglass/LICENSE).
#include "liquid_glass.h"
#include <QFile>
#include <QQuickWindow>
#include <QSGRenderNode>
#include <QSGTexture>
#include <QSGTextureProvider>
#include <rhi/qrhi.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>
#include <vector>

namespace {
struct Parameters {
    float radius, bevelDepth, blurRadius;
    float refraction, chromaticAberration, edgeHighlight, specular;
    float fresnel, saturation, brightness, tint;
    float shadowOpacity, shadowSpread, shadowOffsetY;
};

// Matches the std140 block in liquid_glass.vert/.frag.
struct CompositeUniforms {
    float mvp[16];
    float quad[4], panel[4], optics[4], finish[4], shadow[4];
    float sharpRect[4], blurRect[4], blurClamp[4];
    float opacity;
    float pad[3];
};
static_assert(sizeof(CompositeUniforms) == 208);

// Matches liquid_glass_box.frag / liquid_glass_blur.frag.
struct PassUniforms {
    float inputRect[4], inputClamp[4];
    float texel[2], targetScale[2];
    float direction[2], taps, center;
    float kernel[8][4];
    bool operator==(const PassUniforms &o) const { return std::memcmp(this, &o, sizeof(*this)) == 0; }
};
static_assert(sizeof(PassUniforms) == 192);

QShader loadShader(const QString &path) {
    QFile file(path);
    return file.open(QIODevice::ReadOnly) ? QShader::fromSerialized(file.readAll()) : QShader();
}

bool hasDepthStencil(QRhiRenderTarget *rt) {
    if (rt->resourceType() != QRhiResource::TextureRenderTarget) return true;  // Qt Quick swapchains always carry one
    const auto desc = static_cast<QRhiTextureRenderTarget *>(rt)->description();
    return desc.depthStencilBuffer() || desc.depthTexture();
}

// Power-of-two downscale that leaves a Gaussian of `sigma` source texels at <= 3 working texels,
// so the separable passes stay short no matter how wide the blur is.
int blurDownscale(float sigma, QSize region) {
    const int fit = int(std::floor(std::log2(float(std::max(1, std::min(region.width(), region.height()))))));
    return std::clamp(int(std::ceil(std::log2(sigma / 3.0f))), 0, std::min(4, fit));
}

// Discrete Gaussian folded into bilinear pairs: one tap per two texels, weights normalized.
void gaussianKernel(float sigma, PassUniforms &u) {
    const int radius = std::clamp(int(std::ceil(3 * sigma)), 1, 16);
    float w[17];
    float total = 0;
    for (int i = 0; i <= radius; ++i) {
        w[i] = std::exp(-float(i * i) / (2 * sigma * sigma));
        total += i ? 2 * w[i] : w[i];
    }
    int pairs = 0;
    for (int i = 1; i <= radius; i += 2, ++pairs) {
        const float a = w[i], b = i + 1 <= radius ? w[i + 1] : 0;
        u.kernel[pairs][0] = (i * a + (i + 1) * b) / (a + b);
        u.kernel[pairs][1] = (a + b) / total;
    }
    u.taps = float(pairs);
    u.center = w[0] / total;
}

// Used region of `size` texels inside a texture of `capacity`, plus a half-texel inset clamp.
void regionUv(QSize size, QSize capacity, float rect[4], float clampRect[4]) {
    const float w = float(capacity.width()), h = float(capacity.height());
    rect[0] = 0; rect[1] = 0; rect[2] = size.width() / w; rect[3] = size.height() / h;
    clampRect[0] = 0.5f / w; clampRect[1] = 0.5f / h;
    clampRect[2] = (size.width() - 0.5f) / w; clampRect[3] = (size.height() - 0.5f) / h;
}
}

// Layers (item layers and ShaderEffectSource) announce new content before the frame that grabs it.
// A shared layer may be grabbed by another consumer first, so updateTexture()'s result is not enough.
class LiquidGlassSourceWatcher : public QObject {
    Q_OBJECT
public:
    bool dirty{true};
public slots:
    void markDirty() { dirty = true; }
};

namespace {
class LiquidGlassNode final : public QSGRenderNode {
public:
    explicit LiquidGlassNode(QQuickWindow *window) : m_window(window) { setFlag(UsePreprocess); }
    ~LiquidGlassNode() override { releaseResources(); }

    void preprocess() override;
    void prepare() override;
    void render(const RenderState *state) override;
    void releaseResources() override;
    StateFlags changedStates() const override { return BlendState | ScissorState | ViewportState; }
    RenderingFlags flags() const override { return BoundedRectRendering | DepthAwareRendering | NoExternalRendering; }
    QRectF rect() const override { return quad; }

    QPointer<QSGTextureProvider> provider;
    Parameters params{};
    QSizeF size;
    QRectF quad;
    QRectF sourceRect{0, 0, 1, 1};
    float dpr{1};

private:
    struct Target {
        std::unique_ptr<QRhiTexture> texture;
        std::unique_ptr<QRhiTextureRenderTarget> rt;
        QSize used;
    };
    struct Pass {
        Target *input{nullptr};  // null reads the source texture
        Target *target{nullptr};
        bool box{false};
        bool vertical{false};
        std::unique_ptr<QRhiBuffer> ubuf;
        std::unique_ptr<QRhiShaderResourceBindings> srb;
        quint64 inputId{0}, targetId{0};
        PassUniforms uniforms{};
    };

    bool ensureShared(QRhi *rhi, QRhiResourceUpdateBatch *u);
    bool ensureChain(QRhi *rhi, QRhiTexture *source, QSize region, int downscale, float sigma);
    bool ensureTarget(QRhi *rhi, Target &target, QSize used);
    bool ensureComposite(QRhi *rhi, QRhiRenderTarget *rt, QRhiTexture *source);
    std::unique_ptr<QRhiGraphicsPipeline> makePassPipeline(QRhi *rhi, const QShader &fragment);
    void dropChain();

    QQuickWindow *m_window;
    QPointer<QSGTexture> m_texture;
    LiquidGlassSourceWatcher m_watcher;
    bool m_ready{false};

    std::unique_ptr<QRhiBuffer> m_quad;
    std::unique_ptr<QRhiSampler> m_sampler;
    std::unique_ptr<QRhiTexture> m_layoutTexture;
    std::unique_ptr<QRhiBuffer> m_passLayoutBuffer;
    std::unique_ptr<QRhiShaderResourceBindings> m_passLayout;
    std::unique_ptr<QRhiRenderPassDescriptor> m_passRp;
    std::unique_ptr<QRhiGraphicsPipeline> m_boxPipeline, m_blurPipeline;

    // Box prefilter into A, horizontal into B, vertical back into A. Textures only grow, or shrink
    // past a threshold, so animated resizes reuse them.
    Target m_a, m_b;
    std::vector<Pass> m_passes;
    int m_downscale{-1};

    std::unique_ptr<QRhiBuffer> m_ubuf;
    std::unique_ptr<QRhiShaderResourceBindings> m_srb;
    std::unique_ptr<QRhiGraphicsPipeline> m_pipeline;
    QVector<quint32> m_pipelineFormat;
    int m_pipelineSamples{0};
    bool m_pipelineDepth{false};
    quint64 m_srbSharp{0}, m_srbBlur{0};
};

void LiquidGlassNode::preprocess() {
    QSGTexture *texture = provider ? provider->texture() : nullptr;
    if (texture != m_texture) {
        if (m_texture) QObject::disconnect(m_texture, nullptr, &m_watcher, nullptr);
        if (texture && texture->metaObject()->indexOfSignal("updateRequested()") >= 0) {
            QObject::connect(texture, SIGNAL(updateRequested()), &m_watcher, SLOT(markDirty()), Qt::DirectConnection);
            QObject::connect(texture, SIGNAL(scheduledUpdateCompleted()), &m_watcher, SLOT(markDirty()),
                             Qt::DirectConnection);
        }
        m_texture = texture;
        m_watcher.dirty = true;
    }
    // Consumers drive layer grabs; ours may be the one that renders it.
    if (auto *dynamic = qobject_cast<QSGDynamicTexture *>(texture); dynamic && dynamic->updateTexture())
        m_watcher.dirty = true;
}

bool LiquidGlassNode::ensureShared(QRhi *rhi, QRhiResourceUpdateBatch *u) {
    if (m_quad && m_passLayout && m_boxPipeline && m_blurPipeline) return true;
    static const float quad[] = {0, 0, 1, 0, 0, 1, 1, 1};
    m_quad.reset(rhi->newBuffer(QRhiBuffer::Immutable, QRhiBuffer::VertexBuffer, sizeof(quad)));
    m_sampler.reset(rhi->newSampler(QRhiSampler::Linear, QRhiSampler::Linear, QRhiSampler::None,
                                    QRhiSampler::ClampToEdge, QRhiSampler::ClampToEdge));
    // Pipelines need a layout and a render pass up front; a 1x1 stand-in provides both.
    m_layoutTexture.reset(rhi->newTexture(QRhiTexture::RGBA8, QSize(1, 1), 1, QRhiTexture::RenderTarget));
    m_passLayoutBuffer.reset(rhi->newBuffer(QRhiBuffer::Dynamic, QRhiBuffer::UniformBuffer, sizeof(PassUniforms)));
    if (!m_quad->create() || !m_sampler->create() || !m_layoutTexture->create() || !m_passLayoutBuffer->create())
        return false;
    u->uploadStaticBuffer(m_quad.get(), quad);
    std::unique_ptr<QRhiTextureRenderTarget> probe(rhi->newTextureRenderTarget({QRhiColorAttachment(m_layoutTexture.get())}));
    m_passRp.reset(probe->newCompatibleRenderPassDescriptor());
    m_passLayout.reset(rhi->newShaderResourceBindings());
    m_passLayout->setBindings({
        QRhiShaderResourceBinding::uniformBuffer(0, QRhiShaderResourceBinding::FragmentStage, m_passLayoutBuffer.get()),
        QRhiShaderResourceBinding::sampledTexture(1, QRhiShaderResourceBinding::FragmentStage, m_layoutTexture.get(), m_sampler.get()),
    });
    if (!m_passLayout->create()) return false;
    m_boxPipeline = makePassPipeline(rhi, loadShader(QStringLiteral(":/shaders/liquid_glass_box.frag.qsb")));
    m_blurPipeline = makePassPipeline(rhi, loadShader(QStringLiteral(":/shaders/liquid_glass_blur.frag.qsb")));
    return m_boxPipeline && m_blurPipeline;
}

std::unique_ptr<QRhiGraphicsPipeline> LiquidGlassNode::makePassPipeline(QRhi *rhi, const QShader &fragment) {
    static const QShader vertex = loadShader(QStringLiteral(":/shaders/liquid_glass_pass.vert.qsb"));
    std::unique_ptr<QRhiGraphicsPipeline> ps(rhi->newGraphicsPipeline());
    ps->setTopology(QRhiGraphicsPipeline::TriangleStrip);
    ps->setShaderStages({{QRhiShaderStage::Vertex, vertex}, {QRhiShaderStage::Fragment, fragment}});
    QRhiVertexInputLayout layout;
    layout.setBindings({{2 * sizeof(float)}});
    layout.setAttributes({{0, 0, QRhiVertexInputAttribute::Float2, 0}});
    ps->setVertexInputLayout(layout);
    ps->setShaderResourceBindings(m_passLayout.get());
    ps->setRenderPassDescriptor(m_passRp.get());
    return ps->create() ? std::move(ps) : nullptr;
}

bool LiquidGlassNode::ensureTarget(QRhi *rhi, Target &target, QSize used) {
    target.used = used;
    // 64-texel slack absorbs animated resizes (the player pill tracks the queue) without reallocating.
    const QSize want((used.width() + 63) & ~63, (used.height() + 63) & ~63);
    if (target.texture) {
        const QSize cap = target.texture->pixelSize();
        const bool fits = used.width() <= cap.width() && used.height() <= cap.height();
        const bool wasteful = want.width() * 2 <= cap.width() || want.height() * 2 <= cap.height();
        if (fits && !wasteful) return true;
    }
    const QSize cap = want;
    target.rt.reset();
    target.texture.reset(rhi->newTexture(QRhiTexture::RGBA8, cap, 1, QRhiTexture::RenderTarget));
    if (!target.texture->create()) return false;
    target.rt.reset(rhi->newTextureRenderTarget({QRhiColorAttachment(target.texture.get())}));
    target.rt->setRenderPassDescriptor(m_passRp.get());
    return target.rt->create();
}

void LiquidGlassNode::dropChain() {
    m_passes.clear();
    m_a = {};
    m_b = {};
    m_downscale = -1;
}

bool LiquidGlassNode::ensureChain(QRhi *rhi, QRhiTexture *source, QSize region, int downscale, float sigma) {
    if (downscale != m_downscale) {
        dropChain();
        m_downscale = downscale;
        if (downscale > 0) m_passes.push_back({nullptr, &m_a, true, false});
        if (downscale >= 0) {
            m_passes.push_back({downscale > 0 ? &m_a : nullptr, &m_b, false, false});
            m_passes.push_back({&m_b, &m_a, false, true});
        }
    }
    if (downscale < 0) return true;

    const QSize used(std::max(1, (region.width() + (1 << downscale) - 1) >> downscale),
                     std::max(1, (region.height() + (1 << downscale) - 1) >> downscale));
    if (!ensureTarget(rhi, m_a, used) || !ensureTarget(rhi, m_b, used)) return false;

    for (auto &pass : m_passes) {
        QRhiTexture *input = pass.input ? pass.input->texture.get() : source;
        PassUniforms uniforms{};
        if (pass.input) {
            regionUv(pass.input->used, input->pixelSize(), uniforms.inputRect, uniforms.inputClamp);
        } else {
            // Source reads cover the panel's region; clamping to the whole texture lets a shared
            // backdrop blur in its real surroundings instead of smearing the panel edge.
            uniforms.inputRect[0] = float(sourceRect.x()); uniforms.inputRect[1] = float(sourceRect.y());
            uniforms.inputRect[2] = float(sourceRect.width()); uniforms.inputRect[3] = float(sourceRect.height());
            uniforms.inputClamp[2] = uniforms.inputClamp[3] = 1;
        }
        uniforms.texel[0] = 1.0f / input->pixelSize().width();
        uniforms.texel[1] = 1.0f / input->pixelSize().height();
        uniforms.targetScale[0] = 1.0f / used.width();
        uniforms.targetScale[1] = 1.0f / used.height();
        if (pass.box) {
            uniforms.taps = float(1 << (downscale - 1));  // each bilinear tap averages 2x2 texels
        } else {
            uniforms.direction[pass.vertical ? 1 : 0] = 1;
            const float scale = pass.vertical ? float(used.height()) / region.height() : float(used.width()) / region.width();
            gaussianKernel(std::max(sigma * scale, 0.3f), uniforms);
        }
        // A moved or resized panel needs a fresh blur even when the backdrop is idle.
        if (!(uniforms == pass.uniforms)) m_watcher.dirty = true;
        pass.uniforms = uniforms;

        const quint64 inputId = input->globalResourceId(), targetId = pass.target->texture->globalResourceId();
        if (pass.srb && pass.inputId == inputId && pass.targetId == targetId) continue;
        if (!pass.ubuf) {
            pass.ubuf.reset(rhi->newBuffer(QRhiBuffer::Dynamic, QRhiBuffer::UniformBuffer, sizeof(PassUniforms)));
            if (!pass.ubuf->create()) return false;
            pass.srb.reset(rhi->newShaderResourceBindings());
        }
        pass.srb->setBindings({
            QRhiShaderResourceBinding::uniformBuffer(0, QRhiShaderResourceBinding::FragmentStage, pass.ubuf.get()),
            QRhiShaderResourceBinding::sampledTexture(1, QRhiShaderResourceBinding::FragmentStage, input, m_sampler.get()),
        });
        if (!pass.srb->create()) return false;
        pass.inputId = inputId;
        pass.targetId = targetId;
        m_watcher.dirty = true;
    }
    return true;
}

bool LiquidGlassNode::ensureComposite(QRhi *rhi, QRhiRenderTarget *rt, QRhiTexture *source) {
    QRhiTexture *blurred = m_passes.empty() ? source : m_passes.back().target->texture.get();
    if (!m_ubuf) {
        m_ubuf.reset(rhi->newBuffer(QRhiBuffer::Dynamic, QRhiBuffer::UniformBuffer, sizeof(CompositeUniforms)));
        if (!m_ubuf->create()) { m_ubuf.reset(); return false; }
        m_srb.reset(rhi->newShaderResourceBindings());
    }
    // Ids, not pointers: a recreated texture can land at the old address.
    if (source->globalResourceId() != m_srbSharp || blurred->globalResourceId() != m_srbBlur) {
        const auto stages = QRhiShaderResourceBinding::VertexStage | QRhiShaderResourceBinding::FragmentStage;
        m_srb->setBindings({
            QRhiShaderResourceBinding::uniformBuffer(0, stages, m_ubuf.get()),
            QRhiShaderResourceBinding::sampledTexture(1, QRhiShaderResourceBinding::FragmentStage, source, m_sampler.get()),
            QRhiShaderResourceBinding::sampledTexture(2, QRhiShaderResourceBinding::FragmentStage, blurred, m_sampler.get()),
        });
        if (!m_srb->create()) return false;
        m_srbSharp = source->globalResourceId();
        m_srbBlur = blurred->globalResourceId();
    }

    // Layers and the window can differ in format, MSAA and depth, so the pipeline follows the target.
    const auto format = rt->renderPassDescriptor()->serializedFormat();
    const bool depth = hasDepthStencil(rt);
    if (m_pipeline && format == m_pipelineFormat && rt->sampleCount() == m_pipelineSamples && depth == m_pipelineDepth)
        return true;
    static const QShader vertex = loadShader(QStringLiteral(":/shaders/liquid_glass.vert.qsb"));
    static const QShader fragment = loadShader(QStringLiteral(":/shaders/liquid_glass.frag.qsb"));
    m_pipeline.reset(rhi->newGraphicsPipeline());
    m_pipeline->setFlags(QRhiGraphicsPipeline::UsesScissor);
    m_pipeline->setTopology(QRhiGraphicsPipeline::TriangleStrip);
    QRhiGraphicsPipeline::TargetBlend blend;
    blend.enable = true;  // Qt Quick is premultiplied end to end
    blend.srcColor = QRhiGraphicsPipeline::One;
    blend.dstColor = QRhiGraphicsPipeline::OneMinusSrcAlpha;
    blend.srcAlpha = QRhiGraphicsPipeline::One;
    blend.dstAlpha = QRhiGraphicsPipeline::OneMinusSrcAlpha;
    m_pipeline->setTargetBlends({blend});
    // Test against opaque items stacked above us; never write, like any other alpha item.
    m_pipeline->setDepthTest(depth);
    m_pipeline->setDepthOp(QRhiGraphicsPipeline::LessOrEqual);
    m_pipeline->setDepthWrite(false);
    m_pipeline->setSampleCount(rt->sampleCount());
    m_pipeline->setShaderStages({{QRhiShaderStage::Vertex, vertex}, {QRhiShaderStage::Fragment, fragment}});
    QRhiVertexInputLayout layout;
    layout.setBindings({{2 * sizeof(float)}});
    layout.setAttributes({{0, 0, QRhiVertexInputAttribute::Float2, 0}});
    m_pipeline->setVertexInputLayout(layout);
    m_pipeline->setShaderResourceBindings(m_srb.get());
    m_pipeline->setRenderPassDescriptor(rt->renderPassDescriptor());
    if (!m_pipeline->create()) { m_pipeline.reset(); return false; }
    m_pipelineFormat = format;
    m_pipelineSamples = rt->sampleCount();
    m_pipelineDepth = depth;
    return true;
}

void LiquidGlassNode::prepare() {
    m_ready = false;
    QRhi *rhi = m_window->rhi();
    QRhiCommandBuffer *cb = commandBuffer();
    QRhiRenderTarget *rt = renderTarget();
    if (!rhi || !cb || !rt || !m_texture) return;

    QRhiResourceUpdateBatch *u = rhi->nextResourceUpdateBatch();
    m_texture->commitTextureOperations(rhi, u);
    QRhiTexture *source = m_texture->rhiTexture();
    const float panelWidth = float(size.width()) * dpr;
    if (!source || panelWidth <= 0 || sourceRect.isEmpty() || !ensureShared(rhi, u)) { cb->resourceUpdate(u); return; }

    // Source texels under the panel; a per-glass capture is downscaled, a shared backdrop layer is not.
    const QSize sourceSize = source->pixelSize();
    const QSize region(std::max(1, int(std::ceil(sourceRect.width() * sourceSize.width()))),
                       std::max(1, int(std::ceil(sourceRect.height() * sourceSize.height()))));
    // blurRadius reads as a visual radius; sigma = radius / 2.5 keeps the old frost at the default 24.
    const float sigma = params.blurRadius * dpr * region.width() / panelWidth / 2.5f;
    const int downscale = sigma < 0.3f ? -1 : blurDownscale(sigma, region);
    if (!ensureChain(rhi, source, region, downscale, sigma)) {
        // Fall back to the unblurred source rather than sampling a half-built chain.
        dropChain();
        m_watcher.dirty = true;
    }
    if (!ensureComposite(rhi, rt, source)) { cb->resourceUpdate(u); return; }

    const QMatrix4x4 mvp = *projectionMatrix() * *matrix();
    CompositeUniforms uniforms{};
    std::memcpy(uniforms.mvp, mvp.constData(), sizeof(uniforms.mvp));
    const float w = float(size.width()), h = float(size.height());
    uniforms.quad[0] = float(quad.x()); uniforms.quad[1] = float(quad.y());
    uniforms.quad[2] = float(quad.width()); uniforms.quad[3] = float(quad.height());
    uniforms.panel[0] = w * dpr; uniforms.panel[1] = h * dpr;
    uniforms.panel[2] = params.radius * dpr; uniforms.panel[3] = params.bevelDepth * dpr;
    uniforms.optics[0] = params.refraction; uniforms.optics[1] = params.chromaticAberration;
    uniforms.optics[2] = params.edgeHighlight; uniforms.optics[3] = params.specular;
    uniforms.finish[0] = params.fresnel; uniforms.finish[1] = params.saturation;
    uniforms.finish[2] = params.brightness; uniforms.finish[3] = params.tint;
    uniforms.shadow[0] = params.shadowOpacity; uniforms.shadow[1] = params.shadowSpread * dpr;
    uniforms.shadow[2] = params.shadowOffsetY * dpr; uniforms.shadow[3] = dpr;
    uniforms.sharpRect[0] = float(sourceRect.x()); uniforms.sharpRect[1] = float(sourceRect.y());
    uniforms.sharpRect[2] = float(sourceRect.width()); uniforms.sharpRect[3] = float(sourceRect.height());
    if (m_passes.empty()) {
        std::memcpy(uniforms.blurRect, uniforms.sharpRect, sizeof(uniforms.blurRect));
        uniforms.blurClamp[2] = uniforms.blurClamp[3] = 1;
    } else {
        const Target &last = *m_passes.back().target;
        regionUv(last.used, last.texture->pixelSize(), uniforms.blurRect, uniforms.blurClamp);
    }
    uniforms.opacity = float(inheritedOpacity());
    u->updateDynamicBuffer(m_ubuf.get(), 0, sizeof(uniforms), &uniforms);

    // Idle frames (a ticking progress bar, a hover) skip the chain and reuse the last blur.
    if (m_watcher.dirty && !m_passes.empty()) {
        for (auto &pass : m_passes)
            u->updateDynamicBuffer(pass.ubuf.get(), 0, sizeof(PassUniforms), &pass.uniforms);
        const QRhiCommandBuffer::VertexInput input(m_quad.get(), 0);
        for (auto &pass : m_passes) {
            // The whole texture is rasterised; the slack beyond the used region is never sampled.
            const QSize out = pass.target->texture->pixelSize();
            cb->beginPass(pass.target->rt.get(), Qt::transparent, {1.0f, 0}, u);
            u = nullptr;
            cb->setGraphicsPipeline(pass.box ? m_boxPipeline.get() : m_blurPipeline.get());
            cb->setViewport({0, 0, float(out.width()), float(out.height())});
            cb->setShaderResources(pass.srb.get());
            cb->setVertexInput(0, 1, &input);
            cb->draw(4);
            cb->endPass();
        }
    }
    if (u) cb->resourceUpdate(u);
    m_watcher.dirty = false;
    m_ready = true;
}

void LiquidGlassNode::render(const RenderState *state) {
    if (!m_ready) return;
    QRhiCommandBuffer *cb = commandBuffer();
    const QSize target = renderTarget()->pixelSize();
    cb->setGraphicsPipeline(m_pipeline.get());
    cb->setViewport({0, 0, float(target.width()), float(target.height())});
    if (state->scissorEnabled()) {
        const QRect clip = state->scissorRect();
        cb->setScissor({clip.x(), clip.y(), clip.width(), clip.height()});
    } else {
        cb->setScissor({0, 0, target.width(), target.height()});
    }
    cb->setShaderResources(m_srb.get());
    const QRhiCommandBuffer::VertexInput input(m_quad.get(), 0);
    cb->setVertexInput(0, 1, &input);
    cb->draw(4);
}

void LiquidGlassNode::releaseResources() {
    m_pipeline.reset();
    m_srb.reset();
    m_ubuf.reset();
    dropChain();
    m_boxPipeline.reset();
    m_blurPipeline.reset();
    m_passLayout.reset();
    m_passLayoutBuffer.reset();
    m_passRp.reset();
    m_layoutTexture.reset();
    m_sampler.reset();
    m_quad.reset();
    m_pipelineFormat.clear();
    m_srbSharp = m_srbBlur = 0;
    m_watcher.dirty = true;
}
}

LiquidGlass::LiquidGlass(QQuickItem *parent) : QQuickItem(parent) {
    setFlag(ItemHasContents);
    connect(this, &LiquidGlass::parametersChanged, this, &QQuickItem::update);
}

void LiquidGlass::setSource(QQuickItem *source) {
    if (source == m_source) return;
    m_source = source;
    emit sourceChanged();
    update();
}

void LiquidGlass::setBackdrop(QQuickItem *backdrop) {
    if (backdrop == m_backdrop) return;
    m_backdrop = backdrop;
    emit backdropChanged();
    trackBackdrop();
    update();
}

// Runs once per frame after animations advance and before sync: a transform walk, no QML, and
// an update() only when the footprint actually moved.
void LiquidGlass::trackBackdrop() {
    if (!m_backdrop || !isVisible()) return;
    const QRectF rect = mapRectToItem(m_backdrop, QRectF(0, 0, width(), height()));
    if (rect == m_backdropRect) return;
    m_backdropRect = rect;
    emit backdropRectChanged();
    update();
}

void LiquidGlass::watchWindow(QQuickWindow *window) {
    disconnect(m_frameConnection);
    if (window) m_frameConnection = connect(window, &QQuickWindow::afterAnimating, this, &LiquidGlass::trackBackdrop);
}

void LiquidGlass::geometryChange(const QRectF &newGeometry, const QRectF &oldGeometry) {
    QQuickItem::geometryChange(newGeometry, oldGeometry);
    trackBackdrop();
    update();
}

void LiquidGlass::itemChange(ItemChange change, const ItemChangeData &value) {
    if (change == ItemSceneChange) watchWindow(value.window);
    if (change == ItemVisibleHasChanged) trackBackdrop();
    if (change == ItemDevicePixelRatioHasChanged) update();
    QQuickItem::itemChange(change, value);
}

QSGNode *LiquidGlass::updatePaintNode(QSGNode *node, UpdatePaintNodeData *) {
    QSGTextureProvider *provider = m_source && m_source->isTextureProvider() ? m_source->textureProvider() : nullptr;
    if (provider != m_provider) {
        if (m_provider) disconnect(m_provider, nullptr, this, nullptr);
        // Layer creation and resizes swap the provider's texture; resync so the node picks it up.
        if (provider) connect(provider, &QSGTextureProvider::textureChanged, this, &QQuickItem::update, Qt::QueuedConnection);
        m_provider = provider;
    }
    if (!provider || !window() || width() <= 0 || height() <= 0) { delete node; return nullptr; }

    auto *n = static_cast<LiquidGlassNode *>(node);
    if (!n) n = new LiquidGlassNode(window());
    n->provider = provider;
    n->size = size();
    n->dpr = float(window()->effectiveDevicePixelRatio());
    // MEMBER properties take anything QML hands them; NaN would poison the shader, so it reads as 0.
    auto f = [](qreal v) { return std::isfinite(v) ? float(v) : 0.0f; };
    const float shadowOpacity = std::clamp(f(m_shadowOpacity), 0.0f, 1.0f);
    const float shadowSpread = std::max(1.0f, f(m_shadowSpread));
    const float shadowOffsetY = f(m_shadowOffsetY);
    n->params = {f(m_radius), f(m_bevelDepth), std::max(0.0f, f(m_blurRadius)),
                 f(m_refraction), f(m_chromaticAberration), f(m_edgeHighlight), f(m_specular),
                 f(m_fresnel), f(m_saturation), f(m_brightness), f(m_tint),
                 shadowOpacity, shadowSpread, shadowOffsetY};
    QRectF rect = m_sourceRect;
    if (m_backdrop && m_source == m_backdrop && m_backdrop->width() > 0 && m_backdrop->height() > 0) {
        // Sampling the backdrop's own layer: normalize the tracked footprint by its size.
        const qreal w = m_backdrop->width(), h = m_backdrop->height();
        rect = QRectF(m_backdropRect.x() / w, m_backdropRect.y() / h, m_backdropRect.width() / w, m_backdropRect.height() / h);
    }
    n->sourceRect = rect.isValid() && std::isfinite(rect.x()) && std::isfinite(rect.y()) ? rect : QRectF(0, 0, 1, 1);
    // One logical px covers the AA fringe; the shadow's Gaussian is negligible past two spreads.
    const qreal pad = 1 + (shadowOpacity > 0 ? 2 * shadowSpread + std::abs(shadowOffsetY) : 0);
    n->quad = QRectF(-pad, -pad, width() + 2 * pad, height() + 2 * pad);
    n->markDirty(QSGNode::DirtyMaterial);
    return n;
}

#include "liquid_glass.moc"
