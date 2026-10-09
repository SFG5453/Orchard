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

#include <QJsonObject>
#include <QObject>

class ProviderRuntime;

class YouTubeProvider final : public QObject
{
    Q_OBJECT

public:
    explicit YouTubeProvider(QObject *parent = nullptr);

    quint64 invoke(const QString &method, const QJsonValue &payload);
    quint64 loadAccountProfile(const QJsonObject &session);
    quint64 enrichAccountProfile(const QJsonObject &profile);
    void cancelAll();

signals:
    void resultReady(quint64 requestId, const QJsonValue &result);
    void requestFailed(quint64 requestId, const QString &message);

private:
    ProviderRuntime *m_runtime;
};

