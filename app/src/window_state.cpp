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

#include "window_state.h"

#include <QEvent>
#include <QGuiApplication>
#include <QScreen>
#include <QWindow>

namespace {
constexpr int kDefaultWidth = 1360;
constexpr int kDefaultHeight = 820;
// Keeps the background animating while alt-tab previews the window.
constexpr int kUnfocusedGraceMs = 2500;
}

WindowState::WindowState(QObject *parent) : QObject(parent)
{
    watchScreen(qGuiApp->primaryScreen());
    m_enabled = m_settings.value(QStringLiteral("window/remember"), true).toBool();
    m_showWindowButtons = m_settings.value(QStringLiteral("window/showButtons"), true).toBool();
    if (m_enabled) {
        m_width = qMax(m_settings.value(QStringLiteral("window/width"), kDefaultWidth).toInt(), 1);
        m_height = qMax(m_settings.value(QStringLiteral("window/height"), kDefaultHeight).toInt(), 1);
        m_maximized = m_settings.value(QStringLiteral("window/maximized"), false).toBool();
    }
    // Coalesce drag-resize bursts into one write.
    m_saveTimer.setSingleShot(true);
    m_saveTimer.setInterval(400);
    connect(&m_saveTimer, &QTimer::timeout, this, &WindowState::save);

    m_unfocusedTimer.setSingleShot(true);
    m_unfocusedTimer.setInterval(kUnfocusedGraceMs);
    connect(&m_unfocusedTimer, &QTimer::timeout, this, [this] { setUnfocused(true); });
    connect(qGuiApp, &QGuiApplication::applicationStateChanged, this, &WindowState::updateState);
    connect(&m_occlusion, &WindowOcclusion::occludedChanged, this, &WindowState::updateState);
}

void WindowState::setEnabled(bool value)
{
    if (value == m_enabled)
        return;
    m_enabled = value;
    m_settings.setValue(QStringLiteral("window/remember"), value);
    if (value)
        save();
    emit enabledChanged();
}

void WindowState::setShowWindowButtons(bool value)
{
    if (value == m_showWindowButtons)
        return;
    m_showWindowButtons = value;
    m_settings.setValue(QStringLiteral("window/showButtons"), value);
    emit showWindowButtonsChanged();
}

void WindowState::setWindow(QWindow *window)
{
    m_window = window;
    if (!window)
        return;
    watchScreen(window->screen());
    connect(window, &QWindow::screenChanged, this, &WindowState::watchScreen);
    const auto schedule = [this] { m_saveTimer.start(); };
    connect(window, &QWindow::widthChanged, this, schedule);
    connect(window, &QWindow::heightChanged, this, schedule);
    connect(window, &QWindow::visibilityChanged, this, schedule);
    connect(window, &QWindow::visibilityChanged, this, &WindowState::updateState);
    connect(window, &QWindow::visibleChanged, this, &WindowState::updateState);
    window->installEventFilter(this);
    m_occlusion.setWindow(window);
    updateState();
}

int WindowState::availableWidth() const
{
    return m_screen ? qMax(1, m_screen->availableGeometry().width() - 32) : kDefaultWidth;
}

int WindowState::availableHeight() const
{
    return m_screen ? qMax(1, m_screen->availableGeometry().height() - 48) : kDefaultHeight;
}

void WindowState::watchScreen(QScreen *screen)
{
    disconnect(m_screenGeometryConnection);
    m_screen = screen;
    if (screen)
        m_screenGeometryConnection = connect(screen, &QScreen::availableGeometryChanged,
                                            this, &WindowState::availableGeometryChanged);
    emit availableGeometryChanged();
}

bool WindowState::eventFilter(QObject *watched, QEvent *event)
{
    // Platforms that report occlusion deliver it as an expose change.
    if (watched == m_window && event->type() == QEvent::Expose)
        updateState();
    return QObject::eventFilter(watched, event);
}

void WindowState::updateState()
{
    if (!m_window)
        return;
    const bool minimized = m_window->visibility() == QWindow::Minimized
        || m_window->visibility() == QWindow::Hidden;
    m_hidden = minimized || !m_window->isVisible() || !m_window->isExposed();
    setIdle(m_hidden || m_occlusion.occluded());

    if (qGuiApp->applicationState() == Qt::ApplicationActive) {
        m_unfocusedTimer.stop();
        setUnfocused(false);
    } else if (!m_unfocused && !m_unfocusedTimer.isActive()) {
        m_unfocusedTimer.start();
    }
    syncBackgroundIdle();
}

void WindowState::setIdle(bool value)
{
    if (value == m_idle)
        return;
    m_idle = value;
    emit idleChanged();
    syncBackgroundIdle();
}

void WindowState::setUnfocused(bool value)
{
    if (value == m_unfocused)
        return;
    m_unfocused = value;
    emit unfocusedChanged();
    syncBackgroundIdle();
}

void WindowState::syncBackgroundIdle()
{
    const bool fullscreen = m_window && m_window->visibility() == QWindow::FullScreen;
    const bool value = m_idle || (m_unfocused && !fullscreen);
    if (value != m_backgroundIdle) {
        m_backgroundIdle = value;
        emit backgroundIdleChanged();
    }
    syncArtworkIdle();
}

void WindowState::setPauseArtworkInBackground(bool value)
{
    m_pauseArtworkInBackground = value;
    syncArtworkIdle();
}

void WindowState::syncArtworkIdle()
{
    const bool value = m_pauseArtworkInBackground ? m_backgroundIdle : m_hidden;
    if (value == m_artworkIdle)
        return;
    m_artworkIdle = value;
    emit artworkIdleChanged();
}

void WindowState::save()
{
    if (!m_enabled || !m_window)
        return;
    const bool maximized = m_window->visibility() == QWindow::Maximized;
    m_settings.setValue(QStringLiteral("window/maximized"), maximized);
    // Keep the last normal size while maximized or fullscreen so restore returns to it.
    if (m_window->visibility() == QWindow::Windowed) {
        m_settings.setValue(QStringLiteral("window/width"), m_window->width());
        m_settings.setValue(QStringLiteral("window/height"), m_window->height());
    }
}
