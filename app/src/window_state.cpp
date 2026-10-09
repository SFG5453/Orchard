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

#include <QWindow>

namespace {
constexpr int kDefaultWidth = 1360;
constexpr int kDefaultHeight = 820;
}

WindowState::WindowState(QObject *parent) : QObject(parent)
{
    m_enabled = m_settings.value(QStringLiteral("window/remember"), true).toBool();
    if (m_enabled) {
        m_width = qMax(m_settings.value(QStringLiteral("window/width"), kDefaultWidth).toInt(), 1);
        m_height = qMax(m_settings.value(QStringLiteral("window/height"), kDefaultHeight).toInt(), 1);
        m_maximized = m_settings.value(QStringLiteral("window/maximized"), false).toBool();
    }
    // Coalesce drag-resize bursts into one write.
    m_saveTimer.setSingleShot(true);
    m_saveTimer.setInterval(400);
    connect(&m_saveTimer, &QTimer::timeout, this, &WindowState::save);
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

void WindowState::setWindow(QWindow *window)
{
    m_window = window;
    if (!window)
        return;
    const auto schedule = [this] { m_saveTimer.start(); };
    connect(window, &QWindow::widthChanged, this, schedule);
    connect(window, &QWindow::heightChanged, this, schedule);
    connect(window, &QWindow::visibilityChanged, this, schedule);
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
