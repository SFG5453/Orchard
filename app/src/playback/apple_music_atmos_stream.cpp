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

#include "apple_music_atmos.h"
#include "apple_music_requests.h"
#include "apple_music_stream.h"
#include "apple_music_stream_state.h"
#include "integrations/apple_music.h"

#include <QDebug>
#include <QNetworkReply>

void AppleMusicStreams::resolveAtmos(const QString &appleId, Resolved done) {
  if (!m_account->connected()) {
    resolve(appleId, std::move(done));
    return;
  }
  m_account->atmos(appleId, [this, appleId, done = std::move(done)](const QJsonObject &answer, const QString &error) {
    if (!error.isEmpty())
      qInfo().noquote() << "AppleMusic: no Atmos for" << appleId << "|" << error;
    if (!error.isEmpty() || !answer.value(QStringLiteral("available")).toBool()) {
      resolve(appleId, done);
      return;
    }
    loadAtmos(appleId, answer, done);
  });
}

// Any failure before the stream opens falls back to the stereo stream.
void AppleMusicStreams::loadAtmos(const QString &appleId, const QJsonObject &answer, Resolved done) {
  const QUrl masterUrl(answer.value(QStringLiteral("master")).toString());
  const QUrl licenseUrl(answer.value(QStringLiteral("licenseUrl")).toString());
  const auto stereo = [this, appleId, done](const QString &why) {
    qWarning().noquote() << "AppleMusic: Atmos unavailable for" << appleId << "|" << why;
    resolve(appleId, done);
  };
  if (masterUrl.scheme() != QStringLiteral("https") || licenseUrl.scheme() != QStringLiteral("https")) {
    stereo(QStringLiteral("incomplete answer"));
    return;
  }
  const auto media = [this, appleId, licenseUrl, stereo, done](const QUrl &url) {
    QNetworkReply *reply = m_network.get(QNetworkRequest(url));
    connect(reply, &QNetworkReply::finished, this, [this, reply, appleId, licenseUrl, stereo, done] {
      reply->deleteLater();
      auto stream = std::make_shared<Stream>();
      QString error = reply->error() == QNetworkReply::NoError ? QString() : apple_music_requests::replyError(reply);
      if (error.isEmpty())
        error = apple_music_playlist::parse(reply->url(), reply->readAll(), stream->playlist);
      if (!error.isEmpty()) {
        stereo(error);
        return;
      }
      stream->atmos = true;
      openStream(appleId, licenseUrl, stream, done);
    });
  };
  const auto pick = [stereo, media](const QUrl &url, const QByteArray &text) {
    const apple_music_atmos::Rendition rendition = apple_music_atmos::pick(url, text);
    if (rendition.url.scheme() != QStringLiteral("https"))
      stereo(QStringLiteral("no JOC rendition"));
    else
      media(rendition.url);
  };
  // The provider usually returns the master playlist it fetched beside the playback request.
  if (const QString text = answer.value(QStringLiteral("masterText")).toString(); !text.isEmpty()) {
    pick(masterUrl, text.toUtf8());
    return;
  }
  QNetworkReply *reply = m_network.get(QNetworkRequest(masterUrl));
  connect(reply, &QNetworkReply::finished, this, [reply, pick, stereo] {
    reply->deleteLater();
    if (reply->error() != QNetworkReply::NoError)
      stereo(apple_music_requests::replyError(reply));
    else
      pick(reply->url(), reply->readAll());
  });
}
