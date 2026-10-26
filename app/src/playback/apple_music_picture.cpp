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
#include "apple_music_picture.h"
#include "apple_music_requests.h"
#include "integrations/apple_music.h"
#include "playback_controller.h"

#include <QDebug>
#include <QJsonArray>
#include <QJsonDocument>
#include <QNetworkReply>
#include <QVideoFrame>
#include <QVideoSink>

#include <algorithm>
#include <cmath>
#include <cstring>

using namespace widevine_protocol;
using apple_music_requests::replyError;

namespace {
bool timing() {
  static const bool on = qEnvironmentVariableIsSet("ORCHARD_PLAYBACK_TIMING");
  return on;
}

// Decoding runs this far ahead of the playhead; a 486p segment of about 6 s holds ~90 MB of frames.
constexpr double kAheadSeconds = 3.0;
// Frames are picked against the steady clock this often; the scene graph shows them on the next vsync.
constexpr int kTickMs = 4;
// The engine's position steers the clock this often, and replaces it past kJumpSeconds (a seek).
constexpr qint64 kSyncNs = 250'000'000;
constexpr double kJumpSeconds = 0.2;
// Share of the measured error taken per sync; averages out the engine's stepped position.
constexpr double kSlew = 0.15;
// Windows reports HDCP to the CDM, so Apple's taller renditions may play there.
#ifdef Q_OS_WIN
constexpr bool kTryHardware = true;
#else
constexpr bool kTryHardware = false;
#endif
} // namespace

AppleMusicPicture::AppleMusicPicture(AppleMusic *account, PlaybackController *playback, QObject *parent)
    : QObject(parent), m_account(account), m_playback(playback) {
  m_ticker.setInterval(kTickMs);
  m_ticker.setTimerType(Qt::PreciseTimer);
  connect(&m_ticker, &QTimer::timeout, this, &AppleMusicPicture::tick);
  m_wall.start();
  connect(&m_cdm, &WidevineClient::picture, this, &AppleMusicPicture::receive);
}

void AppleMusicPicture::setSink(QVideoSink *sink) { m_sink = sink; }

void AppleMusicPicture::start(const QString &appleId, int maxHeight) {
  stop();
  m_appleId = appleId;
  m_maxHeight = maxHeight;
  m_hardware = kTryHardware && !m_hdcpRefused;
  const quint64 session = m_session;
  if (!m_account || !m_account->connected()) {
    fail(session, {}, tr("Connect Apple Music under Settings, Integrations to watch this video."));
    return;
  }
  m_account->playbackToken([this, session](const QString &token, const QString &error) {
    if (session != m_session)
      return;
    if (token.isEmpty())
      fail(session, {}, error.isEmpty() ? tr("Apple Music could not start this video.") : error);
    else
      requestPlayback(session, token);
  });
}

void AppleMusicPicture::stop() {
  ++m_session;
  ++m_epoch;
  m_ticker.stop();
  if (m_ready)
    m_cdm.close(m_stream);
  m_ready = false;
  m_variantHardware = false;
  m_shown = false;
  m_decoding = false;
  m_drained = false;
  m_frames.clear();
  m_origin = -1.0;
  m_bufferedEnd = 0.0;
  m_shownTime = -1.0;
  m_playlist = {};
}

void AppleMusicPicture::requestPlayback(quint64 session, const QString &token) {
  QNetworkReply *reply = m_network.post(
      apple_music_requests::request(apple_music_requests::kWebPlaybackUrl, token, m_account->userToken()),
      apple_music_requests::webPlaybackBody(m_appleId));
  connect(reply, &QNetworkReply::finished, this, [this, reply, session, token] {
    reply->deleteLater();
    if (session != m_session)
      return;
    const QJsonObject song =
        QJsonDocument::fromJson(reply->readAll()).object().value(QStringLiteral("songList")).toArray().first().toObject();
    const QUrl master(song.value(QStringLiteral("hls-playlist-url")).toString());
    const QUrl licenseUrl(song.value(QStringLiteral("hls-key-server-url")).toString());
    if (reply->error() != QNetworkReply::NoError || master.scheme() != QStringLiteral("https") ||
        licenseUrl.scheme() != QStringLiteral("https")) {
      fail(session, replyError(reply), tr("Apple Music did not offer this video for playback."));
      return;
    }
    loadMaster(session, token, master, licenseUrl);
  });
}

