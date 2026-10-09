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

#include "offline_store.h"

#include <QHash>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QObject>
#include <QPointer>
#include <QTimer>
#include <QVariantList>
#include <QVariantMap>

class AnimatedArtworkService;
class AuthManager;
class ConnectivityMonitor;
class FileFetch;
class StreamDownload;
class YouTubeProvider;

// Saves songs for offline listening: resolves a stream at the chosen quality,
// writes it to disk, then adds cover art and, when enabled, the animated loop.
class DownloadManager final : public QObject {
  Q_OBJECT
  // Bumps on every change so QML bindings that call isDownloaded() refresh.
  Q_PROPERTY(int revision READ revision NOTIFY changed)
  Q_PROPERTY(int count READ count NOTIFY changed)
  Q_PROPERTY(double bytesUsed READ bytesUsed NOTIFY changed)
  // "saver", "normal" or "high".
  Q_PROPERTY(QString quality READ quality WRITE setQuality NOTIFY settingsChanged)
  Q_PROPERTY(bool animatedArtwork READ animatedArtwork WRITE setAnimatedArtwork NOTIFY settingsChanged)
  // Queued and running downloads.
  Q_PROPERTY(int pending READ pending NOTIFY activityChanged)
  Q_PROPERTY(int failedCount READ failedCount NOTIFY activityChanged)
  // Average progress of the pending songs, 0 to 1.
  Q_PROPERTY(double progress READ progress NOTIFY activityChanged)
  Q_PROPERTY(QString currentTitle READ currentTitle NOTIFY activityChanged)

public:
  // Everything but the provider is optional; without a provider downloads fail fast.
  explicit DownloadManager(YouTubeProvider *provider, AuthManager *auth, QString rootDir = defaultRoot(),
                           QObject *parent = nullptr);
  ~DownloadManager() override;

  static QString defaultRoot();
  // Rows the app can download: playable YouTube songs and videos.
  static bool eligible(const QVariantMap &track);

  void setConnectivity(ConnectivityMonitor *monitor);
  void setAnimatedArtworkService(AnimatedArtworkService *service) { m_artwork = service; }

  [[nodiscard]] int revision() const { return m_revision; }
  [[nodiscard]] int count() const { return static_cast<int>(m_store.tracks.size()); }
  [[nodiscard]] double bytesUsed() const { return static_cast<double>(m_store.totalBytes()); }
  [[nodiscard]] QString quality() const { return m_quality; }
  void setQuality(const QString &quality);
  [[nodiscard]] bool animatedArtwork() const { return m_animated; }
  void setAnimatedArtwork(bool enabled);
  [[nodiscard]] int pending() const;
  [[nodiscard]] int failedCount() const;
  [[nodiscard]] double progress() const;
  [[nodiscard]] QString currentTitle() const;
  [[nodiscard]] const offline::OfflineStore &store() const { return m_store; }

  Q_INVOKABLE bool isDownloaded(const QString &id) const { return m_store.tracks.contains(id); }
  // "none", "queued", "downloading", "failed" or "downloaded".
  Q_INVOKABLE QString stateOf(const QString &id) const;
  Q_INVOKABLE void download(const QVariantMap &track);
  // Queues every song and remembers the playlist or album they came from.
  Q_INVOKABLE void downloadAll(const QVariantList &tracks, const QVariantMap &collection = {});
  Q_INVOKABLE void remove(const QString &id);
  // Deletes the songs of one collection and the collection itself.
  Q_INVOKABLE void removeAll(const QVariantList &tracks, const QString &collectionId = {});
  Q_INVOKABLE void cancel(const QString &id);
  Q_INVOKABLE void retryFailed();
  Q_INVOKABLE void clearAll();
  // { total, downloaded, pending } for the songs of a collection.
  Q_INVOKABLE QVariantMap collectionState(const QVariantList &tracks) const;
  Q_INVOKABLE QString formatBytes(double bytes) const;

  // Playback side: where the audio and loop of a downloaded song live.
  [[nodiscard]] QString audioPathFor(const QString &id) const;
  [[nodiscard]] QString animatedUrlFor(const QString &id) const;
  [[nodiscard]] int bitrateFor(const QString &id) const;

signals:
  void changed();
  void settingsChanged();
  void activityChanged();
  void noticeRequested(const QString &message);

private:
  enum class Phase { Queued, Resolving, Transferring, Artwork, Failed };
  struct Job {
    QVariantMap track;
    Phase phase{Phase::Queued};
    QJsonObject stream;
    QString path;
    QString error;
    double progress{0};
    int attempts{0};
    QPointer<StreamDownload> transfer;
    QPointer<FileFetch> fetch;
    quint64 artworkRequest{0};
  };

  // download_manager.cpp: queue and audio transfer.
  bool enqueue(const QVariantMap &track);
  bool offline() const;
  void pump();
  void resolve(const QString &id, bool refresh);
  void receiveStream(quint64 request, const QJsonValue &result);
  void receiveFailure(quint64 request, const QString &message);
  void beginTransfer(const QString &id, const QJsonObject &stream);
  void finishTransfer(const QString &id, bool ok);
  void fail(const QString &id, const QString &message);
  void failQueued(const QString &message);
  void complete(const QString &id);
  void abortJob(Job &job);

  // download_manager_files.cpp: art, loops, removal and settings.
  void fetchCover(const QString &id);
  void fetchLoop(const QString &id);
  void saveCollection(const QVariantMap &collection, const QStringList &ids);
  void deleteFiles(const offline::TrackRecord &record);
  void dropEmptyCollections();
  void touch();
  void activity();
  void scheduleSave();

  YouTubeProvider *m_provider;
  AuthManager *m_auth;
  AnimatedArtworkService *m_artwork{nullptr};
  ConnectivityMonitor *m_connectivity{nullptr};
  offline::OfflineStore m_store;
  QNetworkAccessManager m_network;
  QHash<QString, Job> m_jobs;
  QStringList m_queue;
  QHash<quint64, QString> m_requests;
  QTimer m_saveTimer;
  QString m_quality{QStringLiteral("high")};
  quint64 m_nextArtworkRequest{0};
  int m_revision{0};
  bool m_animated{false};
};
