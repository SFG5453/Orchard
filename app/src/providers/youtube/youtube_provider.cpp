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


#include "youtube_provider.h"

#include "providers/runtime/provider_runtime.h"

YouTubeProvider::YouTubeProvider(QObject *parent)
    : QObject(parent), m_runtime(new ProviderRuntime(this))
{
    connect(m_runtime, &ProviderRuntime::invocationSucceeded, this, &YouTubeProvider::resultReady);
    connect(m_runtime, &ProviderRuntime::invocationFailed, this, &YouTubeProvider::requestFailed);
}

quint64 YouTubeProvider::invoke(const QString &method, const QJsonValue &payload)
{
    return m_runtime->invoke(method, payload);
}

quint64 YouTubeProvider::loadAccountProfile(const QJsonObject &session)
{
    return invoke(QStringLiteral("account.profile"), QJsonObject{{QStringLiteral("session"), session}});
}

quint64 YouTubeProvider::enrichAccountProfile(const QJsonObject &profile)
{
    return invoke(QStringLiteral("account.enrich"), profile);
}

void YouTubeProvider::cancelAll()
{
    m_runtime->cancelAll();
}
