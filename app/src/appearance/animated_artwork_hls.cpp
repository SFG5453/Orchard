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

#include "animated_artwork_service.h"

#include <QNetworkReply>
#include <QNetworkRequest>
#include <QRegularExpression>
#include <QUrl>

// Apple serves direct MP4 video files under the exact same path prefix if you
// swap .m3u8 with -.mp4. Thank you, Cupertino CDN engineers, for making our
// lives easier.
QString AnimatedArtworkService::directMp4FromHlsUrl(const QString &url) {
  static const QRegularExpression regex(
      QStringLiteral("(-?)\\.m3u8(\\?.*)?$"),
      QRegularExpression::CaseInsensitiveOption);
  QString copy = url;
  return copy.replace(regex, QStringLiteral("\\1-.mp4\\2"))
      .replace(QStringLiteral("--.mp4"), QStringLiteral("-.mp4"));
}

// Because video codecs in HLS playlists are like a box of chocolates: you never
// know if you're getting H.264 or an unplayable HEVC profile.
void AnimatedArtworkService::resolveHlsVariant(
    const QString &manifestUrl, std::shared_ptr<LookupState> state,
    std::function<void(const QString &)> onResolved) {
  Q_UNUSED(state);
  const QUrl url(manifestUrl);
  QNetworkRequest request(url);
  request.setHeader(QNetworkRequest::UserAgentHeader,
                    QStringLiteral("Orchard/3.0"));
  request.setAttribute(QNetworkRequest::Http2AllowedAttribute, false);

  QNetworkReply *reply = m_network.get(request);
  connect(reply, &QNetworkReply::finished, this,
          [this, reply, manifestUrl, onResolved]() {
            reply->deleteLater();
            if (reply->error() != QNetworkReply::NoError) {
              // Fall back to direct conversion of the manifest URL itself
              onResolved(directMp4FromHlsUrl(manifestUrl));
              return;
            }

            const QString manifestText = QString::fromUtf8(reply->readAll());
            const QStringList lines =
                manifestText.split(QRegularExpression(QStringLiteral("\r?\n")));

            QString chosenVariantUrl;
            int chosenWidth = 0;
            bool pendingVariant = false;
            QString pendingCodecs;
            int pendingResWidth = 0;

            for (const QString &rawLine : lines) {
              const QString line = rawLine.trimmed();
              if (line.isEmpty())
                continue;

              if (line.startsWith(QStringLiteral("#EXT-X-STREAM-INF:"))) {
                pendingVariant = true;
                pendingCodecs.clear();
                pendingResWidth = 0;

                static const QRegularExpression codecsRegex(
                    QStringLiteral("CODECS=\"([^\"]+)\""));
                const auto codecsMatch = codecsRegex.match(line);
                if (codecsMatch.hasMatch()) {
                  pendingCodecs = codecsMatch.captured(1);
                }

                static const QRegularExpression resRegex(
                    QStringLiteral("RESOLUTION=(\\d+)x(\\d+)"));
                const auto resMatch = resRegex.match(line);
                if (resMatch.hasMatch()) {
                  pendingResWidth = resMatch.captured(1).toInt();
                }
                continue;
              }

              if (line.startsWith(QLatin1Char('#')))
                continue;

              if (pendingVariant) {
                pendingVariant = false;
                const bool isAvc = pendingCodecs.contains(
                    QStringLiteral("avc1"), Qt::CaseInsensitive);
                if (isAvc && pendingResWidth <= 1080) {
                  if (pendingResWidth >= chosenWidth) {
                    chosenWidth = pendingResWidth;
                    QUrl base(manifestUrl);
                    chosenVariantUrl = base.resolved(QUrl(line)).toString();
                  }
                }
              }
            }

            if (!chosenVariantUrl.isEmpty()) {
              onResolved(directMp4FromHlsUrl(chosenVariantUrl));
            } else {
              onResolved(directMp4FromHlsUrl(manifestUrl));
            }
          });
}

QString AnimatedArtworkService::masterManifestUrl(const QString &mp4Url) {
  static const QRegularExpression regex(
      QStringLiteral("^(.*/P\\d+)_[^/]*\\.mp4(?:\\?.*)?$"));
  const auto match = regex.match(mp4Url);
  return match.hasMatch() ? match.captured(1) + QStringLiteral("_default.m3u8")
                          : QString();
}

QString AnimatedArtworkService::smallVariantMp4(const QString &manifestText,
                                                const QString &manifestUrl) {
  static const QRegularExpression resRegex(
      QStringLiteral("RESOLUTION=(\\d+)x\\d+"));
  static const QRegularExpression bandwidthRegex(
      QStringLiteral("AVERAGE-BANDWIDTH=(\\d+)"));
  QString best;
  int bestWidth = 0;
  qint64 bestBandwidth = 0;
  bool pending = false;
  int width = 0;
  qint64 bandwidth = 0;
  for (const QString &raw : manifestText.split(QLatin1Char('\n'))) {
    const QString line = raw.trimmed();
    if (line.startsWith(QStringLiteral("#EXT-X-STREAM-INF:"))) {
      const auto res = resRegex.match(line);
      const auto bw = bandwidthRegex.match(line);
      pending = line.contains(QStringLiteral("avc1"), Qt::CaseInsensitive) && res.hasMatch();
      width = pending ? res.captured(1).toInt() : 0;
      bandwidth = bw.hasMatch() ? bw.captured(1).toLongLong() : 0;
      continue;
    }
    if (!pending || line.isEmpty() || line.startsWith(QLatin1Char('#')))
      continue;
    pending = false;
    if (width < 480)
      continue;
    const bool smaller = best.isEmpty() || width < bestWidth ||
                         (width == bestWidth && bandwidth < bestBandwidth);
    if (smaller) {
      best = QUrl(manifestUrl).resolved(QUrl(line)).toString();
      bestWidth = width;
      bestBandwidth = bandwidth;
    }
  }
  return best.isEmpty() ? QString() : directMp4FromHlsUrl(best);
}

void AnimatedArtworkService::resolveSmallVariant(
    const QString &fullUrl, std::function<void(const QString &)> done) {
  const QString manifest = masterManifestUrl(fullUrl);
  if (fullUrl.isEmpty() || manifest.isEmpty()) {
    done(fullUrl);
    return;
  }
  if (const auto hit = m_smallVariants.constFind(fullUrl);
      hit != m_smallVariants.constEnd()) {
    done(*hit);
    return;
  }

  QNetworkRequest request{QUrl(manifest)};
  request.setHeader(QNetworkRequest::UserAgentHeader,
                    QStringLiteral("Orchard/3.0"));
  QNetworkReply *reply = m_network.get(request);
  connect(reply, &QNetworkReply::finished, this,
          [this, reply, fullUrl, manifest, done = std::move(done)]() {
            reply->deleteLater();
            QString small;
            if (reply->error() == QNetworkReply::NoError)
              small = smallVariantMp4(QString::fromUtf8(reply->readAll()), manifest);
            // A failed manifest is not cached, so the next hover retries it.
            if (small.isEmpty()) {
              done(fullUrl);
              return;
            }
            m_smallVariants.insert(fullUrl, small);
            done(small);
          });
}