void AppleMusicPicture::loadMaster(quint64 session, const QString &token, const QUrl &masterUrl,
                                   const QUrl &licenseUrl) {
  QNetworkReply *reply = m_network.get(QNetworkRequest(masterUrl));
  connect(reply, &QNetworkReply::finished, this, [this, reply, session, token, licenseUrl] {
    reply->deleteLater();
    if (session != m_session)
      return;
    apple_music_playlist::Master master;
    QString error = reply->error() == QNetworkReply::NoError ? QString() : replyError(reply);
    if (error.isEmpty())
      error = apple_music_playlist::parseMaster(reply->url(), reply->readAll(), master);
    const apple_music_playlist::Variant *variant = apple_music_playlist::pickVariant(master, m_maxHeight, m_hardware);
    if (!variant) {
      fail(session, error.isEmpty() ? QStringLiteral("no software rendition") : error);
      return;
    }
    QList<int> heights;
    for (const auto &entry : std::as_const(master.variants))
      if ((entry.software || m_hardware) && !heights.contains(entry.height))
        heights.append(entry.height);
    std::sort(heights.begin(), heights.end(), std::greater<>());
    m_heights.clear();
    for (int height : heights)
      m_heights.append(height);
    m_height = variant->height;
    m_variantHardware = !variant->software;
    QNetworkReply *media = m_network.get(QNetworkRequest(variant->url));
    connect(media, &QNetworkReply::finished, this, [this, media, session, token, licenseUrl] {
      media->deleteLater();
      if (session != m_session)
        return;
      QString error = media->error() == QNetworkReply::NoError ? QString() : replyError(media);
      if (error.isEmpty())
        error = apple_music_playlist::parse(media->url(), media->readAll(), m_playlist);
      if (error.isEmpty() && m_playlist.files.isEmpty())
        error = QStringLiteral("video segments are byte ranges");
      if (!error.isEmpty()) {
        fail(session, error);
        return;
      }
      license(session, token, licenseUrl);
    });
  });
}

void AppleMusicPicture::license(quint64 session, const QString &token, const QUrl &licenseUrl) {
  m_stream = m_nextStream++;
  const quint32 stream = m_stream;
  m_cdm.request(Open, stream, m_playlist.pssh, [this, session, token, licenseUrl, stream](const QByteArray &challenge,
                                                                                           const QString &error) {
    if (session != m_session)
      return;
    if (!error.isEmpty()) {
      fail(session, error);
      return;
    }
    QNetworkReply *reply = m_network.post(apple_music_requests::request(licenseUrl, token, m_account->userToken()),
                                          apple_music_requests::licenseBody(challenge, m_playlist.keyUri, m_appleId));
    connect(reply, &QNetworkReply::finished, this, [this, reply, session, stream] {
      reply->deleteLater();
      if (session != m_session)
        return;
      QString error;
      const QByteArray license = apple_music_requests::license(reply, error);
      if (!error.isEmpty()) {
        fail(session, error);
        return;
      }
      m_cdm.request(License, stream, license, [this, session, stream](const QByteArray &, const QString &error) {
        if (session != m_session)
          return;
        if (!error.isEmpty()) {
          fail(session, error);
          return;
        }
        QNetworkReply *init = m_network.get(QNetworkRequest(m_playlist.files.first()));
        connect(init, &QNetworkReply::finished, this, [this, init, session, stream] {
          init->deleteLater();
          if (session != m_session)
            return;
          if (init->error() != QNetworkReply::NoError) {
            fail(session, replyError(init));
            return;
          }
          m_cdm.request(VideoInit, stream, init->readAll(), [this, session](const QByteArray &, const QString &error) {
            if (session != m_session)
              return;
            if (!error.isEmpty()) {
              fail(session, error);
              return;
            }
            m_ready = true;
            syncClock(true);
            restart(clock());
            m_ticker.start();
          });
        });
      });
    });
  });
}

