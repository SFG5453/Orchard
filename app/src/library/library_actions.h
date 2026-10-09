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

#include <QHash>
#include <QJsonObject>
#include <QJsonValue>
#include <QObject>
#include <QPointF>
#include <QVariantList>
#include <QVariantMap>

class AuthManager;
class HomeController;
class PlaybackController;
class YouTubeProvider;

// Likes and playlist additions. Every write targets the video id playback
// opens, so a music-video row is saved as the album audio it plays as.
class LibraryActions final : public QObject {
  Q_OBJECT
  Q_PROPERTY(bool currentLiked READ currentLiked NOTIFY likeChanged)
  Q_PROPERTY(bool likeKnown READ likeKnown NOTIFY likeChanged)
  Q_PROPERTY(bool likeBusy READ likeBusy NOTIFY likeChanged)
  Q_PROPERTY(QVariantList playlistTargets READ playlistTargets NOTIFY targetsChanged)
  Q_PROPERTY(bool targetsLoading READ targetsLoading NOTIFY targetsChanged)
  Q_PROPERTY(QString targetsError READ targetsError NOTIFY targetsChanged)
  Q_PROPERTY(bool saving READ saving NOTIFY targetsChanged)

public:
  explicit LibraryActions(YouTubeProvider *provider, AuthManager *auth,
                          PlaybackController *playback, HomeController *home,
                          QObject *parent = nullptr);

  [[nodiscard]] bool currentLiked() const { return m_liked; }
  [[nodiscard]] bool likeKnown() const { return m_likeKnown; }
  [[nodiscard]] bool likeBusy() const { return m_likeSetRequest != 0; }
  [[nodiscard]] QVariantList playlistTargets() const { return m_targets; }
  [[nodiscard]] bool targetsLoading() const { return m_targetsRequest != 0; }
  [[nodiscard]] QString targetsError() const { return m_targetsError; }
  [[nodiscard]] bool saving() const { return m_saveRequest != 0; }

  Q_INVOKABLE void toggleCurrentLike();
  Q_INVOKABLE void loadPlaylistTargets(const QVariantMap &track);
  // Loads targets and asks the shell to show its picker at a window position.
  Q_INVOKABLE void openPlaylistPicker(const QVariantMap &track, const QPointF &position);
  Q_INVOKABLE void addToPlaylist(const QVariantMap &track, const QString &playlistId,
                                 const QString &title);
  Q_INVOKABLE void createPlaylist(const QVariantMap &track, const QString &title);
  // A new private YouTube Music playlist with nothing in it yet.
  Q_INVOKABLE void createEmptyPlaylist(const QString &title);
  Q_INVOKABLE void removeFromPlaylist(const QVariantMap &track, const QString &playlistId,
                                      const QString &title);

signals:
  void likeChanged();
  void targetsChanged();
  void noticeRequested(const QString &message);
  void playlistPickerRequested(const QVariantMap &track, const QPointF &position);
  void removedFromPlaylist(const QString &playlistId, const QString &setVideoId,
                           const QString &videoId);

private:
  void syncTrack();
  void requestLikeStatus();
  QJsonObject payloadFor(const QVariantMap &track) const;
  void receive(quint64 requestId, const QJsonValue &result);
  void fail(quint64 requestId, const QString &message);

  YouTubeProvider *m_provider;
  AuthManager *m_auth;
  PlaybackController *m_playback;
  HomeController *m_home;
  QString m_trackId;
  QString m_likeVideoId;
  QString m_likeSetTrackId;
  // Keyed by resolved video id; survives skipping back and forth in a queue.
  QHash<QString, bool> m_likeCache;
  QVariantList m_targets;
  QString m_targetsTrackId;
  QString m_targetsVideoId;
  QString m_targetsError;
  quint64 m_likeStatusRequest{0};
  quint64 m_likeSetRequest{0};
  quint64 m_targetsRequest{0};
  quint64 m_saveRequest{0};
  quint64 m_removeRequest{0};
  bool m_liked{false};
  bool m_likeKnown{false};
  bool m_saveCreates{false};
};
