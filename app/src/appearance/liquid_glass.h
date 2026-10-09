// Adapted from LiquidGlass. Copyright (c) 2026 ybouane.
// SPDX-License-Identifier: MIT
// See third_party/liquidglass/LICENSE (also bundled at :/licenses/liquidglass/LICENSE).
#pragma once
#include <QQuickItem>
#include <QPointer>
#include <QtQml/qqmlregistration.h>

class QSGTextureProvider;

// Liquid glass panel over any texture provider: a layered backdrop shared by every panel, or a
// per-panel ShaderEffectSource. Blur, refraction and shadow run in one render node, and the blur
// chain reruns only when the source pixels or the panel's region change.
class LiquidGlass : public QQuickItem {
    Q_OBJECT
    QML_ELEMENT
    Q_PROPERTY(QQuickItem *source READ source WRITE setSource NOTIFY sourceChanged)
    // Item the panel floats over. Its footprint there is tracked every frame, so ancestor animations
    // (popup scale-in, queue slide) never leave the sampled region behind.
    Q_PROPERTY(QQuickItem *backdrop READ backdrop WRITE setBackdrop NOTIFY backdropChanged)
    Q_PROPERTY(QRectF backdropRect READ backdropRect NOTIFY backdropRectChanged)
    // Region of the source texture, normalized. Ignored when source is the backdrop itself.
    Q_PROPERTY(QRectF sourceRect MEMBER m_sourceRect NOTIFY parametersChanged)
    Q_PROPERTY(qreal radius MEMBER m_radius NOTIFY parametersChanged)
    Q_PROPERTY(qreal bevelDepth MEMBER m_bevelDepth NOTIFY parametersChanged)
    Q_PROPERTY(qreal blurRadius MEMBER m_blurRadius NOTIFY parametersChanged)
    Q_PROPERTY(qreal refraction MEMBER m_refraction NOTIFY parametersChanged)
    Q_PROPERTY(qreal chromaticAberration MEMBER m_chromaticAberration NOTIFY parametersChanged)
    Q_PROPERTY(qreal edgeHighlight MEMBER m_edgeHighlight NOTIFY parametersChanged)
    Q_PROPERTY(qreal specular MEMBER m_specular NOTIFY parametersChanged)
    Q_PROPERTY(qreal fresnel MEMBER m_fresnel NOTIFY parametersChanged)
    Q_PROPERTY(qreal saturation MEMBER m_saturation NOTIFY parametersChanged)
    Q_PROPERTY(qreal brightness MEMBER m_brightness NOTIFY parametersChanged)
    Q_PROPERTY(qreal tint MEMBER m_tint NOTIFY parametersChanged)
    Q_PROPERTY(qreal shadowOpacity MEMBER m_shadowOpacity NOTIFY parametersChanged)
    Q_PROPERTY(qreal shadowSpread MEMBER m_shadowSpread NOTIFY parametersChanged)
    Q_PROPERTY(qreal shadowOffsetY MEMBER m_shadowOffsetY NOTIFY parametersChanged)
public:
    explicit LiquidGlass(QQuickItem *parent = nullptr);
    QQuickItem *source() const { return m_source; }
    void setSource(QQuickItem *source);
    QQuickItem *backdrop() const { return m_backdrop; }
    void setBackdrop(QQuickItem *backdrop);
    QRectF backdropRect() const { return m_backdropRect; }
signals:
    void sourceChanged();
    void backdropChanged();
    void backdropRectChanged();
    void parametersChanged();
protected:
    QSGNode *updatePaintNode(QSGNode *node, UpdatePaintNodeData *) override;
    void geometryChange(const QRectF &newGeometry, const QRectF &oldGeometry) override;
    void itemChange(ItemChange change, const ItemChangeData &value) override;
private:
    void trackBackdrop();
    void watchWindow(QQuickWindow *window);
    QPointer<QQuickItem> m_source;
    QPointer<QQuickItem> m_backdrop;
    QRectF m_backdropRect;
    QMetaObject::Connection m_frameConnection;
    QPointer<QSGTextureProvider> m_provider;  // render thread; touched only while the GUI thread is blocked
    QRectF m_sourceRect{0, 0, 1, 1};
    qreal m_radius{20}, m_bevelDepth{16}, m_blurRadius{24};
    qreal m_refraction{0.69}, m_chromaticAberration{0.05}, m_edgeHighlight{0.05};
    qreal m_specular{0}, m_fresnel{1}, m_saturation{0}, m_brightness{0}, m_tint{0};
    qreal m_shadowOpacity{0}, m_shadowSpread{10}, m_shadowOffsetY{1};
};
