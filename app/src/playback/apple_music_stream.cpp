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

#include "apple_music_stream.h"
#include "apple_music_requests.h"
#include "apple_music_stream_state.h"
#include "integrations/apple_music.h"
#include "widevine_path.h"

#include <QDebug>
#include <QElapsedTimer>
#include <QJsonArray>
#include <QJsonDocument>
#include <QNetworkReply>
#include <QPointer>
#include <QUuid>

#include <utility>

using namespace widevine_protocol;

namespace {
// AAC-LC 256 kbps with AES-CTR first; the cbcs flavors carry the same audio.
const QStringList kFlavors{QStringLiteral("28:ctrp256"), QStringLiteral("30:cbcp256"), QStringLiteral("32:ctrp64"),
                           QStringLiteral("34:cbcp64")};
// The current song, the prepared one and a song or two the listener skipped back to.
constexpr int kOpenStreams = 4;

bool timing() {
  static const bool on = qEnvironmentVariableIsSet("ORCHARD_PLAYBACK_TIMING");
  return on;
}

} // namespace

using apple_music_requests::replyError;

AppleMusicStreams::AppleMusicStreams(AppleMusic *account, QObject *parent) : QObject(parent), m_account(account) {
  connect(&m_cdm, &WidevineClient::renewal, this, &AppleMusicStreams::renew);
}

AppleMusicStreams::~AppleMusicStreams() = default;

bool AppleMusicStreams::available() {
  return WidevineClient::available();
}

QString AppleMusicStreams::unavailableReason() {
#if defined(Q_OS_LINUX)
  if (findWidevineCdm().isEmpty())
    return tr("Apple Music playback needs Widevine. Install Google Chrome or Chromium with Widevine, then restart Orchard.");
#elif defined(Q_OS_WIN)
  if (findWidevineCdm().isEmpty())
    return tr("Apple Music playback needs Widevine. Open Microsoft Edge or Google Chrome once so it downloads Widevine, then restart Orchard.");
#endif
  return tr("Apple Music playback is not available on this system yet.");
}

void AppleMusicStreams::resolve(const QString &appleId, Resolved done) {
  if (!m_account->connected()) {
    done({}, tr("Connect Apple Music under Settings, Integrations to play this song."));
    return;
  }
  QElapsedTimer clock;
  clock.start();
  Resolved timed = [clock, done = std::move(done)](const QJsonObject &stream, const QString &error) {
    if (timing())
      qInfo() << "AppleMusic: stream resolved after" << clock.elapsed() << "ms";
    done(stream, error);
  };
  m_account->playbackToken([this, appleId, done = std::move(timed)](const QString &token, const QString &error) {
    if (token.isEmpty())
      done({}, error.isEmpty() ? tr("Apple Music could not start playback.") : error);
    else
      requestPlayback(appleId, token, done);
  });
}

void AppleMusicStreams::requestPlayback(const QString &appleId, const QString &developerToken, Resolved done) {
  QNetworkReply *reply = m_network.post(
      apple_music_requests::request(apple_music_requests::kWebPlaybackUrl, developerToken, m_account->userToken()),
      apple_music_requests::webPlaybackBody(appleId));
  connect(reply, &QNetworkReply::finished, this, [this, reply, appleId, done = std::move(done)] {
    reply->deleteLater();
    const QJsonObject body = QJsonDocument::fromJson(reply->readAll()).object();
    const QJsonObject song = body.value(QStringLiteral("songList")).toArray().first().toObject();
    if (reply->error() != QNetworkReply::NoError || song.isEmpty()) {
      const QString message = body.value(QStringLiteral("customerMessage")).toString();
      qWarning().noquote() << "AppleMusic: webPlayback failed |" << replyError(reply)
                           << body.value(QStringLiteral("failureType")).toString();
      done({}, message.isEmpty() ? tr("Apple Music did not offer this song for playback.") : message);
      return;
    }
    // Music videos answer with a master playlist and no audio assets.
    if (song.value(QStringLiteral("assets")).toArray().isEmpty() && song.contains(QStringLiteral("hls-playlist-url")))
      loadVideoAudio(appleId, song, done);
    else
      loadPlaylist(appleId, song, done);
  });
}