int AppleMusicPicture::segmentAt(double position) const {
  double start = 0.0;
  for (int i = 0; i < m_playlist.durations.size(); ++i) {
    start += m_playlist.durations.at(i);
    if (position < start)
      return i;
  }
  return int(m_playlist.durations.size()) - 1;
}

double AppleMusicPicture::segmentStart(int index) const {
  double start = 0.0;
  for (int i = 0; i < index && i < m_playlist.durations.size(); ++i)
    start += m_playlist.durations.at(i);
  return start;
}

void AppleMusicPicture::restart(double position) {
  ++m_epoch;
  m_frames.clear();
  m_drained = false;
  m_next = qMax(0, segmentAt(position));
  m_windowStart = segmentStart(m_next);
  m_bufferedEnd = m_windowStart;
  m_cdm.request(VideoReset, m_stream, {}, [](const QByteArray &, const QString &) {});
}

double AppleMusicPicture::clock() const {
  return m_running ? m_anchor + double(m_wall.nsecsElapsed() - m_anchorNs) / 1e9 : m_anchor;
}

void AppleMusicPicture::syncClock(bool force) {
  const qint64 now = m_wall.nsecsElapsed();
  const bool playing = m_playback->playing();
  if (!force && playing == m_running && now - m_syncedNs < kSyncNs)
    return;
  m_syncedNs = now;
  const double audible = m_playback->audiblePosition();
  const double error = audible - clock();
  if (timing() && m_running)
    m_pacing.maxErrorMs = std::max(m_pacing.maxErrorMs, std::abs(error) * 1000.0);
  if (force || playing != m_running || std::abs(error) > kJumpSeconds) {
    m_anchor = audible;
    m_anchorNs = now;
    m_running = playing;
    return;
  }
  // Re-anchor at now so the correction shifts the timeline without bending what came before.
  m_anchor = clock() + error * kSlew;
  m_anchorNs = now;
}

void AppleMusicPicture::tick() {
  if (!m_ready)
    return;
  syncClock(false);
  const double position = clock();
  // A seek lands before what is decoded, or past the segment fetched next. Within that
  // segment the decode catches up on its own.
  const double from = m_frames.isEmpty() ? m_windowStart : m_frames.first().time;
  if (position < from - 0.25 || position > segmentStart(m_next + 1) + 0.5) {
    if (timing())
      qInfo().nospace() << "AppleMusic: picture seek from " << from << " to " << position;
    restart(position);
  }
  // After a seek the segment decodes from its start; showing those frames as they arrive
  // would fast-forward to the playhead, so wait for the decode to reach it.
  const bool reached = !m_frames.isEmpty() && m_frames.last().time >= position - 0.05;
  int shown = -1;
  for (int i = 0; (reached || !m_decoding) && i < m_frames.size() && m_frames.at(i).time <= position + 0.005; ++i)
    shown = i;
  if (shown >= 0) {
    if (m_frames.at(shown).time != m_shownTime)
      present(m_frames.at(shown));
    m_frames.remove(0, shown);
  }
  if (m_decoding || m_bufferedEnd > position + kAheadSeconds)
    return;
  if (m_next < m_playlist.durations.size())
    decodeNext();
  else if (!m_drained) {
    // Frames the decoder holds back for reordering come out on an empty fragment.
    m_drained = true;
    m_decoding = true;
    m_decodingEpoch = m_epoch;
    m_cdm.request(VideoFragment, m_stream, {}, [this, session = m_session](const QByteArray &, const QString &) {
      if (session == m_session)
        m_decoding = false;
    });
  }
}

