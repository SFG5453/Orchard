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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

#pragma once

#include <QAbstractNativeEventFilter>
#include <QObject>
#include <QPointer>
#include <QTimer>

#include <memory>

class OcclusionWatch;
class QWindow;

// Reports whether the window is fully covered by other windows or cloaked.
// X11 uses visibility events, Windows polls z-order and macOS asks AppKit; other platforms
// never report occlusion.
class WindowOcclusion final : public QObject, public QAbstractNativeEventFilter
{
    Q_OBJECT

public:
    explicit WindowOcclusion(QObject *parent = nullptr);
    ~WindowOcclusion() override;

    void setWindow(QWindow *window);
    [[nodiscard]] bool occluded() const { return m_occluded; }

    bool nativeEventFilter(const QByteArray &eventType, void *message, qintptr *result) override;

signals:
    void occludedChanged();

private:
    void setOccluded(bool value);
    void watchX11();
    void pollWindows();

    QPointer<QWindow> m_window;
    QTimer m_timer;
#ifdef Q_OS_MACOS
    std::unique_ptr<OcclusionWatch> m_macWatch;
#endif
    bool m_occluded{false};
    bool m_filterInstalled{false};
};
