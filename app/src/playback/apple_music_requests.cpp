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
#include "apple_music_requests.h"
#include "integrations/apple_music.h"

#include <QJsonDocument>
#include <QNetworkReply>

namespace apple_music_requests {

const QUrl kWebPlaybackUrl(QStringLiteral("https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/webPlayback"));

QNetworkRequest request(const QUrl &url, const QString &developerToken, const QString &userToken) {
  QNetworkRequest request(url);
  request.setTransferTimeout(20000);
  request.setRawHeader("Authorization", "Bearer " + developerToken.toUtf8());
  request.setRawHeader("Music-User-Token", userToken.toUtf8());
  const QByteArray origin = AppleMusic::tokenOrigin().toUtf8();
  request.setRawHeader("Origin", origin);
  request.setRawHeader("Referer", origin + '/');
  request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
  return request;
}

QByteArray webPlaybackBody(const QString &appleId) {
  return QJsonDocument(QJsonObject{{QStringLiteral("salableAdamId"), appleId}}).toJson(QJsonDocument::Compact);
}

QByteArray licenseBody(const QByteArray &challenge, const QString &keyUri, const QString &appleId) {
  const QJsonObject body{{QStringLiteral("challenge"), QString::fromLatin1(challenge.toBase64())},
                         {QStringLiteral("key-system"), QStringLiteral("com.widevine.alpha")},
                         {QStringLiteral("uri"), keyUri},
                         // Library songs license by their catalog id too.
                         {QStringLiteral("adamId"), appleId},
                         {QStringLiteral("isLibrary"), false},
                         {QStringLiteral("user-initiated"), true}};
  return QJsonDocument(body).toJson(QJsonDocument::Compact);
}

QByteArray license(QNetworkReply *reply, QString &error) {
  const QJsonObject answer = QJsonDocument::fromJson(reply->readAll()).object();
  const QByteArray license = QByteArray::fromBase64(answer.value(QStringLiteral("license")).toString().toLatin1());
  if (reply->error() != QNetworkReply::NoError)
    error = replyError(reply);
  else if (answer.value(QStringLiteral("status")).toInt(-1) != 0 || license.isEmpty())
    error = QStringLiteral("license refused (status %1)").arg(answer.value(QStringLiteral("status")).toInt());
  return error.isEmpty() ? license : QByteArray();
}

QString replyError(QNetworkReply *reply) {
  const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
  return status ? QStringLiteral("HTTP %1").arg(status) : reply->errorString();
}

} // namespace apple_music_requests
