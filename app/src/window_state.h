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

#include <QObject>
#include <QPointer>
#include <QSettings>
#include <QTimer>

#include "window_occlusion.h"

class QWindow;
class QScreen;

// Remembers the main window size and maximized state between launches.
class WindowState final : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool enabled READ enabled WRITE setEnabled NOTIFY enabledChanged)
    Q_PROPERTY(bool showWindowButtons READ showWindowButtons WRITE setShowWindowButtons NOTIFY showWindowButtonsChanged)
    Q_PROPERTY(int initialWidth READ initialWidth CONSTANT)
    Q_PROPERTY(int initialHeight READ initialHeight CONSTANT)
    Q_PROPERTY(bool initialMaximized READ initialMaximized CONSTANT)
    Q_PROPERTY(int availableWidth READ availableWidth NOTIFY availableGeometryChanged)
    Q_PROPERTY(int availableHeight READ availableHeight NOTIFY availableGeometryChanged)
    Q_PROPERTY(bool idle READ idle NOTIFY idleChanged)
    Q_PROPERTY(bool unfocused READ unfocused NOTIFY unfocusedChanged)
    Q_PROPERTY(bool backgroundIdle READ backgroundIdle NOTIFY backgroundIdleChanged)
    Q_PROPERTY(bool artworkIdle READ artworkIdle NOTIFY artworkIdleChanged)

public:
    explicit WindowState(QObject *parent = nullptr);

    [[nodiscard]] bool enabled() const { return m_enabled; }
    void setEnabled(bool value);
    [[nodiscard]] bool showWindowButtons() const { return m_showWindowButtons; }
    void setShowWindowButtons(bool value);
    [[nodiscard]] int initialWidth() const { return m_width; }
    [[nodiscard]] int initialHeight() const { return m_height; }
    [[nodiscard]] bool initialMaximized() const { return m_maximized; }
    [[nodiscard]] int availableWidth() const;
    [[nodiscard]] int availableHeight() const;
    void setWindow(QWindow *window);
    // True when the window is hidden, minimized, or reported as covered by the platform.
    [[nodiscard]] bool idle() const { return m_idle; }
    // True once the app has stayed unfocused past a short grace.
    [[nodiscard]] bool unfocused() const { return m_unfocused; }
    // Idle, or unfocused outside fullscreen. Drives the immersive background frame rate.
    [[nodiscard]] bool backgroundIdle() const { return m_backgroundIdle; }
    // When looping artwork should pause: backgroundIdle, or only minimized/hidden when the
    // listener keeps artwork running in the background.
    [[nodiscard]] bool artworkIdle() const { return m_artworkIdle; }
    void setPauseArtworkInBackground(bool value);

signals:
    void availableGeometryChanged();
    void enabledChanged();
    void showWindowButtonsChanged();
    void idleChanged();
    void unfocusedChanged();
    void backgroundIdleChanged();
    void artworkIdleChanged();

protected:
    bool eventFilter(QObject *watched, QEvent *event) override;

private:
    void watchScreen(QScreen *screen);
    void save();
    void updateState();
    void setIdle(bool value);
    void setUnfocused(bool value);
    void syncBackgroundIdle();
    void syncArtworkIdle();

    QSettings m_settings;
    QPointer<QWindow> m_window;
    QPointer<QScreen> m_screen;
    QMetaObject::Connection m_screenGeometryConnection;
    QTimer m_saveTimer;
    QTimer m_unfocusedTimer;
    WindowOcclusion m_occlusion;
    bool m_idle{false};
    bool m_unfocused{false};
    bool m_backgroundIdle{false};
    // Minimized, hidden or unexposed; ignores focus and the occlusion heuristic.
    bool m_hidden{false};
    bool m_pauseArtworkInBackground{true};
    bool m_artworkIdle{false};
    bool m_enabled{true};
    bool m_showWindowButtons{true};
    int m_width{1360};
    int m_height{820};
    bool m_maximized{false};
};
