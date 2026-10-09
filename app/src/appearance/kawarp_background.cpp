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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

#include "kawarp_background.h"
#include "kawarp_artwork.h"
#include <QBuffer>
#include <QFutureWatcher>
#include <QImageReader>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QQuickWindow>
#include <QSGGeometryNode>
#include <QSGMaterial>
#include <QSGMaterialShader>
#include <QSGTexture>
#include <QtConcurrentRun>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>

namespace {
float easedBlend(float progress) { return 0.5f - 0.5f * std::cos(progress * 3.14159265358979323846f); }
class KawarpShader final : public QSGMaterialShader {
public:
    KawarpShader() {
        setShaderFileName(VertexStage, QStringLiteral(":/shaders/kawarp.vert.qsb"));
        setShaderFileName(FragmentStage, QStringLiteral(":/shaders/kawarp.frag.qsb"));
    }
    bool updateUniformData(RenderState &, QSGMaterial *, QSGMaterial *) override;
    void updateSampledImage(RenderState &, int, QSGTexture **, QSGMaterial *, QSGMaterial *) override;
};
class KawarpMaterial final : public QSGMaterial {
public:
    KawarpMaterial() { setFlag(Blending); setFlag(NoBatching); }
    QSGMaterialType *type() const override { static QSGMaterialType type; return &type; }
    QSGMaterialShader *createShader(QSGRendererInterface::RenderMode) const override { return new KawarpShader; }
    std::unique_ptr<QSGTexture> previous, next;
    quint64 revision{0};
    float time{0}, intensity{0.92f}, saturation{1.24f}, blend{1}, brightness{1};
};
bool KawarpShader::updateUniformData(RenderState &state, QSGMaterial *material, QSGMaterial *) {
    auto *m = static_cast<KawarpMaterial *>(material);
    auto *buf = state.uniformData();
    Q_ASSERT(buf->size() >= 88);
    const auto matrix = state.combinedMatrix();
    const float opacity = state.opacity();
    std::memcpy(buf->data(), matrix.constData(), 64);
    std::memcpy(buf->data() + 64, &opacity, 4);
    std::memcpy(buf->data() + 68, &m->time, 4);
    std::memcpy(buf->data() + 72, &m->intensity, 4);
    std::memcpy(buf->data() + 76, &m->saturation, 4);
    std::memcpy(buf->data() + 80, &m->blend, 4);
    std::memcpy(buf->data() + 84, &m->brightness, 4);
    return true;
}
void KawarpShader::updateSampledImage(RenderState &state, int binding, QSGTexture **texture,
                                     QSGMaterial *material, QSGMaterial *) {
    auto *m = static_cast<KawarpMaterial *>(material);
    *texture = binding == 1 ? m->previous.get() : m->next.get();
    (*texture)->commitTextureOperations(state.rhi(), state.resourceUpdateBatch());
}
}

