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

#include <QByteArray>
#include <QJsonObject>
#include <QNetworkRequest>
#include <QString>
#include <QUrl>

class QNetworkReply;

// Requests to Apple's playback service, shared by songs and music videos.
namespace apple_music_requests {

// Answers a catalog id with its stream assets (songs) or master playlist (music videos).
extern const QUrl kWebPlaybackUrl;

// A JSON POST carrying the developer and listener tokens from Apple's web origin.
QNetworkRequest request(const QUrl &url, const QString &developerToken, const QString &userToken);
QByteArray webPlaybackBody(const QString &appleId);
QByteArray licenseBody(const QByteArray &challenge, const QString &keyUri, const QString &appleId);
// The license from a license reply, or empty with `error` set.
QByteArray license(QNetworkReply *reply, QString &error);
QString replyError(QNetworkReply *reply);

} // namespace apple_music_requests