void AppleMusicStreams::loadPlaylist(const QString &appleId, const QJsonObject &song, Resolved done) {
  QUrl playlistUrl;
  const QJsonArray assets = song.value(QStringLiteral("assets")).toArray();
  for (const QString &flavor : kFlavors) {
    for (const QJsonValue &asset : assets)
      if (asset[QStringLiteral("flavor")].toString() == flavor)
        playlistUrl = QUrl(asset[QStringLiteral("URL")].toString());
    if (playlistUrl.isValid())
      break;
  }
  const QUrl licenseUrl(song.value(QStringLiteral("hls-key-server-url")).toString());
  if (!playlistUrl.isValid() || licenseUrl.scheme() != QStringLiteral("https")) {
    done({}, tr("Apple Music has no Widevine stream for this song."));
    return;
  }
  QNetworkReply *reply = m_network.get(QNetworkRequest(playlistUrl));
  connect(reply, &QNetworkReply::finished, this, [this, reply, appleId, licenseUrl, done = std::move(done)] {
    reply->deleteLater();
    auto stream = std::make_shared<Stream>();
    QString error = reply->error() == QNetworkReply::NoError ? QString() : replyError(reply);
    if (error.isEmpty())
      error = apple_music_playlist::parse(reply->url(), reply->readAll(), stream->playlist);
    if (!error.isEmpty()) {
      qWarning().noquote() << "AppleMusic: stream playlist unusable |" << error;
      done({}, tr("Apple Music returned a stream Orchard cannot play."));
      return;
    }
    openStream(appleId, licenseUrl, stream, done);
  });
}

void AppleMusicStreams::openStream(const QString &appleId, const QUrl &licenseUrl, std::shared_ptr<Stream> stream,
                                   const Resolved &done) {
  const QString playbackId = QUuid::createUuid().toString(QUuid::WithoutBraces);
  stream->id = m_nextStream++;
  stream->playbackId = playbackId;
  stream->licenseUrl = licenseUrl;
  stream->appleId = appleId;
  const apple_music_playlist::Playlist &playlist = stream->playlist;
  const qint64 length = playlist.servedSize();
  m_streams.insert(playbackId, stream);
  m_order.append(playbackId);
  evict(kOpenStreams);
  trimPrefetches();
  // License and fetch the opening fragment while the player sets up.
  segment(stream.get(), 0, [](const QString &) {});
  if (playlist.ranges.size() > 1)
    segment(stream.get(), 1, [](const QString &) {});
  // After the opening requests so they are not queued behind it.
  startPrefetch(stream.get());
  done({{QStringLiteral("provider"), QStringLiteral("apple")},
        {QStringLiteral("playbackId"), playbackId},
        {QStringLiteral("contentLength"), double(length)},
        {QStringLiteral("mimeType"), QStringLiteral("audio/mp4; codecs=mp4a.40.2")},
        {QStringLiteral("durationSeconds"), playlist.duration},
        {QStringLiteral("bitrate"), playlist.duration > 0 ? int(length * 8 / playlist.duration) : 0}},
       {});
}

AppleMusicStreams::Stream *AppleMusicStreams::find(const QString &playbackId) const {
  const auto found = m_streams.constFind(playbackId);
  return found == m_streams.cend() ? nullptr : found->get();
}

