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

#pragma once

#include "audio_stream_proxy.h"

#include <QHash>
#include <QJsonValue>
#include <QObject>
#include <QPointer>
#include <QRectF>
#include <QTimer>
#include <QUrl>
#include <QVariantList>
#include <QVariantMap>

class AuthManager;
class QVideoSink;
class PlaybackController;
class YouTubeProvider;

// Finds the playing song's music video and serves it to the theater view.
// While the theater is open the audio engine plays the video's soundtrack and
// the picture plays muted against it.
class MusicVideoController final : public QObject {
  Q_OBJECT
  // "", "checking", "available", "unavailable", "loading", "ready" or "error".
  Q_PROPERTY(QString status READ status NOTIFY changed)
  Q_PROPERTY(bool available READ available NOTIFY changed)
  Q_PROPERTY(bool open READ isOpen NOTIFY changed)
  Q_PROPERTY(QUrl source READ source NOTIFY changed)
  Q_PROPERTY(QString videoId READ videoId NOTIFY changed)
  Q_PROPERTY(QString errorMessage READ errorMessage NOTIFY changed)
  // Height cap for the picture, 0 for the best available; saved across sessions.
  Q_PROPERTY(int maxHeight READ maxHeight WRITE setMaxHeight NOTIFY changed)
  // Heights the current video offers, tallest first, and the one playing.
  Q_PROPERTY(QVariantList heights READ heights NOTIFY changed)
  Q_PROPERTY(int height READ height NOTIFY changed)
  // Picture inside black bars baked into the video, in 0..1 frame coordinates.
  Q_PROPERTY(QRectF contentRect READ contentRect NOTIFY contentRectChanged)
public:
  MusicVideoController(YouTubeProvider *provider, AuthManager *auth, PlaybackController *playback,
                       QObject *parent = nullptr);

  [[nodiscard]] QString status() const { return m_status; }
  [[nodiscard]] bool available() const;
  [[nodiscard]] bool isOpen() const { return m_open; }
  [[nodiscard]] QUrl source() const { return m_source; }
  [[nodiscard]] QString videoId() const { return m_videoId; }
  [[nodiscard]] QString errorMessage() const { return m_error; }
  [[nodiscard]] int maxHeight() const { return m_maxHeight; }
  void setMaxHeight(int height);
  [[nodiscard]] QVariantList heights() const { return m_heights; }
  [[nodiscard]] int height() const { return m_height; }
  [[nodiscard]] QRectF contentRect() const;

  Q_INVOKABLE void show();
  // Plays a video row from the catalog and opens the theater on it.
  Q_INVOKABLE void playVideo(const QVariantMap &media);
  Q_INVOKABLE void hide();
  // Re-resolves the stream, e.g. after the signed URL expired.
  Q_INVOKABLE void retry();
  // Samples frames from the theater's VideoOutput sink to find baked-in bars.
  Q_INVOKABLE void watchFrames(QObject *sink);

signals:
  void changed();
  void contentRectChanged();

private:
  void syncTrack();
  void lookup();
  void resolveStream();
  void receive(quint64 requestId, const QJsonValue &result);
  void fail(quint64 requestId, const QString &message);
  void setStatus(const QString &status, const QString &error = {});
  void clearStream();
  void sampleFrame();
  void resetContentRect();

  YouTubeProvider *m_provider;
  AuthManager *m_auth;
  PlaybackController *m_playback;
  AudioStreamProxy m_proxy;
  QVariantMap m_track;
  QString m_trackId;
  QString m_videoId;
  QString m_status;
  QString m_error;
  QUrl m_source;
  quint64 m_searchRequest{0};
  quint64 m_streamRequest{0};
  bool m_open{false};
  int m_maxHeight{1080};
  int m_height{0};
  QVariantList m_heights;
  QPointer<QVideoSink> m_sink;
  QTimer m_frameTimer;
  QRectF m_contentRect;
  // Track id to video id; an empty value records a confirmed miss.
  QHash<QString, QString> m_lookups;
};
