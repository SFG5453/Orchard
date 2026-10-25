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

#include "apple_music_playlist.h"
#include "widevine_client.h"

#include <QElapsedTimer>
#include <QList>
#include <QNetworkAccessManager>
#include <QObject>
#include <QPointer>
#include <QTimer>
#include <QVariantList>

class AppleMusic;
class PlaybackController;
class QVideoSink;

// An Apple Music video's picture, shown in a video sink and timed by the audio engine,
// which plays the video's soundtrack. Video licenses refuse plain decryption, so
// orchard-widevine-host decodes inside the CDM and returns frames; a software CDM only
// gets Apple's renditions up to 486p. Frames are decoded a segment at a time just ahead
// of the playhead.
class AppleMusicPicture final : public QObject {
  Q_OBJECT

public:
  AppleMusicPicture(AppleMusic *account, PlaybackController *playback, QObject *parent = nullptr);

  // `maxHeight` 0 picks the best rendition a software CDM may play.
  void start(const QString &appleId, int maxHeight);
  void stop();
  void setSink(QVideoSink *sink);
  // Heights of the playable renditions, tallest first, and the one playing.
  QVariantList heights() const { return m_heights; }
  int height() const { return m_height; }

signals:
  void started();
  void failed(const QString &message);

private:
  struct Frame {
    double time;
    int width;
    int height;
    QByteArray pixels;
  };

  void requestPlayback(quint64 session, const QString &token);
  void loadMaster(quint64 session, const QString &token, const QUrl &masterUrl, const QUrl &licenseUrl);
  void license(quint64 session, const QString &token, const QUrl &licenseUrl);
  void tick();
  // Media time on a steady timeline. The engine's audible position moves in decoder-sized
  // steps and costs a blocking hop to the audio thread, so it only steers this clock.
  double clock() const;
  void syncClock(bool force);
  void restart(double position);
  void decodeNext();
  void receive(quint32 stream, qint64 timeUs, int width, int height, const QByteArray &pixels);
  void present(const Frame &frame);
  // Logs `error`; the listener sees `message`, or a general one.
  void fail(quint64 session, const QString &error, const QString &message = {});
  int segmentAt(double position) const;
  double segmentStart(int index) const;

  AppleMusic *m_account;
  PlaybackController *m_playback;
  WidevineClient m_cdm;
  QNetworkAccessManager m_network;
  QPointer<QVideoSink> m_sink;
  QTimer m_ticker;
  QElapsedTimer m_wall;
  double m_anchor{0.0};
  qint64 m_anchorNs{0};
  bool m_running{false};
  qint64 m_syncedNs{0};
  QString m_appleId;
  int m_maxHeight{0};
  apple_music_playlist::Playlist m_playlist;
  QVariantList m_heights;
  int m_height{0};
  // Bumped by start() and stop(); late replies of an older session are dropped.
  quint64 m_session{0};
  quint32 m_stream{0};
  quint32 m_nextStream{1};
  bool m_ready{false};
  bool m_shown{false};
  // Bumped by seeks; pictures decoded for an older epoch are dropped.
  quint64 m_epoch{0};
  quint64 m_decodingEpoch{0};
  int m_decodingSegment{0};
  bool m_decoding{false};
  bool m_drained{false};
  int m_next{0};
  // Seconds of Apple's media timeline before its position 0; its fragments start at 10 s.
  double m_origin{-1.0};
  // Start of the first segment decoded since the last seek.
  double m_windowStart{0.0};
  double m_bufferedEnd{0.0};
  QList<Frame> m_frames;
  double m_shownTime{-1.0};
  // ORCHARD_PLAYBACK_TIMING pacing stats since the last report.
  struct Pacing {
    qint64 reportNs{0};
    qint64 lastShownNs{0};
    int shown{0};
    double maxGapMs{0.0};
    double maxErrorMs{0.0};
  } m_pacing;
};
