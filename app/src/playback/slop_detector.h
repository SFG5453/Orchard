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

#include "slop_fingerprint.h"

#include <QHash>
#include <QJsonValue>
#include <QNetworkAccessManager>
#include <QObject>
#include <QPointer>
#include <QSet>
#include <QSqlDatabase>
#include <QString>
#include <QThread>
#include <QVariantList>

class AuthManager;
class QAudioBuffer;
class SlopScanWorker;
class StreamDownload;
class YouTubeProvider;

// Scores tracks for AI-generation fakeprints and remembers verdicts per track id.
// Queued songs are downloaded and analysed whole in the background; playing audio
// is scored live as a fallback. Beep boop, this is a human-only dance floor.
class SlopDetector final : public QObject {
  Q_OBJECT
  // "off", "mark", "skip" or "remove".
  Q_PROPERTY(QString action READ action WRITE setAction NOTIFY actionChanged)
  // Bumps on every verdict change so QML bindings over isFlagged() re-evaluate.
  Q_PROPERTY(int revision READ revision NOTIFY flagsChanged)
public:
  // Without a provider and auth, background scanning is disabled.
  explicit SlopDetector(YouTubeProvider *provider = nullptr, AuthManager *auth = nullptr,
                        QObject *parent = nullptr);
  ~SlopDetector() override;

  QString action() const { return m_action; }
  void setAction(const QString &action);
  bool enabled() const { return m_action != QStringLiteral("off"); }
  bool skips() const {
    return m_action == QStringLiteral("skip") || m_action == QStringLiteral("remove");
  }
  bool removes() const { return m_action == QStringLiteral("remove"); }
  int revision() const { return m_revision; }

  Q_INVOKABLE bool isFlagged(const QString &trackId) const;
  // Last stored probability, or -1 when the track has no verdict yet.
  Q_INVOKABLE double probability(const QString &trackId) const;

  void feed(const QString &trackId, const QAudioBuffer &buffer);
  // Replaces the background scan order. Tracks with verdicts are skipped; work in flight continues.
  void scan(const QVariantList &tracks);

signals:
  void actionChanged();
  void flagsChanged();
  // A track crossed the threshold, from live playback or a background scan.
  void flagged(const QString &trackId);

private:
  struct Session {
    SlopFingerprintDetector *handle{nullptr};
    int sampleRate{0};
    int channels{0};
    float nextCheck{0.0f};
    quint64 lastFeed{0};
  };
  void finish(const QString &trackId);
  void record(const QString &trackId, const SlopVerdict &verdict);
  // Stored probability, or -1 when the track has no verdict.
  float lookup(const QString &trackId) const;
  bool ensureDatabase() const;
  void pump();
  void resolved(quint64 request, const QJsonValue &result);
  void scanFailed(const QString &trackId);
  void analyzed(const QString &trackId, bool ok, float probability, float seconds);
  void cancelScans();

  // Early averages lean human, so negatives are stored only after this much audio.
  static constexpr float kSettleSeconds = 60.0f;
  static constexpr float kFirstCheckSeconds = 30.0f;
  static constexpr float kCheckInterval = 10.0f;
  static constexpr int kMaxSessions = 2;
  static constexpr int kMaxStored = 5000;
  static constexpr int kMaxCached = 2000;
  static constexpr int kMaxDownloads = 2;
  // Saver Opus runs about 2 MB for a four-minute song; this covers a 30-minute mix.
  static constexpr qint64 kMaxDownloadBytes = 16LL * 1024 * 1024;

  QString m_action{QStringLiteral("mark")};
  float m_threshold{SlopFingerprintDetector::kDefaultThreshold};
  QHash<QString, Session> m_sessions;
  // Opened on first lookup so startup never touches the disk for this.
  mutable QSqlDatabase m_db;
  mutable bool m_dbAttempted{false};
  QString m_dbName;
  // Per-id lookups, including misses, so QML bindings don't hit SQLite every frame.
  mutable QHash<QString, float> m_cache;
  quint64 m_feedCounter{0};
  int m_revision{0};

  YouTubeProvider *m_provider;
  AuthManager *m_auth;
  QNetworkAccessManager m_network;
  QString m_scanDir;
  QVariantList m_scanQueue;
  // Track id by provider request, then by live download.
  QHash<quint64, QString> m_resolving;
  QHash<QString, QPointer<StreamDownload>> m_downloads;
  // Handed to the worker; it reports back on every one, success or not.
  QSet<QString> m_analyzing;
  // Tracks that failed this session. Retrying a dead stream every queue change helps nobody.
  QSet<QString> m_failed;
  QThread m_scanThread;
  SlopScanWorker *m_worker{nullptr};
};
