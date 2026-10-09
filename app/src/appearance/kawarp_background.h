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

#pragma once
#include <QQuickItem>
#include <QtQml/qqmlregistration.h>
#include <QImage>
#include <QElapsedTimer>
#include <QTimer>
#include <QNetworkAccessManager>
#include <QPointer>

class QNetworkReply;
class KawarpBackground : public QQuickItem {
    Q_OBJECT
    QML_ELEMENT
    Q_PROPERTY(QUrl source READ source WRITE setSource NOTIFY sourceChanged)
    Q_PROPERTY(bool running READ running WRITE setRunning NOTIFY runningChanged)
    Q_PROPERTY(bool ready READ ready NOTIFY readyChanged)
    Q_PROPERTY(double speed READ speed WRITE setSpeed NOTIFY parametersChanged)
    Q_PROPERTY(double intensity READ intensity WRITE setIntensity NOTIFY parametersChanged)
    Q_PROPERTY(double saturation READ saturation WRITE setSaturation NOTIFY parametersChanged)
    Q_PROPERTY(double brightness READ brightness WRITE setBrightness NOTIFY parametersChanged)
public:
    explicit KawarpBackground(QQuickItem *parent = nullptr);
    QUrl source() const { return m_source; }
    bool running() const { return m_running; }
    bool ready() const { return !m_next.isNull(); }
    double speed() const { return m_speed; }
    double intensity() const { return m_intensity; }
    double saturation() const { return m_saturation; }
    double brightness() const { return m_brightness; }
    void setSource(const QUrl &source);
    void setRunning(bool value);
    void setSpeed(double value);
    void setIntensity(double value);
    void setSaturation(double value);
    void setBrightness(double value);
signals:
    void sourceChanged();
    void runningChanged();
    void readyChanged();
    void parametersChanged();
protected:
    QSGNode *updatePaintNode(QSGNode *node, UpdatePaintNodeData *) override;
    void geometryChange(const QRectF &newGeometry, const QRectF &oldGeometry) override;
private:
    void prepare(QByteArray data, QString path, quint64 generation);
    void syncTimer();
    void tick();
    QNetworkAccessManager m_network;
    QPointer<QNetworkReply> m_reply;
    QUrl m_source;
    QImage m_previous, m_next;
    QTimer m_timer;
    QElapsedTimer m_clock;
    quint64 m_generation{0}, m_revision{0};
    double m_time{0}, m_speed{1.38}, m_currentSpeed{1.38};
    double m_intensity{0.92}, m_saturation{1.24}, m_brightness{1.0};
    float m_blend{1};
    bool m_running{false};
};
