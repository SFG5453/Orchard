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

// Holds off the screensaver and display sleep while active.
class ScreenWakeLock final : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool active READ active WRITE setActive NOTIFY activeChanged)

public:
    explicit ScreenWakeLock(QObject *parent = nullptr);
    ~ScreenWakeLock() override;

    bool active() const { return m_active; }
    void setActive(bool active);

signals:
    void activeChanged();

private:
    void acquire();
    void release();
#if defined(Q_OS_LINUX)
    void inhibit(bool gnome);
#endif

    bool m_active{false};
    // Bumped on every change so late D-Bus replies for a dropped request release themselves.
    quint64 m_generation{0};
    // D-Bus inhibit cookie on Linux, power assertion id on macOS.
    quint32 m_cookie{0};
    bool m_gnome{false};
};