void AppleMusicStreams::read(const QString &playbackId, qint64 start, qint64 end, Bytes done) {
  Stream *stream = find(playbackId);
  const int index = stream ? stream->playlist.indexAt(start) : -1;
  if (index < 0) {
    done({}, stream ? 416 : 404);
    return;
  }
  // The prefetch fills from the playhead first.
  stream->prefetchFrom = std::max(index, 1);
  segment(stream, index, [this, playbackId, index, start, end, done = std::move(done)](const QString &error) {
    Stream *stream = find(playbackId);
    if (!stream || !error.isEmpty()) {
      if (!error.isEmpty())
        qWarning().noquote() << "AppleMusic: segment" << index << "failed |" << error;
      done({}, 502);
      return;
    }
    const auto [rangeStart, rangeSize] = stream->playlist.served(index);
    const qint64 last = std::min(end, rangeStart + rangeSize - 1);
    done(stream->clear.value(index).mid(start - rangeStart, last - start + 1), 0);
    // Fetch and decrypt the next fragment while this one plays.
    if (index + 1 < stream->playlist.ranges.size())
      segment(stream, index + 1, [](const QString &) {});
  });
}

void AppleMusicStreams::segment(Stream *stream, int index, Done done) {
  if (stream->clear.contains(index)) {
    if (index > 0) {
      stream->recent.removeOne(index);
      stream->recent.append(index);
    }
    done({});
    return;
  }
  QList<Done> &waiters = stream->loading[index];
  waiters.append(std::move(done));
  if (waiters.size() > 1)
    return;
  if (index > 0) {
    fetchSegment(stream->playbackId, index);
    return;
  }
  ready(stream, [this, id = stream->playbackId](const QString &error) { finishSegment(id, 0, error); });
}

void AppleMusicStreams::decryptSegment(const QString &playbackId, int index, const QByteArray &bytes) {
  Stream *stream = find(playbackId);
  if (!stream)
    return;
  // The host may have restarted while the fragment downloaded.
  ready(stream, [this, playbackId, index, bytes](const QString &error) {
    Stream *stream = find(playbackId);
    if (!stream)
      return;
    if (!error.isEmpty()) {
      finishSegment(playbackId, index, error);
      return;
    }
    m_cdm.request(Fragment, stream->id, bytes, [this, playbackId, index](const QByteArray &clear, const QString &error) {
      Stream *stream = find(playbackId);
      if (!stream)
        return;
      if (error.isEmpty()) {
        stream->clear.insert(index, clear);
        stream->recent.append(index);
        if (!stream->whole)
          stream->trimClear();
      }
      finishSegment(playbackId, index, error);
    });
  });
}

void AppleMusicStreams::finishSegment(const QString &playbackId, int index, const QString &error) {
  Stream *stream = find(playbackId);
  if (!stream)
    return;
  for (const Done &done : stream->loading.take(index))
    done(error);
}

