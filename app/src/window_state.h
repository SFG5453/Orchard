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

class QWindow;

// Remembers the main window size and maximized state between launches.
class WindowState final : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool enabled READ enabled WRITE setEnabled NOTIFY enabledChanged)
    Q_PROPERTY(int initialWidth READ initialWidth CONSTANT)
    Q_PROPERTY(int initialHeight READ initialHeight CONSTANT)
    Q_PROPERTY(bool initialMaximized READ initialMaximized CONSTANT)

public:
    explicit WindowState(QObject *parent = nullptr);

    [[nodiscard]] bool enabled() const { return m_enabled; }
    void setEnabled(bool value);
    [[nodiscard]] int initialWidth() const { return m_width; }
    [[nodiscard]] int initialHeight() const { return m_height; }
    [[nodiscard]] bool initialMaximized() const { return m_maximized; }
    void setWindow(QWindow *window);

signals:
    void enabledChanged();

private:
    void save();

    QSettings m_settings;
    QPointer<QWindow> m_window;
    QTimer m_saveTimer;
    bool m_enabled{true};
    int m_width{1360};
    int m_height{820};
    bool m_maximized{false};
};
