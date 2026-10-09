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

#include <QObject>
#include <QString>
#include <QTimer>

// The app's side of the bootstrapper IPC. Reads install.json and
// update-state.json from the install root, and runs the bootstrapper
// (ORCHARD_BOOTSTRAPPER) for every action. Inert in development builds,
// which are not started by a bootstrapper.
class BootstrapperClient final : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool available READ available CONSTANT)
    Q_PROPERTY(QString state READ state NOTIFY changed)
    Q_PROPERTY(QString currentVersion READ currentVersion NOTIFY changed)
    Q_PROPERTY(QString latestVersion READ latestVersion NOTIFY changed)
    Q_PROPERTY(QString channel READ channel NOTIFY changed)
    Q_PROPERTY(QString error READ error NOTIFY changed)
    Q_PROPERTY(qint64 downloaded READ downloaded NOTIFY changed)
    Q_PROPERTY(qint64 total READ total NOTIFY changed)
    Q_PROPERTY(bool autoUpdate READ autoUpdate NOTIFY changed)

public:
    explicit BootstrapperClient(QObject *parent = nullptr);

    [[nodiscard]] bool available() const { return !m_bootstrapper.isEmpty(); }
    // idle | checking | up-to-date | downloading | staging | ready | error | cancelled
    [[nodiscard]] QString state() const { return m_state; }
    [[nodiscard]] QString currentVersion() const { return m_current; }
    [[nodiscard]] QString latestVersion() const { return m_latest; }
    [[nodiscard]] QString channel() const { return m_channel; }
    [[nodiscard]] QString error() const { return m_error; }
    [[nodiscard]] qint64 downloaded() const { return m_downloaded; }
    [[nodiscard]] qint64 total() const { return m_total; }
    [[nodiscard]] bool autoUpdate() const { return m_autoUpdate; }

    // Check, download and stage in a detached bootstrapper; survives quitting.
    Q_INVOKABLE void checkForUpdates();
    Q_INVOKABLE void cancelDownload();
    // Quit; the bootstrapper waits for us, activates the staged release and relaunches.
    Q_INVOKABLE void restartToUpdate();
    Q_INVOKABLE void setChannel(const QString &channel);
    Q_INVOKABLE void setAutoUpdate(bool enabled);

signals:
    void changed();

private:
    void refresh();
    void run(const QStringList &arguments, bool detached);

    QString m_bootstrapper;
    QString m_root;
    QTimer m_poll;
    QString m_state{QStringLiteral("idle")};
    QString m_current;
    QString m_latest;
    QString m_channel;
    QString m_error;
    qint64 m_downloaded{0};
    qint64 m_total{0};
    bool m_autoUpdate{true};
};
