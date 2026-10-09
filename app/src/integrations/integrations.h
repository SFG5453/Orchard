/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

#pragma once

#include "discord.h"
#include "lastfm.h"

#include <QObject>
#include <QVariantMap>

class OrchardAccount;
class AuthManager;
class YouTubeCatalog;

class Integrations final : public QObject
{
    Q_OBJECT
    Q_PROPERTY(Discord *discord READ discord CONSTANT)
    Q_PROPERTY(Lastfm *lastfm READ lastfm CONSTANT)

public:
    explicit Integrations(OrchardAccount *account = nullptr, YouTubeCatalog *catalog = nullptr,
                          AuthManager *auth = nullptr, QObject *parent = nullptr);

    [[nodiscard]] Discord *discord() { return &m_discord; }
    [[nodiscard]] const Discord *discord() const { return &m_discord; }
    [[nodiscard]] Lastfm *lastfm() { return &m_lastfm; }

    void updatePlayback(const QVariantMap &track, bool playing, double position,
                        double duration, bool mixing, const QString &incomingTitle);

private:
    Discord m_discord;
    Lastfm m_lastfm;
};
