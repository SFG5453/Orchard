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

#include <QByteArray>
#include <QJsonValue>
#include <QObject>
#include <QThread>

namespace orchard::provider { struct Bundle; }

// One QuickJS runtime on its own thread. Each bundle gets a separate runtime,
// so a long YouTube job never stalls another provider's audio reads.
class ProviderRuntime final : public QObject
{
    Q_OBJECT

public:
    explicit ProviderRuntime(QObject *parent = nullptr);
    ProviderRuntime(const orchard::provider::Bundle &bundle, QObject *parent = nullptr);
    ~ProviderRuntime() override;

    quint64 invoke(const QString &method, const QJsonValue &payload);
    void cancelAll();

signals:
    void invocationSucceeded(quint64 requestId, const QJsonValue &result);
    void invocationBytes(quint64 requestId, const QByteArray &bytes);
    void invocationFailed(quint64 requestId, const QString &message);

    void invokeRequested(quint64 requestId, const QString &method, const QJsonValue &payload);
    void cancelRequested();

private:
    QThread m_thread;
    quint64 m_nextRequestId{1};
};