void AppleMusicPicture::decodeNext() {
  const int index = m_next++;
  const quint64 session = m_session;
  const quint64 epoch = m_epoch;
  m_decoding = true;
  QNetworkRequest request(m_playlist.files.at(index + 1));
  request.setTransferTimeout(30000);
  QNetworkReply *reply = m_network.get(request);
  connect(reply, &QNetworkReply::finished, this, [this, reply, session, epoch, index] {
    reply->deleteLater();
    if (session != m_session)
      return;
    if (epoch != m_epoch) {
      m_decoding = false;
      return;
    }
    if (reply->error() != QNetworkReply::NoError) {
      fail(session, replyError(reply));
      return;
    }
    m_decodingEpoch = epoch;
    m_decodingSegment = index;
    m_cdm.request(VideoFragment, m_stream, reply->readAll(),
                  [this, session, epoch, index](const QByteArray &, const QString &error) {
                    if (session != m_session)
                      return;
                    m_decoding = false;
                    if (!error.isEmpty() && epoch == m_epoch)
                      fail(session, error);
                    else if (epoch == m_epoch)
                      m_bufferedEnd = qMax(m_bufferedEnd, segmentStart(index + 1));
                  });
  });
}

void AppleMusicPicture::receive(quint32 stream, qint64 timeUs, int width, int height, const QByteArray &pixels) {
  if (stream != m_stream || !m_ready || m_decodingEpoch != m_epoch)
    return;
  const double media = double(timeUs) / 1e6;
  // Output follows presentation order, so the first frame after a reset starts its segment.
  if (m_origin < 0)
    m_origin = media - segmentStart(m_decodingSegment);
  const double time = media - m_origin;
  if (!m_frames.isEmpty() && time <= m_frames.last().time)
    return;
  m_frames.append({time, width, height, pixels});
}

void AppleMusicPicture::present(const Frame &frame) {
  m_shownTime = frame.time;
  if (timing()) {
    const qint64 now = m_wall.nsecsElapsed();
    if (m_pacing.lastShownNs && m_running)
      m_pacing.maxGapMs = std::max(m_pacing.maxGapMs, double(now - m_pacing.lastShownNs) / 1e6);
    m_pacing.lastShownNs = now;
    ++m_pacing.shown;
    if (now - m_pacing.reportNs >= 5'000'000'000) {
      qInfo().nospace() << "AppleMusic: picture showed " << m_pacing.shown << " frames in 5 s, longest gap "
                        << qRound(m_pacing.maxGapMs) << " ms, clock error up to " << qRound(m_pacing.maxErrorMs)
                        << " ms";
      m_pacing = {now, now};
    }
  }
  if (!m_sink)
    return;
  QVideoFrame video(QVideoFrameFormat(QSize(frame.width, frame.height), QVideoFrameFormat::Format_YUV420P));
  if (!video.map(QVideoFrame::WriteOnly))
    return;
  const char *source = frame.pixels.constData();
  const char *end = source + frame.pixels.size();
  for (int plane = 0; plane < 3; ++plane) {
    const int width = plane ? (frame.width + 1) / 2 : frame.width;
    const int rows = plane ? (frame.height + 1) / 2 : frame.height;
    uchar *target = video.bits(plane);
    for (int y = 0; y < rows && source + width <= end; ++y, source += width)
      std::memcpy(target + qsizetype(y) * video.bytesPerLine(plane), source, size_t(width));
  }
  video.unmap();
  m_sink->setVideoFrame(video);
  if (!m_shown) {
    m_shown = true;
    emit started();
  }
}

void AppleMusicPicture::fail(quint64 session, const QString &error, const QString &message) {
  if (session != m_session)
    return;
  if (!error.isEmpty())
    qWarning().noquote() << "AppleMusic: music video picture failed |" << error;
  // A failed HDCP rendition usually means the display lacks HDCP; stay on software ones after that.
  if (m_variantHardware && !m_ready) {
    m_hdcpRefused = true;
    qInfo().noquote() << "AppleMusic: falling back to software renditions";
    start(m_appleId, m_maxHeight);
    return;
  }
  stop();
  emit failed(message.isEmpty() ? tr("This video could not be shown.") : message);
}