// Licenses a session in the running host and hands it the init segment, once per host.
void AppleMusicStreams::ready(Stream *stream, Done done) {
  if (stream->generation == m_cdm.generation() && stream->clear.contains(0)) {
    done({});
    return;
  }
  stream->readyWaiters.append(std::move(done));
  if (stream->licensing)
    return;
  stream->licensing = true;
  const QString playbackId = stream->playbackId;
  QElapsedTimer clock;
  clock.start();
  // The license and the init segment download in parallel; Init needs both. Negative once finished.
  const auto pending = std::make_shared<int>(stream->rawInit.isEmpty() ? 2 : 1);
  const auto finish = [this, playbackId, clock, pending](const QString &error) {
    if (*pending < 0)
      return;
    *pending = -1;
    if (timing())
      qInfo() << "AppleMusic: licensed after" << clock.elapsed() << "ms" << error;
    Stream *stream = find(playbackId);
    if (!stream)
      return;
    stream->licensing = false;
    for (const Done &waiter : std::exchange(stream->readyWaiters, {}))
      waiter(error);
  };
  const quint64 generation = m_cdm.generation();
  const auto decryptInit = [this, playbackId, generation, finish, pending] {
    if (*pending < 0 || --*pending > 0)
      return;
    Stream *stream = find(playbackId);
    if (!stream)
      return;
    m_cdm.request(Init, stream->id, stream->rawInit,
                  [this, playbackId, generation, finish](const QByteArray &clear, const QString &error) {
      Stream *stream = find(playbackId);
      QString failure = error;
      if (failure.isEmpty() && generation != m_cdm.generation())
        failure = QStringLiteral("the host restarted");
      const QByteArray index = stream && failure.isEmpty() ? stream->playlist.segmentIndex(clear) : QByteArray();
      if (stream && failure.isEmpty() && index.isEmpty())
        failure = QStringLiteral("the init segment names no track timing");
      if (stream && failure.isEmpty()) {
        stream->clear.insert(0, clear + index);
        stream->generation = generation;
      }
      finish(failure);
    });
  };
  m_cdm.request(Open, stream->id, stream->playlist.pssh,
                [this, playbackId, finish, decryptInit](const QByteArray &challenge, const QString &error) {
    if (!error.isEmpty()) {
      finish(error);
      return;
    }
    license(playbackId, challenge, [finish, decryptInit](const QString &error) {
      if (!error.isEmpty()) {
        finish(error);
        return;
      }
      decryptInit();
    });
  });
  if (!stream->rawInit.isEmpty())
    return;
  const auto [start, size] = stream->playlist.ranges.first();
  QNetworkRequest request(stream->playlist.url(0));
  request.setTransferTimeout(20000);
  if (const QByteArray range = stream->playlist.rangeHeader(0); !range.isEmpty())
    request.setRawHeader("Range", range);
  QNetworkReply *reply = m_network.get(request);
  connect(reply, &QNetworkReply::finished, this, [this, reply, playbackId, size, finish, decryptInit] {
    reply->deleteLater();
    Stream *stream = find(playbackId);
    if (!stream)
      return;
    const QByteArray init = reply->readAll();
    if (reply->error() != QNetworkReply::NoError || init.size() != size) {
      finish(reply->error() != QNetworkReply::NoError ? replyError(reply) : QStringLiteral("short init segment"));
      return;
    }
    stream->rawInit = init;
    decryptInit();
  });
}

void AppleMusicStreams::license(const QString &playbackId, const QByteArray &challenge, Done done) {
  m_account->playbackToken([this, playbackId, challenge, done = std::move(done)](const QString &token,
                                                                               const QString &error) {
    Stream *stream = find(playbackId);
    if (!stream)
      return;
    if (token.isEmpty()) {
      done(error.isEmpty() ? QStringLiteral("no developer token") : error);
      return;
    }
    QNetworkReply *reply = m_network.post(
        apple_music_requests::request(stream->licenseUrl, token, m_account->userToken()),
        apple_music_requests::licenseBody(challenge, stream->playlist.keyUri, stream->appleId));
    connect(reply, &QNetworkReply::finished, this, [this, reply, playbackId, done] {
      reply->deleteLater();
      Stream *stream = find(playbackId);
      if (!stream)
        return;
      QString error;
      const QByteArray license = apple_music_requests::license(reply, error);
      if (!error.isEmpty()) {
        done(error);
        return;
      }
      m_cdm.request(License, stream->id, license, [done](const QByteArray &, const QString &error) { done(error); });
    });
  });
}

void AppleMusicStreams::renew(quint32 streamId, const QByteArray &message) {
  for (const auto &stream : std::as_const(m_streams)) {
    if (stream->id != streamId)
      continue;
    license(stream->playbackId, message, [](const QString &error) {
      if (!error.isEmpty())
        qWarning().noquote() << "AppleMusic: license renewal failed |" << error;
    });
    return;
  }
}

void AppleMusicStreams::evict(int keep) {
  while (m_order.size() > keep) {
    const std::shared_ptr<Stream> stream = m_streams.take(m_order.takeFirst());
    if (!stream)
      continue;
    dropPrefetch(stream.get());
    m_cdm.close(stream->id);
    const QString closed = tr("The song's stream was closed.");
    for (const Done &waiter : std::exchange(stream->readyWaiters, {}))
      waiter(closed);
    for (const QList<Done> &waiters : std::exchange(stream->loading, {}))
      for (const Done &waiter : waiters)
        waiter(closed);
  }
}

void AppleMusicStreams::clear() {
  evict(0);
  m_cdm.shutdown();
}