KawarpBackground::KawarpBackground(QQuickItem *parent) : QQuickItem(parent) {
    setFlag(ItemHasContents);
    m_timer.setInterval(16);
    m_timer.setTimerType(Qt::PreciseTimer);
    connect(&m_timer, &QTimer::timeout, this, &KawarpBackground::tick);
    connect(this, &QQuickItem::visibleChanged, this, &KawarpBackground::syncTimer);
}
void KawarpBackground::setSource(const QUrl &source) {
    if (source == m_source) return;
    m_source = source;
    const auto generation = ++m_generation;
    if (m_reply) m_reply->abort();
    emit sourceChanged();
    if (source.isEmpty()) {
        const bool wasReady = ready();
        m_previous = m_next = {};
        ++m_revision;
        if (wasReady) emit readyChanged();
        syncTimer();
        update();
        return;
    }
    if (source.isLocalFile() || source.scheme() == QStringLiteral("qrc")) {
        prepare({}, source.isLocalFile() ? source.toLocalFile() : QStringLiteral(":") + source.path(), generation);
        return;
    }
    if (source.scheme() != QStringLiteral("https") && source.scheme() != QStringLiteral("http")) return;
    QNetworkRequest request(source);
    request.setAttribute(QNetworkRequest::Http2AllowedAttribute, false);
    request.setTransferTimeout(15000);
    auto *reply = m_network.get(request);
    m_reply = reply;
    connect(reply, &QNetworkReply::finished, this, [this, reply, generation] {
        const bool ok = reply->error() == QNetworkReply::NoError;
        const QByteArray data = ok ? reply->readAll() : QByteArray{};
        reply->deleteLater();
        if (generation != m_generation) return;
        if (ok) prepare(data, {}, generation);
        else qWarning() << "Unable to load immersive artwork:" << reply->errorString();
    });
}
void KawarpBackground::prepare(QByteArray data, QString path, quint64 generation) {
    auto *watcher = new QFutureWatcher<QImage>(this);
    connect(watcher, &QFutureWatcher<QImage>::finished, this, [this, watcher, generation] {
        const QImage image = watcher->result();
        watcher->deleteLater();
        if (generation != m_generation) return;
        if (image.isNull()) {
            qWarning() << "Unable to decode immersive artwork";
            return;
        }
        const bool wasReady = ready();
        m_previous = wasReady ? KawarpArtwork::crossfade(m_previous, m_next, easedBlend(m_blend)) : image;
        m_next = image;
        m_blend = wasReady && m_running && isVisible() ? 0 : 1;
        ++m_revision;
        if (!wasReady) emit readyChanged();
        syncTimer();
        update();
    });
    watcher->setFuture(QtConcurrent::run([data = std::move(data), path = std::move(path)] {
        QBuffer buffer;
        buffer.setData(data);
        buffer.open(QIODevice::ReadOnly);
        QImageReader reader;
        if (path.isEmpty()) reader.setDevice(&buffer);
        else reader.setFileName(path);
        reader.setAutoTransform(true);
        const QSize size = reader.size();
        if (size.isValid()) reader.setScaledSize(size.scaled(512, 512, Qt::KeepAspectRatio));
        return KawarpArtwork::prepare(reader.read());
    }));
}
void KawarpBackground::setRunning(bool value) {
    if (value == m_running) return;
    m_running = value;
    if (!value) m_blend = 1;
    emit runningChanged();
    syncTimer();
    update();
}
void KawarpBackground::setSpeed(double value) {
    if (!std::isfinite(value)) return;
    value = std::clamp(value, 0.0, 5.0);
    if (value == m_speed) return;
    m_speed = value;
    // Zero freezes immediately; other speed changes ease without resetting phase.
    if (value == 0) m_currentSpeed = 0;
    emit parametersChanged();
    syncTimer();
    update();
}
void KawarpBackground::setIntensity(double value) {
    if (!std::isfinite(value)) return;
    value = std::clamp(value, 0.0, 1.0);
    if (value == m_intensity) return;
    m_intensity = value;
    emit parametersChanged();
    update();
}
void KawarpBackground::setSaturation(double value) {
    if (!std::isfinite(value)) return;
    value = std::clamp(value, 0.0, 3.0);
    if (value == m_saturation) return;
    m_saturation = value;
    emit parametersChanged();
    update();
}
void KawarpBackground::setBrightness(double value) {
    if (!std::isfinite(value)) return;
    value = std::clamp(value, 0.0, 2.0);
    if (value == m_brightness) return;
    m_brightness = value;
    emit parametersChanged();
    update();
}
void KawarpBackground::syncTimer() {
    const bool animate = m_running && isVisible() && ready() && (m_speed > 0 || m_blend < 1);
    if (animate && !m_timer.isActive()) { m_clock.start(); m_timer.start(); }
    else if (!animate) m_timer.stop();
}
void KawarpBackground::tick() {
    const double dt = std::min(m_clock.restart() / 1000.0, 0.1);
    m_currentSpeed += (m_speed - m_currentSpeed) * (1 - std::exp(-dt * 3));
    m_time += dt * m_currentSpeed;
    m_blend = std::min(1.0f, m_blend + float(dt / 1.2));
    update();
    syncTimer();
}
void KawarpBackground::geometryChange(const QRectF &newGeometry, const QRectF &oldGeometry) {
    QQuickItem::geometryChange(newGeometry, oldGeometry);
    update();
}
QSGNode *KawarpBackground::updatePaintNode(QSGNode *node, UpdatePaintNodeData *) {
    if (!ready() || !window() || width() <= 0 || height() <= 0) { delete node; return nullptr; }
    auto *n = static_cast<QSGGeometryNode *>(node);
    if (!n) {
        n = new QSGGeometryNode;
        auto *geometry = new QSGGeometry(QSGGeometry::defaultAttributes_TexturedPoint2D(), 4);
        geometry->setDrawingMode(QSGGeometry::DrawTriangleStrip);
        n->setGeometry(geometry);
        n->setFlag(QSGNode::OwnsGeometry);
        n->setMaterial(new KawarpMaterial);
        n->setFlag(QSGNode::OwnsMaterial);
    }
    QSGGeometry::updateTexturedRectGeometry(n->geometry(), boundingRect(), QRectF(0, 0, 1, 1));
    n->markDirty(QSGNode::DirtyGeometry);
    auto *m = static_cast<KawarpMaterial *>(n->material());
    if (m->revision != m_revision) {
        m->previous.reset(window()->createTextureFromImage(m_previous, QQuickWindow::TextureIsOpaque));
        m->next.reset(window()->createTextureFromImage(m_next, QQuickWindow::TextureIsOpaque));
        if (!m->previous || !m->next) { delete n; return nullptr; }
        for (auto *texture : {m->previous.get(), m->next.get()}) {
            texture->setFiltering(QSGTexture::Linear);
            texture->setHorizontalWrapMode(QSGTexture::ClampToEdge);
            texture->setVerticalWrapMode(QSGTexture::ClampToEdge);
        }
        m->revision = m_revision;
    }
    m->time = float(m_time);
    m->intensity = float(m_intensity);
    m->saturation = float(m_saturation);
    m->brightness = float(m_brightness);
    m->blend = easedBlend(m_blend);
    n->markDirty(QSGNode::DirtyMaterial);
    return n;
}
