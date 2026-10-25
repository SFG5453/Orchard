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

#include "widevine_client.h"

#include <QHash>
#include <QJsonObject>
#include <QList>
#include <QNetworkAccessManager>
#include <QObject>
#include <QUrl>
#include <functional>
#include <memory>

class AppleMusic;

// Apple Music songs as plain MP4 for the audio stream proxy. A song's HLS playlist
// names byte ranges of one encrypted fragmented MP4; each range is fetched, decrypted
// by orchard-widevine-host and served at its own offset, so Qt's media backend sees
// an ordinary seekable file. The newest songs keep their whole encrypted file and every
// fragment decrypted so far, since Widevine rate-limits decryption and seeks would
// otherwise wait on it; older songs keep a few clear fragments.
class AppleMusicStreams final : public QObject {
  Q_OBJECT

public:
  using Resolved = std::function<void(const QJsonObject &stream, const QString &error)>;
  using Bytes = std::function<void(const QByteArray &bytes, int status)>;

  explicit AppleMusicStreams(AppleMusic *account, QObject *parent = nullptr);
  ~AppleMusicStreams() override;

  static bool available();
  // Why available() is false, for the listener.
  static QString unavailableReason();

  // A proxy stream for the catalog song `appleId` ("provider": "apple").
  void resolve(const QString &appleId, Resolved done);
  // The AudioStreamProxy range reader for streams from resolve().
  void read(const QString &playbackId, qint64 start, qint64 end, Bytes done);
  // Forgets every stream and ends the host.
  void clear();

private:
  struct Stream;
  using Done = std::function<void(const QString &error)>;

  void requestPlayback(const QString &appleId, const QString &developerToken, Resolved done);
  void loadPlaylist(const QString &appleId, const QJsonObject &song, Resolved done);
  // A music video's own soundtrack: its audio rendition, one file per fragment.
  void loadVideoAudio(const QString &appleId, const QJsonObject &song, Resolved done);
  void openStream(const QString &appleId, const QUrl &licenseUrl, std::shared_ptr<Stream> stream,
                  const Resolved &done);
  void measureFiles(const std::shared_ptr<Stream> &stream, Done done);
  Stream *find(const QString &playbackId) const;
  void segment(Stream *stream, int index, Done done);
  void fetchSegment(const QString &playbackId, int index);
  void fetchRange(const QString &playbackId, int index);
  void decryptSegment(const QString &playbackId, int index, const QByteArray &bytes);
  void startPrefetch(Stream *stream);
  void pumpPrefetch(const QString &playbackId);
  void dropPrefetch(Stream *stream);
  void trimPrefetches();
  void ready(Stream *stream, Done done);
  void license(const QString &playbackId, const QByteArray &challenge, Done done);
  void renew(quint32 streamId, const QByteArray &message);
  void finishSegment(const QString &playbackId, int index, const QString &error);
  void evict(int keep);

  AppleMusic *m_account;
  WidevineClient m_cdm;
  QNetworkAccessManager m_network;
  QHash<QString, std::shared_ptr<Stream>> m_streams;
  // Oldest first; the newest few streams stay open for the current and next song.
  QList<QString> m_order;
  quint32 m_nextStream{1};
};
