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

#include "orchard_core.h"

#include <QByteArray>
#include <QObject>
#include <QVariantMap>

// The largest variant of a YouTube Music or ytimg thumbnail URL.
QByteArray highResArtworkUrl(const QByteArray &url);

class SystemMediaBridge final : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool attached READ attached NOTIFY attachedChanged)
    Q_PROPERTY(QString lastError READ lastError NOTIFY lastErrorChanged)

public:
    explicit SystemMediaBridge(QObject *parent = nullptr);
    ~SystemMediaBridge() override;

    [[nodiscard]] bool attached() const;
    [[nodiscard]] QString lastError() const;

    Q_INVOKABLE bool start(qulonglong windowHandle = 0);
    Q_INVOKABLE bool publish(const QVariantMap &state);
    Q_INVOKABLE void stop();

signals:
    void attachedChanged();
    void lastErrorChanged();
    void commandReceived(const QString &type, const QVariant &value);

private:
    static void receiveCommand(void *context, const OrchardSystemMediaCommand *command);
    void destroyHandle();

    OrchardSystemMediaHandle *m_handle = nullptr;
    QString m_lastError;
};
